package com.sal7one.tvtestproject.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.sal7one.tvtestproject.receiver.VideoConfig
import com.sal7one.tvtestproject.receiver.VideoSink
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Session-owned AVC decoder. enqueue accepts one complete length-prefixed access unit.
 * ptsUs is an absolute System.nanoTime()/1000 deadline, not a stream-relative timestamp.
 * configure validates and accepts configuration; decoder failures are reported by onError/lastError.
 * The caller retains ownership of Surface objects and must call flush on transport discontinuities.
 */
class VideoPipeline(
    private val onError: ((String) -> Unit)? = null,
    private val onRenderAttempt: (() -> Unit)? = null,
) : VideoSink {
    private data class Frame(val bytes: ByteArray, val ptsUs: Long, val isIdr: Boolean,
        val enqueuedAtNs: Long, val queueDepth: Int)
    private class InputTimeout(val failedRevision: Long) : Exception("Video input backpressure timed out")
    private data class DesiredState(val config: VideoConfig?, val surface: Surface?, val revision: Long, val closed: Boolean)
    private data class PreparedAvc(val config: VideoConfig, val dimensions: H264Config.SpsDimensions,
        val sps: ByteArray, val pps: ByteArray)
    private var preparedAvc: PreparedAvc? = null // Decoder worker only; reusable across surface recreation.

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor = Object()
    private val queue = ArrayDeque<Frame>()
    private var queuedBytes = 0
    private var configuration: VideoConfig? = null
    private var outputSurface: Surface? = null
    private var revision = 0L
    private var failedRevision = -1L
    private var waitingForIdr = true
    private var closed = false
    private var error: String? = null
    private var lastBackpressureLogNs = Long.MIN_VALUE
    private val timing = VideoTimingMetrics() // Decoder worker only.
    private var renderedRevision = -1L // Decoder worker only.
    private val worker = Thread(::decodeLoop, "FossMirrorTV-Video").apply {
        isDaemon = true
        start()
    }

    val lastError: String? get() = synchronized(monitor) { error }
    val videoSize: Pair<Int, Int>? get() = synchronized(monitor) { configuration?.let { it.width to it.height } }

    override fun configure(config: VideoConfig): Boolean {
        val validated = try {
            H264Config.fromParameterSets(config.sps, config.pps, config.nalLengthSize)
        } catch (e: IllegalArgumentException) {
            reportError("Invalid AVC configuration: ${e.message}", e)
            return false
        }
        return synchronized(monitor) {
            if (closed) return@synchronized false
            if (!sameConfiguration(configuration, validated) || failedRevision == revision) {
                configuration = validated
                invalidateLocked("configuration changed")
            }
            monitor.notifyAll()
            true
        }
    }

    override fun enqueue(accessUnit: ByteArray, ptsUs: Long, isKeyframe: Boolean): Boolean {
        val state = synchronized(monitor) {
            if (closed || configuration == null || failedRevision == revision) return false
            desiredStateLocked()
        }
        val unit = try {
            prepareAccessUnit(accessUnit, state.config!!.nalLengthSize)
        } catch (e: IllegalArgumentException) {
            synchronized(monitor) {
                if (!closed && revision == state.revision) invalidateLocked("malformed access unit")
            }
            reportError("Discarded malformed AVC access unit: ${e.message}", e)
            return false
        }
        return acceptPrepared(unit, ptsUs, state)
    }

    override fun prepareAccessUnit(accessUnit: ByteArray, nalLengthSize: Int): H264Config.AccessUnit =
        NativeAvcParser.parse(accessUnit, nalLengthSize)

    override fun enqueuePrepared(unit: H264Config.AccessUnit, ptsUs: Long): Boolean {
        val state = synchronized(monitor) {
            if (closed || configuration == null || failedRevision == revision) return false
            desiredStateLocked()
        }
        if (unit.nalLengthSize != state.config!!.nalLengthSize) return false
        return acceptPrepared(unit, ptsUs, state)
    }

    private fun acceptPrepared(unit: H264Config.AccessUnit, ptsUs: Long, state: DesiredState): Boolean {
        return try {
            synchronized(monitor) {
                if (closed || revision != state.revision || failedRevision == revision) return@synchronized false
                val updated = H264Config.mergeParameterSets(configuration!!, unit)
                if (!sameConfiguration(configuration, updated)) {
                    configuration = updated
                    invalidateLocked("in-band configuration changed")
                }
                // The wire keyframe hint cannot turn a dependent slice into a recovery point.
                if (!unit.hasVcl) return@synchronized true
                if (outputSurface == null) return@synchronized false
                if (waitingForIdr && !unit.isIdr) return@synchronized false
                val targetRevision = revision
                val startedWaitingNs = System.nanoTime()
                while (queue.size >= MAX_QUEUED_ACCESS_UNITS || queuedBytes + unit.annexB.size > MAX_QUEUED_BYTES) {
                    if (closed || revision != targetRevision || failedRevision == revision) return@synchronized false
                    val nowNs = System.nanoTime()
                    val remainingNs = MAX_ENQUEUE_WAIT_NS - (nowNs - startedWaitingNs)
                    if (remainingNs <= 0L) {
                        throw InputTimeout(targetRevision)
                    }
                    if (lastBackpressureLogNs == Long.MIN_VALUE || nowNs - lastBackpressureLogNs >= 1_000_000_000L) {
                        lastBackpressureLogNs = nowNs
                        Log.i(TAG, "video input backpressure revision=$revision queued=${queue.size} bytes=$queuedBytes; preserving reference frames")
                    }
                    // The TCP reader must slow the sender while MediaCodec starts. Dropping
                    // the initial IDR here leaves an otherwise healthy stream black forever.
                    monitor.wait((remainingNs / 1_000_000L).coerceAtLeast(1L))
                }
                if (closed || revision != targetRevision || failedRevision == revision) return@synchronized false
                queue.addLast(Frame(unit.annexB, ptsUs, unit.isIdr, System.nanoTime(), queue.size + 1))
                queuedBytes += unit.annexB.size
                waitingForIdr = false
                monitor.notifyAll()
                true
            }
        } catch (e: InputTimeout) {
            // Error callbacks can close the pipeline; never invoke them under the producer lock.
            failRevision(e.failedRevision, "AVC decoder did not accept queued video within 5 seconds", e)
            false
        } catch (e: InterruptedException) {
            // MirrorReceiver interrupts its producer during close, before joining it.
            Thread.currentThread().interrupt()
            false
        } catch (e: IllegalArgumentException) {
            synchronized(monitor) { if (!closed && revision == state.revision) invalidateLocked("invalid parameter sets") }
            reportError("Invalid in-band AVC parameter sets: ${e.message}", e)
            false
        }
    }

    override fun attachSurface(surface: Surface?) {
        synchronized(monitor) {
            if (closed) return
            if (outputSurface === surface) return
            outputSurface = surface
            invalidateLocked(if (surface == null) "surface detached" else "surface attached")
        }
    }

    override fun flush() {
        synchronized(monitor) {
            if (!closed) invalidateLocked("transport flush")
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (!closed) {
                closed = true
                outputSurface = null
                configuration = null
                invalidateLocked("closed")
            }
        }
        if (Thread.currentThread() !== worker) {
            try {
                worker.join(5_000)
                check(!worker.isAlive) { "Video decoder worker did not finish within shutdown timeout" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while closing video decoder", e)
            }
        }
    }

    private fun desiredStateLocked() = DesiredState(configuration, outputSurface, revision, closed)

    private fun invalidateLocked(reason: String) {
        Log.i(TAG, "video state reset reason=$reason revision=${revision + 1} droppedAccessUnits=${queue.size}")
        queue.clear()
        queuedBytes = 0
        waitingForIdr = true
        revision++
        monitor.notifyAll()
    }

    private fun isCurrent(value: Long): Boolean = synchronized(monitor) {
        !closed && revision == value && failedRevision != value
    }

    private fun decodeLoop() {
        var decoder: MediaCodec? = null
        var activeRevision = -1L
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val state = synchronized(monitor) { desiredStateLocked() }
                if (state.closed) break
                if (activeRevision != state.revision || !isCurrent(activeRevision)) {
                    releaseDecoder(decoder)
                    decoder = null
                    activeRevision = state.revision
                }
                val canCreate = isCurrent(state.revision)
                if (decoder == null && state.config != null && state.surface != null && canCreate) {
                    if (!state.surface.isValid) {
                        synchronized(monitor) {
                            if (revision == state.revision) {
                                outputSurface = null
                                invalidateLocked("surface became invalid")
                            }
                        }
                        Log.w(TAG, "Video surface is invalid; waiting for a new surface")
                        continue
                    }
                    try {
                        val created = createDecoder(state.config, state.surface)
                        val accepted = synchronized(monitor) {
                            if (!closed && revision == state.revision && failedRevision != state.revision) {
                                error = null
                                true
                            } else false
                        }
                        if (accepted) decoder = created else releaseDecoder(created)
                    } catch (e: Exception) {
                        failRevision(state.revision, "Unable to start AVC decoder: ${e.message}", e)
                    }
                }
                val codec = decoder
                var progressed = false
                var inputBlocked = false
                if (codec != null && isCurrent(activeRevision)) {
                    try {
                        progressed = drainOutput(codec, info, activeRevision)
                        val candidate = synchronized(monitor) {
                            if (!closed && revision == activeRevision && failedRevision != activeRevision) queue.peekFirst() else null
                        }
                        if (candidate != null && isCurrent(activeRevision)) {
                            val index = codec.dequeueInputBuffer(0)
                            inputBlocked = index < 0
                            if (index >= 0) {
                                val frame = synchronized(monitor) {
                                    if (!closed && revision == activeRevision && failedRevision != activeRevision && queue.peekFirst() === candidate) {
                                        queue.removeFirst().also {
                                            queuedBytes -= it.bytes.size
                                            monitor.notifyAll()
                                        }
                                    } else null
                                }
                                if (frame != null) {
                                    val input = checkNotNull(codec.getInputBuffer(index)) { "AVC decoder has no input buffer" }
                                    input.clear()
                                    check(input.remaining() >= frame.bytes.size) { "AVC access unit exceeds decoder input capacity" }
                                    input.put(frame.bytes)
                                    codec.queueInputBuffer(index, 0, frame.bytes.size, frame.ptsUs,
                                        if (frame.isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                    timing.recordInput((System.nanoTime() - frame.enqueuedAtNs) / 1000, frame.queueDepth)
                                    progressed = true
                                }
                                // If invalidated while acquiring input, the next iteration releases the codec.
                            }
                        }
                        if (isCurrent(activeRevision)) progressed = drainOutput(codec, info, activeRevision) || progressed
                    } catch (e: Exception) {
                        releaseDecoder(decoder)
                        decoder = null
                        failRevision(activeRevision, "AVC decoder failed: ${e.message}", e)
                    }
                }
                if (!progressed) {
                    synchronized(monitor) {
                        if (!closed && revision == activeRevision && (queue.isEmpty() || inputBlocked || decoder == null)) {
                            // Poll even without input: the last queued frame may finish asynchronously.
                            monitor.wait(if (decoder == null) 0L else OUTPUT_POLL_MS)
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            reportError("Video worker interrupted", e)
        } finally {
            releaseDecoder(decoder)
            preparedAvc = null
        }
    }

    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo, value: Long): Boolean {
        var progressed = false
        repeat(MAX_OUTPUTS_PER_PASS) {
            if (!isCurrent(value)) return progressed
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    if (!isCurrent(value) || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ||
                        (info.size == 0 && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)) {
                        codec.releaseOutputBuffer(index, false)
                    } else {
                        val nowNs = System.nanoTime()
                        val nowUs = nowNs / 1000
                        val ptsUs = info.presentationTimeUs
                        timing.recordOutput((nowUs - ptsUs).coerceAtLeast(0))
                        timing.snapshotIfDue(nowUs)?.let { Log.i(TAG, "video_timing revision=$value $it") }
                        val canSchedule = ptsUs > nowUs && ptsUs <= nowUs + MAX_FUTURE_DEADLINE_US &&
                            ptsUs in (Long.MIN_VALUE / 1000)..(Long.MAX_VALUE / 1000)
                        // Boolean render=true can still use the codec PTS, so explicitly stamp late frames now.
                        codec.releaseOutputBuffer(index, if (canSchedule) ptsUs * 1000 else nowNs)
                        if (renderedRevision != value) {
                            renderedRevision = value
                            Log.i(TAG, "first_frame_render_attempted ptsUs=$ptsUs revision=$value")
                            onRenderAttempt?.invoke()
                        }
                    }
                    progressed = true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "video output format revision=$value format=${codec.outputFormat}")
                    progressed = true
                }
                index == -3 -> progressed = true // Legacy INFO_OUTPUT_BUFFERS_CHANGED; buffers use indexed APIs.
                else -> return progressed
            }
        }
        return progressed
    }

    private fun createDecoder(config: VideoConfig, surface: Surface): MediaCodec {
        val prepared = preparedAvc?.takeIf { it.config === config } ?: run {
            val activeSpsId = H264Config.ppsIds(config.pps.first()).second
            PreparedAvc(config, config.sps.map(H264Config::parseSps).first { it.spsId == activeSpsId },
                H264Config.parameterSetsToAnnexB(config.sps), H264Config.parameterSetsToAnnexB(config.pps))
                .also { preparedAvc = it }
        }
        val dimensions = prepared.dimensions
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dimensions.codedWidth, dimensions.codedHeight)
        format.setInteger("crop-left", dimensions.cropLeft)
        format.setInteger("crop-right", dimensions.codedWidth - dimensions.cropRight - 1)
        format.setInteger("crop-top", dimensions.cropTop)
        format.setInteger("crop-bottom", dimensions.codedHeight - dimensions.cropBottom - 1)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, H264Config.MAX_ACCESS_UNIT_BYTES)
        androidProfile(dimensions.profileIdc)?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }
        format.setByteBuffer("csd-0", ByteBuffer.wrap(prepared.sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(prepared.pps))
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .filter {
                try { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }
                catch (_: IllegalArgumentException) { false }
            }
            .sortedBy { if (isHardware(it)) 0 else 1 }
        check(candidates.isNotEmpty()) { "No AVC decoder supports ${dimensions.codedWidth}x${dimensions.codedHeight}" }
        var lastFailure: Exception? = null
        for (candidate in candidates) {
            val supportsLowLatency = Build.VERSION.SDK_INT >= 30 && candidate
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            // A device that advertises this optional feature may still reject it for this stream.
            for (lowLatency in if (supportsLowLatency) listOf(true, false) else listOf(false)) {
                var codec: MediaCodec? = null
                try {
                    if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, if (lowLatency) 1 else 0)
                    codec = MediaCodec.createByCodecName(candidate.name)
                    codec.configure(format, surface, null, 0)
                    codec.start()
                    Log.i(TAG, "decoder configured name=${candidate.name} dimensions=${dimensions.codedWidth}x${dimensions.codedHeight} lowLatency=$lowLatency")
                    return codec
                } catch (e: Exception) {
                    lastFailure = e
                    releaseDecoder(codec)
                    if (lowLatency) Log.w(TAG, "Low-latency mode rejected by ${candidate.name}; retrying normal mode", e)
                }
            }
        }
        throw IllegalStateException("All supported AVC decoders failed to start", lastFailure)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name.lowercase(java.util.Locale.ROOT)
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.") ||
            name.contains(".sw.") || name.contains(".sw.dec"))
    }

    private fun androidProfile(profileIdc: Int): Int? = when (profileIdc) {
        66 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        88 -> MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
        100 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        110 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        122 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422
        244 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
        else -> null
    }

    private fun failRevision(value: Long, message: String, cause: Throwable) {
        synchronized(monitor) {
            if (closed || revision != value || failedRevision == value) return
            failedRevision = value
            queue.clear()
            queuedBytes = 0
            waitingForIdr = true
            error = message
            monitor.notifyAll()
        }
        Log.e(TAG, message, cause)
        notifyError(message)
    }

    private fun reportError(message: String, cause: Throwable? = null) {
        synchronized(monitor) { error = message }
        Log.e(TAG, message, cause)
        notifyError(message)
    }

    private fun notifyError(message: String) {
        try { onError?.invoke(message) } catch (e: Exception) { Log.e(TAG, "Video error callback failed", e) }
    }

    private fun releaseDecoder(codec: MediaCodec?) {
        if (codec == null) return
        try { codec.stop() } catch (e: Exception) { Log.w(TAG, "AVC decoder stop failed", e) }
        try { codec.release() } catch (e: Exception) { Log.w(TAG, "AVC decoder release failed", e) }
    }

    private fun sameConfiguration(a: VideoConfig?, b: VideoConfig): Boolean = a != null &&
        a.width == b.width && a.height == b.height && a.nalLengthSize == b.nalLengthSize &&
        a.sps.size == b.sps.size && a.pps.size == b.pps.size &&
        a.sps.indices.all { a.sps[it].contentEquals(b.sps[it]) } &&
        a.pps.indices.all { a.pps[it].contentEquals(b.pps[it]) }

    private companion object {
        const val TAG = "FossMirrorTV-Video"
        const val MAX_QUEUED_ACCESS_UNITS = 12
        const val MAX_QUEUED_BYTES = 16 * 1024 * 1024
        const val MAX_ENQUEUE_WAIT_NS = 5_000_000_000L
        const val OUTPUT_POLL_MS = 5L
        const val MAX_OUTPUTS_PER_PASS = 32
        const val MAX_FUTURE_DEADLINE_US = 1_000_000L
    }
}
