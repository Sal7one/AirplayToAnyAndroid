package com.sal7one.tvtestproject

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.AudioConfig
import com.sal7one.tvtestproject.receiver.PlaybackMode
import com.sal7one.tvtestproject.receiver.media.AudioPipeline
import com.sal7one.tvtestproject.receiver.media.AudioPlaybackDiagnostics
import com.sal7one.tvtestproject.receiver.transport.SessionClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** Real AudioTrack progress, without capture permissions, injected outputs, or reflection. */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackRuntimeTest {
    @Test fun boundedPcmBurstsKeepPlayingAndRecoverAfterAnUnderrun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val error = AtomicReference<String?>()
        try {
            AudioPipeline(context, { error.set(it) }).use { pipeline ->
                assertTrue("PCM AudioTrack configuration failed", pipeline.configure(
                    AudioConfig(AudioCodec.PCM, SAMPLE_RATE, 2, SAMPLES)))
                pipeline.setVolume(-30f)
                stream(pipeline, 368, error)
                awaitPlayed(pipeline, 368L * SAMPLES, error)
                val beforeGap = pipeline.diagnostics
                assertEquals(0L, beforeGap.pcmOverflowFrames)
                assertTrue("No sustained PCM playback: $beforeGap", beforeGap.totalPlayedFrames > SAMPLE_RATE * 3)

                SystemClock.sleep(350)
                assertTrue("Empty track was not re-primed: ${pipeline.diagnostics}",
                    pipeline.diagnostics.trackResetCount > beforeGap.trackResetCount ||
                        beforeGap.trackResetCount > 0)
                val recoveryBaseline = pipeline.diagnostics
                stream(pipeline, 184, error)
                awaitPlayed(pipeline, 184L * SAMPLES, error, recoveryBaseline)
                val recovered = pipeline.diagnostics
                assertEquals("Unplayed PCM was evicted: $recovered", 0L, recovered.pcmOverflowFrames)
                assertTrue("Recovery did not write real PCM: $recovered",
                    recovered.totalPcmWrittenFrames > beforeGap.totalPcmWrittenFrames + SAMPLE_RATE)
                assertTrue("Unexpected recurring late trimming: $recovered", recovered.lateTrimmedFrames < SAMPLE_RATE / 4)
                assertNull(error.get())
                android.util.Log.i("FossAudioTest", "pcm_burst_underrun_recovery $recovered")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun realAacLcBurstsDrainDecoderOutputWithoutEvictingPcm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packets = adtsAccessUnits(instrumentation.context.assets.open("aac-lc-44100-stereo.aac")
            .use { it.readBytes() })
        assertTrue("Fixture must exercise sustained decoding", packets.size >= 80)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val error = AtomicReference<String?>()
        try {
            AudioPipeline(context, { error.set(it) }).use { pipeline ->
                assertTrue("Real AAC-LC decoder/AudioTrack configuration failed", pipeline.configure(
                    AudioConfig(AudioCodec.AAC_LC, SAMPLE_RATE, 2, 1024, byteArrayOf(0x12, 0x10))))
                pipeline.setVolume(-30f)
                // Six 1024-sample packets reserve 139 ms, within the unchanged 160 ms budget.
                stream(pipeline, packets.size, error, samplesPerPacket = 1024, initialBurst = 6,
                    frameAt = { packets[it] })
                // This is an ongoing stream, not codec EOS. The platform AAC decoder
                // may retain two access units internally; available output must drain.
                awaitPlayed(pipeline, packets.size * 1024L, error, maxDecoderResidency = 2048)
                val snapshot = pipeline.diagnostics
                assertTrue(snapshot.pendingDecoderFrames in 0..2048)
                assertEquals(0, snapshot.queuedPcmFrames)
                assertEquals(0L, snapshot.pcmOverflowFrames)
                assertTrue("No real AAC playback: $snapshot", snapshot.totalPlayedFrames > SAMPLE_RATE)
                assertNull(error.get())
                android.util.Log.i("FossAudioTest", "aac_lc_burst_decoder_drain $snapshot")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun eachPlaybackModeKeepsRealPcmAndAacPlaybackBounded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packets = adtsAccessUnits(instrumentation.context.assets.open("aac-lc-44100-stereo.aac")
            .use { it.readBytes() })
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            for (mode in PlaybackMode.entries) {
                val error = AtomicReference<String?>()
                AudioPipeline(context, { error.set(it) }).use { pipeline ->
                    assertTrue(pipeline.configure(AudioConfig(AudioCodec.PCM, SAMPLE_RATE, 2, SAMPLES)))
                    pipeline.setVolume(-30f)
                    stream(pipeline, 184, error, playoutDelayUs = mode.playoutDelayUs)
                    awaitPlayed(pipeline, 184L * SAMPLES, error)
                }
                AudioPipeline(context, { error.set(it) }).use { pipeline ->
                    assertTrue(pipeline.configure(AudioConfig(AudioCodec.AAC_LC, SAMPLE_RATE, 2, 1024, byteArrayOf(0x12, 0x10))))
                    pipeline.setVolume(-30f)
                    stream(pipeline, packets.size, error, samplesPerPacket = 1024, initialBurst = 6,
                        playoutDelayUs = mode.playoutDelayUs, frameAt = { packets[it] })
                    awaitPlayed(pipeline, packets.size * 1024L, error, maxDecoderResidency = 2048)
                }
                assertNull("Audio failed in $mode", error.get())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun stream(
        pipeline: AudioPipeline,
        packets: Int,
        error: AtomicReference<String?>,
        samplesPerPacket: Int = SAMPLES,
        initialBurst: Int = 12,
        playoutDelayUs: Long = 120_000L,
        frameAt: (Int) -> ByteArray = { tone(it * SAMPLES) },
    ) {
        val firstPtsUs = nowUs() + playoutDelayUs
        val presentationClock = SessionClock(playoutDelayUs)
        var packet = 0
        var progressAtUs = nowUs()
        var played = pipeline.diagnostics.totalPlayedFrames
        val initialResets = pipeline.diagnostics.trackResetCount
        var settledTrimmedFrames: Long? = null
        while (packet < packets) {
            sleepUntil(firstPtsUs + packet * samplesPerPacket * 1_000_000L / SAMPLE_RATE - playoutDelayUs)
            val burst = minOf(if (packet == 0) initialBurst else 4, packets - packet)
            repeat(burst) {
                val rtp = (0xffff_f000L + packet.toLong() * samplesPerPacket) and 0xffff_ffffL
                val ptsUs = presentationClock.audioPresentationTimeUs(rtp, SAMPLE_RATE, nowUs())
                assertTrue("Bounded audio input rejected at $packet: ${pipeline.diagnostics}",
                    pipeline.enqueue(frameAt(packet), ptsUs))
                packet++
            }
            val snapshot = pipeline.diagnostics
            assertNull(error.get())
            assertEquals("Unplayed PCM evicted: $snapshot", 0L, snapshot.pcmOverflowFrames)
            assertEquals("Continuous input caused a trim/underrun/reprime cycle: $snapshot",
                initialResets, snapshot.trackResetCount)
            assertTrue("PCM capacity exceeded: $snapshot",
                snapshot.queuedPcmFrames + snapshot.pendingDecoderFrames <= snapshot.pcmCapacityFrames)
            settledTrimmedFrames?.let {
                assertEquals("Late trimming recurred after startup: $snapshot", it, snapshot.lateTrimmedFrames)
            }
            if (nowUs() - progressAtUs >= 1_000_000) {
                assertTrue("Playback stopped making progress: $snapshot",
                    snapshot.totalPlayedFrames - played > SAMPLE_RATE / 2)
                progressAtUs = nowUs()
                played = snapshot.totalPlayedFrames
                settledTrimmedFrames = snapshot.lateTrimmedFrames
            }
        }
    }

    private fun awaitPlayed(
        pipeline: AudioPipeline,
        frames: Long,
        error: AtomicReference<String?>,
        baseline: AudioPlaybackDiagnostics = AudioPlaybackDiagnostics(),
        maxDecoderResidency: Int = 0,
    ) {
        val deadlineUs = nowUs() + 2_000_000
        while (nowUs() < deadlineUs) {
            val snapshot = pipeline.diagnostics
            val trimmed = snapshot.lateTrimmedFrames - baseline.lateTrimmedFrames
            val pending = snapshot.pendingDecoderFrames - baseline.pendingDecoderFrames
            val silence = snapshot.totalSilenceWrittenFrames - baseline.totalSilenceWrittenFrames
            if (pending in 0..maxDecoderResidency &&
                snapshot.totalPlayedFrames - baseline.totalPlayedFrames + trimmed + pending >= frames + silence) break
            assertNull(error.get())
            SystemClock.sleep(10)
        }
        val snapshot = pipeline.diagnostics
        val trimmed = snapshot.lateTrimmedFrames - baseline.lateTrimmedFrames
        val pending = snapshot.pendingDecoderFrames - baseline.pendingDecoderFrames
        val silence = snapshot.totalSilenceWrittenFrames - baseline.totalSilenceWrittenFrames
        assertTrue("Decoder retained an unbounded amount of input: $snapshot", pending in 0..maxDecoderResidency)
        // Calibrating the hardware clock may trim a bounded prefix, but no samples may disappear.
        assertEquals("PCM write accounting: $snapshot", frames,
            snapshot.totalPcmWrittenFrames - baseline.totalPcmWrittenFrames + trimmed + pending)
        assertEquals("AudioTrack playback accounting, including explicit gap fill: $snapshot", frames + silence,
            snapshot.totalPlayedFrames - baseline.totalPlayedFrames + trimmed + pending)
        assertTrue("Startup trimming exceeded 250 ms: $snapshot", trimmed < SAMPLE_RATE / 4)
        assertTrue("Gap-fill silence exceeded 250 ms: $snapshot", silence < SAMPLE_RATE / 4)
    }

    private fun adtsAccessUnits(bytes: ByteArray): List<ByteArray> = buildList {
        var offset = 0
        fun u(index: Int) = bytes[offset + index].toInt() and 255
        while (offset < bytes.size) {
            require(bytes.size - offset >= 7) { "Truncated ADTS header" }
            require(u(0) == 255 && u(1) and 0xf6 == 0xf0) { "Invalid ADTS sync/layer" }
            require(u(2) ushr 6 == 1 && (u(2) ushr 2) and 15 == 4) { "Expected 44100 Hz AAC-LC" }
            require(((u(2) and 1) shl 2 or (u(3) ushr 6)) == 2) { "Expected stereo AAC" }
            require(u(6) and 3 == 0) { "Expected one 1024-sample raw data block" }
            val headerBytes = if (u(1) and 1 == 1) 7 else 9
            val size = ((u(3) and 3) shl 11) or (u(4) shl 3) or (u(5) ushr 5)
            require(size > headerBytes && size <= bytes.size - offset) { "Invalid ADTS frame length" }
            add(bytes.copyOfRange(offset + headerBytes, offset + size))
            offset += size
        }
    }

    private fun tone(offset: Int): ByteArray = ByteBuffer.allocate(SAMPLES * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(SAMPLES) { sample ->
                val value = (sin((offset + sample) * 2.0 * Math.PI * 440 / SAMPLE_RATE) * 8192).toInt().toShort()
                putShort(value)
                putShort(value)
            }
        }.array()

    private fun sleepUntil(deadlineUs: Long) {
        while (nowUs() < deadlineUs) SystemClock.sleep(((deadlineUs - nowUs()) / 1000).coerceAtLeast(1))
    }

    private fun nowUs() = System.nanoTime() / 1000

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val SAMPLES = 480
    }
}
