package com.sal7one.tvtestproject

import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.tvtestproject.receiver.AirPlayServer
import com.sal7one.tvtestproject.receiver.ReceiverState
import com.sal7one.tvtestproject.receiver.PlaybackMode
import com.sal7one.tvtestproject.receiver.PlaybackPreferences
import com.sal7one.tvtestproject.receiver.media.VideoCapabilities
import com.sal7one.tvtestproject.receiver.VideoConfig
import com.sal7one.tvtestproject.receiver.media.H264Config
import com.sal7one.tvtestproject.receiver.media.VideoPipeline
import com.sal7one.tvtestproject.receiver.protocol.Plist
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run server restart tests on an isolated emulator. The SurfaceView pixel test also supports TV hardware. */
@RunWith(AndroidJUnit4::class)
class ReceiverRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before @After fun stopActivityOwnedReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { context.stopService(Intent(context, ReceiverService::class.java)) }
        instrumentation.waitForIdleSync()
    }

    @Test fun twentyRestartsKeepIdentityAndReleaseControlPort() {
        awaitFreeControlPort()
        var publicKey: String? = null
        repeat(20) {
            AirPlayServer(context) { }.use { server ->
                val announcement = server.start()
                assertEquals(7000, announcement.port)
                if (publicKey == null) publicKey = announcement.publicKeyHex
                assertEquals(publicKey, announcement.publicKeyHex)
                UxPlayDnsTxt.validate(announcement)
                connection(announcement.port).use { client ->
                    val reply = exchange(client, "GET", "/info", 1)
                    assertEquals(200, reply.status)
                    val info = Plist.decode(reply.body)
                    assertEquals(announcement.deviceId, info["deviceID"])
                    assertArrayEquals(hex(announcement.publicKeyHex), info["pk"] as ByteArray)
                }
                // macOS can keep an auxiliary control connection open.
                connection(announcement.port).use { first ->
                    assertEquals(200, exchange(first, "OPTIONS", "*", 1).status)
                    connection(announcement.port).use { second ->
                        assertEquals(200, exchange(second, "GET", "/info", 1).status)
                    }
                }
            }
        }
    }

    @Test fun occupiedControlPortUsesAnotherPortAndKeepsTheOtherListenerOpen() {
        awaitFreeControlPort()
        ServerSocket().use { occupied ->
            occupied.reuseAddress = true
            occupied.bind(InetSocketAddress(7000))
            var fallbackPort = 0
            AirPlayServer(context) { }.use { server ->
                val announcement = server.start()
                fallbackPort = announcement.port
                assertTrue(fallbackPort in 1..65535)
                assertNotEquals(7000, fallbackPort)
                assertEquals(announcement, server.start())
                UxPlayDnsTxt.validate(announcement)
                connection(fallbackPort).use { client ->
                    assertEquals(200, exchange(client, "GET", "/info", 1).status)
                }
                assertFalse(occupied.isClosed)
            }
            assertThrows(java.io.IOException::class.java) { connection(fallbackPort).close() }
            assertFalse(occupied.isClosed)
        }
    }

    @Test fun twentyClientDisconnectsKeepTheListenerAndIdentity() {
        val states = CopyOnWriteArrayList<ReceiverState>()
        AirPlayServer(context) { states.add(it.state) }.use { server ->
            val original = server.start()
            repeat(20) {
                connection(original.port).use { client ->
                    assertEquals(200, exchange(client, "OPTIONS", "*", 1).status)
                    server.disconnectClient()
                    assertEquals(-1, client.getInputStream().read())
                }
                assertEquals(original, server.start())
                connection(original.port).use { client ->
                    val reply = exchange(client, "GET", "/info", 1)
                    assertEquals(200, reply.status)
                    assertArrayEquals(hex(original.publicKeyHex), Plist.decode(reply.body)["pk"] as ByteArray)
                    assertEquals("Old disconnect completion overwrote the new session", ReceiverState.CONNECTED, states.last())
                }
            }
        }
    }

    @Test fun resetConnectionsDuringDisconnectDoNotKillTheListener() {
        AirPlayServer(context) { }.use { server ->
            val announcement = server.start()
            val clients = List(4) { connection(announcement.port) }
            try {
                clients.forEach { assertEquals(200, exchange(it, "OPTIONS", "*", 1).status) }
                val retries = Thread {
                    repeat(80) {
                        try {
                            connection(announcement.port).use { it.setSoLinger(true, 0) }
                        } catch (_: java.io.IOException) { /* The final probe must still succeed. */ }
                    }
                }
                retries.start()
                server.disconnectClient()
                retries.join(10_000)
                assertFalse("Reconnect burst did not finish", retries.isAlive)
                server.disconnectClient()
                connection(announcement.port).use {
                    assertEquals(200, exchange(it, "OPTIONS", "*", 1).status)
                }
                assertEquals(announcement, server.start())
            } finally { clients.forEach { it.close() } }
        }
    }

    @Test fun playbackModesPublishMatchingAudioDelayAndCapabilityGatedFrameRate() {
        val previous = PlaybackPreferences.read(context)
        try {
            AirPlayServer(context) { }.use { server ->
                val announcement = server.start()
                assertEquals("Unverified HEVC must stay off", 0L, announcement.features and (1L shl 42))
                for (mode in PlaybackMode.entries) {
                    PlaybackPreferences.write(context, mode)
                    connection(announcement.port).use { client ->
                        val info = Plist.decode(exchange(client, "GET", "/info", 1).body)
                        val latencies = info["audioLatencies"] as List<*>
                        for (latency in latencies) {
                            assertEquals(mode.playoutDelayUs, (latency as Map<*, *>)["outputLatencyMicros"])
                        }
                        val display = (info["displays"] as List<*>).first() as Map<*, *>
                        val hardware60 = VideoCapabilities.probeAvc(1920, 1088, 60.0).hardwareCandidates.isNotEmpty()
                        assertEquals(mode.maximumFps(hardware60).toLong(), display["maxFPS"])
                    }
                    server.disconnectClient()
                }
            }
        } finally { PlaybackPreferences.write(context, previous) }
    }

    @Test fun setupBeforeKeysFailsAndDoesNotOpenMirrorPort() {
        AirPlayServer(context) { }.use { server ->
            val announcement = server.start()
            connection(announcement.port).use { client ->
                val body = Plist.encode(mapOf("streams" to listOf(mapOf("type" to 110L, "streamConnectionID" to 1L))))
                assertEquals(455, exchange(client, "SETUP", "/stream", 1, body).status)
                assertThrows(java.io.IOException::class.java) { connection(7100).close() }
            }
        }
    }

    @Test fun realBundledIdrPostsADecodedSurfaceBuffer() {
        val (config, accessUnit) = firstFixtureIdr()
        val frame = CountDownLatch(1)
        val error = AtomicReference<String?>()
        val texture = SurfaceTexture(false).apply {
            setDefaultBufferSize(config.width, config.height)
            setOnFrameAvailableListener { frame.countDown() }
        }
        val surface = Surface(texture)
        try {
            VideoPipeline({ error.set(it); frame.countDown() }).use { decoder ->
                decoder.attachSurface(surface)
                assertTrue(decoder.configure(config))
                assertTrue(decoder.enqueue(accessUnit, System.nanoTime() / 1000 + 120_000, true))
                assertTrue("Decoder did not post a surface frame", frame.await(10, TimeUnit.SECONDS))
                assertNull(error.get())
                decoder.attachSurface(null)
            }
        } finally {
            surface.release()
            texture.release()
        }
    }

    @Test fun realBundledIdrProducesColoredSurfaceViewPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ready = CountDownLatch(1)
        val view = AtomicReference<SurfaceView>()
        instrumentation.runOnMainSync {
            val output = activity.findViewById<SurfaceView>(R.id.fullscreen_surface)
            view.set(output)
            if (output.holder.surface.isValid) ready.countDown()
            else output.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) { ready.countDown() }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
            })
        }
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            assertTrue("Activity surface did not become available", ready.await(10, TimeUnit.SECONDS))
            val (config, accessUnit) = firstFixtureIdr()
            val error = AtomicReference<String?>()
            val released = CountDownLatch(1)
            VideoPipeline({ error.set(it); released.countDown() }, { released.countDown() }).use { decoder ->
                decoder.attachSurface(view.get().holder.surface)
                assertTrue(decoder.configure(config))
                assertTrue(decoder.enqueue(accessUnit, System.nanoTime() / 1000 + 120_000, true))
                assertTrue("No decoder output", released.await(10, TimeUnit.SECONDS))
                assertNull(error.get())
                val deadline = SystemClock.elapsedRealtime() + 5_000
                var colored = 0
                var copyStatus = -1
                while (SystemClock.elapsedRealtime() < deadline) {
                    val copied = CountDownLatch(1)
                    PixelCopy.request(view.get().holder.surface, bitmap, {
                        copyStatus = it
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                    assertTrue("PixelCopy did not complete", copied.await(2, TimeUnit.SECONDS))
                    if (copyStatus == PixelCopy.SUCCESS) {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        colored = pixels.count {
                            val channels = listOf(Color.red(it), Color.green(it), Color.blue(it))
                            channels.max() - channels.min() > 80
                        }
                        if (colored > pixels.size / 2) break
                    }
                    SystemClock.sleep(50)
                }
                android.util.Log.i("FossPixelTest", "surfaceview_pixel_check status=$copyStatus colored=$colored total=${bitmap.width * bitmap.height}")
                assertEquals("Cannot read decoded SurfaceView pixels", PixelCopy.SUCCESS, copyStatus)
                assertTrue("Decoded fixture is black or missing its colors: $colored colored pixels", colored > bitmap.width * bitmap.height / 2)
            }
        } finally {
            bitmap.recycle()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun firstFixtureIdr(): Pair<VideoConfig, ByteArray> {
        val nals = annexB(context.assets.open("test_clip.h264").use { it.readBytes() })
        val start = nals.indexOfFirst { type(it) == 5 }
        assertTrue("Clip must contain a real IDR", start >= 0)
        val parameterSets = nals.take(start).distinctBy { it.toList() }
        val config = H264Config.fromParameterSets(
            parameterSets.filter { type(it) == 7 }, parameterSets.filter { type(it) == 8 }, 4,
        )
        val idr = nals.drop(start).takeWhile { type(it) == 5 }
        val accessUnit = ByteArrayOutputStream().apply {
            for (nal in idr) {
                for (shift in listOf(24, 16, 8, 0)) write(nal.size ushr shift and 255)
                write(nal)
            }
        }.toByteArray()
        return config to accessUnit
    }

    private data class Reply(val status: Int, val body: ByteArray)

    private fun awaitFreeControlPort() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(7000))
                }
                return
            } catch (_: java.net.BindException) { SystemClock.sleep(20) }
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("RTSP 7000 is occupied; run port-ownership tests on an isolated emulator")
    }

    private fun connection(port: Int) = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), 2_000)
        soTimeout = 5_000
    }

    private fun exchange(socket: Socket, method: String, path: String, cseq: Int, body: ByteArray = ByteArray(0)): Reply {
        socket.getOutputStream().apply {
            write("$method $path RTSP/1.0\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
        // Test calls are request/response lockstep, with no pipelined server replies.
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0 && bytes.size() < 32 * 1024) { "Truncated/oversized response" }
                if (next == 13) { check(input.read() == 10); return bytes.toString("US-ASCII") }
                bytes.write(next)
            }
        }
        val status = line().split(' ')[1].toInt()
        var length = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.substringBefore(':').equals("Content-Length", true)) length = header.substringAfter(':').trim().toInt()
        }
        require(length in 0..2 * 1024 * 1024)
        val response = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(response, offset, length - offset)
            check(count > 0)
            offset += count
        }
        return Reply(status, response)
    }

    private fun type(nal: ByteArray) = nal.first().toInt() and 31
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun annexB(bytes: ByteArray): List<ByteArray> {
        val boundaries = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index <= bytes.size - 3) {
            val code = if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) 3
                else if (index <= bytes.size - 4 && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) 4 else 0
            } else 0
            if (code > 0) { boundaries.add(index to code); index += code } else index++
        }
        return boundaries.mapIndexedNotNull { i, (start, code) ->
            val end = boundaries.getOrNull(i + 1)?.first ?: bytes.size
            if (end > start + code) bytes.copyOfRange(start + code, end) else null
        }
    }
}
