package com.sal7one.tvtestproject.receiver

import android.view.Surface
import com.sal7one.tvtestproject.receiver.media.H264Config

data class VideoConfig(
    val width: Int,
    val height: Int,
    val sps: List<ByteArray>,
    val pps: List<ByteArray>,
    val nalLengthSize: Int = 4,
)

enum class AudioCodec { AAC_LC, AAC_ELD, PCM, ALAC }

data class AudioConfig(
    val codec: AudioCodec,
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val samplesPerFrame: Int = 1024,
    val audioSpecificConfig: ByteArray? = null,
)

interface VideoSink : AutoCloseable {
    fun configure(config: VideoConfig): Boolean
    fun enqueue(accessUnit: ByteArray, ptsUs: Long, isKeyframe: Boolean): Boolean
    fun prepareAccessUnit(accessUnit: ByteArray, nalLengthSize: Int): H264Config.AccessUnit =
        H264Config.parseAccessUnit(accessUnit, nalLengthSize)
    fun enqueuePrepared(unit: H264Config.AccessUnit, ptsUs: Long): Boolean =
        enqueue(unit.source, ptsUs, unit.isIdr)
    fun attachSurface(surface: Surface?)
    fun flush()
}

interface AudioSink : AutoCloseable {
    fun configure(config: AudioConfig): Boolean
    fun enqueue(frame: ByteArray, ptsUs: Long): Boolean
    fun setVolume(decibels: Float)
    fun flush()
}

enum class ReceiverState { STOPPED, STARTING, READY, CONNECTED, STREAMING, ERROR }

data class ReceiverStatus(
    val state: ReceiverState,
    val message: String,
    val peer: String? = null,
    val addresses: List<String> = emptyList(),
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
)

data class ReceiverAnnouncement(
    val name: String,
    val deviceId: String,
    val pairingId: String,
    val publicKeyHex: String,
    val features: Long,
    val port: Int,
    val audioCodecs: Set<AudioCodec> = emptySet(),
)
