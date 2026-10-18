package com.sal7one.tvtestproject.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Exercises the actual worker queue/clock state with PCM, without Android API substitutes. */
class AudioPlaybackTest {
    private fun queue() = AudioPcmQueue(44_100, 2, 160_000, 32)
    private fun pcm(samples: Int, marker: Int = 1): ByteBuffer =
        ByteBuffer.wrap(ByteArray(samples * 4) { marker.toByte() })
    private fun clock() = AudioPlaybackClock(15_000, 250_000)

    @Test fun earlyEldBurstReservesOutputBeforeItIsDecoded() {
        val queue = queue()
        repeat(14) { queue.reserve(480) }
        assertEquals(6720, queue.pendingFrames)
        assertFalse(queue.canReserve(480))
        // All output must drain even if no new input arrives and playback has not started.
        repeat(14) { packet ->
            queue.decoded(480)
            assertTrue(queue.offer(pcm(480, packet + 1), 120_000L + packet * 480_000_000L / 44_100))
        }
        assertEquals(0, queue.pendingFrames)
        assertEquals(6720, queue.queuedFrames)
        assertFalse(queue.canReserve(480))
        repeat(14) { packet ->
            val first = checkNotNull(queue.first)
            assertEquals(packet + 1, first.buffer.get(first.buffer.position()).toInt())
            assertEquals(120_000L + packet * 480_000_000L / 44_100, first.ptsUs)
            queue.consume(480 * 4)
        }
        assertNull(queue.first)
        assertTrue(queue.canReserve(480))
    }

    @Test fun partialTrackWritesReleaseOnlyConsumedCapacity() {
        val queue = queue()
        repeat(14) {
            queue.reserve(480)
            queue.decoded(480)
            assertTrue(queue.offer(pcm(480), it.toLong()))
        }
        queue.consume(100 * 4)
        assertEquals(6620, queue.queuedFrames)
        assertFalse(queue.canReserve(480))
        queue.consume(140 * 4)
        assertEquals(6480, queue.queuedFrames)
        assertTrue(queue.canReserve(480))
        assertEquals(240 * 4, queue.first!!.buffer.remaining())
    }

    @Test fun lateEldPrefixIsTrimmedInOnePassAndTimelyPcmRemainsWritable() {
        val queue = queue()
        repeat(12) { packet ->
            assertTrue(queue.offer(pcm(480, packet + 1), 1_000_000L + packet * 480_000_000L / 44_100))
        }
        // A 65 ms correction must not cost six worker polls without a write.
        assertEquals(5 * 480, queue.trimBefore(1_065_306, 15_000))
        val next = checkNotNull(queue.first)
        assertEquals(6, next.buffer.get(next.buffer.position()).toInt())
        assertEquals(7 * 480, queue.queuedFrames)
        assertEquals(0, queue.trimBefore(1_065_306, 15_000))
        queue.consume(147 * 4)
        assertEquals(7 * 480 - 147, queue.queuedFrames)
        assertTrue(queue.canReserve(480))
    }

    @Test fun lateTrimUsesTheUnwrittenRemainderAndPreservesDecoderCredits() {
        val queue = queue()
        assertTrue(queue.offer(pcm(1024), 1_000_000))
        queue.reserve(2048)
        queue.consume(100 * 4)
        // The partial position rounds down to microseconds; lateFrames rounds up.
        assertEquals(783, queue.trimBefore(1_020_000, 15_000))
        assertEquals(1_020_022L, queue.presentationTimeUs(checkNotNull(queue.first)))
        assertEquals(141, queue.queuedFrames)
        assertEquals(2048, queue.pendingFrames)
        // A zero-byte nonblocking write must leave both sample time and credits unchanged.
        queue.consume(0)
        assertEquals(1_020_022L, queue.presentationTimeUs(checkNotNull(queue.first)))
        assertEquals(141, queue.queuedFrames)
        assertEquals(2048, queue.pendingFrames)
        assertEquals(0, queue.trimBefore(1_020_000, 15_000))
    }

    @Test fun reprimeDiscardsAStalePrefixWithoutWaitingBetweenPackets() {
        val queue = queue()
        repeat(12) { packet ->
            assertTrue(queue.offer(pcm(480), 1_000_000L + packet * 480_000_000L / 44_100))
        }
        assertEquals(5 * 480, queue.trimBefore(1_059_307, 15_000))
        assertTrue(1_059_307 - queue.presentationTimeUs(checkNotNull(queue.first)) <= 15_000)
        queue.consume(480 * 4)
        assertEquals(6 * 480, queue.queuedFrames)
    }

    @Test fun unexpectedOutputNeverEvictsUnplayedPcm() {
        val queue = queue()
        assertTrue(queue.offer(pcm(queue.capacityFrames, 7), 120_000))
        assertFalse(queue.offer(pcm(480, 9), 130_884))
        assertEquals(queue.capacityFrames, queue.queuedFrames)
        assertEquals(120_000L, queue.first!!.ptsUs)
        assertEquals(7, queue.first!!.buffer.get(0).toInt())
    }

