package com.sal7one.tvtestproject

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.tvtestproject.receiver.media.NativeAlacDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeAlacDecoderTest {
    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("alac/$name").use { it.readBytes() }

    @Test fun realFfmpegPacketsDecodeToOriginalPcm() {
        NativeAlacDecoder(44_100, 2, 4096).use { decoder ->
            for (name in listOf("ramp4096", "ramp352", "noise352")) {
                val expected = asset("$name.pcm")
                assertArrayEquals(name, expected, decoder.decode(asset("$name.alac")))
                decoder.flush()
                assertArrayEquals("$name after flush", expected, decoder.decode(asset("$name.alac")))
            }
        }
        NativeAlacDecoder(44_100, 2, 352).use { decoder ->
            assertArrayEquals(asset("ramp352.pcm"), decoder.decode(asset("ramp352.alac")))
            assertArrayEquals(asset("noise352.pcm"), decoder.decode(asset("noise352.alac")))
        }
    }

    @Test fun unsupportedConfigurationsAreRejected() {
        for ((rate, channels, frames) in listOf(
            Triple(48_000, 2, 4096), Triple(44_100, 1, 4096),
            Triple(44_100, 2, 351), Triple(44_100, 2, 4097),
            Triple(44_100, 2, Int.MAX_VALUE),
        )) {
            assertThrows(IllegalArgumentException::class.java) { NativeAlacDecoder(rate, channels, frames).close() }
        }
    }

    @Test fun malformedPacketsAreRejectedAndDoNotPoisonDecoder() {
        val packet = asset("ramp352.alac")
        NativeAlacDecoder(44_100, 2, 352).use { decoder ->
            val invalid = listOf(
                byteArrayOf(), ByteArray(65_537), byteArrayOf(0xe0.toByte()), ByteArray(12),
                declaredSamples(0), declaredSamples(353), declaredSamples(0xffffffffL),
                asset("ramp4096.alac"), packet + byteArrayOf(0),
            )
            for (frame in invalid) assertThrows(IllegalArgumentException::class.java) { decoder.decode(frame) }
            for (size in 1 until packet.size)
                assertThrows("truncated at $size", IllegalArgumentException::class.java) {
                    decoder.decode(packet.copyOf(size))
                }
            assertArrayEquals(asset("ramp352.pcm"), decoder.decode(packet))
        }
    }

    @Test fun instancesAreIndependentAndCloseIsIdempotent() {
        val first = NativeAlacDecoder(44_100, 2, 352)
        NativeAlacDecoder(44_100, 2, 4096).use { second ->
            first.close()
            first.close()
            assertThrows(IllegalStateException::class.java) { first.decode(asset("ramp352.alac")) }
            assertThrows(IllegalStateException::class.java) { first.flush() }
            assertArrayEquals(asset("ramp4096.pcm"), second.decode(asset("ramp4096.alac")))
        }
    }

    @Test fun concurrentDecodeFlushAndCloseCannotUseFreedHandle() {
        val decoder = NativeAlacDecoder(44_100, 2, 352)
        val packet = asset("ramp352.alac")
        val expected = asset("ramp352.pcm")
        val start = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val failure = AtomicReference<Throwable?>()
        val actions: List<() -> Unit> = listOf(
            { repeat(40) { try { assertArrayEquals(expected, decoder.decode(packet)) } catch (_: IllegalStateException) { } } },
            { repeat(40) { try { decoder.flush() } catch (_: IllegalStateException) { } } },
            { repeat(40) { decoder.close() } },
        )
        actions.forEach { action ->
            Thread {
                try { start.await(); action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { finished.countDown() }
            }.start()
        }
        start.countDown()
        try {
            assertEquals(true, finished.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Concurrent ALAC operation failed", it) }
        } finally { decoder.close() }
    }

    private fun declaredSamples(count: Long): ByteArray {
        val bytes = ByteArray(8)
        var position = 0
        fun put(value: Long, width: Int) {
            repeat(width) { bit ->
                val valueBit = ((value ushr (width - bit - 1)) and 1).toInt()
                bytes[position / 8] = (bytes[position / 8].toInt() or (valueBit shl (7 - position % 8))).toByte()
                position++
            }
        }
        put(1, 3); put(0, 4); put(0, 12); put(1, 1); put(0, 2); put(1, 1)
        put(count, 32); put(7, 3)
        return bytes
    }
}
