package com.sal7one.tvtestproject.receiver.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class H265ConfigTest {
    // FFmpeg/libx265 4.2: one 1920x1080 yuv420p frame, and one 3840x2160 yuv420p10le frame.
    // ffmpeg -f lavfi -i color=size=1920x1080:rate=1 -frames:v 1 -c:v libx265
    //   -x265-params pools=1:frame-threads=1:log-level=error -tag:v hvc1 output.mp4
    // Parameter sets extracted from the hvcC box; encoder-identification SEI is intentionally omitted.
    @Test fun libx265Main1080SpsHasActualDimensionsAndProfile() {
        val info = H265Config.parseSps(SPS_1080)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(1080, info.codedHeight)
        assertEquals(0, info.cropBottom)
        assertEquals(1, info.profile.profileIdc)
        assertEquals(120, info.profile.levelIdc)
        assertEquals(8, info.bitDepthLuma)
        assertEquals(1, info.chromaFormatIdc)
    }

    @Test fun libx265Main10UhdRetainsTenBitProfileAndLevel() {
        val info = H265Config.parseSps(SPS_4K)
        assertEquals(3840, info.width)
        assertEquals(2160, info.height)
        assertEquals(2, info.profile.profileIdc)
        assertEquals(150, info.profile.levelIdc)
        assertEquals(10, info.bitDepthLuma)
        assertEquals(10, info.bitDepthChroma)
    }

    @Test fun hvccPreservesAllThreeParameterSetsAndBuildsSingleAndroidCsd() {
        val config = H265Config.parseHvcc(hvcc())
        assertEquals(4, config.nalLengthSize)
        assertArrayEquals(VPS_1080, config.vps.single())
        assertArrayEquals(SPS_1080, config.sps.single())
        assertArrayEquals(PPS, config.pps.single())
        assertArrayEquals(annexB(listOf(VPS_1080, SPS_1080, PPS)), H265Config.codecSpecificData(config))
        val main10 = H265Config.parseHvcc(hvcc(main10 = true))
        assertEquals(3840, main10.width)
    }

    @Test fun hvc1SampleEntryUsesBoxesNotHardcodedArrayOffset() {
        val canonical = sampleEntry(hvcc())
        assertEquals(0xa0, canonical[0x75].toInt() and 0xff) // Matches UxPlay's reference offset.
        assertEquals(1080, H265Config.parseMirrorCodecData(canonical).height)
        val withUnknownChild = sampleEntry(hvcc(), before = box("pasp", ByteArray(8)))
        assertEquals(1080, H265Config.parseMirrorCodecData(withUnknownChild).height)
        assertEquals(2160, H265Config.parseMirrorCodecData(sampleEntry(hvcc(main10 = true), width = 3840, height = 2160)).height)
    }

    @Test fun everyTruncatedConfigurationAndSampleEntryFailsCleanly() {
        val record = hvcc()
        for (size in 0 until record.size) rejects { H265Config.parseHvcc(record.copyOf(size)) }
        val entry = sampleEntry(record)
        for (size in 0 until entry.size) rejects { H265Config.parseMirrorCodecData(entry.copyOf(size)) }
        for (size in 0 until SPS_1080.size) rejects { H265Config.parseSps(SPS_1080.copyOf(size)) }
    }

    @Test fun reservedFieldsAndUnsupportedLengthSizeAreRejected() {
        for ((offset, value) in listOf(0 to 0, 13 to 0, 15 to 0, 16 to 0, 17 to 0, 18 to 0, 21 to 0x0e, 22 to 0xff, 23 to 0xe0)) {
            rejects { H265Config.parseHvcc(hvcc().also { it[offset] = value.toByte() }) }
        }
        for (size in listOf(1, 2, 4)) assertEquals(size, H265Config.parseHvcc(hvcc(lengthSize = size)).nalLengthSize)
    }

    @Test fun malformedBoxesNeverScanOrOverread() {
        rejects { H265Config.parseMirrorCodecData(sampleEntry(hvcc()).also { it[4] = 'x'.code.toByte() }) }
        rejects { H265Config.parseMirrorCodecData(sampleEntry(hvcc()).also { it[0] = 0x7f }) }
        rejects { H265Config.parseMirrorCodecData(sampleEntry(hvcc()).also { it[86] = 0x7f }) }
        rejects { H265Config.parseMirrorCodecData(sampleEntry(hvcc(), before = box("hvcC", hvcc()))) }
        rejects { H265Config.parseMirrorCodecData(sampleEntry(hvcc(), width = 1280)) }
        rejects { H265Config.parseMirrorCodecData(sampleEntry(hvcc()).also { it[89] = 0 }) }
    }

    @Test fun wrongMissingDuplicateAndUnreferencedParameterSetsAreRejected() {
        rejects { H265Config.parseHvcc(hvcc() + byteArrayOf(0)) }
        rejects { H265Config.parseHvcc(hvcc().also { it[27] = 0 }) }
        rejects { H265Config.fromParameterSets(emptyList(), listOf(SPS_1080), listOf(PPS)) }
        rejects { H265Config.fromParameterSets(listOf(VPS_1080), listOf(SPS_1080, SPS_1080), listOf(PPS)) }
        rejects { H265Config.fromParameterSets(listOf(VPS_1080, VPS_1080), listOf(SPS_1080), listOf(PPS)) }
        rejects { H265Config.fromParameterSets(listOf(VPS_1080), listOf(SPS_1080), listOf(PPS, PPS)) }
        rejects { H265Config.fromParameterSets(listOf(VPS_1080), listOf(SPS_1080), listOf(nal(34, bits { ue(0); ue(1) }))) }
        rejects { H265Config.fromParameterSets(listOf(VPS_1080.alsoCopy { it[2] = 0x1c }), listOf(SPS_1080), listOf(PPS)) }
        rejects { H265Config.parseHvcc(hvcc().also { it[1] = 2 }) }
        rejects { H265Config.parseHvcc(hvcc().also { it[18] = 0xfa.toByte() }) }
    }

    @Test fun configurationDoesNotAliasCallerStorage() {
        val record = hvcc()
        val config = H265Config.parseHvcc(record)
        record.fill(0)
        assertArrayEquals(SPS_1080, config.sps.single())
        val source = SPS_1080.copyOf()
        val copied = H265Config.fromParameterSets(listOf(VPS_1080), listOf(source), listOf(PPS))
        source.fill(0)
        assertArrayEquals(SPS_1080, copied.sps.single())
    }

    @Test fun cropUnitsRespectChromaAndSeparateColourPlane() {
        assertEquals(636, H265Config.parseSps(sps(chroma = 1, right = 2)).width)
        assertEquals(477, H265Config.parseSps(sps(chroma = 2, bottom = 3)).height)
        assertEquals(638, H265Config.parseSps(sps(chroma = 3, separate = true, right = 2)).width)
        rejects { H265Config.parseSps(sps(right = 320)) }
        rejects { H265Config.parseSps(sps(width = 0)) }
    }

    @Test fun subLayerProfileAndLevelFieldsAreConsumed() {
        val info = H265Config.parseSps(sps(subLayers = 2))
        assertEquals(3, info.maxSubLayers)
        assertEquals(640, info.width)
        rejects { H265Config.parseSps(sps(subLayers = 7)) }
    }

    @Test fun malformedSpsRbspAndGolombHaveControlledFailures() {
        for (suffix in listOf(byteArrayOf(0), byteArrayOf(0, 0, 3), byteArrayOf(0, 0, 3, 4))) {
            rejects { H265Config.parseSps(SPS_1080 + suffix) }
        }
        rejects { H265Config.parseSps(SPS_1080.alsoCopy { it[0] = 0xc2.toByte() }) }
        rejects { H265Config.parseSps(SPS_1080.alsoCopy { it[1] = 0 }) }
        rejects { H265Config.parseSps(nal(33, ByteArray(128))) }
    }

    @Test fun allLengthSizesConvertCompleteMultiSliceIdrWithoutSplittingPictures() {
        val nals = listOf(nal(35), slice(19), slice(19, first = false))
        for (size in listOf(1, 2, 4)) {
            val unit = H265Config.parseAccessUnit(lengthPrefixed(nals, size), size)
            assertTrue(unit.hasVcl)
            assertTrue(unit.isIdr)
            assertTrue(unit.isIrap)
            assertFalse(unit.isCra)
            assertEquals(0, unit.ppsId)
            assertArrayEquals(annexB(nals), unit.annexB)
        }
    }

    @Test fun craAndBlaAreNotMislabelledAsIdr() {
        for (type in listOf(16, 17, 18, 21)) {
            val unit = H265Config.parseAccessUnit(lengthPrefixed(listOf(slice(type))))
            assertFalse(unit.isIdr)
            assertTrue(unit.isIrap)
            assertEquals(type == 21, unit.isCra)
        }
        val dependent = H265Config.parseAccessUnit(lengthPrefixed(listOf(slice(1))))
        assertFalse(dependent.isIrap)
        assertTrue(dependent.hasVcl)
        val sets = H265Config.parseAccessUnit(lengthPrefixed(listOf(VPS_1080, SPS_1080, PPS)))
        assertFalse(sets.hasVcl)
        assertEquals(1, sets.vps.size)
        assertArrayEquals(SPS_1080, sets.sps.single())
    }

    @Test fun incompleteMixedAndMultiPictureAccessUnitsAreRejected() {
        for (nals in listOf(listOf(slice(19, first = false)), listOf(slice(19), slice(1, first = false)),
            listOf(slice(19), slice(19)), listOf(slice(19), slice(19, first = false, pps = 1)),
            listOf(slice(19), slice(19, first = false).alsoCopy { it[1] = 2 }))) {
            rejects { H265Config.parseAccessUnit(lengthPrefixed(nals)) }
        }
    }

    @Test fun untrustedNalLengthsHeadersAndLimitsAreRejected() {
        for (payload in listOf(byteArrayOf(), byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(-1, -1, -1, -1), byteArrayOf(0, 0, 0, 2, 38, 1))) {
            rejects { H265Config.parseAccessUnit(payload) }
        }
        for (header in listOf(byteArrayOf(0x80.toByte(), 1, 1), byteArrayOf(38, 0, 1), byteArrayOf(39, 1, 1),
            byteArrayOf(38, 9, 1), nal(10), nal(22), nal(41), nal(63))) {
            rejects { H265Config.parseAccessUnit(lengthPrefixed(listOf(header))) }
        }
        rejects { H265Config.parseAccessUnit(lengthPrefixed(listOf(slice(19))) + byteArrayOf(0)) }
        rejects { H265Config.parseAccessUnit(lengthPrefixed(List(4097) { nal(38) }, 1), 1) }
        rejects { H265Config.parseAccessUnit(ByteArray(H265Config.MAX_ACCESS_UNIT_BYTES + 1)) }
        rejects { H265Config.parseAccessUnit(byteArrayOf(1), 3) }
        // Two-byte framing expands by two bytes per NAL when converted to Annex-B.
        val nearLimit = List(4096) { nal(38, ByteArray(2044)) }
        val expansion = lengthPrefixed(nearLimit, 2)
        assertTrue(expansion.size <= H265Config.MAX_ACCESS_UNIT_BYTES)
        rejects { H265Config.parseAccessUnit(expansion, 2) }
    }

    @Test fun randomMalformedDataNeverEscapesAsIndexOrAllocationErrors() {
        val random = Random(0x265L)
        repeat(2500) {
            val bytes = ByteArray(random.nextInt(512)).also { random.nextBytes(it) }
            try { H265Config.parseHvcc(bytes) } catch (_: IllegalArgumentException) { }
            try { H265Config.parseMirrorCodecData(bytes) } catch (_: IllegalArgumentException) { }
            try { H265Config.parseSps(bytes) } catch (_: IllegalArgumentException) { }
            try { H265Config.parseAccessUnit(bytes, listOf(1, 2, 4)[it % 3]) } catch (_: IllegalArgumentException) { }
        }
    }

    private fun rejects(block: () -> Any?) {
        try { block() } catch (_: IllegalArgumentException) { return }
        fail("Expected IllegalArgumentException")
    }

    private fun hvcc(main10: Boolean = false, lengthSize: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        val header = hex(if (main10) "01022000000090000000000096f000fcfdfafa00000f03" else "01016000000090000000000078f000fcfdf8f800000f03")
        header[21] = ((header[21].toInt() and 0xfc) or (lengthSize - 1)).toByte()
        out.write(header)
        for ((type, nal) in listOf(32 to if (main10) VPS_4K else VPS_1080, 33 to if (main10) SPS_4K else SPS_1080, 34 to PPS)) {
            out.write(0x80 or type); out.write(0); out.write(1)
            out.write(nal.size ushr 8); out.write(nal.size); out.write(nal)
        }
        return out.toByteArray()
    }

    private fun sampleEntry(record: ByteArray, width: Int = 1920, height: Int = 1080, before: ByteArray = byteArrayOf()): ByteArray {
        val header = ByteArray(78)
        header[7] = 1
        header[24] = (width ushr 8).toByte(); header[25] = width.toByte()
        header[26] = (height ushr 8).toByte(); header[27] = height.toByte()
        return box("hvc1", header + before + box("hvcC", record))
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        bigEndian(body.size + 8, 4) + type.toByteArray(Charsets.US_ASCII) + body

    private fun lengthPrefixed(nals: List<ByteArray>, size: Int = 4): ByteArray =
        ByteArrayOutputStream().also { out -> nals.forEach { out.write(bigEndian(it.size, size)); out.write(it) } }.toByteArray()

    private fun annexB(nals: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out -> nals.forEach { out.write(byteArrayOf(0, 0, 0, 1)); out.write(it) } }.toByteArray()

    private fun bigEndian(value: Int, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr ((bytes - it - 1) * 8)).toByte() }
    private fun nal(type: Int, payload: ByteArray = byteArrayOf(0x80.toByte())) = byteArrayOf((type shl 1).toByte(), 1) + payload
    private fun slice(type: Int, first: Boolean = true, pps: Int = 0): ByteArray =
        nal(type, bits { flag(first); if (type in 16..21) flag(false); ue(pps) })
    private fun ByteArray.alsoCopy(block: (ByteArray) -> Unit): ByteArray = copyOf().also(block)

    private fun sps(chroma: Int = 1, separate: Boolean = false, width: Int = 640, right: Int = 0, bottom: Int = 0, subLayers: Int = 0): ByteArray =
        nal(33, bits {
            fixed(0, 4); fixed(subLayers.toLong(), 3); flag(true)
            fixed(1, 8); fixed(0x60000000, 32); fixed(0, 48); fixed(120, 8)
            repeat(subLayers) { flag(it == 0); flag(true) }
            if (subLayers > 0) repeat(8 - subLayers) { fixed(0, 2) }
            repeat(subLayers) { if (it == 0) { fixed(1, 8); fixed(0, 32); fixed(0, 48) }; fixed(120, 8) }
            ue(0); ue(chroma); if (chroma == 3) flag(separate)
            ue(width); ue(480); flag(right != 0 || bottom != 0)
            if (right != 0 || bottom != 0) { ue(0); ue(right); ue(0); ue(bottom) }
            ue(0); ue(0); ue(4); flag(false); ue(4); ue(0); ue(0)
            ue(0); ue(3); ue(0); ue(3); ue(0); ue(0)
            flag(false); flag(false); flag(false); flag(false)
            ue(0); flag(false); flag(false); flag(false); flag(false); flag(false)
        })

    private class Writer {
        private val values = ArrayList<Int>()
        fun fixed(value: Long, count: Int) { repeat(count) { values.add(((value ushr (count - it - 1)) and 1).toInt()) } }
        fun flag(value: Boolean) = fixed(if (value) 1 else 0, 1)
        fun ue(value: Int) { val code = value.toLong() + 1; val width = 64 - java.lang.Long.numberOfLeadingZeros(code); repeat(width - 1) { flag(false) }; fixed(code, width) }
        fun finish(): ByteArray {
            flag(true)
            while (values.size % 8 != 0) flag(false)
            val rbsp = ByteArray(values.size / 8) { i -> (0 until 8).fold(0) { v, b -> (v shl 1) or values[i * 8 + b] }.toByte() }
            val out = ByteArrayOutputStream()
            var zeros = 0
            for (byte in rbsp) {
                val value = byte.toInt() and 0xff
                if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0 }
                out.write(value); zeros = if (value == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }

    private fun bits(block: Writer.() -> Unit): ByteArray = Writer().apply(block).finish()

    companion object {
        private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        internal val VPS_1080 = hex("40010c01ffff016000000300900000030000030078959809")
        internal val SPS_1080 = hex("420101016000000300900000030000030078a003c08010e596566924caf016808000000300800000030084")
        internal val VPS_4K = hex("40010c01ffff022000000300900000030000030096959809")
        internal val SPS_4K = hex("420101022000000300900000030000030096a001e020021c4d96566924caf016808000000300800000030084")
        internal val PPS = hex("4401c172b46240")
    }
}
