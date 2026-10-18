package com.sal7one.tvtestproject.receiver.media

/**
 * Single-layer HEVC parsing, independent of receiver negotiation and MediaCodec ownership.
 * Mirror codec data: jqssun/airplay's UxPlay 4621533, lib/raop_rtp_mirror.c:625-705
 * identifies an hvc1 visual sample entry; its first hvcC array is at 0x75.
 * Parse the boxes instead of trusting that offset. Type-0 bytes must already be decrypted.
 * Syntax: ITU-T H.265, 7.3.1.2, 7.3.2.2, 7.3.3; ISO/IEC 14496-15 HEVC configuration.
 * This parses framing and SPS syntax, not entropy-coded pictures or all VPS/PPS semantics.
 */
object H265Config {
    const val MAX_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 1024 * 1024
    private const val MAX_NALS = 4096
    private const val MAX_PARAMETER_SETS = 64

    data class ProfileTierLevel(
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val compatibilityFlags: Long,
        val constraintFlags: Long,
        val levelIdc: Int,
    )

    data class SpsInfo(
        val width: Int,
        val height: Int,
        val codedWidth: Int,
        val codedHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropRight: Int,
        val cropBottom: Int,
        val vpsId: Int,
        val spsId: Int,
        val chromaFormatIdc: Int,
        val separateColourPlane: Boolean,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val maxSubLayers: Int,
        val temporalIdNested: Boolean,
        val profile: ProfileTierLevel,
    )

    data class DecoderConfig(
        val vps: List<ByteArray>,
        val sps: List<ByteArray>,
        val pps: List<ByteArray>,
        val nalLengthSize: Int,
        val activeSps: SpsInfo,
    ) {
        val width: Int get() = activeSps.width
        val height: Int get() = activeSps.height
    }

    data class AccessUnit(
        val annexB: ByteArray,
        val hasVcl: Boolean,
        val isIdr: Boolean,
        val isIrap: Boolean,
        val isCra: Boolean,
        val ppsId: Int?,
        val nalTypes: List<Int>,
        val vps: List<ByteArray>,
        val sps: List<ByteArray>,
        val pps: List<ByteArray>,
    )

    /** Reads a complete hvcC record, without an enclosing box header. */
    fun parseHvcc(payload: ByteArray): DecoderConfig {
        require(payload.size in 23..MAX_CONFIG_BYTES) { "Invalid hvcC size" }
        val reader = Bytes(payload)
        require(reader.u8() == 1) { "Unsupported hvcC version" }
        val profile = profile(reader.u8(), reader.uint(4), reader.uint(6), reader.u8())
        require(reader.u16() and 0xf000 == 0xf000) { "Invalid hvcC segmentation reserved bits" }
        require(reader.u8() and 0xfc == 0xfc) { "Invalid hvcC parallelism reserved bits" }
        val chroma = reader.u8()
        require(chroma and 0xfc == 0xfc) { "Invalid hvcC chroma reserved bits" }
        val lumaDepth = reader.u8()
        val chromaDepth = reader.u8()
        require(lumaDepth and 0xf8 == 0xf8 && chromaDepth and 0xf8 == 0xf8) { "Invalid hvcC depth reserved bits" }
        reader.u16() // avgFrameRate is descriptive, not a guaranteed playback rate.
        val temporal = reader.u8()
        val lengthSize = (temporal and 3) + 1
        checkLengthSize(lengthSize)
        val arrays = reader.u8()
        require(arrays in 3..5) { "Unsupported hvcC array count" }
        val sets = mutableMapOf<Int, List<ByteArray>>()
        var totalNals = 0
        repeat(arrays) {
            val header = reader.u8()
            require(header and 0x40 == 0) { "Invalid hvcC array reserved bit" }
            val type = header and 63
            require(type in setOf(32, 33, 34, 39, 40) && type !in sets) { "Unsupported or duplicate hvcC array" }
            val count = reader.u16()
            totalNals += count
            require(count in 1..MAX_PARAMETER_SETS && totalNals <= MAX_PARAMETER_SETS) { "Too many hvcC NALs" }
            sets[type] = List(count) {
                val nal = reader.bytes(reader.u16())
                require(nalHeader(nal, 0, nal.size).type == type) { "hvcC array/NAL type mismatch" }
                nal
            }
        }
        require(reader.remaining == 0) { "Unexpected hvcC trailing data" }
        val config = fromParameterSets(sets[32].orEmpty(), sets[33].orEmpty(), sets[34].orEmpty(), lengthSize)
        for (nal in config.sps) {
            val info = parseSps(nal)
            require(info.chromaFormatIdc == chroma and 3 &&
                info.bitDepthLuma == 8 + (lumaDepth and 7) && info.bitDepthChroma == 8 + (chromaDepth and 7)) {
                "hvcC/SPS chroma or bit-depth mismatch"
            }
            require(profile == info.profile) { "hvcC/SPS profile mismatch" }
            val layers = (temporal ushr 3) and 7
            require(layers == 0 || layers == info.maxSubLayers) { "hvcC/SPS temporal-layer mismatch" }
        }
        return config
    }

