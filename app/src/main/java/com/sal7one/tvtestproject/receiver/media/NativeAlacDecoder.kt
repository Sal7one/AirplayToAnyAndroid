package com.sal7one.tvtestproject.receiver.media

/** One ALAC stream, producing interleaved little-endian stereo PCM16. */
class NativeAlacDecoder(sampleRate: Int, channels: Int, samplesPerFrame: Int) : AutoCloseable {
    private var handle: Long

    init {
        require(sampleRate == 44_100 && channels == 2) { "ALAC supports 44100 Hz stereo PCM16" }
        require(samplesPerFrame in 352..4096) { "ALAC samplesPerFrame must be 352..4096" }
        handle = nativeCreate(sampleRate, channels, samplesPerFrame)
        check(handle != 0L) { "ALAC decoder initialization failed" }
    }

    /** Throws IllegalArgumentException for invalid packets and IllegalStateException after close. */
    @Synchronized
    fun decode(frame: ByteArray): ByteArray {
        check(handle != 0L) { "ALAC decoder is closed" }
        require(frame.isNotEmpty() && frame.size <= 65_536) { "ALAC frame must contain 1..65536 bytes" }
        return nativeDecode(handle, frame)
    }

    /** Recreates the predictor and entropy state without changing the negotiated format. */
    @Synchronized
    fun flush() {
        check(handle != 0L) { "ALAC decoder is closed" }
        nativeFlush(handle)
    }

    @Synchronized
    override fun close() {
        val owned = handle
        handle = 0
        if (owned != 0L) nativeClose(owned)
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int, samplesPerFrame: Int): Long
    private external fun nativeDecode(handle: Long, frame: ByteArray): ByteArray
    private external fun nativeFlush(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("fossmirror_alac") }
    }
}