    @Test fun splitAndCombinedDecoderOutputReturnSampleCredits() {
        val queue = queue()
        repeat(4) { queue.reserve(480) }
        for (samples in listOf(240, 240, 960, 480)) {
            queue.decoded(samples)
            assertTrue(queue.offer(pcm(samples), 120_000))
            assertEquals(1920, queue.pendingFrames + queue.queuedFrames)
        }
        assertEquals(0, queue.pendingFrames)
    }

    @Test fun sustainedBurstDecodeAndPartialWritesStayBoundedAndOrdered() {
        val queue = queue()
        var submitted = 0
        var decoded = 0
        var consumed = 0
        val packets = 2000
        while (consumed < packets * 480) {
            repeat(4) { if (submitted < packets && queue.canReserve(480)) {
                queue.reserve(480)
                submitted++
            } }
            repeat(8) { if (decoded < submitted) {
                queue.decoded(480)
                assertTrue(queue.offer(pcm(480, decoded % 100 + 1), decoded.toLong()))
                decoded++
            } }
            val first = checkNotNull(queue.first)
            assertEquals((consumed / 480).toLong(), first.ptsUs)
            assertEquals(consumed / 480 % 100 + 1, first.buffer.get(first.buffer.position()).toInt())
            val samples = minOf(147, first.buffer.remaining() / 4)
            queue.consume(samples * 4)
            consumed += samples
            assertTrue(queue.pendingFrames + queue.queuedFrames <= queue.capacityFrames)
        }
        assertEquals(packets, submitted)
        assertEquals(packets, decoded)
        assertEquals(0, queue.pendingFrames + queue.queuedFrames)
    }

    @Test fun flushReleasesPcmAndUndrainedDecoderReservations() {
        val queue = queue()
        queue.reserve(480)
        assertTrue(queue.offer(pcm(480), 120_000))
        queue.clear()
        assertNull(queue.first)
        assertEquals(0, queue.pendingFrames + queue.queuedFrames)
        assertTrue(queue.canReserve(queue.capacityFrames))
    }

    @Test fun primingRequiresBothFullTrackAndPresentationDeadline() {
        assertFalse(AudioPlaybackTiming.canStart(480, 1764, 1_120_000, 1_120_000, 15_000))
        assertFalse(AudioPlaybackTiming.canStart(1764, 1764, 1_120_000, 1_000_000, 15_000))
        assertTrue(AudioPlaybackTiming.canStart(1764, 1764, 1_120_000, 1_105_000, 15_000))
        // Pre-31 devices may have a minimum larger than the requested 40 ms.
        assertFalse(AudioPlaybackTiming.canStart(1764, 4410, 1_120_000, 1_120_000, 15_000))
        assertTrue(AudioPlaybackTiming.canStart(4410, 4410, 1_120_000, 1_120_000, 15_000))
    }

    @Test fun shortTrackDipsDoNotFlushLiveAudio() {
        assertFalse(AudioPlaybackTiming.shouldReprime(null, 1_000_000, 80_000))
        assertFalse(AudioPlaybackTiming.shouldReprime(1_000_000, 1_079_999, 80_000))
        assertTrue(AudioPlaybackTiming.shouldReprime(1_000_000, 1_080_000, 80_000))
    }

    @Test fun firstTimestampCannotMoveThePlaybackClock() {
        val clock = clock()
        assertEquals(200_000L, clock.pollIntervalUs)
        clock.observe(0, 1_100_000, 1764, 44_100, 1_000_000)
        assertEquals(1_040_000L, clock.nextPlaybackUs(1764, 44_100, 1_040_000, 1_000_000))
        assertEquals(200_000L, clock.pollIntervalUs)
    }

    @Test fun validatedHardwareMappingUsesLongTermPollingWithoutLosingPresentationLatency() {
        val clock = clock()
        clock.observe(0, 1_100_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_300_000, 13_230, 44_100, 1_200_000)
        assertEquals(1_400_000L, clock.nextPlaybackUs(13_230, 44_100, 1_340_000, 1_200_000))
        assertEquals(10_000_000L, clock.pollIntervalUs)
        // The timestamp's age limit must not expire the validated frame/time mapping.
        assertEquals(10_400_000L, clock.nextPlaybackUs(410_130, 44_100, 10_340_000, 10_200_000))
        clock.observe(-1, 0, 454_230, 44_100, 11_200_000)
        assertEquals(200_000L, clock.pollIntervalUs)
        assertEquals(11_400_000L, clock.nextPlaybackUs(454_230, 44_100, 11_340_000, 11_200_000))
        clock.reset()
        assertEquals(200_000L, clock.pollIntervalUs)
    }