    /** Only the reference-proven hvc1 wrapper is accepted; never searches arbitrary bytes for hvcC. */
    fun parseMirrorCodecData(payload: ByteArray): DecoderConfig {
        require(payload.size in 117..MAX_CONFIG_BYTES) { "Invalid mirror HEVC codec-data size" }
        val reader = Bytes(payload)
        require(reader.uint(4) == payload.size.toLong() && reader.fourcc() == "hvc1") { "Expected complete hvc1 sample entry" }
        require(reader.bytes(6).all { it == 0.toByte() } && reader.u16() > 0) { "Invalid hvc1 sample entry" }
        reader.bytes(16) // VisualSampleEntry pre-defined/reserved fields.
        val width = reader.u16()
        val height = reader.u16()
        require(width > 0 && height > 0) { "Invalid hvc1 dimensions" }
        reader.bytes(50) // Resolution, compressor name, depth and pre-defined fields.
        var config: DecoderConfig? = null
        var children = 0
        while (reader.remaining > 0) {
            require(++children <= 64) { "Too many hvc1 child boxes" }
            val size = reader.uint(4)
            require(size >= 8 && size - 4 <= reader.remaining) { "Invalid hvc1 child-box length" }
            val type = reader.fourcc()
            val body = reader.bytes((size - 8).toInt())
            if (type == "hvcC") {
                require(config == null) { "Duplicate hvcC box" }
                config = parseHvcc(body)
            }
        }
        val result = requireNotNull(config) { "hvc1 has no hvcC box" }
        require(width == result.width && height == result.height) { "hvc1/SPS dimensions disagree" }
        return result
    }

    fun fromParameterSets(vps: List<ByteArray>, sps: List<ByteArray>, pps: List<ByteArray>, nalLengthSize: Int = 4): DecoderConfig {
        checkLengthSize(nalLengthSize)
        require(vps.size in 1..16 && sps.size in 1..16 && pps.size in 1..64) { "Missing or excessive HEVC parameter sets" }
        require(vps.size + sps.size + pps.size <= MAX_PARAMETER_SETS) { "Too many HEVC parameter sets" }
        require((vps + sps + pps).sumOf { it.size.toLong() } <= MAX_CONFIG_BYTES) { "HEVC configuration exceeds limit" }
        val vpsIds = vps.map(::vpsId)
        val dimensions = sps.map(::parseSps)
        val ppsIds = pps.map(::ppsIds)
        require(vpsIds.distinct().size == vpsIds.size && dimensions.map { it.spsId }.distinct().size == sps.size &&
            ppsIds.map { it.first }.distinct().size == pps.size) { "Duplicate HEVC parameter-set ids" }
        require(dimensions.all { it.vpsId in vpsIds }) { "SPS refers to missing VPS" }
        require(ppsIds.all { (_, id) -> dimensions.any { it.spsId == id } }) { "PPS refers to missing SPS" }
        val active = dimensions.first { it.spsId == ppsIds.first().second }
        return DecoderConfig(vps.map { it.copyOf() }, sps.map { it.copyOf() }, pps.map { it.copyOf() }, nalLengthSize, active)
    }

