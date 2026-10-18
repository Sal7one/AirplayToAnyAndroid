package com.sal7one.tvtestproject.receiver.media

/** Stateless, bounded JNI conversion; native buffers never escape a call. */
internal object NativeAvcParser {
    init { System.loadLibrary("fossmirror_avc") }

    fun ensureAvailable() = Unit

    fun parse(payload: ByteArray, lengthSize: Int): H264Config.AccessUnit {
        val metadata = IntArray(3)
        val annexB = nativeParse(payload, lengthSize, metadata)
        // Configuration changes are rare and retain the independently tested SPS/PPS parser.
        if (metadata[0] and 4 != 0) {
            annexB.fill(0)
            return H264Config.parseAccessUnit(payload, lengthSize)
        }
        return H264Config.AccessUnit(annexB, metadata[0] and 1 != 0, metadata[0] and 2 != 0,
            emptyList(), emptyList(), metadata[1].takeIf { it >= 0 }, payload, lengthSize)
    }

    private external fun nativeParse(payload: ByteArray, lengthSize: Int, metadata: IntArray): ByteArray
}
