package com.sal7one.tvtestproject.receiver.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SessionClockTest {
    private val bootEpoch = SessionClock.NTP_EPOCH_OFFSET + (123_456L shl 32)
    private fun after(base: Long, deltaUs: Long): Long =
        base + (deltaUs / 1_000_000L shl 32) + ((deltaUs % 1_000_000L shl 32) / 1_000_000L)

    private fun assertWithinUs(expected: Long, actual: Long, tolerance: Long = 1L) {
        assertTrue("Expected $expected +/- $tolerance us, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test fun convertsUnixEpochAndFractionInNetworkOrder() {
        assertEquals(SessionClock.NTP_EPOCH_OFFSET, SessionClock.ntpFromUnixUs(0))
        assertEquals(0x80000000L, SessionClock.ntpFromUnixUs(500_000) and 0xffffffffL)
        val bytes = ByteArray(10)
        SessionClock.writeNtp(bytes, 1, 0x0102030405060708L)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), bytes.copyOfRange(1, 9))
        assertEquals(0x0102030405060708L, SessionClock.readNtp(bytes, 1))
        assertEquals(0x0807060504030201L, SessionClock.readNtp(bytes, 1, true))
    }

    @Test fun ntpAndRtpDifferencesHandleWrapAndNegativeDeltas() {
        assertEquals(1_000_000L, SessionClock.ntpDifferenceUs(0L, -0x1_0000_0000L))
        assertEquals(-500_000L, SessionClock.ntpDifferenceUs(0L, 0x80000000L))
        assertEquals(32L, SessionClock.rtpDifference(16, 0xfffffff0L))
        assertEquals(-32L, SessionClock.rtpDifference(0xfffffff0L, 16))
    }

    @Test fun fourTimestampExchangeMapsBootEpochIntoLocalMonotonicCoordinates() {
        val clock = SessionClock(monotonicNanos = { 10_000_000_000L }, wallMillis = { 1_000L })
        assertTrue(clock.observeTiming(bootEpoch, after(bootEpoch, 2000), 1_000_000, 1_012_000))
        assertWithinUs(1_007_001L, clock.remoteNtpToLocalUs(after(bootEpoch, 2000))!!)
        assertWithinUs(1_107_001L, clock.remoteNtpToLocalUs(after(bootEpoch, 102000))!!)
        assertNull(SessionClock().remoteNtpToLocalUs(bootEpoch))
    }

    @Test fun mirrorEpochAdjustmentAndLittleEndianAreSourceCompatible() {
        val clock = SessionClock()
        clock.observeTiming(bootEpoch, after(bootEpoch, 2000), 1_000_000, 1_012_000)
        val mirrorTimestamp = after(bootEpoch, 2000) - SessionClock.NTP_EPOCH_OFFSET
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(mirrorTimestamp).array()
        assertEquals(clock.remoteNtpToLocalUs(after(bootEpoch, 2000)),
            clock.remoteNtpToLocalUs(SessionClock.readNtp(bytes, 0, true), addEpochOffset = true))
    }

    @Test fun rejectsImpossibleTimingSamples() {
        val clock = SessionClock()
        assertFalse(clock.observeTiming(bootEpoch, after(bootEpoch, 100_000), 1000, 2000))
        assertFalse(clock.observeTiming(bootEpoch, bootEpoch, 5000, 1000))
        assertFalse(clock.observeTiming(bootEpoch, bootEpoch, 1000, 2_000_000))
        assertFalse(clock.observeTiming(0, 0, 1000, 2000))
    }

    @Test fun audioStartsAtBoundedLatencyWithoutSyncAndTracksRtpSampleRate() {
        val clock = SessionClock()
        assertEquals(1_120_000L, clock.audioPresentationTimeUs(0xfffffff0L, 48000, 1_000_000))
        assertEquals(1_130_000L, clock.audioPresentationTimeUs(464, 48000, 1_010_000))
        assertEquals(1_140_000L, clock.audioPresentationTimeUs(944, 48000, 1_020_000))
    }

    @Test fun audioSyncAndTimingNeverProduceEpochSizedPts() {
        val clock = SessionClock()
        clock.observeTiming(bootEpoch, after(bootEpoch, 2000), 1_000_000, 1_012_000)
        clock.updateAudioSync(1000, bootEpoch, 1_012_000)
        val first = clock.audioPresentationTimeUs(8497, 44100, 1_020_000)
        assertTrue(first in 1_120_000L..1_170_000L)
        val next = clock.audioPresentationTimeUs(8977, 44100, 1_030_884)
        assertWithinUs(10_884L, next - first)
    }

    @Test fun audioPtsStayStrictlyMonotonicWhenSyncMovesBackward() {
        val clock = SessionClock()
        clock.updateAudioSync(0, bootEpoch, 1_000_000)
        val first = clock.audioPresentationTimeUs(480, 48000, 1_000_000)
        clock.updateAudioSync(960, bootEpoch, 1_010_000)
        assertTrue(clock.audioPresentationTimeUs(960, 48000, 1_010_000) > first)
    }

    @Test fun presentationRecoversFromLargeSenderDiscontinuity() {
        val clock = SessionClock()
        clock.audioPresentationTimeUs(0, 48000, 1_000_000)
        val next = clock.audioPresentationTimeUs(480000, 48000, 1_010_000)
        assertTrue(next in 1_110_000L..1_160_000L)
    }

    @Test fun resetAudioAndSessionDoNotCarryOldTimelineIntoNewSession() {
        var mono = 1_000_000_000L
        var wall = 1_000L
        val clock = SessionClock(monotonicNanos = { mono }, wallMillis = { wall })
        clock.updateAudioSync(123, bootEpoch)
        clock.observeTiming(bootEpoch, bootEpoch, 1_000_000, 1_010_000)
        clock.audioPresentationTimeUs(123, 48000)
        clock.resetAudio()
        assertNotNull(clock.remoteNtpToLocalUs(bootEpoch))
        assertEquals(2_120_000L, clock.audioPresentationTimeUs(999, 48000, 2_000_000))
        mono = 3_000_000_000L
        wall = 3000L
        clock.reset()
        assertNull(clock.remoteNtpToLocalUs(bootEpoch))
        assertEquals(3_120_000L, clock.audioPresentationTimeUs(7, 48000))
    }

    @Test fun localNtpDoesNotJumpWithWallClockChanges() {
        var mono = 1_000_000_000L
        var wall = 1_000L
        val clock = SessionClock(monotonicNanos = { mono }, wallMillis = { wall })
        val first = clock.localNtpTimestamp()
        mono += 10_000_000L
        wall += 86_400_000L
        assertWithinUs(10_000L, SessionClock.ntpDifferenceUs(clock.localNtpTimestamp(), first))
    }

    @Test fun audioAndVideoShareRemoteTimeAndBoundedPresentationOrigin() {
        val clock = SessionClock()
        clock.observeTiming(bootEpoch, after(bootEpoch, 2000), 1_000_000, 1_012_000)
        clock.updateAudioSync(1000, bootEpoch, 1_012_000)
        val audio = clock.audioPresentationTimeUs(1000, 48000, 1_020_000)
        val video = clock.videoPresentationTimeUs(bootEpoch - SessionClock.NTP_EPOCH_OFFSET, 1_020_000)
        assertEquals(audio, video)
    }

    @Test fun eldBurstsKeepSampleCadenceWithinReceiverAndPcmCapacityAcrossRtpWrap() {
        for (delayUs in listOf(100_000L, 120_000L, 150_000L)) {
            val clock = SessionClock(playoutDelayUs = delayUs)
            val capacity = (44_100L * delayUs / (480 * 1_000_000L) + 8).toInt()
            if (delayUs == 120_000L) assertEquals(19, capacity)
            val jitter = RaopJitterBuffer(capacity)
            val packetsPerBurst = (44_100L * 160_000 / (480 * 1_000_000L)).toInt()
            assertTrue(packetsPerBurst <= capacity)
            assertEquals(14, packetsPerBurst)
            val firstRtp = 0xffff_f000L
            val firstArrivalUs = 1_000_000L
            repeat(20) { burst ->
                val burstArrivalUs = firstArrivalUs + burst * packetsPerBurst * 480_000_000L / 44_100
                repeat(packetsPerBurst) { packet ->
                    val sequence = burst * packetsPerBurst + packet
                    val rtp = (firstRtp + sequence * 480) and 0xffff_ffffL
                    assertTrue(jitter.offer(RaopRtpPacket(sequence, rtp, 1, byteArrayOf(1)),
                        burstArrivalUs + packet * 100))
                }
                val drainUs = burstArrivalUs + 20_000
                repeat(packetsPerBurst) { packet ->
                    val frame = checkNotNull(jitter.poll(drainUs))
                    // Exactly the receiver's references: first arrival, then actual drain time.
                    val receivedUs = if (burst == 0 && packet == 0) jitter.lastPolledArrivalUs else drainUs
                    val ptsUs = clock.audioPresentationTimeUs(frame.timestamp, 44_100, receivedUs)
                    assertEquals(firstArrivalUs + delayUs + frame.sequence * 480_000_000L / 44_100, ptsUs)
                    assertTrue(ptsUs <= receivedUs + delayUs + 160_000)
                }
                assertNull(jitter.poll(drainUs))
            }
        }
    }

    @Test fun interleavedVideoAndNtpSyncKeepTheSharedOriginDuringAnEldBurst() {
        val clock = SessionClock()
        assertTrue(clock.observeTiming(bootEpoch, bootEpoch, 980_000, 1_000_000))
        assertTrue(clock.updateAudioSync(0, bootEpoch, 1_000_000))
        val first = clock.audioPresentationTimeUs(0, 44_100, 1_000_000)
        assertEquals(first, clock.videoPresentationTimeUs(bootEpoch - SessionClock.NTP_EPOCH_OFFSET, 1_000_000))
        repeat(13) { index ->
            val samples = (index + 1) * 480L
            val elapsedUs = samples * 1_000_000 / 44_100
            // Keep the sender's Q32.32 sample fraction when refreshing its sync pair.
            val sourceNtp = bootEpoch + (samples shl 32) / 44_100
            val audio = clock.audioPresentationTimeUs(samples, 44_100, 1_020_000)
            assertWithinUs(first + elapsedUs, audio)
            val video = clock.videoPresentationTimeUs(sourceNtp - SessionClock.NTP_EPOCH_OFFSET, 1_020_000)
            assertWithinUs(audio, video)
            if (index == 6) assertTrue(clock.updateAudioSync(samples, sourceNtp, 1_020_000))
        }
        assertWithinUs(first + 160_000,
            clock.videoPresentationTimeUs(after(bootEpoch, 160_000) - SessionClock.NTP_EPOCH_OFFSET, 1_160_000))
    }

    @Test fun audioRtpDiscontinuityDoesNotRebaseVideoAndValidSyncRejoinsSharedTime() {
        val clock = SessionClock()
        clock.updateAudioSync(0, bootEpoch, 1_000_000)
        val first = clock.audioPresentationTimeUs(0, 44_100, 1_000_000)
        assertEquals(first, clock.videoPresentationTimeUs(bootEpoch - SessionClock.NTP_EPOCH_OFFSET, 1_000_000))
        val jumped = clock.audioPresentationTimeUs(441_000, 44_100, 1_010_000)
        assertEquals(1_130_000L, jumped)
        val video = clock.videoPresentationTimeUs(after(bootEpoch, 10_000) - SessionClock.NTP_EPOCH_OFFSET, 1_010_000)
        assertWithinUs(first + 10_000, video)
        assertWithinUs(jumped + 10_884, clock.audioPresentationTimeUs(441_480, 44_100, 1_020_000))
        clock.updateAudioSync(500_000, after(bootEpoch, 40_000), 1_040_000)
        val resynced = clock.audioPresentationTimeUs(500_000, 44_100, 1_040_000)
        assertWithinUs(first + 40_000, resynced)
        assertEquals(resynced,
            clock.videoPresentationTimeUs(after(bootEpoch, 40_000) - SessionClock.NTP_EPOCH_OFFSET, 1_040_000))
    }

    @Test fun videoEpochSizedJumpDoesNotRebaseAudio() {
        val clock = SessionClock()
        clock.updateAudioSync(0, bootEpoch, 1_000_000)
        val first = clock.audioPresentationTimeUs(0, 44_100, 1_000_000)
        clock.videoPresentationTimeUs(bootEpoch - SessionClock.NTP_EPOCH_OFFSET, 1_000_000)
        val badEpoch = after(bootEpoch, 1_000_000_000_000L)
        val rebased = clock.videoPresentationTimeUs(badEpoch - SessionClock.NTP_EPOCH_OFFSET, 1_010_000)
        assertEquals(1_130_000L, rebased)
        assertWithinUs(first + 10_884, clock.audioPresentationTimeUs(480, 44_100, 1_020_000))
        val nextVideo = clock.videoPresentationTimeUs(after(badEpoch, 10_000) - SessionClock.NTP_EPOCH_OFFSET, 1_020_000)
        assertWithinUs(rebased + 10_000, nextVideo)
    }

    @Test fun backwardRtpPacketCannotPoisonTheFollowingForwardDeadline() {
        val clock = SessionClock()
        val first = clock.audioPresentationTimeUs(960, 44_100, 1_000_000)
        val backward = clock.audioPresentationTimeUs(480, 44_100, 1_010_000)
        assertTrue(backward > first)
        assertTrue(backward <= 1_010_000 + 280_000)
        assertEquals(first + 480_000_000L / 44_100,
            clock.audioPresentationTimeUs(1440, 44_100, 1_020_000))
    }

    @Test fun backwardTenSecondRtpJumpRebasesOnceThenRestoresSampleCadence() {
        val clock = SessionClock()
        clock.audioPresentationTimeUs(441_000, 44_100, 1_000_000)
        assertEquals(1_130_000L, clock.audioPresentationTimeUs(0, 44_100, 1_010_000))
        repeat(13) { packet ->
            // Crossing a negative RTP delta can differ by one integer microsecond.
            assertWithinUs(1_130_000L + (packet + 1) * 480_000_000L / 44_100,
                clock.audioPresentationTimeUs((packet + 1) * 480L, 44_100, 1_020_000))
        }
    }

    @Test fun repeatedDiscontinuitiesRemainBoundedEvenWithAnAheadMonotonicFloor() {
        val clock = SessionClock()
        val arrivalUs = 1_000_000L
        clock.audioPresentationTimeUs(0, 44_100, arrivalUs)
        assertEquals(arrivalUs + 280_000, clock.audioPresentationTimeUs(7056, 44_100, arrivalUs))
        repeat(64) { index ->
            val rtp = if (index % 2 == 0) 441_000L else 0L
            val ptsUs = clock.audioPresentationTimeUs(rtp, 44_100, arrivalUs)
            assertTrue(ptsUs in (arrivalUs - 30_000)..(arrivalUs + 280_000))
        }
    }

    @Test fun audioFlushDuringVideoDoesNotMoveTheVideoTimeline() {
        val clock = SessionClock()
        clock.updateAudioSync(0, bootEpoch, 1_000_000)
        val firstVideo = clock.videoPresentationTimeUs(bootEpoch - SessionClock.NTP_EPOCH_OFFSET, 1_000_000)
        clock.audioPresentationTimeUs(0, 44_100, 1_000_000)
        clock.resetAudio()
        clock.audioPresentationTimeUs(123, 44_100, 2_000_000)
        assertWithinUs(firstVideo + 1_000_000,
            clock.videoPresentationTimeUs(after(bootEpoch, 1_000_000) - SessionClock.NTP_EPOCH_OFFSET, 2_000_000))
    }
}
