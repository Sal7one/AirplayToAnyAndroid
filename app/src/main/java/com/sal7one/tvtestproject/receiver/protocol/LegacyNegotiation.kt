package com.sal7one.tvtestproject.receiver.protocol

import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.AudioConfig
import com.sal7one.tvtestproject.receiver.media.AudioSpecificConfig

/** Wire integers are validated as 64-bit values before any narrowing conversion. */
internal object LegacyNegotiation {
    fun streams(root: Map<String, Any?>): List<Map<String, Any?>> {
        if (!root.containsKey("streams")) return emptyList()
        val entries = root["streams"]
        require(entries is List<*> && entries.size in 1..2) { "Invalid streams array" }
        val streams = entries.map { dictionary(it) }
        val types = streams.map { integer(it, "type") }
        require(types.distinct().size == types.size && types.all { it == 96L || it == 110L }) {
            "Unsupported or duplicate stream type"
        }
        return streams
    }

    fun audio(stream: Map<String, Any?>): AudioConfig {
        require(!stream.containsKey("sampleRate") || integer(stream, "sampleRate") == 44100L) { "Unsupported sampleRate" }
        require(!stream.containsKey("channels") || integer(stream, "channels") == 2L) { "Unsupported channels" }
        val compression = integer(stream, "ct").also { require(it in 1..8) { "Unsupported compression type" } }.toInt()
        val samples = if (stream.containsKey("spf")) integer(stream, "spf").also {
            require(it in 1..8192) { "Invalid spf" }
        }.toInt() else null
        val config = stream["audioSpecificConfig"]?.let { require(it is ByteArray); it }
        return AudioSpecificConfig.fromAirPlay44100Stereo(compression,
            if (stream.containsKey("audioFormat")) integer(stream, "audioFormat") else null, samples, config)
    }

    // Feature audio-format bits and /info format selectors use different bit positions.
    // openairplay/airplay-spec index.html, SupportedAudioFormats: 18=PCM,
    // 19=ALAC, 20=AAC, 21=ELD. Keep both representations capability-gated.
    fun features(codecs: Set<AudioCodec>): Long =
        (1L shl 7) or (1L shl 12) or (1L shl 14) or (1L shl 27) or
            (if (codecs.isEmpty()) 0L else (1L shl 9) or (1L shl 30)) or
            (if (AudioCodec.PCM in codecs) 1L shl 18 else 0L) or
            (if (AudioCodec.ALAC in codecs) 1L shl 19 else 0L) or
            (if (AudioCodec.AAC_ELD in codecs) 1L shl 21 else 0L)

    // The captured macOS HAL creation failed with an ELD-only /info mask.
    // Include supported PCM and keep /info consistent with capability-gated Bonjour cn.
    fun audioOutputFormats(codecs: Set<AudioCodec>): Long =
        (if (AudioCodec.PCM in codecs) AudioSpecificConfig.AIRPLAY_PCM_44100_STEREO_MASK else 0L) or
            (if (AudioCodec.AAC_ELD in codecs) AudioSpecificConfig.AIRPLAY_ELD_44100_STEREO_MASK else 0L) or
            (if (AudioCodec.ALAC in codecs) AudioSpecificConfig.AIRPLAY_ALAC_44100_STEREO_MASK else 0L)

    // UxPlay's legacy types 100/101 publish both format lists. macOS builds its
    // HAL device from them before it opens the type-96 transport.
    fun audioFormats(codecs: Set<AudioCodec>): List<Map<String, Long>> {
        val formats = audioOutputFormats(codecs)
        return listOf(100L, 101L).map { type -> mapOf(
            "type" to type, "audioInputFormats" to formats, "audioOutputFormats" to formats,
        ) }
    }

    fun flushSequence(rtpInfo: String?): Int? {
        val fields = rtpInfo?.split(';')?.map { it.trim() } ?: return null
        val sequence = fields.filter { it.substringBefore('=') == "seq" }
        require(sequence.size <= 1) { "Duplicate FLUSH sequence" }
        val field = sequence.singleOrNull() ?: return null
        val value = field.substringAfter('=', "")
        require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "Invalid FLUSH sequence" }
        return value.toIntOrNull()?.also { require(it in 0..65535) { "Invalid FLUSH sequence" } }
            ?: throw IllegalArgumentException("Invalid FLUSH sequence")
    }

    fun integer(values: Map<String, Any?>, key: String): Long =
        (values[key] as? Long) ?: throw IllegalArgumentException("Missing/integer $key required")

    fun dictionary(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "Expected stream dictionary" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }
}
