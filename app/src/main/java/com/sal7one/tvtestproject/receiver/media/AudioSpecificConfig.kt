package com.sal7one.tvtestproject.receiver.media

import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.AudioConfig
import kotlin.math.pow

/**
 * ISO/IEC 14496-3 AudioSpecificConfig, restricted to mono/stereo AAC-LC and
 * AAC-ELD without SBR, resilience, error protection, or ELD extensions.
 * Field definitions cross-checked against FFmpeg's get_object_type,
 * get_sample_rate, decode_ga_specific_config and decode_eld_specific_config:
 * https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/mpeg4audio.c
 * https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/aac/aacdec.c
 * This is configuration serialization, not an AAC decoder.
 */
object AudioSpecificConfig {
    // AirPlay audio format bit 11: PCM signed 16-bit, 44100Hz, stereo.
    // Reference: openairplay/airplay2-receiver ap2/connections/audio.py, AirplayAudFmt.
    const val AIRPLAY_PCM_44100_STEREO_MASK: Long = 0x0000_0800L
    const val AIRPLAY_ALAC_44100_STEREO_MASK: Long = 0x0004_0000L
    // Observed mirroring tuple: ct=8, spf=480, audioFormat=0x1000000,
    // AAC-ELD 44100/2: https://github.com/antimof/UxPlay/issues/141
    const val AIRPLAY_ELD_44100_STEREO_MASK: Long = 0x0100_0000L

    data class Parsed(
        val codec: AudioCodec,
        val sampleRate: Int,
        val channels: Int,
        val samplesPerFrame: Int,
    )

