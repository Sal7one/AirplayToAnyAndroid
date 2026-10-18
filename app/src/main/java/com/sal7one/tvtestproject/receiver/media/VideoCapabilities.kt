package com.sal7one.tvtestproject.receiver.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import kotlin.math.ceil

/** Cached read-only platform queries. Never allocates codecs, reserves surfaces, or advertises capabilities. */
object VideoCapabilities {
    enum class Profile(val mimeType: String, val androidValue: Int) {
        AVC_BASELINE(MediaFormat.MIMETYPE_VIDEO_AVC, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline),
        AVC_MAIN(MediaFormat.MIMETYPE_VIDEO_AVC, MediaCodecInfo.CodecProfileLevel.AVCProfileMain),
        AVC_HIGH(MediaFormat.MIMETYPE_VIDEO_AVC, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh),
        HEVC_MAIN(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
        HEVC_MAIN_10(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
    }

    enum class Acceleration { HARDWARE, SOFTWARE, UNKNOWN }

    data class Request(val width: Int, val height: Int, val frameRate: Double, val profile: Profile = Profile.HEVC_MAIN) {
        init {
            require(width in 1..16384 && height in 1..16384) { "Invalid video capability-query dimensions" }
            require(frameRate.isFinite() && frameRate > 0 && frameRate <= 240) { "Invalid video capability-query frame rate" }
        }
    }

    data class ProfileLevel(val profile: Int, val level: Int)

    data class Decoder(
        val name: String,
        val acceleration: Acceleration,
        val profiles: List<ProfileLevel>,
        val formatSupported: Boolean,
        val sizeAndRateSupported: Boolean,
        val lowLatency: Boolean,
        /** null means unavailable; false means no manufacturer performance point covers this query. */
        val performancePointCoversRequest: Boolean?,
    ) {
        // This is capability evidence only, never proof that configure/start/render will succeed.
        val hardwareCandidate: Boolean get() = acceleration == Acceleration.HARDWARE && formatSupported && sizeAndRateSupported
    }

    data class QueryFailure(val decoderName: String?, val detail: String)

    data class ProbeResult(val request: Request, val decoders: List<Decoder>, val failures: List<QueryFailure>) {
        val hardwareCandidates: List<Decoder> get() = decoders.filter { it.hardwareCandidate }
    }

    private val cache = LinkedHashMap<Request, ProbeResult>()

    /** Exact profile/size/rate evidence, not a guarantee of runtime availability or realtime throughput. */
    fun probe(request: Request): ProbeResult {
        synchronized(cache) { cache[request]?.let { return it } }
        val result = queryPlatform(request)
        return synchronized(cache) {
            cache[request]?.let { return it }
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache[request] = result
            result
        }
    }

    fun clearCachedProbes() = synchronized(cache) { cache.clear() }

    fun probeAvc(width: Int, height: Int, frameRate: Double, profile: Profile = Profile.AVC_HIGH): ProbeResult {
        require(profile.mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) { "Expected AVC profile" }
        return probe(Request(width, height, frameRate, profile))
    }

    fun probeHevc(request: Request): ProbeResult {
        require(request.profile.mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) { "Expected HEVC profile" }
        return probe(request)
    }

    private fun queryPlatform(request: Request): ProbeResult {
        val failures = ArrayList<QueryFailure>()
        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: RuntimeException) {
            return ProbeResult(request, emptyList(), listOf(QueryFailure(null, "Codec enumeration failed: ${e.javaClass.simpleName}")))
        }
        val decoders = ArrayList<Decoder>()
        for (info in infos) {
            var name: String? = null
            try {
                name = info.name
                if (info.isEncoder || (Build.VERSION.SDK_INT >= 29 && info.isAlias)) continue
                val mime = info.supportedTypes.firstOrNull { it.equals(request.profile.mimeType, ignoreCase = true) } ?: continue
                val caps = info.getCapabilitiesForType(mime)
                val video = requireNotNull(caps.videoCapabilities) { "Missing video capabilities" }
                val format = MediaFormat.createVideoFormat(mime, request.width, request.height).apply {
                    setInteger(MediaFormat.KEY_PROFILE, request.profile.androidValue)
                    setFloat(MediaFormat.KEY_FRAME_RATE, request.frameRate.toFloat())
                }
                val acceleration = classifyAcceleration(name,
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else null,
                    if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else null)
                val sizeAndRate = video.areSizeAndRateSupported(request.width, request.height, request.frameRate)
                val performance = if (Build.VERSION.SDK_INT >= 29) {
                    video.supportedPerformancePoints?.let { points ->
                        val target = MediaCodecInfo.VideoCapabilities.PerformancePoint(request.width, request.height, ceil(request.frameRate).toInt())
                        points.any { it.covers(target) }
                    }
                } else null
                decoders.add(Decoder(name, acceleration, caps.profileLevels.map { ProfileLevel(it.profile, it.level) },
                    caps.isFormatSupported(format), sizeAndRate,
                    Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                    performance))
            } catch (e: RuntimeException) {
                failures.add(QueryFailure(name, "Video capability query failed: ${e.javaClass.simpleName}"))
            }
        }
        return ProbeResult(request, decoders, failures)
    }

    /** No OEM-name whitelist: pre-29 Android does not expose a trustworthy hardware indicator. */
    internal fun classifyAcceleration(name: String, hardware: Boolean?, software: Boolean?): Acceleration = when {
        software == true -> Acceleration.SOFTWARE
        hardware == true -> Acceleration.HARDWARE
        name.startsWith("OMX.google.", ignoreCase = true) || name.startsWith("c2.android.", ignoreCase = true) -> Acceleration.SOFTWARE
        else -> Acceleration.UNKNOWN
    }

    fun requestFor(config: H265Config.DecoderConfig, frameRate: Double): Request {
        val sps = config.activeSps
        require(sps.profile.profileSpace == 0 && sps.chromaFormatIdc == 1 && !sps.separateColourPlane &&
            sps.bitDepthLuma == sps.bitDepthChroma) { "Only HEVC Main/Main10 4:2:0 probing is supported" }
        val profile = when (sps.profile.profileIdc) {
            1 -> { require(sps.bitDepthLuma == 8) { "Invalid HEVC Main bit depth" }; Profile.HEVC_MAIN }
            2 -> { require(sps.bitDepthLuma in 8..10) { "Invalid HEVC Main10 bit depth" }; Profile.HEVC_MAIN_10 }
            else -> throw IllegalArgumentException("Unsupported HEVC profile for capability probe")
        }
        // Query coded dimensions: a cropped picture must still fit in the decoder's allocation.
        return Request(sps.codedWidth, sps.codedHeight, frameRate, profile)
    }
}