    /** Android HEVC csd-0 is VPS + SPS + PPS, each with a four-byte Annex-B start code. */
    fun codecSpecificData(config: DecoderConfig): ByteArray = annexB(config.vps + config.sps + config.pps, MAX_CONFIG_BYTES)

    fun parseAccessUnit(payload: ByteArray, nalLengthSize: Int = 4): AccessUnit {
        checkLengthSize(nalLengthSize)
        require(payload.size in 1..MAX_ACCESS_UNIT_BYTES) { "Invalid HEVC access-unit size" }
        val reader = Bytes(payload)
        val ranges = ArrayList<IntRange>()
        val types = ArrayList<Int>()
        val sets = mutableMapOf<Int, MutableList<ByteArray>>()
        var outputSize = 0L
        var pictureType: Int? = null
        var pictureTemporalId: Int? = null
        var picturePpsId: Int? = null
        var firstSliceSeen = false
        while (reader.remaining > 0) {
            require(ranges.size < MAX_NALS) { "Too many HEVC NALs" }
            val length = reader.uint(nalLengthSize)
            require(length in 3..reader.remaining.toLong()) { "Invalid HEVC NAL length" }
            val start = reader.position
            val header = nalHeader(payload, start, length.toInt())
            val type = header.type
            require(type in 0..9 || type in 16..21 || type in 32..40) { "Unsupported HEVC NAL type $type" }
            if (type <= 21) {
                val bits = Bits(rbsp(payload, start, length.toInt(), 32))
                val first = bits.flag()
                if (type in 16..21) bits.flag() // no_output_of_prior_pics_flag
                val ppsId = bits.ue(63)
                require(pictureType == null || pictureType == type) { "Mixed HEVC picture types" }
                require(pictureTemporalId == null || pictureTemporalId == header.temporalId) { "Mixed HEVC temporal ids" }
                require(picturePpsId == null || picturePpsId == ppsId) { "HEVC slices use different PPS ids" }
                require(!first || !firstSliceSeen) { "Multiple HEVC pictures in one access unit" }
                require(first || firstSliceSeen) { "HEVC access unit starts with a later slice" }
                firstSliceSeen = firstSliceSeen || first
                pictureType = type
                pictureTemporalId = header.temporalId
                picturePpsId = ppsId
            } else if (type in 32..34) {
                sets.getOrPut(type) { ArrayList() }.add(payload.copyOfRange(start, start + length.toInt()))
                require(sets.values.sumOf { it.size } <= MAX_PARAMETER_SETS) { "Too many in-band HEVC parameter sets" }
            }
            ranges.add(start until start + length.toInt())
            types.add(type)
            reader.skip(length.toInt())
            outputSize += 4 + length
            require(outputSize <= MAX_ACCESS_UNIT_BYTES) { "HEVC Annex-B access unit exceeds limit" }
        }
        val output = ByteArray(outputSize.toInt())
        var offset = 0
        for (range in ranges) {
            output[offset + 3] = 1
            payload.copyInto(output, offset + 4, range.first, range.last + 1)
            offset += 4 + range.last - range.first + 1
        }
        return AccessUnit(output, pictureType != null, pictureType in 19..20, pictureType in 16..21,
            pictureType == 21, picturePpsId, types, sets[32].orEmpty(), sets[33].orEmpty(), sets[34].orEmpty())
    }

    /** CRA is deliberately distinct from IDR: recovery at CRA also requires discarding associated RASL pictures. */
    fun toAnnexB(payload: ByteArray, nalLengthSize: Int = 4): ByteArray = parseAccessUnit(payload, nalLengthSize).annexB