    private val sampleRates = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
        22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
    )

    fun build(config: AudioConfig): ByteArray {
        validateFormat(config)
        require(config.codec == AudioCodec.AAC_LC || config.codec == AudioCodec.AAC_ELD) {
            "${config.codec} has no AAC AudioSpecificConfig"
        }
        val writer = BitsWriter()
        when (config.codec) {
            AudioCodec.AAC_LC -> writer.put(2, 5)
            AudioCodec.AAC_ELD -> {
                writer.put(31, 5)
                writer.put(39 - 32, 6)
            }
            AudioCodec.PCM, AudioCodec.ALAC -> error("Not an AAC configuration")
        }
        val rateIndex = sampleRates.indexOf(config.sampleRate)
        writer.put(if (rateIndex >= 0) rateIndex else 15, 4)
        if (rateIndex < 0) writer.put(config.sampleRate, 24)
        writer.put(config.channels, 4)
        when (config.codec) {
            AudioCodec.AAC_LC -> {
                writer.put(if (config.samplesPerFrame == 960) 1 else 0, 1)
                writer.put(0, 1) // dependsOnCoreCoder
                writer.put(0, 1) // extensionFlag
            }
            AudioCodec.AAC_ELD -> {
                writer.put(if (config.samplesPerFrame == 480) 1 else 0, 1)
                writer.put(0, 3) // section/scalefactor/spectral resilience flags
                writer.put(0, 1) // ldSbrPresentFlag
                writer.put(0, 4) // ELDEXT_TERM
                writer.put(0, 2) // epConfig
            }
            AudioCodec.PCM, AudioCodec.ALAC -> error("Not an AAC configuration")
        }
        return writer.bytes()
    }

    fun parse(bytes: ByteArray): Parsed {
        require(bytes.size in 2..64) { "Invalid AudioSpecificConfig length" }
        val reader = BitsReader(bytes)
        val initialType = reader.get(5)
        val objectType = if (initialType == 31) 32 + reader.get(6) else initialType
        val codec = when (objectType) {
            2 -> AudioCodec.AAC_LC
            39 -> AudioCodec.AAC_ELD
            else -> throw IllegalArgumentException("Unsupported AAC object type $objectType")
        }
        val rateIndex = reader.get(4)
        val rate = when {
            rateIndex < sampleRates.size -> sampleRates[rateIndex]
            rateIndex == 15 -> reader.get(24)
            else -> throw IllegalArgumentException("Reserved AAC sampling frequency index")
        }
        val channels = reader.get(4)
        require(channels in 1..2) { "Only explicit mono/stereo AAC layouts are supported" }
        val shortFrame = reader.get(1) != 0
        val samples = when (codec) {
            AudioCodec.AAC_LC -> {
                require(reader.get(1) == 0) { "AAC core-coder dependency is unsupported" }
                require(reader.get(1) == 0) { "AAC-LC extensionFlag is unsupported" }
                if (shortFrame) 960 else 1024
            }
            AudioCodec.AAC_ELD -> {
                require(reader.get(3) == 0) { "AAC-ELD resilience is unsupported" }
                require(reader.get(1) == 0) { "AAC-ELD low-delay SBR is unsupported" }
                require(reader.get(4) == 0) { "AAC-ELD extensions are unsupported" }
                require(reader.get(2) == 0) { "AAC-ELD error protection is unsupported" }
                if (shortFrame) 480 else 512
            }
            AudioCodec.PCM, AudioCodec.ALAC -> error("Not an AAC configuration")
        }
        // A supplied LC config may explicitly disable SBR with a sync extension.
        if (codec == AudioCodec.AAC_LC && reader.remaining >= 17) {
            require(reader.get(11) == 0x2b7 && reader.get(5) == 5) {
                "Unsupported AAC sync extension"
            }
            require(reader.get(1) == 0) { "HE-AAC/SBR is unsupported" }
        }
        require(reader.remaining < 8) { "Unsupported trailing AAC configuration" }
        require(reader.get(reader.remaining) == 0) { "Nonzero AAC configuration padding" }
        val parsed = Parsed(codec, rate, channels, samples)
        validateFormat(AudioConfig(codec, rate, channels, samples))
        return parsed
    }

    /** Uses supplied CSD verbatim after verifying that it matches negotiation. */
    fun resolve(config: AudioConfig): ByteArray? {
        validateFormat(config)
        if (config.codec == AudioCodec.PCM || config.codec == AudioCodec.ALAC) {
            require(config.audioSpecificConfig == null) { "${config.codec} cannot carry AAC configuration" }
            return null
        }
        config.audioSpecificConfig?.let {
            require(it.size in 2..64) { "Invalid AudioSpecificConfig length" }
        }
        val bytes = config.audioSpecificConfig?.copyOf() ?: build(config)
        val parsed = parse(bytes)
        require(parsed == Parsed(config.codec, config.sampleRate, config.channels, config.samplesPerFrame)) {
            "AudioSpecificConfig does not match negotiated audio format"
        }
        return bytes
    }

    fun validateFormat(config: AudioConfig) {
        require(config.sampleRate in 7_350..96_000) { "Unsupported audio sample rate" }
        require(config.channels in 1..2) { "Only mono/stereo audio is supported" }
        when (config.codec) {
            AudioCodec.AAC_LC -> require(config.samplesPerFrame == 1024 || config.samplesPerFrame == 960) {
                "AAC-LC requires 1024 or 960 samples per frame"
            }
            AudioCodec.AAC_ELD -> require(config.samplesPerFrame == 512 || config.samplesPerFrame == 480) {
                "AAC-ELD requires 512 or 480 samples per frame"
            }
            AudioCodec.PCM -> require(config.samplesPerFrame in 1..8192) {
                "Invalid PCM packet sample count"
            }
            AudioCodec.ALAC -> {
                require(config.sampleRate == 44_100 && config.channels == 2) {
                    "ALAC supports only 44100Hz stereo PCM16"
                }
                require(config.samplesPerFrame in 352..4096) { "Invalid ALAC packet sample count" }
            }
        }
    }

    /**
     * Strict helper for the server's 44.1kHz/stereo SETUP path. ct is a wire
     * compression bit value, not the Bonjour cn ordinal. ct=2 is ALAC.
     * UxPlay calls ct=4 AAC-MAIN, so that ambiguous AAC family
     * requires supplied ASC proving LC; it is NOT advertised by our mask.
     * https://github.com/FDH2/UxPlay/blob/master/lib/raop_rtp.c
     * The historical 0x400000 AAC-family mapping (mode=AAC-main) is documented
     * in source, not interpreted here as proof of the AAC-LC object type:
     * https://github.com/phonegapX/AirPlay/blob/master/AirplayLibrary/AirPlay/lib/raop.c
     */
    fun fromAirPlay44100Stereo(
        compressionType: Int,
        audioFormat: Long? = null,
        samplesPerFrame: Int? = null,
        audioSpecificConfig: ByteArray? = null,
    ): AudioConfig {
        val codec = when (compressionType) {
            1 -> AudioCodec.PCM
            4 -> AudioCodec.AAC_LC
            8 -> AudioCodec.AAC_ELD
            2 -> AudioCodec.ALAC
            else -> throw IllegalArgumentException("Unsupported AirPlay compression type $compressionType")
        }
        val expectedFormat = when (codec) {
            AudioCodec.AAC_ELD -> AIRPLAY_ELD_44100_STEREO_MASK
            AudioCodec.AAC_LC -> 0x0040_0000L
            AudioCodec.PCM -> AIRPLAY_PCM_44100_STEREO_MASK
            AudioCodec.ALAC -> AIRPLAY_ALAC_44100_STEREO_MASK
        }
        require(audioFormat == null || audioFormat == expectedFormat) {
            "AirPlay audioFormat is not the supported 44100Hz stereo tuple"
        }
        if (codec == AudioCodec.AAC_LC) require(audioSpecificConfig != null) {
            "ct=4 requires AudioSpecificConfig proving AAC-LC, not AAC-MAIN"
        }
        val parsed = audioSpecificConfig?.let(::parse)
        val samples = samplesPerFrame ?: parsed?.samplesPerFrame ?: when (codec) {
            AudioCodec.AAC_ELD -> 480
            AudioCodec.AAC_LC -> 1024
            AudioCodec.PCM -> throw IllegalArgumentException("PCM SETUP must specify samples per packet")
            AudioCodec.ALAC -> 352
        }
        val config = AudioConfig(codec, 44_100, 2, samples, audioSpecificConfig)
        return config.copy(audioSpecificConfig = resolve(config))
    }

    private class BitsReader(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size * 8 - position

        fun get(count: Int): Int {
            require(count in 0..24 && count <= remaining) { "Truncated AudioSpecificConfig" }
            var result = 0
            repeat(count) {
                result = (result shl 1) or
                    ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return result
        }
    }

    private class BitsWriter {
        private val output = ByteArray(8)
        private var position = 0

        fun put(value: Int, count: Int) {
            require(count in 1..24 && value >= 0 && value ushr count == 0)
            for (bit in count - 1 downTo 0) {
                val index = position / 8
                output[index] = (output[index].toInt() or
                    (((value ushr bit) and 1) shl (7 - position % 8))).toByte()
                position++
            }
        }

        fun bytes(): ByteArray = output.copyOf((position + 7) / 8)
    }
}

/** AirPlay uses amplitude dB, with -144 as its explicit mute sentinel. */
internal object AirPlayAudioVolume {
    fun linear(decibels: Float): Float = when {
        decibels.isNaN() -> throw IllegalArgumentException("NaN AirPlay volume")
        decibels <= -144f -> 0f
        else -> 10.0.pow(decibels.coerceIn(-30f, 0f).toDouble() / 20.0).toFloat()
    }
}
