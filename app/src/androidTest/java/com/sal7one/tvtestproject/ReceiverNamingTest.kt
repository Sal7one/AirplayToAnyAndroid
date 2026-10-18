package com.sal7one.tvtestproject

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.tvtestproject.receiver.AirPlayServer
import com.sal7one.tvtestproject.receiver.protocol.Plist
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import javax.jmdns.ServiceInfo

@RunWith(AndroidJUnit4::class)
class ReceiverNamingTest {
    @Test fun activityShowsTheSameDeviceNameAsDiscovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val expected = DeviceIdentity.displayName(context)
                assertEquals(expected.removePrefix("${DeviceIdentity.displayName()} - "),
                    activity.findViewById<TextView>(R.id.receiver_device_label).text.toString())
                assertEquals(expected, activity.findViewById<TextView>(R.id.stream_name).text.toString())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun twoInstallationsHaveDistinctStableDiscoveryAndInfoNames() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val tv = Installation(base, true, "02:11:22:a1:b2:c3")
        val phone = Installation(base, false, "02:11:22:d4:e5:f6")
        try {
            AirPlayServer(tv) { }.use { first -> AirPlayServer(phone) { }.use { second ->
                val a = first.start(); val b = second.start()
                assertEquals("FossMirrorTV - TV A1B2C3", a.name)
                assertEquals("FossMirrorTV - Phone D4E5F6", b.name)
                assertNotEquals(a.name, b.name); assertNotEquals(a.port, b.port)
                assertNotEquals(a.deviceId, b.deviceId); assertNotEquals(a.pairingId, b.pairingId)
                assertNotEquals(a.publicKeyHex, b.publicKeyHex)
                for (announcement in listOf(a, b)) {
                    assertEquals(announcement.name, infoName(announcement.port))
                    val airplay = ServiceInfo.create("_airplay._tcp.local.", announcement.name,
                        announcement.port, 0, 0, UxPlayDnsTxt.airplayTxt(announcement))
                    val raop = ServiceInfo.create("_raop._tcp.local.",
                        DeviceIdentity.raopServiceInstanceName(announcement.deviceId, announcement.name),
                        announcement.port, 0, 0, UxPlayDnsTxt.raopTxt(announcement))
                    assertEquals(announcement.name, airplay.name)
                    assertTrue(raop.name.endsWith("@${announcement.name}"))
                }
            } }
            val expected = DeviceIdentity.displayName(tv)
            AirPlayServer(tv) { }.use { assertEquals(expected, it.start().name) }
            assertEquals(expected, DeviceIdentity.displayName(tv))
        } finally { tv.remove(); phone.remove() }
    }

    private fun infoName(port: Int): String = Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write("GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String = buildString {
            while (true) { val c = input.read(); check(c >= 0); if (c == 10) break; if (c != 13) append(c.toChar()) }
        }
        assertTrue(line().startsWith("RTSP/1.0 200"))
        var length = 0
        while (true) {
            val header = line(); if (header.isEmpty()) break
            if (header.substringBefore(':').equals("Content-Length", true)) length = header.substringAfter(':').trim().toInt()
        }
        check(length in 1..1024 * 1024)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
        Plist.decode(body)["name"] as String
    }

    private class Installation(base: Context, tv: Boolean, id: String) : ContextWrapper(base) {
        private val namespace = "naming-test-${java.util.UUID.randomUUID()}"
        private val directory = File(base.cacheDir, namespace)
        private val configured = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        })
        init { getSharedPreferences("receiver_identity", MODE_PRIVATE).edit().putString("device_id", id).commit() }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory.also { check(it.isDirectory || it.mkdirs()) }
        override fun getResources(): Resources = configured.resources
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$namespace-$name", mode)
        fun remove() {
            baseContext.deleteSharedPreferences("$namespace-receiver_identity")
            baseContext.deleteSharedPreferences("$namespace-receiver_playback")
            directory.deleteRecursively()
        }
    }
}
