package com.sal7one.tvtestproject.receiver.protocol

import com.sal7one.tvtestproject.receiver.AudioCodec
import org.junit.Assert.*
import org.junit.Test

class LegacyNegotiationTest {
    @Test fun timingSetupDoesNotInventStreams() {
        assertTrue(LegacyNegotiation.streams(mapOf("ekey" to ByteArray(72), "eiv" to ByteArray(16))).isEmpty())
    }

    @Test fun videoAndAudioStreamTypesAreDistinct() {
        val streams = listOf(mapOf("type" to 110L), mapOf("type" to 96L))
        assertEquals(streams, LegacyNegotiation.streams(mapOf("streams" to streams)))
    }

    @Test fun malformedUnknownDuplicateAndWrappedStreamTypesAreRejected() {
        val bad = listOf(null, emptyList<Any>(), listOf(null), listOf(mapOf("type" to 110)),
            listOf(mapOf("type" to 110.0)), listOf(mapOf("type" to 110L + (1L shl 32))),
            listOf(mapOf("type" to 110L), mapOf("type" to 110L)), listOf(mapOf("type" to 97L)))
        for (streams in bad) assertThrows(IllegalArgumentException::class.java) {
            LegacyNegotiation.streams(mapOf("streams" to streams))
        }
    }

    @Test fun capturedEldTupleUses480Samples() {
        val config = LegacyNegotiation.audio(mapOf("ct" to 8L, "spf" to 480L, "audioFormat" to 0x1000000L))
        assertEquals(AudioCodec.AAC_ELD, config.codec)
        assertEquals(44100, config.sampleRate)
        assertEquals(2, config.channels)
        assertEquals(480, config.samplesPerFrame)
        assertNotNull(config.audioSpecificConfig)
    }

    @Test fun infoFormatsIncludePcmForMacHalAndOnlySupportedCodecs() {
        assertEquals(0x01000800L, LegacyNegotiation.audioOutputFormats(setOf(AudioCodec.PCM, AudioCodec.AAC_ELD)))
        assertEquals(0x800L, LegacyNegotiation.audioOutputFormats(setOf(AudioCodec.PCM)))
        assertEquals(0x1000000L, LegacyNegotiation.audioOutputFormats(setOf(AudioCodec.AAC_ELD)))
        assertEquals(0x40000L, LegacyNegotiation.audioOutputFormats(setOf(AudioCodec.ALAC)))
        assertEquals(0x1040800L, LegacyNegotiation.audioOutputFormats(setOf(AudioCodec.PCM, AudioCodec.ALAC, AudioCodec.AAC_ELD)))
        assertEquals(0L, LegacyNegotiation.audioOutputFormats(emptySet()))
        assertEquals(0L, LegacyNegotiation.audioOutputFormats(setOf(AudioCodec.AAC_LC)))
    }

    @Test fun alacTransportIsAcceptedOnlyForItsAdvertisedTuple() {
        val stream = mapOf("ct" to 2L, "spf" to 352L, "audioFormat" to 0x40000L)
        val config = LegacyNegotiation.audio(stream)
        assertEquals(AudioCodec.ALAC, config.codec)
        assertEquals(352, config.samplesPerFrame)
        assertNull(config.audioSpecificConfig)
        for (bad in listOf("sampleRate" to 48000L, "channels" to 1L, "spf" to 4097L,
            "audioFormat" to 0x1000000L)) {
            assertThrows(IllegalArgumentException::class.java) { LegacyNegotiation.audio(stream + bad) }
        }
    }

    @Test fun advertisedPcmTupleIsAcceptedWithoutAcceptingOtherRatesOrWidths() {
        val stream = mapOf("ct" to 1L, "spf" to 352L, "audioFormat" to 0x800L)
        val config = LegacyNegotiation.audio(stream)
        assertEquals(AudioCodec.PCM, config.codec)
        assertEquals(44100, config.sampleRate)
        assertEquals(2, config.channels)
        assertNull(config.audioSpecificConfig)
        for (format in listOf(0L, 0x400L, 0x2000L, 0x8000L, 0x1000000L)) {
            assertThrows(IllegalArgumentException::class.java) {
                LegacyNegotiation.audio(stream + ("audioFormat" to format))
            }
        }
    }

