package com.sal7one.tvtestproject.receiver.transport

import android.util.Log
import com.sal7one.tvtestproject.receiver.VideoConfig
import com.sal7one.tvtestproject.receiver.VideoSink
import com.sal7one.tvtestproject.receiver.media.H264Config
import com.sal7one.tvtestproject.receiver.protocol.SessionCrypto
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire definitions checked against UxPlay lib/raop_rtp_mirror.c and byteutils.c. */
internal data class MirrorHeader(val payloadSize: Int, val type: Int, val flags: Int, val timestamp: Long) {
    companion object {
        fun parse(bytes: ByteArray): MirrorHeader {
            require(bytes.size == 128) { "Mirror header must be 128 bytes" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val size = buffer.int.toLong() and 0xffff_ffffL
            require(size <= H264Config.MAX_ACCESS_UNIT_BYTES) { "Mirror payload exceeds 8 MiB" }
            return MirrorHeader(size.toInt(), bytes[4].toInt() and 0xff,
                bytes[6].toInt() and 0xff, buffer.getLong(8))
        }
    }
}

/** Bound synchronously before SETUP succeeds; one continuous CTR stream per connection. */
class MirrorReceiver(
    private val peer: InetAddress,
    private val decryptor: SessionCrypto.MirrorDecryptor,
    private val clock: SessionClock,
    private val video: VideoSink,
    private val sessionId: Long,
    private val onFrameQueued: () -> Unit,
    private val onFailure: (String) -> Unit,
    requestedPort: Int = 7100,
    private val onIdr: ((VideoConfig, ByteArray) -> Unit)? = null,
) : AutoCloseable {
    private val listener = ServerSocket()
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var config: VideoConfig? = null
    private var firstIdr = false
    private var firstQueued = false

    val port: Int get() = listener.localPort

    init {
        try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(requestedPort), 2)
            log("mirror TCP socket bound/listening port=$port before SETUP response")
        } catch (error: Exception) {
            listener.close()
            decryptor.close()
            throw error
        }
    }

    @Synchronized
    fun start() {
        check(!listener.isClosed) { "Mirror receiver is closed" }
        if (running) return
        running = true
        thread = Thread(::receiveLoop, "FossMirrorTV-Mirror-$sessionId").apply { isDaemon = true; start() }
    }

    private fun receiveLoop() {
        try {
            var client: Socket? = null
            while (running && client == null) {
                val accepted = listener.accept()
                if (accepted.inetAddress != peer) {
                    accepted.close()
                    continue
                }
                client = accepted
            }
            if (client == null || !running) { client?.close(); return }
            socket = client
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = 20_000
            log("AirPlay mirror TCP client connected peer=${peer.hostAddress}:${client.port}")
            val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
            var logged = 0
            while (running) {
                val headerBytes = readHeader(input)
                if (headerBytes == null) {
                    log("mirror TCP client closed cleanly between packets")
                    break
                }
                val header = MirrorHeader.parse(headerBytes)
                val payload = readExactly(input, header.payloadSize)
                if (logged++ < 16) {
                    log("mirror packet header: type=${header.type} flags=${header.flags} bytes=${payload.size}")
                }
                when (header.type) {
                    0 -> receiveVideo(header, payload)
                    1 -> {
                        require(payload.isNotEmpty()) { "HEVC/empty codec data is unsupported" }
                        val parsed = H264Config.parseAvcc(payload)
                        require(video.configure(parsed)) { "AVC configuration rejected" }
                        config = parsed
                        log("codec_data AVC ${parsed.width}x${parsed.height} SPS=${parsed.sps.size} PPS=${parsed.pps.size}")
                        log("first_sps_seen first_pps_seen")
                    }
                    2, 5 -> Unit // Unencrypted keepalive and stream report, not video.
                    else -> log("unhandled mirror packet type=${header.type} bytes=${payload.size}")
                }
            }
        } catch (error: Exception) {
            if (running) {
                Log.w(TAG, "session=$sessionId mirror stopped: ${error.message}")
                onFailure(error.message ?: "Mirror transport failed")
            }
        } finally {
            running = false
            socket?.close()
            listener.close()
            decryptor.close()
            log("mirror receiver stopped")
        }
    }

    private fun receiveVideo(header: MirrorHeader, encrypted: ByteArray) {
        // Consume every ciphertext byte, even while the Surface is detached or frames are dropped.
        val clear = decryptor.decrypt(encrypted)
        try {
            val active = config ?: return
            val unit = video.prepareAccessUnit(clear, active.nalLengthSize)
            if (!unit.hasVcl) return
            if (unit.isIdr) {
                if (!firstIdr) {
                    firstIdr = true
                    log("first_idr_seen decryptedBytes=${clear.size}")
                }
                // An opt-in diagnostic can be armed after the sender's blank intro.
                onIdr?.invoke(active, unit.annexB)
            }
            val pts = clock.videoPresentationTimeUs(header.timestamp)
            if (video.enqueuePrepared(unit, pts) && !firstQueued) {
                firstQueued = true
                log("first_frame_queued ptsUs=$pts")
                onFrameQueued()
            }
        } finally {
            clear.fill(0)
        }
    }

    override fun close() {
        running = false
        listener.close()
        socket?.close()
        val worker = thread
        if (worker != null && Thread.currentThread() !== worker) {
            // A full decoder queue can pause the TCP producer even after its socket closes.
            worker.interrupt()
            worker.join(2_000)
            check(!worker.isAlive) { "Mirror worker did not stop" }
        }
        decryptor.close()
    }

    private fun log(message: String) = Log.i(TAG, "session=$sessionId $message")

    companion object {
        private const val TAG = "FossAirPlay"

        internal fun readHeader(input: InputStream): ByteArray? =
            readFramePart(input, 128, "header", allowCleanEof = true)

        internal fun readExactly(input: InputStream, size: Int): ByteArray =
            checkNotNull(readFramePart(input, size, "payload", allowCleanEof = false))

        private fun readFramePart(input: InputStream, size: Int, part: String, allowCleanEof: Boolean): ByteArray? {
            require(size >= 0) { "Mirror read size must not be negative" }
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                if (count < 0) {
                    if (allowCleanEof && offset == 0) return null
                    throw EOFException("Truncated mirror $part: received $offset of $size bytes")
                }
                check(count > 0) { "Mirror input made no progress" }
                offset += count
            }
            return bytes
        }
    }
}