    @Test fun burstCadenceTimestampNoiseCannotRepeatedlyTrimContinuousEld() {
        val clock = clock()
        val queue = queue()
        clock.observe(0, 1_000_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_200_000, 13_230, 44_100, 1_200_000)
        clock.nextPlaybackUs(13_230, 44_100, 1_240_000, 1_200_000)
        var polledAtUs = 1_200_000L
        var written = 13_230L
        var trimmed = 0
        repeat(400) { packet ->
            val nowUs = 1_200_000L + packet * 480_000_000L / 44_100
            if (nowUs - polledAtUs >= clock.pollIntervalUs) {
                // Rate-consistent short pairs can still move the origin by more
                // than a packet over a second while a TV's timestamp warms up.
                val phaseUs = minOf(40_000L, (nowUs - 1_200_000) / 20)
                clock.observe(written - 4410, nowUs + phaseUs, written, 44_100, nowUs)
                polledAtUs = nowUs
            }
            val ptsUs = 1_000_000L + written * 1_000_000 / 44_100
            assertTrue(queue.offer(pcm(480), ptsUs))
            trimmed += queue.trimBefore(clock.nextPlaybackUs(written, 44_100, ptsUs, nowUs), 15_000)
            val remaining = queue.queuedFrames
            if (remaining > 0) queue.consume(remaining * 4)
            written += remaining
            assertEquals("Clock recalibration starved continuous ELD at packet $packet", 0, trimmed)
        }
        assertEquals(13_230L + 400 * 480, written)
        assertEquals(1_200_000L, polledAtUs)
    }

    @Test fun advancingTimestampPairRetainsHardwarePresentationLatency() {
        val clock = clock()
        clock.observe(0, 1_000_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_200_000, 13_230, 44_100, 1_200_000)
        assertEquals(1_300_000L, clock.nextPlaybackUs(13_230, 44_100, 1_240_000, 1_200_000))
    }

    @Test fun stallCorrectionCannotImmediatelyBecomeThePlaybackClock() {
        val clock = clock()
        clock.observe(0, 1_000_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_200_000, 13_230, 44_100, 1_200_000)
        // The timestamp clock jumps by 100 ms without corresponding sample progress.
        clock.observe(17_640, 1_500_000, 19_404, 44_100, 1_400_000)
        assertEquals(1_440_000L, clock.nextPlaybackUs(19_404, 44_100, 1_440_000, 1_400_000))
    }

    @Test fun timestampFailureRetainsTheLastValidatedHardwareMapping() {
        val clock = clock()
        clock.observe(0, 1_100_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_300_000, 13_230, 44_100, 1_200_000)
        assertEquals(1_400_000L, clock.nextPlaybackUs(13_230, 44_100, 1_340_000, 1_200_000))
        clock.observe(-1, 0, 22_050, 44_100, 1_400_000)
        assertEquals(1_600_000L, clock.nextPlaybackUs(22_050, 44_100, 1_540_000, 1_400_000))
        clock.reset()
        assertEquals(2_040_000L, clock.nextPlaybackUs(1764, 44_100, 2_040_000, 2_000_000))
    }

    @Test fun laterTimestampCorrectionsAreFilteredOncePerObservation() {
        val clock = clock()
        clock.observe(0, 1_000_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_200_000, 13_230, 44_100, 1_200_000)
        assertEquals(1_300_000L, clock.nextPlaybackUs(13_230, 44_100, 1_240_000, 1_200_000))
        clock.observe(17_640, 1_410_000, 22_050, 44_100, 1_400_000)
        repeat(100) {
            assertEquals(1_500_500L, clock.nextPlaybackUs(22_050, 44_100, 1_440_000, 1_400_000))
        }
    }

    @Test fun staleUnavailableAndStationaryTimestampsUseThePcmTimeline() {
        val clock = clock()
        clock.observe(0, 1_000_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_200_000, 13_230, 44_100, 1_200_000)
        assertEquals(1_540_000L, clock.nextPlaybackUs(23_814, 44_100, 1_540_000, 1_500_001))
        clock.observe(8820, 1_400_000, 19_404, 44_100, 1_400_000)
        assertEquals(1_440_000L, clock.nextPlaybackUs(19_404, 44_100, 1_440_000, 1_400_000))
        clock.observe(-1, 1_400_000, 19_404, 44_100, 1_600_000)
        assertEquals(1_640_000L, clock.nextPlaybackUs(19_404, 44_100, 1_640_000, 1_600_000))
    }

    @Test fun underrunResetRequiresANewTimestampPair() {
        val clock = clock()
        clock.observe(0, 1_000_000, 8820, 44_100, 1_000_000)
        clock.observe(8820, 1_200_000, 13_230, 44_100, 1_200_000)
        clock.reset()
        clock.observe(0, 8_100_000, 1764, 44_100, 8_000_000)
        assertEquals(8_040_000L, clock.nextPlaybackUs(1764, 44_100, 8_040_000, 8_000_000))
        assertTrue(AudioPlaybackTiming.canStart(1764, 1764, 8_120_000, 8_105_000, 15_000))
    }
}
