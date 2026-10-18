package com.sal7one.tvtestproject.receiver.media

import com.sal7one.tvtestproject.receiver.VideoConfig

/** Pure AVC parsing. NAL arrays contain raw NALs, without start codes or length prefixes. */
object H264Config {
    const val MAX_ACCESS_UNIT_BYTES = 8 * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 1024 * 1024
    private const val MAX_NALS_PER_ACCESS_UNIT = 4096
    private val extendedProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135, 144)

    data class SpsDimensions(
        val width: Int,
        val height: Int,
        val codedWidth: Int,
        val codedHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropRight: Int,
        val cropBottom: Int,
        val spsId: Int,
        val profileIdc: Int,
        val levelIdc: Int,
    )

    /** Parser-owned output. Keep the source unchanged until enqueuePrepared returns. */
    class AccessUnit internal constructor(
        val annexB: ByteArray,
        val isIdr: Boolean,
        val hasVcl: Boolean,
        val sps: List<ByteArray>,
        val pps: List<ByteArray>,
        val ppsId: Int?,
        internal val source: ByteArray,
        internal val nalLengthSize: Int,
    )

    /** Throws IllegalArgumentException for malformed or unsupported AVC configuration records. */
    fun parseAvcc(payload: ByteArray): VideoConfig {
        require(payload.size in 7..MAX_CONFIG_BYTES) { "Invalid avcC size" }
        val reader = ByteReader(payload)
        require(reader.u8() == 1) { "Unsupported avcC version" }
        val profile = reader.u8()
        reader.u8() // profile compatibility
        reader.u8() // level
        val lengthField = reader.u8()
        require(lengthField and 0xfc == 0xfc) { "Invalid avcC length-size reserved bits" }
        val nalLengthSize = (lengthField and 3) + 1
        checkLengthSize(nalLengthSize)
        val spsCountField = reader.u8()
        require(spsCountField and 0xe0 == 0xe0) { "Invalid avcC SPS-count reserved bits" }
        val sps = reader.nals(spsCountField and 0x1f, 7)
        val pps = reader.nals(reader.u8(), 8)
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "avcC requires SPS and PPS" }
        if (reader.remaining > 0) {
            require(profile in extendedProfiles) { "Unexpected avcC trailing data" }
            require(reader.u8() and 0xfc == 0xfc) { "Invalid avcC chroma reserved bits" }
            val lumaDepth = reader.u8()
            val chromaDepth = reader.u8()
            require(lumaDepth and 0xf8 == 0xf8 && lumaDepth and 7 <= 6) { "Invalid avcC luma depth" }
            require(chromaDepth and 0xf8 == 0xf8 && chromaDepth and 7 <= 6) { "Invalid avcC chroma depth" }
            val extensions = reader.nals(reader.u8(), 13)
            require(extensions.isEmpty()) { "Auxiliary SPS extensions are not supported" }
        }
        require(reader.remaining == 0) { "Unexpected avcC trailing data" }
        return fromParameterSets(sps, pps, nalLengthSize)
    }

    /** Copies parameter sets and selects the SPS referenced by the first PPS. */
    fun fromParameterSets(sps: List<ByteArray>, pps: List<ByteArray>, nalLengthSize: Int = 4): VideoConfig {
        checkLengthSize(nalLengthSize)
        require(sps.size in 1..31 && pps.size in 1..255) { "Invalid parameter-set count" }
        require(sps.sumOf { it.size.toLong() } + pps.sumOf { it.size.toLong() } <= MAX_CONFIG_BYTES) {
            "Parameter sets exceed configuration limit"
        }
        val dimensions = sps.map(::parseSps)
        require(dimensions.map { it.spsId }.distinct().size == dimensions.size) { "Duplicate SPS ids" }
        val ids = pps.map(::ppsIds)
        require(ids.map { it.first }.distinct().size == ids.size) { "Duplicate PPS ids" }
        ids.forEach { (_, spsId) ->
            require(dimensions.any { it.spsId == spsId }) { "PPS refers to missing SPS" }
        }
        val active = dimensions.first { it.spsId == ids.first().second }
        return VideoConfig(active.width, active.height, sps.map { it.copyOf() }, pps.map { it.copyOf() }, nalLengthSize)
    }

    fun parseSps(nal: ByteArray): SpsDimensions {
        require(nal.size in 5..65535) { "Invalid SPS size" }
        require(nalType(nal, 0, nal.size) == 7) { "Expected SPS NAL" }
        val bits = BitReader(rbsp(nal))
        val profile = bits.bits(8)
        require(bits.bits(8) and 3 == 0) { "Invalid SPS constraint reserved bits" }
        val level = bits.bits(8)
        val spsId = bits.ue(31)
        var chromaFormat = 1
        var separateColourPlane = false
        if (profile in extendedProfiles) {
            chromaFormat = bits.ue(3)
            if (chromaFormat == 3) separateColourPlane = bits.flag()
            bits.ue(6) // bit_depth_luma_minus8
            bits.ue(6) // bit_depth_chroma_minus8
            bits.flag() // qpprime_y_zero_transform_bypass_flag
            if (bits.flag()) {
                repeat(if (chromaFormat == 3) 12 else 8) { index ->
                    if (bits.flag()) skipScalingList(bits, if (index < 6) 16 else 64)
                }
            }
        }
        bits.ue(12) // log2_max_frame_num_minus4
        when (bits.ue(2)) {
            0 -> bits.ue(12)
            1 -> {
                bits.flag()
                bits.se()
                bits.se()
                repeat(bits.ue(255)) { bits.se() }
            }
        }
        bits.ue(16) // max_num_ref_frames
        bits.flag() // gaps_in_frame_num_value_allowed_flag
        val widthMbs = bits.ue(1023) + 1
        val heightMapUnits = bits.ue(1023) + 1
        val frameMbsOnly = bits.flag()
        if (!frameMbsOnly) bits.flag()
        bits.flag() // direct_8x8_inference_flag
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        if (bits.flag()) {
            left = bits.ue(16384)
            right = bits.ue(16384)
            top = bits.ue(16384)
            bottom = bits.ue(16384)
        }
        if (bits.flag()) skipVui(bits)
        bits.trailingBits()
        val chromaArrayType = if (separateColourPlane) 0 else chromaFormat
        val frameFactor = if (frameMbsOnly) 1 else 2
        val cropUnitX = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
        val cropUnitY = (if (chromaArrayType == 1) 2 else 1) * frameFactor
        val codedWidth = widthMbs * 16
        val codedHeight = heightMapUnits * 16 * frameFactor
        require(codedWidth <= 16384 && codedHeight <= 16384) { "SPS dimensions exceed limit" }
        left *= cropUnitX
        right *= cropUnitX
        top *= cropUnitY
        bottom *= cropUnitY
        val width = codedWidth - left - right
        val height = codedHeight - top - bottom
        require(width > 0 && height > 0) { "Invalid SPS crop rectangle" }
        return SpsDimensions(width, height, codedWidth, codedHeight, left, top, right, bottom, spsId, profile, level)
    }

    /** Converts one complete length-prefixed access unit, not an individual slice. */
    fun toAnnexB(accessUnit: ByteArray, nalLengthSize: Int): ByteArray =
        parseAccessUnit(accessUnit, nalLengthSize).annexB

    internal fun parseAccessUnit(payload: ByteArray, nalLengthSize: Int): AccessUnit {
        checkLengthSize(nalLengthSize)
        require(payload.size in 1..MAX_ACCESS_UNIT_BYTES) { "Invalid AVC access-unit size" }
        val ranges = ArrayList<IntRange>()
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        var pos = 0
        var outputSize = 0L
        var idr = false
        var nonIdr = false
        var activePpsId: Int? = null
        var hasFirstSlice = false
        while (pos < payload.size) {
            require(ranges.size < MAX_NALS_PER_ACCESS_UNIT) { "Too many NALs in access unit" }
            require(payload.size - pos >= nalLengthSize) { "Truncated AVC NAL length" }
            var size = 0L
            repeat(nalLengthSize) { size = (size shl 8) or (payload[pos++].toLong() and 0xff) }
            require(size > 0 && size <= payload.size - pos) { "Invalid AVC NAL length" }
            val length = size.toInt()
            val type = nalType(payload, pos, length)
            when (type) {
                1, 5 -> {
                    val header = BitReader(rbsp(payload, pos, length, 32))
                    if (header.ue() == 0) hasFirstSlice = true // first_mb_in_slice
                    header.ue(9) // slice_type
                    val ppsId = header.ue(255)
                    require(activePpsId == null || activePpsId == ppsId) { "Slices use different PPS ids" }
                    activePpsId = ppsId
                    if (type == 5) idr = true else nonIdr = true
                }
                7 -> sps.add(payload.copyOfRange(pos, pos + length))
                8 -> pps.add(payload.copyOfRange(pos, pos + length))
                6, 9, 10, 11, 12 -> Unit
                else -> throw IllegalArgumentException("Unsupported AVC NAL type $type")
            }
            ranges.add(pos until pos + length)
            pos += length
            outputSize += 4L + length
            require(outputSize <= MAX_ACCESS_UNIT_BYTES) { "Annex-B access unit exceeds limit" }
        }
        require(!(idr && nonIdr)) { "Mixed IDR and non-IDR slices in one access unit" }
        require(!(idr || nonIdr) || hasFirstSlice) { "Incomplete access unit: missing first picture slice" }
        val output = ByteArray(outputSize.toInt())
        var offset = 0
        for (range in ranges) {
            output[offset + 3] = 1
            offset += 4
            payload.copyInto(output, offset, range.first, range.last + 1)
            offset += range.last - range.first + 1
        }
        return AccessUnit(output, idr, idr || nonIdr, sps, pps, activePpsId, payload, nalLengthSize)
    }

    internal fun ppsIds(nal: ByteArray): Pair<Int, Int> {
        require(nal.size in 2..65535 && nalType(nal, 0, nal.size) == 8) { "Invalid PPS NAL" }
        val bits = BitReader(rbsp(nal))
        return bits.ue(255) to bits.ue(31)
    }

    internal fun mergeParameterSets(config: VideoConfig, unit: AccessUnit): VideoConfig {
        if (unit.sps.isEmpty() && unit.pps.isEmpty() && unit.ppsId == ppsIds(config.pps.first()).first) return config
        if (unit.sps.isEmpty() && unit.pps.isEmpty() && unit.ppsId == null) return config
        val spsById = config.sps.associateBy { parseSps(it).spsId }.toMutableMap()
        val ppsById = config.pps.associateBy { ppsIds(it).first }.toMutableMap()
        unit.sps.forEach { spsById[parseSps(it).spsId] = it }
        unit.pps.forEach { ppsById[ppsIds(it).first] = it }
        // Select the parameter set used by the actual slice header, not merely the first avcC SPS.
        val activeId = unit.ppsId ?: unit.pps.firstOrNull()?.let { ppsIds(it).first } ?: ppsIds(config.pps.first()).first
        require(activeId in ppsById) { "Slice refers to missing PPS" }
        val ppsOrder = listOf(activeId) + ppsById.keys.filter { it != activeId }
        return fromParameterSets(spsById.values.toList(), ppsOrder.map { ppsById.getValue(it) }, config.nalLengthSize)
    }

    fun parameterSetsToAnnexB(nals: List<ByteArray>): ByteArray {
        require(nals.isNotEmpty()) { "Missing codec parameter sets" }
        val size = nals.sumOf { it.size.toLong() + 4 }
        require(size <= MAX_CONFIG_BYTES) { "Codec parameter sets exceed limit" }
        val output = ByteArray(size.toInt())
        var pos = 0
        nals.forEach {
            require(it.isNotEmpty()) { "Empty codec parameter set" }
            output[pos + 3] = 1
            pos += 4
            it.copyInto(output, pos)
            pos += it.size
        }
        return output
    }

    private fun checkLengthSize(size: Int) {
        require(size == 1 || size == 2 || size == 4) { "AVC NAL length size must be 1, 2, or 4" }
    }

    private fun nalType(bytes: ByteArray, offset: Int, size: Int): Int {
        require(size > 0) { "Empty NAL" }
        val header = bytes[offset].toInt() and 0xff
        require(header and 0x80 == 0) { "NAL forbidden_zero_bit is set" }
        val type = header and 31
        require(type in 1..23) { "Invalid NAL type" }
        if (type == 5 || type == 7 || type == 8) {
            require(header and 0x60 != 0) { "Reference NAL has zero nal_ref_idc" }
        }
        return type
    }

    private fun rbsp(nal: ByteArray, offset: Int = 0, length: Int = nal.size, maxBytes: Int = length - 1): ByteArray {
        val output = ByteArray(minOf(length - 1, maxBytes))
        var size = 0
        var zeros = 0
        for (i in offset + 1 until offset + length) {
            if (size == output.size) break
            val value = nal[i].toInt() and 0xff
            if (zeros >= 2 && value == 3) {
                require(i + 1 < offset + length && (nal[i + 1].toInt() and 0xff) <= 3) { "Invalid emulation-prevention byte" }
                zeros = 0
                continue
            }
            require(zeros < 2 || value > 2) { "Unescaped start-code sequence in parameter set" }
            output[size++] = nal[i]
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.copyOf(size)
    }

    private fun skipScalingList(bits: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) nextScale = (lastScale + bits.se(-128, 127) + 256) % 256
            if (nextScale != 0) lastScale = nextScale
        }
    }

    private fun skipVui(bits: BitReader) {
        if (bits.flag() && bits.bits(8) == 255) { bits.bits(16); bits.bits(16) }
        if (bits.flag()) bits.flag()
        if (bits.flag()) {
            bits.bits(3)
            bits.flag()
            if (bits.flag()) { bits.bits(8); bits.bits(8); bits.bits(8) }
        }
        if (bits.flag()) { bits.ue(5); bits.ue(5) }
        if (bits.flag()) { bits.bits(32); bits.bits(32); bits.flag() }
        val nalHrd = bits.flag()
        if (nalHrd) skipHrd(bits)
        val vclHrd = bits.flag()
        if (vclHrd) skipHrd(bits)
        if (nalHrd || vclHrd) bits.flag()
        bits.flag() // pic_struct_present_flag
        if (bits.flag()) {
            bits.flag()
            bits.ue(16)
            bits.ue(16)
            bits.ue(16)
            bits.ue(16)
            bits.ue(16)
            bits.ue(16)
        }
    }

    private fun skipHrd(bits: BitReader) {
        val count = bits.ue(31) + 1
        bits.bits(4)
        bits.bits(4)
        repeat(count) { bits.ue(); bits.ue(); bits.flag() }
        repeat(4) { bits.bits(5) }
    }

    private class ByteReader(private val data: ByteArray) {
        private var pos = 0
        val remaining: Int get() = data.size - pos
        fun u8(): Int {
            require(remaining > 0) { "Truncated avcC" }
            return data[pos++].toInt() and 0xff
        }
        fun nals(count: Int, type: Int): List<ByteArray> = List(count) {
            val size = (u8() shl 8) or u8()
            require(size > 0 && size <= remaining) { "Invalid avcC parameter-set length" }
            require(nalType(data, pos, size) == type) { "Unexpected avcC NAL type" }
            data.copyOfRange(pos, pos + size).also { pos += size }
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        private var pos = 0
        fun bits(count: Int): Int {
            require(count in 0..32 && bytes.size * 8 - pos >= count) { "Truncated H.264 RBSP" }
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((bytes[pos / 8].toInt() ushr (7 - pos % 8)) and 1)
                pos++
            }
            return value
        }
        fun flag(): Boolean = bits(1) != 0
        fun ue(max: Int = Int.MAX_VALUE): Int {
            var zeros = 0
            while (!flag()) {
                zeros++
                require(zeros <= 30) { "H.264 Exp-Golomb value exceeds limit" }
            }
            val value = (1L shl zeros) - 1 + bits(zeros)
            require(value <= max) { "H.264 syntax value exceeds limit" }
            return value.toInt()
        }
        fun se(min: Int = -Int.MAX_VALUE, max: Int = Int.MAX_VALUE): Int {
            val code = ue()
            val value = if (code and 1 == 0) -(code / 2) else code / 2 + 1
            require(value in min..max) { "H.264 signed syntax value exceeds limit" }
            return value
        }
        fun trailingBits() {
            require(flag()) { "Missing H.264 rbsp_stop_one_bit" }
            while (pos % 8 != 0) require(!flag()) { "Invalid H.264 RBSP alignment" }
            require(pos == bytes.size * 8) { "Unexpected H.264 RBSP trailing bytes" }
        }
    }
}