    fun parseSps(nal: ByteArray): SpsInfo {
        val bits = parameterBits(nal, 33)
        val vpsId = bits.bits(4)
        val subLayers = bits.bits(3)
        require(subLayers <= 6) { "Invalid HEVC sub-layer count" }
        val nested = bits.flag()
        require(subLayers != 0 || nested) { "Invalid HEVC temporal nesting" }
        val profile = profile(bits.bits(8), bits.uint(32), bits.uint(48), bits.bits(8))
        val subProfiles = BooleanArray(subLayers)
        val subLevels = BooleanArray(subLayers)
        repeat(subLayers) { subProfiles[it] = bits.flag(); subLevels[it] = bits.flag() }
        if (subLayers > 0) repeat(8 - subLayers) { require(bits.bits(2) == 0) { "Invalid HEVC PTL reserved bits" } }
        repeat(subLayers) { if (subProfiles[it]) bits.skip(88); if (subLevels[it]) bits.skip(8) }
        val spsId = bits.ue(15)
        val chroma = bits.ue(3)
        val separatePlane = chroma == 3 && bits.flag()
        val codedWidth = bits.ue(16384)
        val codedHeight = bits.ue(16384)
        require(codedWidth > 0 && codedHeight > 0) { "Invalid HEVC SPS dimensions" }
        val cropUnitX = if (!separatePlane && chroma in 1..2) 2 else 1
        val cropUnitY = if (!separatePlane && chroma == 1) 2 else 1
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (bits.flag()) {
            left = bits.ue(16384) * cropUnitX; right = bits.ue(16384) * cropUnitX
            top = bits.ue(16384) * cropUnitY; bottom = bits.ue(16384) * cropUnitY
        }
        require(left + right < codedWidth && top + bottom < codedHeight) { "Invalid HEVC conformance window" }
        val luma = bits.ue(8) + 8
        val chromaDepth = bits.ue(8) + 8
        val pocBits = bits.ue(12) + 4
        val allOrdering = bits.flag()
        for (i in (if (allOrdering) 0 else subLayers)..subLayers) {
            val buffering = bits.ue(15)
            require(bits.ue(15) <= buffering) { "HEVC reorder exceeds picture buffer" }
            bits.ue() // sps_max_latency_increase_plus1
        }
        val minBlock = bits.ue(3) + 3
        val blockDiff = bits.ue(3)
        require(minBlock + blockDiff in 4..6) { "Invalid HEVC coding-tree size" }
        val minTransform = bits.ue(3) + 2
        val transformDiff = bits.ue(3)
        require(minTransform + transformDiff <= 5 && minTransform <= minBlock) { "Invalid HEVC transform size" }
        bits.ue(4); bits.ue(4) // max_transform_hierarchy_depth_inter/intra
        if (bits.flag() && bits.flag()) skipScalingLists(bits)
        bits.flag(); bits.flag() // amp_enabled_flag, sample_adaptive_offset_enabled_flag
        if (bits.flag()) {
            bits.bits(4); bits.bits(4)
            bits.ue(2); bits.ue(2); bits.flag()
        }
        val referenceCounts = ArrayList<Int>()
        repeat(bits.ue(64)) { index ->
            var count = 0
            if (index > 0 && bits.flag()) {
                bits.flag(); bits.ue(32767)
                repeat(referenceCounts.last() + 1) { if (bits.flag() || bits.flag()) count++ }
            } else {
                val negative = bits.ue(16)
                val positive = bits.ue(16)
                count = negative + positive
                repeat(count) { bits.ue(32767); bits.flag() }
            }
            require(count <= 16) { "Too many HEVC reference pictures" }
            referenceCounts.add(count)
        }
        if (bits.flag()) repeat(bits.ue(32)) { bits.bits(pocBits); bits.flag() }
        bits.flag(); bits.flag() // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag
        if (bits.flag()) skipVui(bits, subLayers)
        if (bits.flag()) {
            val rangeExtension = bits.flag()
            require(bits.bits(7) == 0) { "Unsupported multilayer/3D/SCC HEVC SPS extension" }
            if (rangeExtension) bits.skip(9)
        }
        bits.trailingBits()
        return SpsInfo(codedWidth - left - right, codedHeight - top - bottom, codedWidth, codedHeight,
            left, top, right, bottom, vpsId, spsId, chroma, separatePlane, luma, chromaDepth,
            subLayers + 1, nested, profile)
    }

