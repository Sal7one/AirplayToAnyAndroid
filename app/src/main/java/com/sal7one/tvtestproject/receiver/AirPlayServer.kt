package com.sal7one.tvtestproject.receiver

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.Surface
import com.sal7one.tvtestproject.DeviceIdentity
import com.sal7one.tvtestproject.UxPlayDnsTxt
import com.sal7one.tvtestproject.receiver.media.AudioPipeline
import com.sal7one.tvtestproject.receiver.media.H264Config
import com.sal7one.tvtestproject.receiver.media.NativeAvcParser
import com.sal7one.tvtestproject.receiver.media.VideoPipeline
import com.sal7one.tvtestproject.receiver.media.VideoCapabilities
import com.sal7one.tvtestproject.receiver.protocol.DeviceKeys
import com.sal7one.tvtestproject.receiver.protocol.LegacyPairing
import com.sal7one.tvtestproject.receiver.protocol.LegacyNegotiation
import com.sal7one.tvtestproject.receiver.protocol.NativeFairPlaySession
import com.sal7one.tvtestproject.receiver.protocol.Plist
import com.sal7one.tvtestproject.receiver.protocol.RtspCodec
import com.sal7one.tvtestproject.receiver.protocol.RtspRequest
import com.sal7one.tvtestproject.receiver.protocol.RtspResponse
import com.sal7one.tvtestproject.receiver.protocol.SessionCrypto
import com.sal7one.tvtestproject.receiver.transport.AudioRtpReceiver
import com.sal7one.tvtestproject.receiver.transport.MirrorReceiver
import com.sal7one.tvtestproject.receiver.transport.SessionClock
import com.sal7one.tvtestproject.receiver.transport.TimingReceiver
import java.io.File
import java.io.BufferedInputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One LAN client, with protocol, secrets, sockets, clock and media owned by its RTSP session. */
class AirPlayServer(context: Context, private val onStatus: (ReceiverStatus) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val lock = Any()
    private val sessionIds = AtomicLong()
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connections = linkedMapOf<Session, Thread>()
    private var mediaOwner: Session? = null
    private var disconnecting = false
    private var surface: Surface? = null
    private lateinit var keys: DeviceKeys
    private lateinit var announcement: ReceiverAnnouncement
    @Volatile private var running = false
    @Volatile private var cleanupFailure: Exception? = null

    fun start(): ReceiverAnnouncement = synchronized(lock) {
        if (running) return@synchronized announcement
        check(cleanupFailure == null && connections.isEmpty()) { "Previous receiver session did not shut down" }
        report(ReceiverState.STARTING, "Starting receiver")
        keys = DeviceKeys.loadOrCreate(File(context.noBackupFilesDir, "airplay-identity.key"))
        val public = keys.publicKey
        var pendingSocket: ServerSocket? = null
        try {
            // Never advertise a receiver whose native crypto library cannot load.
            try { NativeFairPlaySession().use { } } catch (error: LinkageError) {
                throw IllegalStateException("Native FairPlay library unavailable", error)
            }
            try { NativeAvcParser.ensureAvailable() } catch (error: LinkageError) {
                throw IllegalStateException("Native AVC library unavailable", error)
            }
            val socket = bindControlSocket().also { pendingSocket = it }
            val audioCodecs = setOf(AudioCodec.PCM, AudioCodec.AAC_ELD, AudioCodec.ALAC).filterTo(mutableSetOf()) { codec ->
                AudioPipeline.isSupported(AudioConfig(codec, samplesPerFrame = if (codec == AudioCodec.AAC_ELD) 480 else 352))
            }
            announcement = ReceiverAnnouncement(
                DeviceIdentity.displayName(context),
                DeviceIdentity.airplayDeviceId(DeviceIdentity.mac6Bytes(context)),
                UUID.nameUUIDFromBytes(public).toString(),
                public.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) },
                LegacyNegotiation.features(audioCodecs),
                socket.localPort,
                audioCodecs,
            )
            listener = socket
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "FossMirrorTV-RTSP").apply { isDaemon = true; start() }
            Log.i(TAG, "RTSP bound/listening port=${socket.localPort}")
            report(ReceiverState.READY, "Ready for screen mirroring")
            announcement
        } catch (error: Exception) {
            pendingSocket?.close()
            report(ReceiverState.ERROR, error.message ?: "Receiver startup failed")
            throw error
        }
    }

    private fun bindControlSocket(): ServerSocket {
        fun bind(port: Int): ServerSocket {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port), 4)
                return socket
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
        return try {
            bind(CONTROL_PORT)
        } catch (error: BindException) {
            Log.w(TAG, "RTSP preferred port=$CONTROL_PORT unavailable; requesting a free port", error)
            // DNS-SD publishes the actual bound port, not a fixed AirPlay control port.
            bind(0)
        }
    }

    fun attachSurface(surface: Surface?) = synchronized(lock) {
        this.surface = surface
        connections.keys.forEach { it.attachSurface(surface) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (running) {
                val client = socket.accept()
                client.tcpNoDelay = true
                synchronized(lock) {
                    if (!running) client.close()
                    else if (disconnecting || connections.size >= 4 || connections.keys.any { it.peer != client.inetAddress }) {
                        // One LAN client, with bounded auxiliary control connections from that same peer.
                        try {
                            client.use {
                                RtspCodec.write(it.getOutputStream(), RtspResponse(453, "Not Enough Bandwidth"))
                            }
                        } catch (error: Exception) {
                            // A retrying sender can reset before reading its rejection.
                            Log.w(TAG, "Rejected control connection closed: ${error.javaClass.simpleName}")
                        }
                    } else {
                        val session = Session(client, sessionIds.incrementAndGet())
                        session.attachSurface(surface)
                        val worker = Thread({
                            try { session.run() } finally {
                                synchronized(lock) {
                                    connections.remove(session)
                                    if (mediaOwner === session) mediaOwner = null
                                    if (running && !disconnecting && cleanupFailure == null && connections.isEmpty()) {
                                        report(ReceiverState.READY, "Ready for screen mirroring")
                                    }
                                }
                            }
                        }, "FossMirrorTV-Session-${session.id}").apply { isDaemon = true }
                        connections[session] = worker
                        worker.start()
                    }
                }
            }
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "RTSP accept failed", error)
                report(ReceiverState.ERROR, "RTSP listener failed")
            }
        }
    }

    /** Drop the sender without the multi-second DNS-SD unregister/register cycle. */
    fun disconnectClient() {
        val workers = synchronized(lock) {
            check(running) { "Receiver is stopped" }
            disconnecting = true
            connections.keys.forEach { it.requestStop() }
            connections.values.toList()
        }
        var clean = false
        try {
            workers.forEach { if (it !== Thread.currentThread()) it.join(5_000) }
            check(workers.none { it !== Thread.currentThread() && it.isAlive }) { "Client did not disconnect" }
            cleanupFailure?.let { throw IllegalStateException("Media cleanup failed", it) }
            clean = true
        } finally {
            synchronized(lock) {
                if (running) {
                    if (clean) report(ReceiverState.READY, "Ready for screen mirroring")
                    else report(ReceiverState.ERROR, "Client disconnect failed; restart receiver")
                }
                // Publish completion before admitting a new session, whose status must win.
                disconnecting = !clean
            }
        }
    }

    override fun close() {
        val workers = synchronized(lock) {
            running = false
            listener?.close()
            listener = null
            connections.keys.forEach { it.requestStop() }
            listOfNotNull(acceptThread) + connections.values
        }
        workers.forEach { if (it !== Thread.currentThread()) it.join(5_000) }
        check(workers.none { it !== Thread.currentThread() && it.isAlive }) { "Receiver worker did not stop; restart refused" }
        cleanupFailure?.let { throw IllegalStateException("Media cleanup failed; restart refused", it) }
        report(ReceiverState.STOPPED, "Receiver stopped")
    }

    private fun report(state: ReceiverState, message: String, peer: String? = null, videoSize: Pair<Int, Int>? = null) {
        onStatus(ReceiverStatus(state, message, peer, videoWidth = videoSize?.first, videoHeight = videoSize?.second))
    }

    private class Failure(val status: Int, val reason: String, message: String) : Exception(message)

    private inner class Session(private val socket: Socket, val id: Long) {
        val peer = socket.inetAddress
        private val playbackMode = PlaybackPreferences.read(context)
        private val clock = SessionClock(playbackMode.playoutDelayUs)
        private val connectedAtNs = System.nanoTime()
        private val maximumFps: Int by lazy {
            val hardware60 = playbackMode == PlaybackMode.SMOOTH &&
                VideoCapabilities.probeAvc(1920, 1088, 60.0).hardwareCandidates.isNotEmpty()
            playbackMode.maximumFps(hardware60).also {
                log("video_offer maxFPS=$it width=1920 height=1080 hardware60=$hardware60")
            }
        }
        private val pairing = LegacyPairing(keys)
        private var fairplay: NativeFairPlaySession? = null
        private var crypto: SessionCrypto? = null
        private var timing: TimingReceiver? = null
        private var mirror: MirrorReceiver? = null
        private var audioReceiver: AudioRtpReceiver? = null
        @Volatile private var video: VideoPipeline? = null
        private var audio: AudioPipeline? = null
        @Volatile private var outputSurface: Surface? = null
        @Volatile private var stop = false
        private var pairingRequested = false
        private var volumeDb = 0f

        fun attachSurface(surface: Surface?) {
            outputSurface = surface
            video?.attachSurface(surface)
        }

        fun requestStop() { stop = true; socket.close() }

        private fun mediaFailure(message: String) {
            Log.e(TAG, "session=$id media failed: $message")
            report(ReceiverState.ERROR, message, peer.hostAddress)
            requestStop()
        }

        fun run() {
            log("client connected peer=${peer.hostAddress}:${socket.port}")
            log("playback_mode=$playbackMode playoutDelayUs=${clock.playoutDelayUs}")
            synchronized(lock) {
                if (mediaOwner == null) report(ReceiverState.CONNECTED, "Mac connected", peer.hostAddress)
            }
            try {
                socket.soTimeout = 0
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                while (running && !stop) {
                    val req = RtspCodec.read(socket, input, RtspCodec.Limits(
                        idleTimeoutMillis = if (crypto == null) 30_000 else 0,
                    )) ?: break
                    log("RTSP request method=${req.method} path=${req.path.substringBefore('?').take(120)} CSeq=${req.header("cseq")} body=${req.body.size}")
                    val response = try {
                        if (req.version == "RTSP/1.0" && req.header("cseq") == null) {
                            throw Failure(400, "Bad Request", "Missing CSeq")
                        }
                        dispatch(req)
                    } catch (error: LegacyPairing.PairingException) {
                        log("pair_verify rejected reason=${error.message}")
                        stop = true
                        failure(403, "Forbidden", "Pairing verification failed")
                    } catch (error: Failure) {
                        if (req.method == "SETUP") stop = true
                        failure(error.status, error.reason, error.message ?: error.reason)
                    } catch (error: IllegalArgumentException) {
                        if (req.method == "SETUP") stop = true
                        failure(400, "Bad Request", error.message ?: "Invalid protocol data")
                    } catch (error: Exception) {
                        Log.e(TAG, "session=$id request failed", error)
                        stop = true
                        failure(500, "Internal Server Error", "Receiver operation failed")
                    }
                    RtspCodec.write(socket.getOutputStream(), response.copy(
                        version = req.version, cSeq = req.header("cseq"),
                        headers = response.headers + ("Server" to "FossMirrorTV/2"),
                    ))
                    log("RTSP response status=${response.statusCode} reason=${response.reason} CSeq=${req.header("cseq")}")
                }
            } catch (error: Exception) {
                if (!stop && running) Log.w(TAG, "session=$id control disconnected: ${error.message}")
            } finally {
                stop = true
                socket.close()
                // Closing transports first ensures no producer can enqueue into a released renderer.
                listOfNotNull(mirror, audioReceiver, timing, video, audio, crypto, fairplay, pairing).forEach {
                    try { it.close() } catch (error: Exception) {
                        Log.w(TAG, "session=$id cleanup failed", error)
                        cleanupFailure = error
                    }
                }
                if (cleanupFailure != null) {
                    synchronized(lock) {
                        running = false
                        listener?.close()
                        connections.keys.forEach { it.requestStop() }
                    }
                    report(ReceiverState.ERROR, "Media cleanup failed; receiver stopped", peer.hostAddress)
                }
                clock.reset()
                log("session stopped; sockets/media/crypto released")
            }
        }

        private fun dispatch(req: RtspRequest): RtspResponse {
            val path = if (req.path.startsWith("/")) req.path.substringBefore('?') else {
                runCatching { URI(req.path).path }.getOrNull() ?: req.path
            }
            return when {
                req.method == "OPTIONS" -> RtspResponse(headers = mapOf("Public" to
                    "OPTIONS, GET, POST, SETUP, RECORD, GET_PARAMETER, SET_PARAMETER, FLUSH, TEARDOWN"))
                req.method == "GET" && path == "/info" -> plist(info(req))
                req.method == "POST" && path == "/pair-setup" -> {
                    val result = pairing.pairSetup(req.body)
                    pairingRequested = true
                    log("pair_setup completed")
                    RtspResponse(body = result, contentType = "application/octet-stream")
                }
                req.method == "POST" && path == "/pair-verify" -> {
                    pairingRequested = true
                    val result = pairing.pairVerify(req.body)
                    log("pair_verify verified=${pairing.isVerified}")
                    RtspResponse(body = result, contentType = "application/octet-stream")
                }
                req.method == "POST" && path == "/fp-setup" -> {
                    val adapter = fairplay ?: NativeFairPlaySession().also { fairplay = it }
                    val result = adapter.setup(req.body)
                    log("fairplay exchange requestBytes=${req.body.size} responseBytes=${result.size}")
                    RtspResponse(body = result, contentType = "application/octet-stream")
                }
                req.method == "POST" && path == "/feedback" -> {
                    // Legacy heartbeat, not a request to manufacture decoder/session success.
                    // Reference trace: FDH2/UxPlay issues/67, recurring empty RTSP POSTs.
                    require(req.body.isEmpty()) { "Feedback statistics bodies are unsupported" }
                    if (crypto == null) throw Failure(455, "Method Not Valid in This State", "Session keys not ready")
                    RtspResponse()
                }
                req.method == "SETUP" -> setup(Plist.decode(req.body))
                req.method == "RECORD" -> {
                    if (crypto == null) throw Failure(455, "Method Not Valid in This State", "Session keys not ready")
                    RtspResponse(headers = mapOf("Audio-Latency" to
                        (clock.playoutDelayUs * 44_100L / 1_000_000L).toString(), "Session" to id.toString()))
                }
                req.method == "GET_PARAMETER" -> {
                    val parameters = req.body.toString(Charsets.US_ASCII).trim()
                    if (parameters.isEmpty()) RtspResponse()
                    else if (parameters == "volume") RtspResponse(body = "volume: $volumeDb\r\n".toByteArray(), contentType = "text/parameters")
                    else throw Failure(451, "Parameter Not Understood", "Unknown parameter")
                }
                req.method == "SET_PARAMETER" -> setParameter(req)
                req.method == "FLUSH" -> {
                    val seq = LegacyNegotiation.flushSequence(req.header("rtp-info"))
                    audioReceiver?.flush(seq)
                    audio?.flush()
                    // Legacy FLUSH applies to RAOP audio, not the mirror TCP reference chain.
                    log("audio FLUSH nextSeq=$seq mirrorPreserved=${mirror != null}")
                    RtspResponse()
                }
                req.method == "TEARDOWN" -> teardown(req)
                else -> failure(501, "Not Implemented", "Unsupported ${req.method} $path")
            }
        }

        private fun setup(root: Map<String, Any?>): RtspResponse {
            if (root["isRemoteControlOnly"] == true) throw Failure(461, "Unsupported Transport", "AirPlay 2 remote control is unsupported")
            val streams = LegacyNegotiation.streams(root)
            val types = streams.map { number(it, "type") }
            synchronized(lock) {
                if (mediaOwner != null && mediaOwner !== this) throw Failure(409, "Conflict", "Another control connection owns the media session")
                mediaOwner = this
            }
            log("SETUP classify keys=${root.containsKey("ekey")} timing=${root["timingProtocol"]} streams=$types")
            val result = linkedMapOf<String, Any?>()
            if (root.containsKey("ekey")) {
                if (crypto != null) throw Failure(455, "Method Not Valid in This State", "Session keys already negotiated")
                if (pairingRequested && !pairing.isVerified) throw Failure(403, "Forbidden", "Pair verification incomplete")
                require(root["timingProtocol"] == null || root["timingProtocol"] == "NTP") { "Only NTP timing is supported" }
                val remotePort = number(root, "timingPort").also { require(it in 1..65535) }.toInt()
                val iv = root["eiv"] as? ByteArray ?: throw IllegalArgumentException("Missing eiv")
                val encryptedKey = root["ekey"] as? ByteArray ?: throw IllegalArgumentException("Missing ekey")
                require(iv.size == 16 && encryptedKey.size == 72) { "Invalid session key/IV length" }
                val adapter = fairplay ?: throw Failure(455, "Method Not Valid in This State", "FairPlay handshake not ready")
                val key = adapter.decryptKey(encryptedKey)
                val shared = pairing.sharedSecret
                try {
                    crypto = SessionCrypto(key, iv, shared)
                    timing = TimingReceiver(peer, remotePort, clock, ::transportFailure).also { it.start() }
                } finally { key.fill(0); shared?.fill(0) }
                result["timingPort"] = timing!!.port.toLong()
                result["eventPort"] = 0L // UxPlay: unused in legacy mirror/audio mode.
                log("session_keys_ready timingPort=${timing!!.port} eventPort=0")
            }
            if (streams.isNotEmpty()) {
                val secrets = crypto ?: throw Failure(455, "Method Not Valid in This State", "Session keys not ready")
                val responseStreams = streams.map { stream ->
                    when (number(stream, "type")) {
                        110L -> {
                            if (mirror != null) throw Failure(455, "Method Not Valid in This State", "Mirror stream already active")
                            val streamId = number(stream, "streamConnectionID")
                            val sink = VideoPipeline(::mediaFailure) {
                                log("connection_first_video_ms=${(System.nanoTime() - connectedAtNs) / 1_000_000}")
                                report(ReceiverState.STREAMING, "Mirroring", peer.hostAddress, video?.videoSize)
                            }.also {
                                video = it
                                it.attachSurface(outputSurface)
                            }
                            val receiver = MirrorReceiver(peer, secrets.createMirrorDecryptor(streamId), clock, sink, id,
                                { log("video decoder accepted first frame") }, ::mediaFailure,
                                onIdr = ::captureDiagnosticIdr)
                            mirror = receiver
                            receiver.start()
                            mapOf("type" to 110L, "dataPort" to receiver.port.toLong())
                        }
                        96L -> {
                            if (audioReceiver != null) throw Failure(455, "Method Not Valid in This State", "Audio stream already active")
                            val config = audioConfiguration(stream)
                            val sink = AudioPipeline(context, ::mediaFailure).also { audio = it }
                            if (!sink.configure(config)) throw Failure(415, "Unsupported Media Type", "Negotiated audio decoder unavailable")
                            sink.setVolume(volumeDb)
                            var logged = false
                            val receiver = AudioRtpReceiver(peer, config, clock, secrets::decryptAudioPacket, ::transportFailure) { frame, pts ->
                                if (sink.enqueue(frame, pts) && !logged) {
                                    logged = true
                                    log("first_audio_frame_queued codec=${config.codec} samples=${config.samplesPerFrame}")
                                }
                            }
                            audioReceiver = receiver
                            if (stream.containsKey("controlPort")) {
                                number(stream, "controlPort").also { require(it in 1..65535) { "Invalid audio controlPort" } }
                                    .toInt().let(receiver::setRemoteControlPort)
                            }
                            receiver.start()
                            log("audio UDP bound dataPort=${receiver.dataPort} controlPort=${receiver.controlPort} codec=${config.codec}")
                            mapOf("type" to 96L, "dataPort" to receiver.dataPort.toLong(), "controlPort" to receiver.controlPort.toLong())
                        }
                        else -> error("Validated above")
                    }
                }
                result["streams"] = responseStreams
            }
            require(result.isNotEmpty()) { "SETUP contains neither keys nor streams" }
            return plist(result).copy(headers = mapOf("Session" to id.toString()))
        }

        private fun captureDiagnosticIdr(config: VideoConfig, annexB: ByteArray) {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
            val request = File(context.cacheDir, "capture-next-mirror-idr")
            if (!request.isFile || !request.delete()) return
            try {
                // Explicit, one-shot local diagnostic. Never retain keys or record a full session.
                val output = File(context.cacheDir, "mirror-first-idr.h264")
                output.outputStream().use {
                    it.write(H264Config.parameterSetsToAnnexB(config.sps))
                    it.write(H264Config.parameterSetsToAnnexB(config.pps))
                    it.write(annexB)
                }
                log("diagnostic_idr_saved bytes=${output.length()} file=${output.name}")
            } catch (error: Exception) {
                Log.w(TAG, "session=$id diagnostic IDR capture failed", error)
            }
        }

        private fun audioConfiguration(stream: Map<String, Any?>): AudioConfig {
            val config = LegacyNegotiation.audio(stream)
            if (!AudioPipeline.isSupported(config)) throw Failure(415, "Unsupported Media Type", "Audio format unavailable on this Android device")
            return config
        }

        private fun transportFailure(error: Throwable) = mediaFailure("Media transport failed: ${error.javaClass.simpleName}")

        private fun setParameter(req: RtspRequest): RtspResponse {
            return when (req.header("content-type")?.substringBefore(';')) {
                "text/parameters" -> {
                    val lines = req.body.toString(Charsets.US_ASCII).trim().split("\r\n")
                    for (line in lines) {
                        val name = line.substringBefore(':').trim()
                        when (name) {
                            "volume" -> {
                                val value = line.substringAfter(':').trim().toFloatOrNull()
                                require(value != null && value.isFinite() && value in -144f..0f) { "Invalid volume" }
                                volumeDb = value
                                audio?.setVolume(value)
                            }
                            "progress" -> require(line.substringAfter(':').trim().matches(Regex("[0-9]+/[0-9]+/[0-9]+")))
                            else -> throw Failure(451, "Parameter Not Understood", "Unknown parameter")
                        }
                    }
                    RtspResponse()
                }
                // Metadata/artwork is optional in mirroring; acknowledge receipt without rendering it.
                "application/x-dmap-tagged", "image/jpeg", "image/png" -> RtspResponse()
                else -> throw Failure(415, "Unsupported Media Type", "Unsupported parameter format")
            }
        }

        private fun teardown(req: RtspRequest): RtspResponse {
            if (req.body.isEmpty()) stop = true
            else {
                val root = Plist.decode(req.body)
                val streams = root["streams"] as? List<*> ?: emptyList<Any?>()
                if (streams.isEmpty()) stop = true
                else for (stream in streams) {
                    val type = number(dictionary(stream), "type")
                    log("TEARDOWN stream type=$type")
                    when (type) {
                        110L -> { mirror?.close(); mirror = null; video?.close(); video = null }
                        96L -> { audioReceiver?.close(); audioReceiver = null; audio?.close(); audio = null; clock.resetAudio() }
                        else -> throw IllegalArgumentException("Unknown TEARDOWN stream")
                    }
                }
            }
            return RtspResponse()
        }

        private fun info(req: RtspRequest): Map<String, Any?> {
            if (req.body.isNotEmpty()) {
                val qualifiers = Plist.decode(req.body)["qualifier"] as? List<*>
                if (qualifiers?.contains("txtAirPlay") == true) {
                    return mapOf("txtAirPlay" to txtBytes(UxPlayDnsTxt.airplayTxt(announcement)))
                }
                if (qualifiers?.contains("txtRAOP") == true) {
                    return mapOf("txtRAOP" to txtBytes(UxPlayDnsTxt.raopTxt(announcement)))
                }
            }
            val audioOutputFormats = LegacyNegotiation.audioOutputFormats(announcement.audioCodecs)
            log("info audioInputFormats=0x${audioOutputFormats.toString(16)} " +
                "audioOutputFormats=0x${audioOutputFormats.toString(16)} codecs=${announcement.audioCodecs}")
            return mapOf(
                "deviceID" to announcement.deviceId, "macAddress" to announcement.deviceId,
                "name" to announcement.name, "model" to "AppleTV3,2", "pk" to keys.publicKey,
                "pi" to announcement.pairingId, "features" to announcement.features,
                "vv" to 2L, "statusFlags" to UxPlayDnsTxt.STATUS_FLAGS.toLong(), "sourceVersion" to "220.68",
                "keepAliveLowPower" to 1L, "keepAliveSendStatsAsBody" to false, "initialVolume" to volumeDb.toDouble(),
                "displays" to listOf(mapOf(
                    "uuid" to announcement.pairingId, "width" to 1920L, "height" to 1080L,
                    "widthPixels" to 1920L, "heightPixels" to 1080L, "widthPhysical" to 0L, "heightPhysical" to 0L,
                    "refreshRate" to 1.0 / 60.0, "maxFPS" to maximumFps.toLong(), "features" to 14L,
                    "overscanned" to false, "rotation" to false,
                )),
                "audioLatencies" to listOf(100L, 101L).map { mapOf("type" to it, "audioType" to "default",
                    "inputLatencyMicros" to 0L, "outputLatencyMicros" to clock.playoutDelayUs) },
                "audioFormats" to LegacyNegotiation.audioFormats(announcement.audioCodecs),
            )
        }

        private fun failure(code: Int, reason: String, detail: String): RtspResponse {
            log("protocol reject status=$code reason=$reason detail=$detail")
            return RtspResponse(code, reason, body = "$detail\n".toByteArray(), contentType = "text/plain")
        }

        private fun log(message: String) = Log.i(TAG, "session=$id $message")
    }

    companion object {
        private const val TAG = "FossAirPlay"
        private const val CONTROL_PORT = 7000
        private fun plist(values: Map<String, Any?>) = RtspResponse(body = Plist.encode(values), contentType = Plist.CONTENT_TYPE)
        private fun number(values: Map<String, Any?>, key: String): Long =
            LegacyNegotiation.integer(values, key)
        private fun dictionary(value: Any?): Map<String, Any?> = LegacyNegotiation.dictionary(value)
        private fun txtBytes(txt: Map<String, String>): ByteArray = txt.entries.sortedBy { it.key }.flatMap {
            val bytes = "${it.key}=${it.value}".toByteArray(Charsets.UTF_8)
            require(bytes.size <= 255)
            listOf(bytes.size.toByte()) + bytes.toList()
        }.toByteArray()
    }
}