    @Test fun infoPlistCarriesBothLegacyHalFormatLists() {
        val codecs = setOf(AudioCodec.PCM, AudioCodec.AAC_ELD)
        val decoded = Plist.decode(Plist.encode(mapOf("audioFormats" to LegacyNegotiation.audioFormats(codecs))))
        val entries = decoded["audioFormats"] as List<*>
        assertEquals(listOf(100L, 101L), entries.map { (it as Map<*, *>)["type"] })
        entries.forEach { entry ->
            val formats = entry as Map<*, *>
            assertEquals(0x01000800L, formats["audioInputFormats"])
            assertEquals(0x01000800L, formats["audioOutputFormats"])
        }
    }

    @Test fun audioNegotiationRejectsWrappingAndWrongFormat() {
        val valid = mapOf<String, Any?>("ct" to 8L, "spf" to 480L, "audioFormat" to 0x1000000L)
        for (bad in listOf("ct" to 8L + (1L shl 32), "spf" to 480L + (1L shl 32),
            "sampleRate" to 44100L + (1L shl 32), "channels" to 2L + (1L shl 32),
            "audioFormat" to 0x400000L, "ct" to 2L)) {
            assertThrows(IllegalArgumentException::class.java) { LegacyNegotiation.audio(valid + bad) }
        }
    }

    @Test fun aacMainIsNotSilentlyDecodedAsLc() {
        assertThrows(IllegalArgumentException::class.java) { LegacyNegotiation.audio(mapOf("ct" to 4L)) }
    }

    @Test fun featureMaskDoesNotClaimPhotosHlsDrmMetadataOrRsa() {
        val mask = LegacyNegotiation.features(setOf(AudioCodec.AAC_ELD))
        for (bit in listOf(0, 1, 2, 4, 5, 13, 15, 16, 17, 23, 40, 41)) assertEquals(0L, mask and (1L shl bit))
        for (bit in listOf(7, 9, 12, 14, 27, 30)) assertNotEquals(0L, mask and (1L shl bit))
        assertEquals(0L, LegacyNegotiation.features(emptySet()) and (1L shl 9))
    }

    @Test fun featureAudioFormatsMatchInfoAndBonjourCodecs() {
        val codecs = setOf(AudioCodec.PCM, AudioCodec.ALAC, AudioCodec.AAC_ELD)
        val features = LegacyNegotiation.features(codecs)
        for (bit in listOf(18, 19, 21)) assertNotEquals(0L, features and (1L shl bit))
        assertEquals(0L, features and (1L shl 20))
        assertEquals("0,1,3", com.sal7one.tvtestproject.UxPlayDnsTxt.audioCodecList(codecs))
        assertEquals(0L, LegacyNegotiation.features(emptySet()) and (0xfL shl 18))
        assertEquals(0L, LegacyNegotiation.features(setOf(AudioCodec.AAC_ELD)) and (1L shl 18))
        assertEquals(0L, LegacyNegotiation.features(setOf(AudioCodec.PCM)) and (1L shl 21))
    }

    @Test fun flushSequenceAcceptsUnsigned16BitValuesAndOptionalTimestamp() {
        assertNull(LegacyNegotiation.flushSequence(null))
        assertNull(LegacyNegotiation.flushSequence("rtptime=1234"))
        assertEquals(0, LegacyNegotiation.flushSequence("seq=0;rtptime=1234"))
        assertEquals(65535, LegacyNegotiation.flushSequence(" seq=65535 "))
    }

    @Test fun flushSequenceDoesNotConvertMalformedDataIntoAnUnconditionalFlush() {
        for (bad in listOf("seq", "seq=", "seq=no", "seq=-1", "seq=+1", "seq=65536",
            "seq=4294967296", "seq=1;seq=2", "seq=1,seq=2")) {
            assertThrows(IllegalArgumentException::class.java) { LegacyNegotiation.flushSequence(bad) }
        }
    }
}