    private fun skipScalingLists(bits: Bits) {
        for (sizeId in 0..3) for (matrixId in 0..5 step if (sizeId == 3) 3 else 1) {
            if (!bits.flag()) bits.ue(matrixId)
            else {
                if (sizeId > 1) bits.se(-7, 247)
                repeat(minOf(64, 1 shl (4 + 2 * sizeId))) { bits.se(-128, 127) }
            }
        }
    }

    private fun skipVui(bits: Bits, subLayers: Int) {
        if (bits.flag() && bits.bits(8) == 255) { bits.bits(16); bits.bits(16) }
        if (bits.flag()) bits.flag()
        if (bits.flag()) { bits.bits(3); bits.flag(); if (bits.flag()) bits.skip(24) }
        if (bits.flag()) { bits.ue(5); bits.ue(5) }
        bits.flag(); bits.flag(); bits.flag()
        if (bits.flag()) repeat(4) { bits.ue(16384) }
        if (bits.flag()) {
            require(bits.uint(32) > 0 && bits.uint(32) > 0) { "Invalid HEVC VUI timing" }
            if (bits.flag()) bits.ue()
            if (bits.flag()) skipHrd(bits, subLayers)
        }
        if (bits.flag()) { bits.skip(3); bits.ue(4095); repeat(4) { bits.ue(16) } }
    }

    private fun skipHrd(bits: Bits, subLayers: Int) {
        val nal = bits.flag()
        val vcl = bits.flag()
        var subPic = false
        if (nal || vcl) {
            subPic = bits.flag()
            if (subPic) bits.skip(19)
            bits.skip(if (subPic) 12 else 8)
            bits.skip(15)
        }
        repeat(subLayers + 1) {
            val fixed = bits.flag() || bits.flag()
            val lowDelay = if (fixed) { bits.ue(2047); false } else bits.flag()
            val cpbCount = if (lowDelay) 1 else bits.ue(31) + 1
            repeat((if (nal) 1 else 0) + (if (vcl) 1 else 0)) {
                repeat(cpbCount) {
                    bits.ue(); bits.ue()
                    if (subPic) { bits.ue(); bits.ue() }
                    bits.flag()
                }
            }
        }
    }

    private fun vpsId(nal: ByteArray): Int {
        val bits = parameterBits(nal, 32)
        val id = bits.bits(4)
        bits.bits(2) // base_layer_internal/available_flag
        require(bits.bits(6) == 0) { "Multilayer HEVC VPS is unsupported" }
        val subLayers = bits.bits(3)
        require(subLayers <= 6) { "Invalid HEVC VPS sub-layer count" }
        val nested = bits.flag()
        require(subLayers != 0 || nested) { "Invalid HEVC VPS temporal nesting" }
        require(bits.bits(16) == 0xffff) { "Invalid HEVC VPS reserved bits" }
        bits.skip(96) // general profile_tier_level; full VPS semantics are left to the decoder.
        return id
    }

    private fun ppsIds(nal: ByteArray): Pair<Int, Int> {
        val bits = parameterBits(nal, 34)
        return bits.ue(63) to bits.ue(15)
    }

    private fun profile(header: Int, compatibility: Long, constraints: Long, level: Int) =
        ProfileTierLevel(header ushr 6, header and 0x20 != 0, header and 31, compatibility, constraints, level)

    private fun parameterBits(nal: ByteArray, type: Int): Bits {
        require(nal.size in 3..65535 && nalHeader(nal, 0, nal.size).type == type) { "Invalid HEVC parameter-set NAL" }
        require(nal[1].toInt() and 7 == 1) { "HEVC parameter-set temporal id must be zero" }
        return Bits(rbsp(nal, 0, nal.size))
    }

    private data class NalHeader(val type: Int, val temporalId: Int)

    private fun nalHeader(bytes: ByteArray, offset: Int, size: Int): NalHeader {
        require(size >= 2) { "Truncated HEVC NAL header" }
        val first = bytes[offset].toInt() and 0xff
        val second = bytes[offset + 1].toInt() and 0xff
        require(first and 0x80 == 0) { "HEVC forbidden_zero_bit is set" }
        require((first and 1) == 0 && second ushr 3 == 0) { "Multilayer HEVC is unsupported" }
        require(second and 7 != 0) { "HEVC temporal_id_plus1 is zero" }
        return NalHeader((first ushr 1) and 63, (second and 7) - 1)
    }

