package com.sal7one.tvtestproject

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.tvtestproject.receiver.AirPlayServer
import com.sal7one.tvtestproject.receiver.ReceiverState
import com.sal7one.tvtestproject.receiver.media.H264Config
import com.sal7one.tvtestproject.receiver.protocol.NativeFairPlaySession
import com.sal7one.tvtestproject.receiver.protocol.Plist
import com.sal7one.tvtestproject.receiver.protocol.SessionCrypto
import com.sal7one.tvtestproject.receiver.transport.SessionClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Real RTSP, native key derivation, continuous CTR, AVC decoding and SurfaceView pixels.
 * The generated FairPlay input is a test vector, not evidence of macOS interoperability.
 */
@RunWith(AndroidJUnit4::class)
class MirrorPlaybackIntegrationTest {
    @Test fun audioFlushPreservesVideoReferencesAndBlackIntroCanBecomeColored() =
        verifyMirrorPlayback(startupBurst = false)

    @Test fun startupBurstPreservesTheOnlyIdrWhileTheHardwareDecoderStarts() =
        verifyMirrorPlayback(startupBurst = true)

    private fun verifyMirrorPlayback(startupBurst: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var view: SurfaceView
        instrumentation.runOnMainSync { view = activity.findViewById(R.id.fullscreen_surface) }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!view.holder.surface.isValid && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Activity SurfaceView unavailable", view.holder.surface.isValid)
        val firstOutput = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            AirPlayServer(context) { if (it.state == ReceiverState.STREAMING) firstOutput.countDown() }.use { server ->
                server.attachSurface(view.holder.surface)
                val announcement = server.start()
                Socket(InetAddress.getLoopbackAddress(), announcement.port).use { control ->
                    control.soTimeout = 5_000
                    val input = BufferedInputStream(control.getInputStream())
                    var cseq = 0
                    fun request(method: String, body: ByteArray = byteArrayOf(), headers: String = "", path: String = "/stream"): ByteArray {
                        control.getOutputStream().apply {
                            write(("$method $path RTSP/1.0\r\nCSeq: ${++cseq}\r\n" + headers +
                                "Content-Length: ${body.size}\r\n\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                        val status = line(input)
                        assertTrue("RTSP failed: $status", status.startsWith("RTSP/1.0 200 "))
                        var length = 0
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            if (header.substringBefore(':').equals("Content-Length", true)) {
                                length = header.substringAfter(':').trim().toInt()
                            }
                        }
                        require(length in 0..2 * 1024 * 1024)
                        return ByteArray(length).also { bytes ->
                            var offset = 0
                            while (offset < bytes.size) {
                                val count = input.read(bytes, offset, bytes.size - offset)
                                check(count > 0)
                                offset += count
                            }
                        }
                    }
                    // Exercise the production FairPlay adapter with the same deterministic
                    // framing vector as the native tests; no captured user keys are retained.
                    fun fpInput(size: Int) = ByteArray(size).apply {
                        "FPLY".toByteArray().copyInto(this)
                        this[4] = 3
                    }
                    val setup = fpInput(16)
                    val handshake = fpInput(164)
                    val encryptedKey = ByteArray(72)
                    val key = NativeFairPlaySession().use {
                        it.setup(setup)
                        it.setup(handshake)
                        it.decryptKey(encryptedKey)
                    }
                    request("POST", setup, path = "/fp-setup")
                    request("POST", handshake, path = "/fp-setup")
                    DatagramSocket(0, InetAddress.getLoopbackAddress()).use { timing ->
                        request("SETUP", Plist.encode(mapOf("ekey" to encryptedKey, "eiv" to ByteArray(16),
                            "timingProtocol" to "NTP", "timingPort" to timing.localPort.toLong())))
                        val streamId = 123L
                        val stream = Plist.decode(request("SETUP", Plist.encode(mapOf("streams" to listOf(
                            mapOf("type" to 110L, "streamConnectionID" to streamId))))))
                        val values = (stream["streams"] as List<*>).single() as Map<*, *>
                        val port = (values["dataPort"] as Number).toInt()
                        val nals = annexB(instrumentation.context.assets.open("black-to-color.h264").use { it.readBytes() })
                        val sps = nals.first { type(it) == 7 }
                        val pps = nals.first { type(it) == 8 }
                        val avcc = ByteArrayOutputStream().apply {
                            write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
                            write(sps.size ushr 8); write(sps.size and 255); write(sps)
                            write(1); write(pps.size ushr 8); write(pps.size and 255); write(pps)
                        }.toByteArray()
                        H264Config.parseAvcc(avcc)
                        val units = mutableListOf<MutableList<ByteArray>>()
                        for (nal in nals) {
                            if (type(nal) == 9) units.add(mutableListOf())
                            if (type(nal) !in listOf(7, 8, 9)) units.last().add(nal)
                        }
                        assertTrue("Fixture must exceed the bounded video queue", units.size > 12)
                        assertTrue(units.first().any { type(it) == 5 })
                        assertTrue("Fixture must have no recovery IDR after the flush", units.drop(1).flatten().none { type(it) == 5 })
                        val material = SessionCrypto.deriveMirrorKeyIv(key, streamId)
                        val encryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
                            init(Cipher.ENCRYPT_MODE, SecretKeySpec(material.key, "AES"), IvParameterSpec(material.iv))
                        }
                        Socket(InetAddress.getLoopbackAddress(), port).use { mirror ->
                            mirror.tcpNoDelay = true
                            val start = System.currentTimeMillis() * 1000
                            fun packet(type: Int, payload: ByteArray, frame: Int) {
                                val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
                                    putInt(payload.size); put(type.toByte())
                                    putLong(8, SessionClock.ntpFromUnixUs(start + frame * 100_000L) - SessionClock.NTP_EPOCH_OFFSET)
                                }.array()
                                mirror.getOutputStream().apply { write(header); write(payload); flush() }
                            }
                            fun sendUnit(index: Int) {
                                val bytes = ByteArrayOutputStream().apply {
                                    for (nal in units[index]) {
                                        write(ByteBuffer.allocate(4).putInt(nal.size).array()); write(nal)
                                    }
                                }.toByteArray()
                                packet(0, encryptor.update(bytes), index)
                            }
                            packet(1, avcc, 0)
                            if (startupBurst) {
                                // No render wait or pacing: reproduce the real sender filling the
                                // bounded queue while a slow hardware codec is still configuring.
                                for (index in units.indices) sendUnit(index)
                                assertTrue("Startup burst lost the only IDR", firstOutput.await(10, TimeUnit.SECONDS))
                            } else {
                                sendUnit(0)
                                assertTrue("Initial IDR did not reach the decoder", firstOutput.await(10, TimeUnit.SECONDS))
                                assertTrue("Initial fixture frame must really be black", coloredPixels(view, bitmap) < 10)
                                request("FLUSH", headers = "RTP-Info: seq=123;rtptime=480\r\n")
                                for (index in 1 until units.size) { sendUnit(index); SystemClock.sleep(100) }
                            }
                            val deadline = SystemClock.elapsedRealtime() + 5_000
                            var colored: Int
                            do {
                                colored = coloredPixels(view, bitmap)
                                if (colored > bitmap.width * bitmap.height / 3) break
                                SystemClock.sleep(20)
                            } while (SystemClock.elapsedRealtime() < deadline)
                            assertTrue("Mirror lost reference pictures (startupBurst=$startupBurst): only $colored colored pixels",
                                colored > bitmap.width * bitmap.height / 3)
                            android.util.Log.i("FossPixelTest", "encrypted_mirror startupBurst=$startupBurst colored=$colored total=${bitmap.width * bitmap.height}")
                            request("TEARDOWN", Plist.encode(mapOf("streams" to listOf(mapOf("type" to 110L)))))
                        }
                        key.fill(0); material.key.fill(0); material.iv.fill(0)
                    }
                }
            }
        } finally {
            bitmap.recycle()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun coloredPixels(view: SurfaceView, bitmap: Bitmap): Int {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val copied = CountDownLatch(1)
            var status = -1
            PixelCopy.request(view.holder.surface, bitmap, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(copied.await(2, TimeUnit.SECONDS))
            if (status == PixelCopy.SUCCESS) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return pixels.count {
                    maxOf(Color.red(it), Color.green(it), Color.blue(it)) -
                        minOf(Color.red(it), Color.green(it), Color.blue(it)) > 80
                }
            }
            SystemClock.sleep(20)
        }
        fail("Cannot read decoded SurfaceView pixels")
        return 0
    }

    private fun type(nal: ByteArray) = nal[0].toInt() and 31

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

    private fun line(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            check(next >= 0 && bytes.size() < 32 * 1024)
            if (next == 13) { check(input.read() == 10); return bytes.toString("US-ASCII") }
            bytes.write(next)
        }
    }
}
