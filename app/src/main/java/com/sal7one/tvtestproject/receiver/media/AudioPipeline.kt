package com.sal7one.tvtestproject.receiver.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.AudioConfig
import com.sal7one.tvtestproject.receiver.AudioSink
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One instance per receiver session. AAC input is one decrypted, headerless
 * access unit per enqueue; PCM is signed interleaved 16-bit little-endian.
 * This matches UxPlay's ct=1 S16LE input; its outgoing RTP L16 path is separate.
 * Actual macOS PCM transport still needs a captured sample before claiming playback.
 * ptsUs is the absolute System.nanoTime()/1000 presentation deadline, not an
 * RTP sample counter or elapsedRealtime timestamp. ALAC uses the native PCM16
 * decoder; AAC uses MediaCodec. No HE-AAC, multichannel,
 * SBR/resilient ELD, or software fallback decoder is implemented here.
 *
 * Only the worker touches MediaCodec/AudioTrack/focus resources. Its output
 * drain runs even without new input, and writes never block that drain.
 */
class AudioPipeline(
    context: Context,
    private val onError: (String) -> Unit = {},
) : AudioSink {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val operationLock = Any()
    private val commands = LinkedBlockingQueue<Command>(4)
    private val incoming = ArrayDeque<InputFrame>()
    private var incomingBytes = 0
    private var incomingDurationUs = 0L
    private var ingressConfig: AudioConfig? = null
    private var lastIngressPtsUs = Long.MIN_VALUE
    @Volatile private var closed = false
    @Volatile private var volume = 1f

    // Everything below, except the atomic focus mailbox, belongs to the worker.
    private var config: AudioConfig? = null
    private var decoder: MediaCodec? = null
    private var alacDecoder: NativeAlacDecoder? = null
    private var decoderName: String? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGeneration = 0L
    private var hasFocus = false
    private val focusEvents = AtomicReference<FocusEvent?>()
    private var pcm: AudioPcmQueue? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var appliedVolume = Float.NaN
    private var playing = false
    private var primingPtsUs: Long? = null
    private var writtenFrames = 0L
    private var headWrap = 0L
    private var lastHead = 0L
    private var lastHeadProgressUs = 0L
    private var emptyTrackSinceUs: Long? = null
    private val timestamp = AudioTimestamp()
    private var lastTimestampPollUs = Long.MIN_VALUE
    private val playbackClock = AudioPlaybackClock(CLOCK_TOLERANCE_US, MAX_TRACK_BUFFER_US)
    private val silence = ByteBuffer.allocateDirect(4096)
    private var lastDropLogUs = Long.MIN_VALUE
    private var loggedFirstPcm = false
    private var loggedFirstWrite = false
    private var totalPcmWrittenFrames = 0L
    private var totalSilenceWrittenFrames = 0L
    private var totalPlayedFrames = 0L
    private var observedPlayedFrames = 0L
    private var pcmOverflowFrames = 0L
    private var lateTrimmedFrames = 0L
    private var trackResetCount = 0
    private var clockLogsRemaining = 12
    private var lastClockLogUs = Long.MIN_VALUE
    @Volatile internal var diagnostics = AudioPlaybackDiagnostics()
        private set
    private val worker = Thread(::runWorker, "AirPlay-audio").apply {
        isDaemon = true
        start()
    }

    override fun configure(config: AudioConfig): Boolean = synchronized(operationLock) {
        if (closed) return@synchronized false
        val snapshot = try {
            config.copy(audioSpecificConfig = AudioSpecificConfig.resolve(config))
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Rejected audio configuration: ${error.message}")
            return@synchronized false
        }
        disableIngress()
        execute(Command.Configure(snapshot, CompletableFuture()))
    }

    override fun enqueue(frame: ByteArray, ptsUs: Long): Boolean {
        val nowUs = nowUs()
        if (frame.isEmpty() || frame.size > MAX_ACCESS_UNIT_BYTES ||
            ptsUs < nowUs - MAX_LATE_US || ptsUs > nowUs + MAX_FUTURE_US
        ) return false
        return synchronized(stateLock) {
            val current = ingressConfig ?: return@synchronized false
            if (closed || ptsUs <= lastIngressPtsUs) return@synchronized false
            val frameBytes = current.channels * 2
            if (current.codec == AudioCodec.PCM && frame.size % frameBytes != 0) {
                return@synchronized false
            }
            val samples = if (current.codec == AudioCodec.PCM) frame.size / frameBytes else current.samplesPerFrame
            val durationUs = samples * 1_000_000L / current.sampleRate
            if (durationUs > QUEUE_DURATION_US || incoming.size >= MAX_QUEUED_FRAMES ||
                incomingBytes + frame.size > MAX_QUEUE_BYTES ||
                incomingDurationUs + durationUs > QUEUE_DURATION_US
            ) return@synchronized false
            incoming.addLast(InputFrame(frame.copyOf(), ptsUs, durationUs))
            incomingBytes += frame.size
            incomingDurationUs += durationUs
            lastIngressPtsUs = ptsUs
            true
        }
    }

    override fun setVolume(decibels: Float) {
        try {
            volume = AirPlayAudioVolume.linear(decibels)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Rejected NaN audio volume")
        }
    }

    override fun flush() = synchronized(operationLock) {
        if (!closed) {
            disableIngress()
            execute(Command.Flush(CompletableFuture()))
        }
        Unit
    }

    override fun close() = synchronized(operationLock) {
        if (!closed) {
            closed = true
            disableIngress()
            execute(Command.Close(CompletableFuture()))
        }
        if (worker !== Thread.currentThread()) {
            try { worker.join(OPERATION_TIMEOUT_MS) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Audio shutdown interrupted", error)
            }
            check(!worker.isAlive) { "Audio worker did not terminate within close timeout" }
        }
        Unit
    }

    private fun execute(command: Command): Boolean {
        if (!commands.offer(command)) {
            reportError("Audio control queue is full")
            return false
        }
        return try {
            command.result.get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            synchronized(stateLock) {
                command.result.cancel(false)
                clearIncomingLocked()
                ingressConfig = null
            }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            reportError("Audio ${command.javaClass.simpleName} did not complete", error)
            false
        }
    }

    private fun runWorker() {
        try {
            while (true) {
                val command = commands.poll(if (config == null) 500 else 2, TimeUnit.MILLISECONDS)
                if (command != null) {
                    try {
                        when (command) {
                            is Command.Configure -> {
                                releaseResources()
                                if (!closed && !command.result.isCancelled) {
                                    configureResources(command.config)
                                    if (!publishConfiguration(command.result)) releaseResources()
                                } else command.result.complete(false)
                            }
                            is Command.Flush -> {
                                flushResources()
                                publishConfiguration(command.result)
                            }
                            is Command.Close -> {
                                releaseResources()
                                command.result.complete(true)
                                return
                            }
                        }
                    } catch (error: Exception) {
                        reportError("Audio ${command.javaClass.simpleName} failed", error)
                        releaseResources()
                        command.result.complete(false)
                    }
                }
                val event = focusEvents.getAndSet(null)
                if (event != null && event.generation == focusGeneration) handleFocus(event.change)
                if (config == null || !hasFocus || closed) continue
                try {
                    drainDecoder()
                    feedInput()
                } catch (error: Exception) {
                    reportError("Audio decoder failed (${decoderName ?: "PCM"})", error)
                    releaseResources()
                    continue
                }
                try {
                    applyVolume()
                    writePcm()
                } catch (error: Exception) {
                    reportError("AudioTrack playback failed", error)
                    releaseResources()
                } finally {
                    publishDiagnostics()
                }
            }
        } catch (error: Exception) {
            reportError("Audio worker failed", error)
        } finally {
            releaseResources()
            synchronized(stateLock) { closed = true }
            while (true) (commands.poll() ?: break).result.complete(false)
        }
    }

    private fun publishConfiguration(result: CompletableFuture<Boolean>): Boolean = synchronized(stateLock) {
        if (closed || result.isCancelled || config == null || !hasFocus) {
            result.complete(false)
            false
        } else {
            ingressConfig = config
            lastIngressPtsUs = Long.MIN_VALUE
            if (result.complete(true)) true else {
                ingressConfig = null
                false
            }
        }
    }

    private fun configureResources(current: AudioConfig) {
        AudioSpecificConfig.validateFormat(current)
        require(current.samplesPerFrame * 1_000_000L / current.sampleRate <= QUEUE_DURATION_US) {
            "Negotiated audio packet exceeds queue latency budget"
        }
        config = current
        pcm = AudioPcmQueue(current.sampleRate, current.channels, QUEUE_DURATION_US, MAX_QUEUED_FRAMES)
        totalPcmWrittenFrames = 0
        totalSilenceWrittenFrames = 0
        totalPlayedFrames = 0
        pcmOverflowFrames = 0
        lateTrimmedFrames = 0
        trackResetCount = 0
        when (current.codec) {
            AudioCodec.PCM -> Unit
            AudioCodec.ALAC -> {
                alacDecoder = NativeAlacDecoder(current.sampleRate, current.channels, current.samplesPerFrame)
                decoderName = "Apple ALAC PCM16"
            }
            AudioCodec.AAC_LC, AudioCodec.AAC_ELD -> decoder = createDecoder(current)
        }
        requestFocus()
        track = createTrack(current)
        applyVolume()
        // Exercise the initialized output state before accepting the format.
        track!!.play()
        track!!.pause()
        track!!.flush()
        resetPlaybackClock()
        Log.i(TAG, "Configured ${current.codec} ${current.sampleRate}Hz ${current.channels}ch " +
            "${current.samplesPerFrame} samples/frame decoder=${decoderName ?: "PCM passthrough"}")
    }

    private fun createDecoder(current: AudioConfig): MediaCodec {
        val profile = aacProfile(current.codec)
        val format = aacFormat(current)
        for (entry in aacDecoders.decoders) {
            val info = entry.info
            var candidate: MediaCodec? = null
            try {
                if (!supportsDecoder(entry.capabilities, current, format)) continue
                candidate = MediaCodec.createByCodecName(info.name)
                candidate.configure(format, null, null, 0)
                candidate.start()
                decoderName = info.name
                Log.i(TAG, "Started AAC decoder ${info.name}, profile=$profile format=$format")
                return candidate
            } catch (error: Exception) {
                Log.w(TAG, "Decoder ${info.name} rejected profile=$profile " +
                    "${current.sampleRate}Hz/${current.channels}ch", error)
                try { candidate?.release() } catch (releaseError: Exception) {
                    Log.w(TAG, "Failed to release rejected decoder ${info.name}", releaseError)
                }
            }
        }
        throw IllegalArgumentException("No usable platform decoder for ${current.codec} " +
            "${current.sampleRate}Hz/${current.channels}ch")
    }

    private fun createTrack(current: AudioConfig): AudioTrack {
        val channelMask = if (current.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = current.channels * 2
        val minimum = AudioTrack.getMinBufferSize(current.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "AudioTrack does not support negotiated PCM output" }
        require(minimum.toLong() * 1_000_000 / frameBytes / current.sampleRate <= MAX_TRACK_BUFFER_US) {
            "AudioTrack minimum buffer exceeds latency budget"
        }
        val targetFrames = (current.sampleRate * TRACK_AHEAD_US / 1_000_000).toInt().coerceAtLeast(1)
        val output = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(current.sampleRate)
                .setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minimum, targetFrames * frameBytes))
            .build()
        try {
            check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack is uninitialized" }
            check(output.setBufferSizeInFrames(targetFrames) > 0) { "Cannot set AudioTrack buffer size" }
            // API 31 allows avoiding a hardware-buffer-sized startup threshold.
            if (Build.VERSION.SDK_INT >= 31) output.setStartThresholdInFrames(min(targetFrames, current.sampleRate / 100))
            Log.i(TAG, "AudioTrack PCM16 ${current.sampleRate}Hz ${current.channels}ch " +
                "bufferFrames=${output.bufferSizeInFrames}")
            return output
        } catch (error: Exception) {
            output.release()
            throw error
        }
    }

    private fun requestFocus() {
        val generation = ++focusGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> focusEvents.set(FocusEvent(generation, change)) }, mainHandler)
            .build()
        focusRequest = request
        check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio focus denied; receiver must be foreground/eligible for media playback"
        }
        hasFocus = true
    }

    private fun handleFocus(change: Int) {
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (config != null && track != null) {
                        flushResources()
                        hasFocus = true
                        synchronized(stateLock) { if (!closed) ingressConfig = config }
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    hasFocus = false
                    flushResources()
                    Log.i(TAG, "Audio paused after transient focus loss")
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.i(TAG, "Audio stopped after permanent focus loss")
                    releaseResources()
                    reportError("Audio focus permanently lost; disconnecting receiver session")
                }
            }
        } catch (error: Exception) {
            reportError("Audio focus transition failed", error)
            releaseResources()
        }
    }

    private fun feedInput() {
        val current = config ?: return
        val queue = pcm ?: return
        repeat(4) {
            val input = synchronized(stateLock) { incoming.peekFirst() } ?: return
            if (input.ptsUs + input.durationUs < nowUs() - MAX_LATE_US) {
                removeInput()
                noteDrop("late input")
                return@repeat
            }
            if (input.ptsUs > nowUs() + QUEUE_DURATION_US) return
            val samples = if (current.codec == AudioCodec.PCM) input.bytes.size / (current.channels * 2)
                else current.samplesPerFrame
            // Reserve decoded capacity before submitting input, not after output arrives.
            if (!queue.canReserve(samples)) return
            val codec = decoder
            if (current.codec == AudioCodec.ALAC) {
                val decoded = checkNotNull(alacDecoder).decode(input.bytes)
                removeInput()
                addPcm(ByteBuffer.wrap(decoded), input.ptsUs)
            } else if (codec == null) {
                removeInput()
                addPcm(ByteBuffer.wrap(input.bytes), input.ptsUs)
            } else {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) return
                val buffer = checkNotNull(codec.getInputBuffer(index)) { "Decoder returned null input buffer" }
                check(buffer.capacity() >= input.bytes.size) { "AAC access unit exceeds decoder input capacity" }
                buffer.clear()
                buffer.put(input.bytes)
                queue.reserve(samples)
                codec.queueInputBuffer(index, 0, input.bytes.size, input.ptsUs, 0)
                removeInput()
            }
        }
    }

    private fun drainDecoder() {
        val codec = decoder ?: return
        repeat(8) {
            when (val index = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> validateOutputFormat(codec.outputFormat)
                else -> if (index >= 0) {
                    try {
                        if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = checkNotNull(codec.getOutputBuffer(index)) { "Decoder returned null PCM buffer" }
                            check(bufferInfo.offset >= 0 && bufferInfo.size <= MAX_ACCESS_UNIT_BYTES &&
                                bufferInfo.offset.toLong() + bufferInfo.size <= buffer.capacity()
                            ) { "Invalid decoded PCM buffer range" }
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                            copy.put(buffer).flip()
                            addPcm(copy, bufferInfo.presentationTimeUs)
                        }
                        check(bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                            "Unexpected AAC decoder end of stream"
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun validateOutputFormat(format: MediaFormat) {
        Log.i(TAG, "AAC decoder output format=$format decoder=$decoderName")
        val current = checkNotNull(config)
        check(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == current.sampleRate &&
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == current.channels
        ) { "Decoder changed negotiated sample rate/channel count" }
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT
        check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Decoder did not produce PCM16" }
    }

    private fun addPcm(buffer: ByteBuffer, ptsUs: Long) {
        val current = checkNotNull(config)
        val queue = checkNotNull(pcm)
        check(buffer.remaining() % (current.channels * 2) == 0) { "Decoded PCM is not sample-frame aligned" }
        if (decoder != null) queue.decoded(buffer.remaining() / (current.channels * 2))
        if (buffer.hasRemaining() && !loggedFirstPcm) {
            val sample = buffer.duplicate()
            var nonzero = 0
            while (sample.hasRemaining()) if (sample.get().toInt() != 0) nonzero++
            Log.i(TAG, "first_audio_pcm_decoded codec=${current.codec} bytes=${buffer.remaining()} nonzeroBytes=$nonzero")
            loggedFirstPcm = true
        }
        if (!buffer.hasRemaining()) return
        if (ptsUs < nowUs() - MAX_LATE_US || ptsUs > nowUs() + MAX_FUTURE_US) {
            noteDrop("out-of-window PCM")
            return
        }
        // A malformed/unexpected decoder output must not evict earlier audible PCM.
        if (!queue.offer(buffer, ptsUs)) {
            pcmOverflowFrames += buffer.remaining() / (current.channels * 2)
            noteDrop("decoded PCM exceeds reserved capacity")
        }
    }

    private fun writePcm() {
        val output = track ?: return
        val current = config ?: return
        val queue = pcm ?: return
        val frameBytes = current.channels * 2
        val nowUs = nowUs()
        val playedFrames = playbackHead(output)
        var queuedFrames = (writtenFrames - playedFrames).coerceAtLeast(0)
        if (queuedFrames == 0L) lastHeadProgressUs = nowUs
        if (playing && queuedFrames == 0L && emptyTrackSinceUs == null) emptyTrackSinceUs = nowUs
        if (queuedFrames > 0L) emptyTrackSinceUs = null
        if (playing && queuedFrames == 0L && AudioPlaybackTiming.shouldReprime(emptyTrackSinceUs, nowUs, MAX_GAP_FILL_US)) {
            // A frame/time correlation cannot be extrapolated through an underrun.
            Log.i(TAG, "AudioTrack sustained underrun; re-priming after ${nowUs - checkNotNull(emptyTrackSinceUs)} us")
            output.pause()
            output.flush()
            resetPlaybackClock()
            trackResetCount++
            queuedFrames = 0
        }
        if (playing && queuedFrames > 0 && nowUs - lastHeadProgressUs > MAX_TRACK_BUFFER_US) {
            output.pause()
            output.flush()
            resetPlaybackClock()
            trackResetCount++
            noteDrop("stalled playback clock; track buffer reset")
            return
        }
        val requestedFrames = (current.sampleRate * TRACK_AHEAD_US / 1_000_000).toInt()
        // Before API 31 the start threshold cannot be lowered independently of
        // the track buffer. Filling its actual minimum avoids a startup stall.
        val capacityFrames = if (Build.VERSION.SDK_INT < 31) {
            max(requestedFrames, output.bufferSizeInFrames)
        } else requestedFrames
        var roomFrames = (capacityFrames - queuedFrames).toInt().coerceAtLeast(0)
        startPrimedTrack(output, capacityFrames, nowUs)
        // Correct a late prefix and refill in the same turn. Sleeping between
        // trimmed packets starves the track and moves its hardware clock later.
        repeat(MAX_QUEUED_FRAMES) {
            if (roomFrames == 0) return
            val first = queue.first ?: return
            val firstPtsUs = queue.presentationTimeUs(first)
            val expectedUs = if (!playing) {
                (primingPtsUs ?: max(nowUs, firstPtsUs)) + writtenFrames * 1_000_000 / current.sampleRate
            } else nextPlaybackUs(output, current.sampleRate, nowUs)
            if (clockLogsRemaining > 0 && (lastClockLogUs == Long.MIN_VALUE || nowUs - lastClockLogUs >= 200_000)) {
                clockLogsRemaining--
                lastClockLogUs = nowUs
                Log.i(TAG, "audio_playout codec=${current.codec} playing=$playing nextInUs=${firstPtsUs - nowUs} " +
                    "appendInUs=${expectedUs - nowUs} queued=$queuedFrames written=$writtenFrames played=$playedFrames " +
                    "pendingDecoder=${queue.pendingFrames} timestampFrame=${timestamp.framePosition} timestampAgeUs=${nowUs - timestamp.nanoTime / 1000}")
            }
            val trimmed = queue.trimBefore(expectedUs, CLOCK_TOLERANCE_US)
            if (trimmed > 0) {
                lateTrimmedFrames += trimmed
                noteDrop("late PCM trimmed to playback clock latenessUs=${expectedUs - firstPtsUs} written=$writtenFrames played=$playedFrames")
            }
            val next = queue.first ?: return
            val ptsUs = queue.presentationTimeUs(next)
            if (playing && AudioPlaybackTiming.shouldWait(ptsUs, nowUs, expectedUs, TRACK_AHEAD_US)) return
            val errorUs = ptsUs - expectedUs
            if (errorUs > CLOCK_TOLERANCE_US) {
                if (writtenFrames == 0L) return
                if (errorUs > MAX_GAP_FILL_US) {
                    if (!playing) {
                        // A discontinuity cannot leave an underfilled paused track stuck forever.
                        output.flush()
                        resetPlaybackClock()
                        trackResetCount++
                    }
                    return
                }
                val frames = min(roomFrames, min(silence.capacity() / frameBytes,
                    (errorUs * current.sampleRate / 1_000_000).toInt()))
                val zeros = silence.duplicate().apply { clear(); limit(frames * frameBytes) }
                totalSilenceWrittenFrames += writeTrack(output, zeros, frames * frameBytes, frameBytes) / frameBytes
                return
            }
            val bytes = min(next.buffer.remaining(), roomFrames * frameBytes)
            val slice = next.buffer.duplicate().apply { limit(position() + bytes) }
            val count = writeTrack(output, slice, bytes, frameBytes)
            totalPcmWrittenFrames += count / frameBytes
            queue.consume(count)
            if (count > 0 && primingPtsUs == null) primingPtsUs = ptsUs
            startPrimedTrack(output, capacityFrames, nowUs)
            roomFrames -= count / frameBytes
            if (count < bytes) return
        }
    }

    private fun startPrimedTrack(output: AudioTrack, capacityFrames: Int, nowUs: Long) {
        val firstPtsUs = primingPtsUs ?: return
        if (!playing && AudioPlaybackTiming.canStart(writtenFrames, capacityFrames, firstPtsUs, nowUs,
                CLOCK_TOLERANCE_US)) {
            output.play()
            playing = true
            lastHeadProgressUs = nowUs
        }
    }

    private fun writeTrack(output: AudioTrack, buffer: ByteBuffer, bytes: Int, frameBytes: Int): Int {
        val count = output.write(buffer, bytes, AudioTrack.WRITE_NON_BLOCKING)
        check(count >= 0) { "AudioTrack write error $count (muting is not a write error)" }
        check(count % frameBytes == 0) { "AudioTrack wrote a partial PCM sample frame" }
        writtenFrames += count / frameBytes
        if (count > 0 && !loggedFirstWrite) {
            loggedFirstWrite = true
            Log.i(TAG, "first_audio_track_write codec=${config?.codec} frames=${count / frameBytes} volume=$volume")
        }
        return count
    }

    private fun playbackHead(output: AudioTrack): Long {
        val head = output.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (head != lastHead) lastHeadProgressUs = nowUs()
        if (head < lastHead) headWrap += 1L shl 32
        lastHead = head
        val played = (headWrap + head).coerceAtMost(writtenFrames)
        totalPlayedFrames += (played - observedPlayedFrames).coerceAtLeast(0)
        observedPlayedFrames = played
        return played
    }

    private fun nextPlaybackUs(output: AudioTrack, sampleRate: Int, nowUs: Long): Long {
        if (lastTimestampPollUs == Long.MIN_VALUE || nowUs - lastTimestampPollUs >= playbackClock.pollIntervalUs) {
            lastTimestampPollUs = nowUs
            val available = output.getTimestamp(timestamp)
            playbackClock.observe(if (available) timestamp.framePosition else -1L,
                timestamp.nanoTime / 1000, writtenFrames, sampleRate, nowUs)
        }
        val fallbackUs = checkNotNull(primingPtsUs) + writtenFrames * 1_000_000 / sampleRate
        return playbackClock.nextPlaybackUs(writtenFrames, sampleRate, fallbackUs, nowUs)
    }

    private fun applyVolume() {
        val requested = volume
        if (requested == appliedVolume) return
        val output = track ?: return
        check(output.setVolume(requested) == AudioTrack.SUCCESS) { "Cannot apply AudioTrack volume" }
        appliedVolume = requested
    }

    private fun flushResources() {
        disableIngress()
        pcm?.clear()
        track?.pause()
        track?.flush()
        decoder?.flush()
        alacDecoder?.flush()
        resetPlaybackClock()
    }

    private fun releaseResources() {
        disableIngress()
        pcm = null
        val oldDecoder = decoder
        decoder = null
        decoderName = null
        val oldAlac = alacDecoder
        alacDecoder = null
        try { oldAlac?.close() } catch (error: Exception) { Log.w(TAG, "ALAC decoder release failed", error) }
        try { oldDecoder?.stop() } catch (error: Exception) { Log.w(TAG, "AAC decoder stop failed", error) }
        try { oldDecoder?.release() } catch (error: Exception) { Log.w(TAG, "AAC decoder release failed", error) }
        val oldTrack = track
        track = null
        try { oldTrack?.pause() } catch (error: Exception) { Log.w(TAG, "AudioTrack pause failed", error) }
        try { oldTrack?.flush() } catch (error: Exception) { Log.w(TAG, "AudioTrack flush failed", error) }
        try { oldTrack?.release() } catch (error: Exception) { Log.w(TAG, "AudioTrack release failed", error) }
        val oldFocus = focusRequest
        focusRequest = null
        focusGeneration++
        focusEvents.set(null)
        if (oldFocus != null) {
            try { audioManager.abandonAudioFocusRequest(oldFocus) } catch (error: Exception) {
                Log.w(TAG, "Audio focus cleanup failed", error)
            }
        }
        hasFocus = false
        config = null
        appliedVolume = Float.NaN
        resetPlaybackClock()
    }

    private fun resetPlaybackClock() {
        loggedFirstPcm = false
        loggedFirstWrite = false
        playing = false
        primingPtsUs = null
        writtenFrames = 0
        observedPlayedFrames = 0
        headWrap = 0
        lastHead = 0
        lastHeadProgressUs = nowUs()
        emptyTrackSinceUs = null
        playbackClock.reset()
        lastTimestampPollUs = Long.MIN_VALUE
    }

    private fun publishDiagnostics() {
        val queue = pcm
        diagnostics = AudioPlaybackDiagnostics(totalPcmWrittenFrames, totalPlayedFrames,
            queue?.queuedFrames ?: 0, queue?.pendingFrames ?: 0, queue?.capacityFrames ?: 0,
            pcmOverflowFrames, lateTrimmedFrames, trackResetCount, totalSilenceWrittenFrames)
    }

    private fun disableIngress() = synchronized(stateLock) {
        ingressConfig = null
        clearIncomingLocked()
    }

    private fun clearIncomingLocked() {
        incoming.clear()
        incomingBytes = 0
        incomingDurationUs = 0
        lastIngressPtsUs = Long.MIN_VALUE
    }

    private fun removeInput(): InputFrame? = synchronized(stateLock) {
        val input = incoming.pollFirst() ?: return@synchronized null
        incomingBytes -= input.bytes.size
        incomingDurationUs -= input.durationUs
        input
    }

    private fun noteDrop(reason: String) {
        val now = nowUs()
        if (lastDropLogUs == Long.MIN_VALUE || now - lastDropLogUs >= 1_000_000) {
            Log.w(TAG, "Audio scheduling drop: $reason")
            lastDropLogUs = now
        }
    }

    private fun reportError(message: String, error: Exception? = null) {
        Log.e(TAG, message, error)
        mainHandler.post {
            try { onError(message) } catch (callbackError: Exception) {
                Log.w(TAG, "Audio error callback failed", callbackError)
            }
        }
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()

    private fun nowUs(): Long = System.nanoTime() / 1000

    private data class InputFrame(val bytes: ByteArray, val ptsUs: Long, val durationUs: Long)
    private data class FocusEvent(val generation: Long, val change: Int)
    private sealed class Command(val result: CompletableFuture<Boolean>) {
        class Configure(val config: AudioConfig, result: CompletableFuture<Boolean>) : Command(result)
        class Flush(result: CompletableFuture<Boolean>) : Command(result)
        class Close(result: CompletableFuture<Boolean>) : Command(result)
    }

    companion object {
        // Platform codec metadata is process-wide; negotiated formats and codec instances are not.
        private val aacDecoders = AudioDecoderInventory(
            enumerate = { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() },
            inspect = { info ->
                if (!info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) }) {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                } else null
            },
        )

        /** Match the capability-gated 44.1kHz stereo tuples in /info. */
        @JvmStatic
        fun supportedAirPlayAudioFormats(): Long {
            val pcm = if (isSupported(AudioConfig(AudioCodec.PCM, 44_100, 2, 352)))
                AudioSpecificConfig.AIRPLAY_PCM_44100_STEREO_MASK else 0L
            val eld = if (isSupported(AudioConfig(AudioCodec.AAC_ELD, 44_100, 2, 480)))
                AudioSpecificConfig.AIRPLAY_ELD_44100_STEREO_MASK else 0L
            val alac = if (isSupported(AudioConfig(AudioCodec.ALAC, 44_100, 2, 352)))
                AudioSpecificConfig.AIRPLAY_ALAC_44100_STEREO_MASK else 0L
            return pcm or eld or alac
        }

        /**
         * Capability query for /info and negotiation. Does not start platform
         * codecs/tracks or acquire focus; ALAC checks bounded native initialization.
         * configure() is still the
         * authoritative startup check and must succeed before SETUP sends 200.
         */
        @JvmStatic
        fun isSupported(config: AudioConfig): Boolean {
            return try {
                val current = config.copy(audioSpecificConfig = AudioSpecificConfig.resolve(config))
                if (current.samplesPerFrame * 1_000_000L / current.sampleRate > QUEUE_DURATION_US) return false
                val mask = if (current.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minimum = AudioTrack.getMinBufferSize(current.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0 || minimum.toLong() * 1_000_000 / (current.channels * 2) /
                    current.sampleRate > MAX_TRACK_BUFFER_US
                ) return false
                if (current.codec == AudioCodec.PCM) return true
                if (current.codec == AudioCodec.ALAC) {
                    return try {
                        NativeAlacDecoder(current.sampleRate, current.channels, current.samplesPerFrame).use { }
                        true
                    } catch (error: LinkageError) {
                        Log.w(TAG, "Native ALAC decoder unavailable", error)
                        false
                    }
                }
                val format = aacFormat(current)
                aacDecoders.decoders.any { entry ->
                    try {
                        supportsDecoder(entry.capabilities, current, format)
                    } catch (error: Exception) {
                        Log.w(TAG, "Cannot inspect decoder ${entry.info.name}", error)
                        false
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Audio capability query rejected ${config.codec} " +
                    "${config.sampleRate}Hz/${config.channels}ch: ${error.javaClass.simpleName}")
                false
            }
        }

        private fun supportsDecoder(
            caps: MediaCodecInfo.CodecCapabilities?,
            current: AudioConfig,
            format: MediaFormat,
        ): Boolean {
            val audio = caps?.audioCapabilities ?: return false
            return caps.profileLevels.any { it.profile == aacProfile(current.codec) } &&
                audio.isSampleRateSupported(current.sampleRate) &&
                audio.maxInputChannelCount >= current.channels && caps.isFormatSupported(format)
        }

        private fun aacProfile(codec: AudioCodec): Int = when (codec) {
            AudioCodec.AAC_LC -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
            AudioCodec.AAC_ELD -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
            AudioCodec.PCM, AudioCodec.ALAC -> error("${codec} must bypass AAC MediaCodec")
        }

        private fun aacFormat(current: AudioConfig): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, current.sampleRate, current.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile(current.codec))
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_BYTES)
                setByteBuffer("csd-0", ByteBuffer.wrap(current.audioSpecificConfig!!))
            }

        private const val TAG = "AirPlayAudio"
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val MAX_ACCESS_UNIT_BYTES = 65_536
        private const val MAX_QUEUED_FRAMES = 32
        private const val MAX_QUEUE_BYTES = 256 * 1024
        private const val QUEUE_DURATION_US = 160_000L
        private const val TRACK_AHEAD_US = 40_000L
        private const val MAX_TRACK_BUFFER_US = 250_000L
        private const val CLOCK_TOLERANCE_US = 15_000L
        private const val MAX_LATE_US = 120_000L
        private const val MAX_FUTURE_US = 1_000_000L
        private const val MAX_GAP_FILL_US = 80_000L
    }
}

/** Successful discovery is shared; lazy initialization retries exceptions instead of caching failure. */
internal class AudioDecoderInventory<I, C : Any>(
    enumerate: () -> List<I>,
    inspect: (I) -> C?,
) {
    val decoders: List<Decoder<I, C>> by lazy {
        enumerate().map { info -> Decoder(info) { inspect(info) } }
    }

    class Decoder<I, C : Any>(val info: I, inspect: () -> C?) {
        val capabilities: C? by lazy(inspect)
    }
}

/** Ceiling prevents repeatedly retaining a sub-sample late remainder. */
internal object AudioPlaybackTiming {
    fun shouldReprime(emptySinceUs: Long?, nowUs: Long, gapToleranceUs: Long): Boolean =
        emptySinceUs != null && nowUs - emptySinceUs >= gapToleranceUs

    fun canStart(writtenFrames: Long, primeFrames: Int, firstPtsUs: Long, nowUs: Long, toleranceUs: Long): Boolean =
        writtenFrames >= primeFrames && nowUs >= firstPtsUs - toleranceUs

    fun shouldWait(ptsUs: Long, nowUs: Long, nextPlaybackUs: Long, writeAheadUs: Long): Boolean =
        ptsUs > max(nowUs, nextPlaybackUs) + writeAheadUs

    fun lateFrames(latenessUs: Long, sampleRate: Int, remainingFrames: Int): Int {
        require(latenessUs >= 0 && sampleRate in 7_350..96_000 && remainingFrames >= 0)
        val remainingUs = remainingFrames * 1_000_000L / sampleRate
        if (latenessUs >= remainingUs) return remainingFrames
        return min(remainingFrames, ((latenessUs * sampleRate + 999_999) / 1_000_000).toInt())
    }
}

/** Worker-owned PCM plus the capacity promised to not-yet-drained decoder output. */
internal class AudioPcmQueue(private val sampleRate: Int, channels: Int, budgetUs: Long, private val maxBuffers: Int) {
    data class Frame(val buffer: ByteBuffer, val ptsUs: Long)

    private val frameBytes = channels * 2
    val capacityFrames = (sampleRate * budgetUs / 1_000_000).toInt()
    private val frames = ArrayDeque<Frame>()
    var queuedFrames = 0
        private set
    var pendingFrames = 0
        private set
    val first: Frame? get() = frames.peekFirst()

    fun presentationTimeUs(frame: Frame): Long =
        frame.ptsUs + frame.buffer.position() / frameBytes * 1_000_000L / sampleRate

    fun trimBefore(playbackUs: Long, toleranceUs: Long): Int {
        var trimmed = 0
        while (true) {
            val next = first ?: break
            val latenessUs = playbackUs - presentationTimeUs(next)
            if (latenessUs <= toleranceUs) break
            val samples = AudioPlaybackTiming.lateFrames(latenessUs, sampleRate, next.buffer.remaining() / frameBytes)
            consume(samples * frameBytes)
            trimmed += samples
        }
        return trimmed
    }

    fun canReserve(samples: Int): Boolean = samples > 0 && frames.size < maxBuffers &&
        samples.toLong() + queuedFrames + pendingFrames <= capacityFrames

    fun reserve(samples: Int) {
        check(canReserve(samples)) { "Decoder input exceeds PCM capacity" }
        pendingFrames += samples
    }

    fun decoded(samples: Int) {
        require(samples >= 0)
        pendingFrames = (pendingFrames - samples).coerceAtLeast(0)
    }

    fun offer(buffer: ByteBuffer, ptsUs: Long): Boolean {
        require(buffer.remaining() % frameBytes == 0)
        val samples = buffer.remaining() / frameBytes
        if (!canReserve(samples)) return false
        frames.addLast(Frame(buffer.slice(), ptsUs))
        queuedFrames += samples
        return true
    }

    fun consume(bytes: Int) {
        val frame = checkNotNull(first)
        require(bytes in 0..frame.buffer.remaining() && bytes % frameBytes == 0)
        frame.buffer.position(frame.buffer.position() + bytes)
        queuedFrames -= bytes / frameBytes
        if (!frame.buffer.hasRemaining()) frames.removeFirst()
    }

    fun clear() {
        frames.clear()
        queuedFrames = 0
        pendingFrames = 0
    }
}

/** Only advancing, rate-consistent timestamp pairs can replace the primed PCM timeline. */
internal class AudioPlaybackClock(private val toleranceUs: Long, private val maxAgeUs: Long) {
    private var frame = -1L
    private var timeUs = 0L
    private var observedAtUs = 0L
    private var stable = false
    private var calibratedOriginUs: Long? = null
    private var calibratedObservationUs = Long.MIN_VALUE

    // AudioTrack's short-term timestamp differences are not meaningful after
    // warmup. Keep the validated mapping; recheck its rate over a longer span.
    val pollIntervalUs: Long get() = if (stable && calibratedOriginUs != null) 10_000_000L else 200_000L

    fun observe(position: Long, presentationUs: Long, writtenFrames: Long, sampleRate: Int, nowUs: Long) {
        if (position !in 0..writtenFrames || presentationUs <= 0 ||
            presentationUs < nowUs - maxAgeUs || presentationUs > nowUs + maxAgeUs
        ) {
            frame = -1
            stable = false
            return
        }
        stable = frame >= 0 && position > frame && presentationUs > timeUs &&
            abs(presentationUs - timeUs - (position - frame) * 1_000_000 / sampleRate) <= toleranceUs
        frame = position
        timeUs = presentationUs
        observedAtUs = nowUs
    }

    fun nextPlaybackUs(writtenFrames: Long, sampleRate: Int, fallbackUs: Long, nowUs: Long): Long {
        if (stable && nowUs - observedAtUs <= maxAgeUs && frame <= writtenFrames) {
            val predictedUs = timeUs + (writtenFrames - frame) * 1_000_000 / sampleRate
            if (predictedUs >= nowUs - toleranceUs && predictedUs <= nowUs + maxAgeUs) {
                if (calibratedObservationUs != observedAtUs) {
                    val origin = timeUs - frame * 1_000_000 / sampleRate
                    calibratedOriginUs = calibratedOriginUs?.let { it + (origin - it) / 20 } ?: origin
                    calibratedObservationUs = observedAtUs
                }
                return max(nowUs, checkNotNull(calibratedOriginUs) + writtenFrames * 1_000_000 / sampleRate)
            }
        }
        // Retain the last validated hardware mapping during a temporary timestamp failure.
        val calibratedUs = calibratedOriginUs?.let { it + writtenFrames * 1_000_000 / sampleRate }
        return max(nowUs, calibratedUs ?: fallbackUs)
    }

    fun reset() {
        frame = -1
        timeUs = 0
        observedAtUs = 0
        stable = false
        calibratedOriginUs = null
        calibratedObservationUs = Long.MIN_VALUE
    }
}

/** Immutable observation of the worker; totals survive automatic track underrun recovery. */
internal data class AudioPlaybackDiagnostics(
    val totalPcmWrittenFrames: Long = 0,
    val totalPlayedFrames: Long = 0,
    val queuedPcmFrames: Int = 0,
    val pendingDecoderFrames: Int = 0,
    val pcmCapacityFrames: Int = 0,
    val pcmOverflowFrames: Long = 0,
    val lateTrimmedFrames: Long = 0,
    val trackResetCount: Int = 0,
    val totalSilenceWrittenFrames: Long = 0,
)
