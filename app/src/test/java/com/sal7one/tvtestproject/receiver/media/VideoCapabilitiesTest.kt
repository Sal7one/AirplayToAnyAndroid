package com.sal7one.tvtestproject.receiver.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoCapabilitiesTest {
    @Test fun main10UhdRequestComesFromActualSpsRatherThanDisplayName() {
        val config = H265Config.fromParameterSets(listOf(H265ConfigTest.VPS_4K), listOf(H265ConfigTest.SPS_4K), listOf(H265ConfigTest.PPS))
        val request = VideoCapabilities.requestFor(config, 59.94)
        assertEquals(3840, request.width)
        assertEquals(2160, request.height)
        assertEquals(59.94, request.frameRate, 0.0001)
        assertEquals(VideoCapabilities.Profile.HEVC_MAIN_10, request.profile)
    }

    @Test fun capabilityCheckUsesCodedNotVisibleCropDimensions() {
        val config = H265Config.fromParameterSets(listOf(H265ConfigTest.VPS_1080), listOf(H265ConfigTest.SPS_1080), listOf(H265ConfigTest.PPS))
        val cropped = config.copy(activeSps = config.activeSps.copy(codedHeight = 1088, cropBottom = 8))
        val request = VideoCapabilities.requestFor(cropped, 30.0)
        assertEquals(1920, request.width)
        assertEquals(1088, request.height)
        assertEquals(VideoCapabilities.Profile.HEVC_MAIN, request.profile)
    }

    @Test fun invalidDimensionsAndNonFiniteRatesAreRejectedBeforeAndroidCalls() {
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 241.0)) {
            rejects { VideoCapabilities.Request(3840, 2160, rate) }
        }
        for (size in listOf(0, -1, 16385, Int.MAX_VALUE)) {
            rejects { VideoCapabilities.Request(size, 2160, 60.0) }
            rejects { VideoCapabilities.Request(3840, size, 60.0) }
        }
    }

    @Test fun oemNamesNeverBecomeProofOfHardwareAcceleration() {
        assertEquals(VideoCapabilities.Acceleration.UNKNOWN, VideoCapabilities.classifyAcceleration("c2.mtk.hevc.decoder", null, null))
        assertEquals(VideoCapabilities.Acceleration.UNKNOWN, VideoCapabilities.classifyAcceleration("OMX.vendor.hevc", false, false))
        assertEquals(VideoCapabilities.Acceleration.SOFTWARE, VideoCapabilities.classifyAcceleration("OMX.google.hevc.decoder", null, null))
        assertEquals(VideoCapabilities.Acceleration.SOFTWARE, VideoCapabilities.classifyAcceleration("c2.android.hevc.decoder", null, null))
        assertEquals(VideoCapabilities.Acceleration.SOFTWARE, VideoCapabilities.classifyAcceleration("vendor", true, true))
        assertEquals(VideoCapabilities.Acceleration.HARDWARE, VideoCapabilities.classifyAcceleration("vendor", true, false))
    }

    @Test fun candidateRequiresHardwareAndExactFormatAndSizeRateSupport() {
        val hardware = decoder(VideoCapabilities.Acceleration.HARDWARE)
        assertTrue(hardware.hardwareCandidate)
        assertFalse(hardware.copy(formatSupported = false).hardwareCandidate)
        assertFalse(hardware.copy(sizeAndRateSupported = false).hardwareCandidate)
        assertFalse(decoder(VideoCapabilities.Acceleration.SOFTWARE).hardwareCandidate)
        assertFalse(decoder(VideoCapabilities.Acceleration.UNKNOWN).hardwareCandidate)
        // An unreported performance point is unknown, not a successful real-time playback test.
        assertEquals(null, hardware.performancePointCoversRequest)
        val result = VideoCapabilities.ProbeResult(VideoCapabilities.Request(3840, 2160, 60.0),
            listOf(hardware, hardware.copy(formatSupported = false)), emptyList())
        assertEquals(listOf(hardware), result.hardwareCandidates)
    }

    @Test fun avcSmoothRequestIsExactAndDoesNotImplySupportForOtherModes() {
        val smooth = VideoCapabilities.Request(1920, 1080, 60.0, VideoCapabilities.Profile.AVC_HIGH)
        val balanced = smooth.copy(frameRate = 30.0)
        val hevc = smooth.copy(profile = VideoCapabilities.Profile.HEVC_MAIN)
        assertEquals("video/avc", smooth.profile.mimeType)
        assertFalse(smooth == balanced)
        assertFalse(smooth == hevc)
        val unavailable = VideoCapabilities.ProbeResult(smooth, emptyList(),
            listOf(VideoCapabilities.QueryFailure("failing-vendor-codec", "Query failed")))
        assertTrue(unavailable.hardwareCandidates.isEmpty())
    }

    @Test fun unsupportedColourFormatOrProfileCannotSilentlyProbeAsMain() {
        val config = H265Config.fromParameterSets(listOf(H265ConfigTest.VPS_1080), listOf(H265ConfigTest.SPS_1080), listOf(H265ConfigTest.PPS))
        rejects { VideoCapabilities.requestFor(config.copy(activeSps = config.activeSps.copy(chromaFormatIdc = 3)), 30.0) }
        rejects { VideoCapabilities.requestFor(config.copy(activeSps = config.activeSps.copy(bitDepthLuma = 10)), 30.0) }
        rejects { VideoCapabilities.requestFor(config.copy(activeSps = config.activeSps.copy(profile = config.activeSps.profile.copy(profileIdc = 4))), 30.0) }
    }

    private fun decoder(acceleration: VideoCapabilities.Acceleration) = VideoCapabilities.Decoder(
        "queried-decoder", acceleration, emptyList(), formatSupported = true,
        sizeAndRateSupported = true, lowLatency = false, performancePointCoversRequest = null)

    private fun rejects(block: () -> Any?) {
        try { block() } catch (_: IllegalArgumentException) { return }
        fail("Expected IllegalArgumentException")
    }
}
