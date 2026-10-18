package com.sal7one.tvtestproject.receiver.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Session-local deadlines in System.nanoTime() / 1000 coordinates, never Unix/NTP epoch PTS.
 * Epoch/filter reference: https://github.com/FDH2/UxPlay/blob/master/lib/raop_ntp.c
 */
class SessionClock(
    val playoutDelayUs: Long = 120_000L,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private data class TimingSample(val remote: Long, val localUs: Long, val delayUs: Long)
    private class PresentationTimeline {
        var adjustmentUs = 0L
        var lastUs: Long? = null

        fun reset() {
            adjustmentUs = 0
            lastUs = null
        }
    }
    private val samples = ArrayDeque<TimingSample>()
    private var wallOriginUs = wallMillis() * 1000L
    private var monoOriginUs = nowUs()
    private var timingAnchor: TimingSample? = null
    private var arrivalAnchor: TimingSample? = null
    private var presentationOffsetUs: Long? = null
    private var audioRtp: Long? = null
    private var audioNtp: Long? = null
    private var audioArrivalUs = 0L
    private var fallbackRtp: Long? = null
    private var fallbackLocalUs = 0L
    private val audioTimeline = PresentationTimeline()
    private val videoTimeline = PresentationTimeline()

    init {
        require(playoutDelayUs in 100_000L..150_000L)
    }

    fun nowUs(): Long = monotonicNanos() / 1000L

    /** Stable local NTP wall clock: only the initial wall/monotonic correlation uses wall time. */
    @Synchronized
    fun localNtpTimestamp(atUs: Long = nowUs()): Long =
        ntpFromUnixUs(wallOriginUs + (atUs - monoOriginUs))

    /** Four-timestamp NTP exchange; the matching T1 token is validated by TimingReceiver. */
    @Synchronized
    fun observeTiming(
        remoteReceiveNtp: Long,
        remoteTransmitNtp: Long,
        localSendUs: Long,
        localReceiveUs: Long,
    ): Boolean {
        val elapsedUs = localReceiveUs - localSendUs
        val remoteProcessingUs = ntpDifferenceUs(remoteTransmitNtp, remoteReceiveNtp)
        val delayUs = elapsedUs - remoteProcessingUs
        if (elapsedUs !in 0L..1_000_000L || remoteProcessingUs < 0L ||
            delayUs !in -1_000L..500_000L || remoteTransmitNtp == 0L || remoteReceiveNtp == 0L
        ) return false
        val sample = TimingSample(remoteTransmitNtp, localReceiveUs - delayUs.coerceAtLeast(0L) / 2L,
            delayUs.coerceAtLeast(0L))
        samples.addLast(sample)
        if (samples.size > 8) samples.removeFirst()
        val best = samples.minBy { it.delayUs }
        val previous = timingAnchor
        val adjusted = if (previous == null) best.localUs else {
            val predicted = previous.localUs + ntpDifferenceUs(best.remote, previous.remote)
            // UxPlay's 0.05 clock filter; avoid replacing a good sample with network jitter.
            predicted + (best.localUs - predicted) / 20L
        }
        timingAnchor = best.copy(localUs = adjusted)
        return true
    }

    /** Raw audio/timing NTP uses the same epoch. Mirroring omits the 1900-to-1970 offset. */
    @Synchronized
    fun remoteNtpToLocalUs(timestamp: Long, addEpochOffset: Boolean = false): Long? {
        val anchor = timingAnchor ?: arrivalAnchor ?: return null
        val raw = if (addEpochOffset) timestamp + NTP_EPOCH_OFFSET else timestamp
        return anchor.localUs + ntpDifferenceUs(raw, anchor.remote)
    }

    /** Type 0x54 control packet's sync RTP (offset 4), not its next RTP (offset 16). */
    @Synchronized
    fun updateAudioSync(rtpTimestamp: Long, remoteNtp: Long, receivedAtUs: Long = nowUs()): Boolean {
        if (remoteNtp == 0L) return false
        audioRtp = rtpTimestamp and 0xffff_ffffL
        audioNtp = remoteNtp
        audioArrivalUs = receivedAtUs
        // An authoritative RTP/NTP pair rejoins the common mapping after an RTP-only jump.
        audioTimeline.adjustmentUs = 0
        if (timingAnchor == null && arrivalAnchor == null) {
            arrivalAnchor = TimingSample(remoteNtp, receivedAtUs, 0L)
        }
        return true
    }

    @Synchronized
    fun audioPresentationTimeUs(
        rtpTimestamp: Long,
        sampleRate: Int,
        receivedAtUs: Long = nowUs(),
    ): Long {
        require(sampleRate in 1..384_000)
        val rtp = rtpTimestamp and 0xffff_ffffL
        val syncRtp = audioRtp
        val syncNtp = audioNtp
        val sourceUs = if (syncRtp != null && syncNtp != null) {
            (remoteNtpToLocalUs(syncNtp) ?: audioArrivalUs) +
                rtpDifference(rtp, syncRtp) * 1_000_000L / sampleRate
        } else {
            if (fallbackRtp == null) {
                fallbackRtp = rtp
                fallbackLocalUs = receivedAtUs
            }
            fallbackLocalUs + rtpDifference(rtp, fallbackRtp!!) * 1_000_000L / sampleRate
        }
        return boundedPresentation(sourceUs, receivedAtUs, audioTimeline)
    }

    /** Mirroring headers must be read little-endian before calling this method. */
    @Synchronized
    fun videoPresentationTimeUs(timestamp: Long, receivedAtUs: Long = nowUs()): Long {
        val raw = timestamp + NTP_EPOCH_OFFSET
        if (timingAnchor == null && arrivalAnchor == null) {
            arrivalAnchor = TimingSample(raw, receivedAtUs, 0L)
        }
        val sourceUs = remoteNtpToLocalUs(raw) ?: receivedAtUs
        return boundedPresentation(sourceUs, receivedAtUs, videoTimeline)
    }

    private fun boundedPresentation(sourceUs: Long, receivedAtUs: Long, timeline: PresentationTimeline): Long {
        val offset = presentationOffsetUs ?: (receivedAtUs + playoutDelayUs - sourceUs).also {
            presentationOffsetUs = it
        }
        val minimumUs = receivedAtUs - MAX_LATE_PRESENTATION_US
        // A burst adds sample time to the playout delay. This matches AudioPipeline's
        // 160 ms PCM/ingress budget, not its much looser one-second admission guard.
        val maximumUs = receivedAtUs + playoutDelayUs + MAX_PRESENTATION_BURST_US
        val previousUs = timeline.lastUs
        val monotonicFloorUs = previousUs?.plus(1L) ?: minimumUs
        var candidate = sourceUs + offset + timeline.adjustmentUs
        if (candidate !in minimumUs..maximumUs) {
            // Isolate a sender timestamp discontinuity to its stream. Moving the shared
            // origin here makes interleaved good A/V timestamps repeatedly rebase each other.
            val rebasedUs = maxOf(receivedAtUs + playoutDelayUs, monotonicFloorUs).coerceAtMost(maximumUs)
            timeline.adjustmentUs += rebasedUs - candidate
            candidate = rebasedUs
        }
        // At the hard future boundary a duplicate is safer than an unbounded deadline;
        // the sink already rejects non-increasing PTS. Normal bounded bursts never hit it.
        return maxOf(candidate, monotonicFloorUs).coerceIn(minimumUs, maximumUs).also {
            timeline.lastUs = it
        }
    }

    /** RTSP FLUSH resets the audio timeline without discarding timing/video synchronization. */
    @Synchronized
    fun resetAudio() {
        audioRtp = null
        audioNtp = null
        fallbackRtp = null
        audioTimeline.reset()
        if (videoTimeline.lastUs == null) presentationOffsetUs = null
    }

    @Synchronized
    fun reset() {
        samples.clear()
        timingAnchor = null
        arrivalAnchor = null
        presentationOffsetUs = null
        videoTimeline.reset()
        resetAudio()
        wallOriginUs = wallMillis() * 1000L
        monoOriginUs = nowUs()
    }

    companion object {
        private const val MAX_PRESENTATION_BURST_US = 160_000L
        private const val MAX_LATE_PRESENTATION_US = 30_000L
        const val NTP_SECONDS_1900_TO_1970 = 2_208_988_800L
        val NTP_EPOCH_OFFSET: Long = NTP_SECONDS_1900_TO_1970 shl 32

        fun ntpFromUnixUs(unixUs: Long): Long {
            val seconds = Math.floorDiv(unixUs, 1_000_000L) + NTP_SECONDS_1900_TO_1970
            val fraction = (Math.floorMod(unixUs, 1_000_000L) shl 32) / 1_000_000L
            return (seconds shl 32) or fraction
        }

        /** Modular Q32.32 difference handles the 2036 wrap and arbitrary sender boot epochs. */
        fun ntpDifferenceUs(end: Long, start: Long): Long {
            val delta = end - start
            return (delta shr 32) * 1_000_000L +
                ((delta and 0xffff_ffffL) * 1_000_000L ushr 32)
        }

        fun rtpDifference(end: Long, start: Long): Long = (end - start).toInt().toLong()

        fun readNtp(bytes: ByteArray, offset: Int, littleEndian: Boolean = false): Long {
            require(offset >= 0 && offset <= bytes.size - 8)
            return ByteBuffer.wrap(bytes, offset, 8)
                .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long
        }

        fun writeNtp(bytes: ByteArray, offset: Int, timestamp: Long) {
            require(offset >= 0 && offset <= bytes.size - 8)
            ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp)
        }
    }
}
