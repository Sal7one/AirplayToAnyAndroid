package com.sal7one.tvtestproject.receiver.media

import android.view.Surface
import com.sal7one.tvtestproject.receiver.VideoConfig
import com.sal7one.tvtestproject.receiver.VideoSink
import org.junit.Assert.*
import org.junit.Test

class PreparedVideoSinkTest {
    @Test fun preparedOutputOwnsItsBytesAfterTheWireBufferIsWiped() {
        val wire = byteArrayOf(0, 0, 0, 2, 0x65, 0xb8.toByte())
        val unit = H264Config.parseAccessUnit(wire, 4)
        wire.fill(0)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0xb8.toByte()), unit.annexB)
        assertTrue(unit.isIdr)
    }

    @Test fun legacySinksReceiveOriginalFramingAndValidatedKeyframeFlag() {
        val wire = byteArrayOf(2, 0x65, 0xb8.toByte())
        var calls = 0
        val sink = object : VideoSink {
            override fun configure(config: VideoConfig) = true
            override fun enqueue(accessUnit: ByteArray, ptsUs: Long, isKeyframe: Boolean): Boolean {
                assertSame(wire, accessUnit); assertEquals(123L, ptsUs); assertTrue(isKeyframe)
                calls++; return true
            }
            override fun attachSurface(surface: Surface?) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        }
        val prepared = sink.prepareAccessUnit(wire, 1)
        assertEquals(0, calls)
        assertTrue(sink.enqueuePrepared(prepared, 123))
        assertEquals(1, calls)
    }
}
