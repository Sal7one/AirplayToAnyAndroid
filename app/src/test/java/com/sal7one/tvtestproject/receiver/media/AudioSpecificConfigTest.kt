package com.sal7one.tvtestproject.receiver.media

import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.AudioConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSpecificConfigTest {
    @Test fun lcStandard44100StereoVector() {
        val config = AudioConfig(AudioCodec.AAC_LC)
        assertArrayEquals(hex("1210"), AudioSpecificConfig.build(config))
        assertEquals(AudioSpecificConfig.Parsed(AudioCodec.AAC_LC, 44_100, 2, 1024),
            AudioSpecificConfig.parse(hex("1210")))
    }

    @Test fun lc48000MonoVector() {
        assertArrayEquals(hex("1188"), AudioSpecificConfig.build(AudioConfig(AudioCodec.AAC_LC, 48_000, 1)))
    }

    @Test fun lc960FrameFlag() {
        val config = AudioConfig(AudioCodec.AAC_LC, samplesPerFrame = 960)
        assertArrayEquals(hex("1214"), AudioSpecificConfig.build(config))
        assertEquals(960, AudioSpecificConfig.parse(hex("1214")).samplesPerFrame)
    }

    @Test fun eld512EscapedObjectTypeVector() {
        val config = AudioConfig(AudioCodec.AAC_ELD, samplesPerFrame = 512)
        assertArrayEquals(hex("f8e84000"), AudioSpecificConfig.build(config))
        assertEquals(AudioSpecificConfig.Parsed(AudioCodec.AAC_ELD, 44_100, 2, 512),
            AudioSpecificConfig.parse(hex("f8e84000")))
    }

    @Test fun eld480FrameLengthFlagVector() {
        val config = AudioConfig(AudioCodec.AAC_ELD, samplesPerFrame = 480)
        assertArrayEquals(hex("f8e85000"), AudioSpecificConfig.build(config))
        assertEquals(480, AudioSpecificConfig.parse(hex("f8e85000")).samplesPerFrame)
    }

    @Test fun eld48000StereoVector() {
        assertArrayEquals(hex("f8e64000"),
            AudioSpecificConfig.build(AudioConfig(AudioCodec.AAC_ELD, 48_000, 2, 512)))
    }

    @Test fun everyIndexedRateRoundTripsBothFrameLengths() {
        val rates = listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
            22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
        for (rate in rates) for (channels in 1..2) {
            for ((codec, frames) in listOf(AudioCodec.AAC_LC to listOf(1024, 960),
                AudioCodec.AAC_ELD to listOf(512, 480))) {
                for (samples in frames) {
                    val config = AudioConfig(codec, rate, channels, samples)
                    assertEquals(AudioSpecificConfig.Parsed(codec, rate, channels, samples),
                        AudioSpecificConfig.parse(AudioSpecificConfig.build(config)))
                }
            }
        }
    }

    @Test fun explicit24BitFrequencyRoundTrips() {
        for ((codec, samples) in listOf(AudioCodec.AAC_LC to 1024, AudioCodec.AAC_ELD to 480)) {
            val config = AudioConfig(codec, 50_000, 2, samples)
            assertEquals(50_000, AudioSpecificConfig.parse(AudioSpecificConfig.build(config)).sampleRate)
        }
    }

    @Test fun suppliedConfigPreservedAndDefensivelyCopied() {
        val supplied = hex("f8e85000")
        val resolved = AudioSpecificConfig.resolve(AudioConfig(AudioCodec.AAC_ELD, samplesPerFrame = 480,
            audioSpecificConfig = supplied))
        assertArrayEquals(supplied, resolved)
        assertNotSame(supplied, resolved)
        supplied[0] = 0
        assertEquals(0xf8.toByte(), resolved!![0])
    }

    @Test fun suppliedConfigMustMatchAllNegotiatedFields() {
        val supplied = hex("1210")
        for (config in listOf(
            AudioConfig(AudioCodec.AAC_LC, 48_000, audioSpecificConfig = supplied),
            AudioConfig(AudioCodec.AAC_LC, channels = 1, audioSpecificConfig = supplied),
            AudioConfig(AudioCodec.AAC_LC, samplesPerFrame = 960, audioSpecificConfig = supplied),
            AudioConfig(AudioCodec.AAC_ELD, samplesPerFrame = 512, audioSpecificConfig = supplied),
        )) assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.resolve(config) }
    }

    @Test fun rejectsInvalidRatesChannelsAndFrames() {
        for (config in listOf(
            AudioConfig(AudioCodec.AAC_LC, sampleRate = 0),
            AudioConfig(AudioCodec.AAC_LC, sampleRate = 192_000),
            AudioConfig(AudioCodec.AAC_LC, channels = 6),
            AudioConfig(AudioCodec.AAC_LC, samplesPerFrame = 512),
            AudioConfig(AudioCodec.AAC_ELD, samplesPerFrame = 1024),
            AudioConfig(AudioCodec.PCM, samplesPerFrame = 0),
        )) assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.resolve(config) }
    }

    @Test fun pcmHasNoAacConfig() {
        assertNull(AudioSpecificConfig.resolve(AudioConfig(AudioCodec.PCM)))
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.build(AudioConfig(AudioCodec.PCM))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.resolve(AudioConfig(AudioCodec.PCM, audioSpecificConfig = hex("1210")))
        }
    }

    @Test fun rejectsTruncatedConfigAtEveryByteBoundary() {
        for (valid in listOf(hex("1210"), hex("f8e85000"),
            AudioSpecificConfig.build(AudioConfig(AudioCodec.AAC_ELD, 50_000, 2, 512)))) {
            for (length in 0 until valid.size) {
                assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.parse(valid.copyOf(length)) }
            }
        }
    }

    @Test fun rejectsEldUnsupportedFlagsAndPadding() {
        // Specific config starts at bit 19. Bits 20-22 are resilience, 23 SBR,
        // 24-27 extension type, 28-29 epConfig; 30-31 are padding.
        for (bit in 20..31) {
            val unsupported = hex("f8e84000")
            unsupported[bit / 8] = (unsupported[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.parse(unsupported) }
        }
    }

    @Test fun rejectsLcCoreCoderAndExtensions() {
        for (value in listOf("1212", "1211", "2a10", "1200", "1690", "121000")) {
            assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.parse(hex(value)) }
        }
    }

    @Test fun acceptsLcExplicitlyDisabledSbrSyncExtension() {
        // 0x1210 + syncExtensionType(0x2b7,11), objectType(5,5), sbrPresent(0,1).
        val config = hex("121056e500")
        assertEquals(AudioSpecificConfig.Parsed(AudioCodec.AAC_LC, 44_100, 2, 1024), AudioSpecificConfig.parse(config))
        assertArrayEquals(config, AudioSpecificConfig.resolve(AudioConfig(AudioCodec.AAC_LC,
            audioSpecificConfig = config)))
        assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.parse(hex("121056e580")) }
    }

    @Test fun rejectsOversizedSuppliedConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.resolve(AudioConfig(AudioCodec.AAC_LC, audioSpecificConfig = ByteArray(65)))
        }
    }

    @Test fun airPlayEldCapturedTupleDefaultsTo480() {
        assertEquals(1L shl 24, AudioSpecificConfig.AIRPLAY_ELD_44100_STEREO_MASK)
        val config = AudioSpecificConfig.fromAirPlay44100Stereo(8, 0x1000000L)
        assertEquals(AudioCodec.AAC_ELD, config.codec)
        assertEquals(44_100, config.sampleRate)
        assertEquals(2, config.channels)
        assertEquals(480, config.samplesPerFrame)
        assertArrayEquals(hex("f8e85000"), config.audioSpecificConfig)
        assertEquals(512, AudioSpecificConfig.fromAirPlay44100Stereo(8,
            audioSpecificConfig = hex("f8e84000")).samplesPerFrame)
    }

    @Test fun airPlayCompressionTypesAreNotBonjourOrdinals() {
        assertEquals(AudioCodec.ALAC, AudioSpecificConfig.fromAirPlay44100Stereo(2).codec)
        assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.fromAirPlay44100Stereo(3) }
        assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.fromAirPlay44100Stereo(0) }
        assertEquals(AudioCodec.PCM, AudioSpecificConfig.fromAirPlay44100Stereo(1,
            samplesPerFrame = 352).codec)
    }

    @Test fun alacNegotiationUsesBoundedNativePcm16Configuration() {
        val config = AudioSpecificConfig.fromAirPlay44100Stereo(2, 0x40000L)
        assertEquals(AudioCodec.ALAC, config.codec)
        assertEquals(352, config.samplesPerFrame)
        assertNull(config.audioSpecificConfig)
        assertNull(AudioSpecificConfig.resolve(config))
        assertEquals(4096, AudioSpecificConfig.fromAirPlay44100Stereo(2, 0x40000L, 4096).samplesPerFrame)
        for (samples in listOf(0, 351, 4097, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioSpecificConfig.fromAirPlay44100Stereo(2, 0x40000L, samples)
            }
        }
        for (bad in listOf(config.copy(sampleRate = 48_000), config.copy(channels = 1),
            config.copy(audioSpecificConfig = hex("1210")))) {
            assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.resolve(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.build(config) }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.fromAirPlay44100Stereo(2, 0x800L)
        }
    }

    @Test fun ambiguousAacCompressionTypeRequiresLcSpecificConfig() {
        assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.fromAirPlay44100Stereo(4) }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.fromAirPlay44100Stereo(4, audioSpecificConfig = hex("0a10"))
        }
        val config = AudioSpecificConfig.fromAirPlay44100Stereo(4, 0x400000L, audioSpecificConfig = hex("1210"))
        assertEquals(AudioCodec.AAC_LC, config.codec)
        assertEquals(1024, config.samplesPerFrame)
    }

    @Test fun airPlayHelperRejectsContradictoryOrUnknownTuples() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.fromAirPlay44100Stereo(8, 0x2000000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.fromAirPlay44100Stereo(8, 0x40000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.fromAirPlay44100Stereo(8, samplesPerFrame = 512, audioSpecificConfig = hex("f8e85000"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpecificConfig.fromAirPlay44100Stereo(8, audioSpecificConfig = hex("f8e65000"))
        }
        assertThrows(IllegalArgumentException::class.java) { AudioSpecificConfig.fromAirPlay44100Stereo(1) }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