    private fun rbsp(bytes: ByteArray, offset: Int, size: Int, limit: Int = size): ByteArray {
        val end = offset + size
        val output = ByteArray(minOf(size - 2, limit))
        var length = 0
        var zeros = 0
        var pos = offset + 2
        while (pos < end && length < output.size) {
            val value = bytes[pos++].toInt() and 0xff
            if (zeros >= 2) {
                if (value == 3) {
                    require(pos < end && bytes[pos].toInt() and 0xff <= 3) { "Invalid HEVC emulation prevention" }
                    zeros = 0
                    continue
                }
                require(value > 2) { "Unescaped HEVC start-code sequence" }
            }
            output[length++] = value.toByte()
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.copyOf(length)
    }

    private fun annexB(nals: List<ByteArray>, limit: Int): ByteArray {
        require(nals.isNotEmpty() && nals.size <= MAX_PARAMETER_SETS) { "Invalid HEVC parameter-set count" }
        val size = nals.sumOf { it.size.toLong() + 4 }
        require(size <= limit) { "HEVC codec data exceeds limit" }
        val output = ByteArray(size.toInt())
        var pos = 0
        nals.forEach {
            require(nalHeader(it, 0, it.size).type in 32..34) { "Expected HEVC parameter set" }
            output[pos + 3] = 1
            it.copyInto(output, pos + 4)
            pos += 4 + it.size
        }
        return output
    }

    private fun checkLengthSize(size: Int) {
        require(size == 1 || size == 2 || size == 4) { "HEVC NAL length size must be 1, 2, or 4" }
    }

    private class Bytes(private val data: ByteArray) {
        var position = 0
            private set
        val remaining: Int get() = data.size - position
        fun u8(): Int { require(remaining >= 1) { "Truncated HEVC data" }; return data[position++].toInt() and 0xff }
        fun u16(): Int = (u8() shl 8) or u8()
        fun uint(count: Int): Long { var value = 0L; repeat(count) { value = (value shl 8) or u8().toLong() }; return value }
        fun skip(count: Int) { require(count in 0..remaining) { "Truncated HEVC data" }; position += count }
        fun bytes(count: Int): ByteArray {
            require(count in 1..remaining) { "Invalid HEVC byte length" }
            return data.copyOfRange(position, position + count).also { position += count }
        }
        fun fourcc(): String = String(bytes(4), Charsets.US_ASCII)
    }

    private class Bits(private val data: ByteArray) {
        private var pos = 0
        fun uint(count: Int): Long {
            require(count in 0..48 && data.size * 8 - pos >= count) { "Truncated HEVC RBSP" }
            var value = 0L
            repeat(count) { value = (value shl 1) or ((data[pos / 8].toInt() ushr (7 - pos % 8)) and 1).toLong(); pos++ }
            return value
        }
        fun bits(count: Int): Int = uint(count).toInt()
        fun flag(): Boolean = bits(1) != 0
        fun skip(count: Int) { require(count >= 0 && data.size * 8 - pos >= count) { "Truncated HEVC RBSP" }; pos += count }
        fun ue(max: Int = Int.MAX_VALUE): Int {
            var zeros = 0
            while (!flag()) { require(++zeros <= 30) { "HEVC Exp-Golomb value exceeds limit" } }
            val value = (1L shl zeros) - 1 + uint(zeros)
            require(value <= max) { "HEVC syntax value exceeds limit" }
            return value.toInt()
        }
        fun se(min: Int, max: Int): Int {
            val code = ue()
            val value = if (code and 1 == 0) -(code / 2) else code / 2 + 1
            require(value in min..max) { "HEVC signed syntax value exceeds limit" }
            return value
        }
        fun trailingBits() {
            require(flag()) { "Missing HEVC rbsp_stop_one_bit" }
            while (pos % 8 != 0) require(!flag()) { "Invalid HEVC RBSP alignment" }
            require(pos == data.size * 8) { "Unexpected HEVC SPS trailing bytes" }
        }
    }
}
