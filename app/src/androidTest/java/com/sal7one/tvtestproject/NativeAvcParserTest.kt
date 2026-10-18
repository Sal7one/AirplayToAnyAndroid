package com.sal7one.tvtestproject

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sal7one.tvtestproject.receiver.media.H264Config
import com.sal7one.tvtestproject.receiver.media.NativeAvcParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeAvcParserTest {
    @Test fun nativeMatchesReferenceForHeadersParameterSetsAndEveryTruncation() {
        val sets = listOf(hex("67640028acd940780227e5c044000003000400000300f03c60c658"), hex("68ebe3cb22c0"))
        val samples = listOf(listOf(byteArrayOf(9, -16), byteArrayOf(0x65, 0xb8.toByte())),
            listOf(byteArrayOf(0x41, 0xb8.toByte())), sets,
            sets + listOf(byteArrayOf(0x65, 0xb8.toByte())),
            listOf(byteArrayOf(0x65, 0xb8.toByte()), byteArrayOf(0x41, 0xb8.toByte())),
            listOf(byteArrayOf(0x65, 0x4e)), listOf(byteArrayOf(0x65, 0, 0, 3, 4)))
        for (width in listOf(1, 2, 4)) for (nals in samples) {
            val payload = packet(nals, width)
            for (size in 0..payload.size) parity(payload.copyOf(size), width)
        }
        val tooMany = packet(List(4097) { byteArrayOf(12, -1) }, 1)
        parity(tooMany, 1)
        parity(ByteArray(H264Config.MAX_ACCESS_UNIT_BYTES + 1), 4)
        parity(byteArrayOf(1), 3)
    }

    @Test fun deterministicMutationsMatchTheReferenceWithoutChangingSource() {
        val random = Random(0x415643)
        repeat(3000) {
            val width = listOf(1, 2, 4)[it % 3]
            val bytes = packet(listOf(byteArrayOf(0x65, 0xb8.toByte())), width).copyOf(random.nextInt(65))
            repeat(1 + it % 5) { if (bytes.isNotEmpty()) {
                val at = random.nextInt(bytes.size)
                bytes[at] = (bytes[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            } }
            val saved = bytes.copyOf()
            parity(bytes, width)
            assertArrayEquals(saved, bytes)
        }
    }

    @Test fun statelessNativeCallsAreSafeFromMultipleThreads() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { executor.submit {
                repeat(100) { parity(packet(listOf(byteArrayOf(0x65, 0xb8.toByte())), 4), 4) }
            } }
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    @Test fun nativeBoundaryIndependentlyRejectsNullAndWrongMetadata() {
        NativeAvcParser.parse(packet(listOf(byteArrayOf(0x65, 0xb8.toByte())), 4), 4)
        val method = NativeAvcParser.javaClass.getDeclaredMethod("nativeParse", ByteArray::class.java,
            Int::class.javaPrimitiveType, IntArray::class.java).apply { isAccessible = true }
        fun rejected(bytes: ByteArray?, width: Int, metadata: IntArray?) {
            val error = assertThrows(InvocationTargetException::class.java) {
                method.invoke(NativeAvcParser, bytes, width, metadata)
            }
            assertTrue(error.cause is IllegalArgumentException)
        }
        val good = packet(listOf(byteArrayOf(0x65, 0xb8.toByte())), 4)
        rejected(null, 4, IntArray(3)); rejected(good, 4, null)
        rejected(good, 4, IntArray(2)); rejected(good, 4, IntArray(4))
        rejected(good, 3, IntArray(3)); rejected(byteArrayOf(), 4, IntArray(3))
    }

    @Test fun benchmarkReportsOldDoubleParseAndNewNativePathWithoutSpeedAssertions() {
        val payload = packet(listOf(ByteArray(64 * 1024) { -1 }.also { it[0] = 6 },
            byteArrayOf(0x65, 0xb8.toByte())), 4)
        repeat(200) { H264Config.parseAccessUnit(payload, 4); NativeAvcParser.parse(payload, 4) }
        val old = LongArray(9)
        val native = LongArray(9)
        val managed = LongArray(9)
        var checksum = 0
        for (trial in old.indices) {
            val singleStart = System.nanoTime()
            repeat(100) { checksum = checksum xor H264Config.parseAccessUnit(payload, 4).annexB.size }
            managed[trial] = (System.nanoTime() - singleStart) / 100
            fun measure(nativePath: Boolean): Long {
                val start = System.nanoTime()
                repeat(100) {
                    if (nativePath) checksum = checksum xor NativeAvcParser.parse(payload, 4).annexB.size
                    else {
                        checksum = checksum xor H264Config.parseAccessUnit(payload, 4).annexB.size
                        checksum = checksum xor H264Config.parseAccessUnit(payload, 4).annexB.size
                    }
                }
                return (System.nanoTime() - start) / 100
            }
            if (trial % 2 == 0) { old[trial] = measure(false); native[trial] = measure(true) }
            else { native[trial] = measure(true); old[trial] = measure(false) }
        }
        old.sort(); native.sort(); managed.sort()
        Log.i("FossAvcBench", "bytes=${payload.size} framesPerBatch=100 batches=9 " +
            "old_double_parse_median_ns=${old[4]} managed_single_parse_median_ns=${managed[4]} " +
            "native_single_parse_median_ns=${native[4]} checksum=$checksum")
    }

    private fun parity(bytes: ByteArray, width: Int) {
        val expected = runCatching { H264Config.parseAccessUnit(bytes, width) }
        val actual = runCatching { NativeAvcParser.parse(bytes, width) }
        assertEquals("Different acceptance for width=$width bytes=${bytes.size}", expected.isSuccess, actual.isSuccess)
        if (expected.isSuccess) {
            val a = expected.getOrThrow(); val b = actual.getOrThrow()
            assertArrayEquals(a.annexB, b.annexB); assertEquals(a.isIdr, b.isIdr)
            assertEquals(a.hasVcl, b.hasVcl); assertEquals(a.ppsId, b.ppsId)
            assertEquals(a.sps.size, b.sps.size); assertEquals(a.pps.size, b.pps.size)
            a.sps.indices.forEach { assertArrayEquals(a.sps[it], b.sps[it]) }
            a.pps.indices.forEach { assertArrayEquals(a.pps[it], b.pps[it]) }
        } else {
            assertTrue(expected.exceptionOrNull() is IllegalArgumentException)
            assertTrue(actual.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun packet(nals: List<ByteArray>, width: Int): ByteArray = ByteArrayOutputStream().apply {
        for (nal in nals) { for (i in width - 1 downTo 0) write(nal.size ushr (8 * i)); write(nal) }
    }.toByteArray()
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
