package com.sal7one.tvtestproject.receiver

import com.sal7one.tvtestproject.receiver.transport.SessionClock
import org.junit.Assert.*
import org.junit.Test

class PlaybackModeTest {
    @Test fun defaultPreservesTheWorkingBaseline() {
        assertEquals(PlaybackMode.BALANCED, PlaybackMode.fromStored(null))
        assertEquals(PlaybackMode.BALANCED, PlaybackMode.fromStored("corrupt"))
        assertEquals(120_000L, PlaybackMode.BALANCED.playoutDelayUs)
    }

    @Test fun eachModeKeepsAudioAndVideoOnOneBoundedClock() {
        for (mode in PlaybackMode.entries) {
            assertEquals(mode, PlaybackMode.fromStored(mode.name))
            val nowUs = 1_000_000L
            val clock = SessionClock(mode.playoutDelayUs, { nowUs * 1000 }, { 0L })
            val ntp = SessionClock.ntpFromUnixUs(0L)
            clock.updateAudioSync(100L, ntp, nowUs)
            val audio = clock.audioPresentationTimeUs(100L, 44_100, nowUs)
            val video = clock.videoPresentationTimeUs(ntp - SessionClock.NTP_EPOCH_OFFSET, nowUs)
            assertEquals(nowUs + mode.playoutDelayUs, audio)
            assertEquals(audio, video)
        }
    }

    @Test fun sixtyFpsRequiresSmoothModeAndHardwareCapability() {
        for (mode in PlaybackMode.entries) assertEquals(30, mode.maximumFps(false))
        assertEquals(60, PlaybackMode.SMOOTH.maximumFps(true))
        assertEquals(30, PlaybackMode.BALANCED.maximumFps(true))
        assertEquals(30, PlaybackMode.RESPONSIVE.maximumFps(true))
    }
}
