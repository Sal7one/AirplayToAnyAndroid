package com.sal7one.tvtestproject.receiver.transport

import com.sal7one.tvtestproject.receiver.AudioCodec
import com.sal7one.tvtestproject.receiver.AudioConfig
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * RAOP type-96 UDP audio (UxPlay lib/raop_rtp.c and lib/raop_buffer.c).
 * decrypt receives only the complete AES-CBC blocks; it MUST reset to the negotiated IV each call
 * and return exactly the same number of bytes. The partial clear tail is appended here.
 * onFrame receives the raw codec frame, without invented AAC AU/ADTS headers, and monotonic PTS.
 * Callbacks must be nonblocking; exceptions stop this receiver and are exposed through failure.
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_rtp.c
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_buffer.c
 */
class AudioRtpReceiver(
    private val peer: InetAddress,
    private val config: AudioConfig,
    private val clock: SessionClock,
    private val decrypt: (ByteArray) -> ByteArray,
    private val onFailure: (Throwable) -> Unit = {},
    private val onFrame: (ByteArray, Long) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val lifecycle = Any()
    private val dataSocket: DatagramSocket
    private val controlSocket: DatagramSocket
    private val jitter: RaopJitterBuffer
    private var remoteControl: InetSocketAddress? = null
    private var remoteDataPort: Int? = null
    private var ssrc: Long? = null
    private var controlSequence = 0
    private var frameDelivered = false
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile var failure: Throwable? = null
        private set

    val dataPort: Int get() = dataSocket.localPort
    val controlPort: Int get() = controlSocket.localPort

    init {
        require(config.sampleRate in 1..384_000 && config.samplesPerFrame > 0)
        val capacity = ((config.sampleRate.toLong() * clock.playoutDelayUs /
            (config.samplesPerFrame.toLong() * 1_000_000L)) + 8L).coerceIn(16L, 256L).toInt()
        jitter = RaopJitterBuffer(capacity)
        dataSocket = bindSocket()
        try {
            controlSocket = bindSocket()
        } catch (error: Throwable) {
            dataSocket.close()
            throw error
        }
    }

    /** Optional negotiated sender control port. Otherwise learned from its first valid control packet. */
    fun setRemoteControlPort(port: Int) {
        require(port in 1..65535)
        synchronized(lock) {
            check(!closed)
            remoteControl = InetSocketAddress(peer, port)
        }
    }

    fun start() {
        synchronized(lifecycle) {
            check(!closed) { "Audio receiver is closed" }
            if (running) return
            running = true
            controlThread = Thread({ controlLoop() }, "raop-control-$controlPort").apply { isDaemon = true }
            dataThread = Thread({ dataLoop() }, "raop-audio-$dataPort").apply { isDaemon = true }
            controlThread!!.start()
            dataThread!!.start()
        }
    }

    /** seq is the next sequence to accept, inclusive, as in UxPlay raop_buffer_flush. */
    fun flush(seq: Int? = null) {
        require(seq == null || seq in 0..65535)
        synchronized(lock) {
            jitter.flush(seq)
            frameDelivered = false
            clock.resetAudio()
        }
    }

    private fun bindSocket(): DatagramSocket {
        val socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 5
            socket.receiveBufferSize = 256 * 2048
            return socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun dataLoop() {
        val bytes = ByteArray(65536)
        val datagram = DatagramPacket(bytes, bytes.size)
        try {
            while (running) {
                try {
                    datagram.length = bytes.size
                    dataSocket.receive(datagram)
                    if (datagram.address == peer) {
                        val packet = RaopRtpPacket.parse(bytes, datagram.length)
                        if (packet != null) synchronized(lock) {
                            if (running && (remoteDataPort == null || remoteDataPort == datagram.port) &&
                                acceptSsrc(packet.ssrc)
                            ) {
                                remoteDataPort = datagram.port
                                jitter.offer(packet, clock.nowUs())
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Tick even with no packets so a lost sequence cannot stall the session forever.
                }
                synchronized(lock) {
                    if (running) drainAndResend(clock.nowUs())
                }
            }
        } catch (error: Exception) {
            if (running) fail(error)
        }
    }

    private fun controlLoop() {
        val bytes = ByteArray(65536)
        val datagram = DatagramPacket(bytes, bytes.size)
        try {
            while (running) {
                try {
                    datagram.length = bytes.size
                    controlSocket.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (datagram.address != peer) continue
                val packet = RaopControlPacket.parse(bytes, datagram.length) ?: continue
                synchronized(lock) {
                    if (!running) return
                    val remote = remoteControl
                    if (remote != null && remote.port != datagram.port) return@synchronized
                    remoteControl = InetSocketAddress(peer, datagram.port)
                    when (packet) {
                        is RaopControlPacket.Sync -> clock.updateAudioSync(packet.rtp, packet.ntp, clock.nowUs())
                        is RaopControlPacket.Resent -> if (acceptSsrc(packet.packet.ssrc)) {
                            jitter.offer(packet.packet, clock.nowUs())
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (running) fail(error)
        }
    }

    private fun acceptSsrc(value: Long): Boolean {
        if (ssrc == null) ssrc = value
        return ssrc == value
    }

    private fun drainAndResend(nowUs: Long) {
        while (running) {
            val packet = jitter.poll(nowUs) ?: break
            if (packet.payload.isEmpty() || (config.codec == AudioCodec.AAC_ELD && packet.isNoData())) continue
            val frame = decryptPayload(packet.payload, decrypt)
            val referenceUs = if (frameDelivered) nowUs else jitter.lastPolledArrivalUs
            val ptsUs = clock.audioPresentationTimeUs(packet.timestamp, config.sampleRate, referenceUs)
            frameDelivered = true
            onFrame(frame, ptsUs)
        }
        val remote = remoteControl ?: return
        val missing = jitter.resendDue(nowUs) ?: return
        val request = RaopControlPacket.resendRequest(controlSequence++, missing.first, missing.count)
        controlSocket.send(DatagramPacket(request, request.size, remote))
    }

    private fun fail(error: Throwable) {
        if (!running) return
        failure = error
        running = false
        closed = true
        dataSocket.close()
        controlSocket.close()
        onFailure(error)
    }

    override fun close() {
        val threads = synchronized(lifecycle) {
            closed = true
            running = false
            dataSocket.close()
            controlSocket.close()
            listOfNotNull(dataThread, controlThread)
        }
        for (thread in threads) if (thread != Thread.currentThread()) {
            try {
                thread.join(1000L)
                check(!thread.isAlive) { "Audio RTP worker did not stop" }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Audio RTP shutdown interrupted")
            }
        }
        synchronized(lock) { jitter.flush() }
    }

    companion object {
        internal fun decryptPayload(payload: ByteArray, decrypt: (ByteArray) -> ByteArray): ByteArray {
            val encryptedLength = payload.size / 16 * 16
            if (encryptedLength == 0) return payload.copyOf()
            val clear = decrypt(payload.copyOfRange(0, encryptedLength))
            if (clear.size != encryptedLength) throw IOException("AES-CBC callback changed frame length")
            return payload.copyOf().also { clear.copyInto(it) }
        }
    }
}

internal data class RaopRtpPacket(
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray,
) {
    fun isNoData(): Boolean = payload.contentEquals(byteArrayOf(0x00, 0x68, 0x34, 0x00))

    companion object {
        fun parse(bytes: ByteArray, length: Int = bytes.size, offset: Int = 0): RaopRtpPacket? {
            if (offset < 0 || length < 12 || offset > bytes.size - length) return null
            val header = bytes[offset].toInt() and 0xff
            if (header ushr 6 != 2 || (bytes[offset + 1].toInt() and 0x7f) != 96) return null
            val end = offset + length
            var payloadOffset = offset + 12 + (header and 0x0f) * 4
            if (payloadOffset > end) return null
            if (header and 0x10 != 0) {
                if (end - payloadOffset < 4) return null
                val extensionWords = unsigned16(bytes, payloadOffset + 2)
                payloadOffset += 4 + extensionWords * 4
                if (payloadOffset > end) return null
            }
            var payloadEnd = end
            if (header and 0x20 != 0) {
                val padding = bytes[end - 1].toInt() and 0xff
                if (padding == 0 || padding > end - payloadOffset) return null
                payloadEnd -= padding
            }
            return RaopRtpPacket(unsigned16(bytes, offset + 2), unsigned32(bytes, offset + 4),
                unsigned32(bytes, offset + 8), bytes.copyOfRange(payloadOffset, payloadEnd))
        }
    }
}

internal sealed class RaopControlPacket {
    data class Sync(val rtp: Long, val ntp: Long, val nextRtp: Long) : RaopControlPacket()
    data class Resent(val packet: RaopRtpPacket) : RaopControlPacket()

    companion object {
        fun parse(bytes: ByteArray, length: Int = bytes.size): RaopControlPacket? {
            if (length < 4 || length > bytes.size || (bytes[0].toInt() and 0xc0) != 0x80) return null
            return when (bytes[1].toInt() and 0x7f) {
                0x54 -> if (length >= 20 && SessionClock.readNtp(bytes, 8) != 0L) {
                    Sync(unsigned32(bytes, 4), SessionClock.readNtp(bytes, 8), unsigned32(bytes, 16))
                } else null
                0x56 -> RaopRtpPacket.parse(bytes, length - 4, 4)?.let { Resent(it) }
                else -> null
            }
        }

        fun resendRequest(requestSequence: Int, firstMissing: Int, count: Int): ByteArray {
            require(firstMissing in 0..65535 && count in 1..65535)
            return byteArrayOf(0x80.toByte(), 0xd5.toByte(), (requestSequence ushr 8).toByte(),
                requestSequence.toByte(), (firstMissing ushr 8).toByte(), firstMissing.toByte(),
                (count ushr 8).toByte(), count.toByte())
        }
    }
}

internal fun unsigned16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

internal fun unsigned32(bytes: ByteArray, offset: Int): Long =
    (unsigned16(bytes, offset).toLong() shl 16) or unsigned16(bytes, offset + 2).toLong()

/** Bounded wrap-aware reorder buffer; missing packets expire after 60 ms, not 256 frames. */
internal class RaopJitterBuffer(
    private val capacity: Int = 32,
    private val startupHoldUs: Long = 20_000L,
    private val gapWaitUs: Long = 60_000L,
) {
    private data class Entry(val packet: RaopRtpPacket, val arrivedUs: Long)
    data class Missing(val first: Int, val count: Int)
    private val pending = HashMap<Int, Entry>()
    private var expected: Int? = null
    private var firstArrivalUs: Long? = null
    private var gapSinceUs: Long? = null
    private var lastResendUs: Long? = null
    private var emitted = false
    private var fixedSequence = false
    val size: Int get() = pending.size
    var lastPolledArrivalUs: Long = 0L
        private set

    init { require(capacity in 2..256 && startupHoldUs >= 0L && gapWaitUs > 0L) }

    fun offer(packet: RaopRtpPacket, nowUs: Long): Boolean {
        if (expected == null) expected = packet.sequence
        var distance = sequenceDifference(packet.sequence, expected!!)
        if (distance < 0) {
            if (emitted || fixedSequence || -distance >= capacity ||
                (firstArrivalUs != null && nowUs - firstArrivalUs!! >= startupHoldUs)
            ) return false
            if (pending.keys.any { sequenceDifference(it, packet.sequence) >= capacity }) return false
            expected = packet.sequence
            distance = 0
        }
        if (distance >= capacity) {
            // UxPlay also flushes on sequence-window overflow; memory and loss recovery stay bounded.
            flush(packet.sequence)
        }
        if (pending.containsKey(packet.sequence)) return false
        if (firstArrivalUs == null) firstArrivalUs = nowUs
        pending[packet.sequence] = Entry(packet, nowUs)
        return true
    }

    fun poll(nowUs: Long): RaopRtpPacket? {
        if (pending.isEmpty()) return null
        if (nowUs - (firstArrivalUs ?: nowUs) < startupHoldUs) return null
        var sequence = expected ?: return null
        var entry = pending.remove(sequence)
        if (entry == null) {
            val next = pending.values.minBy { sequenceDifference(it.packet.sequence, sequence) }
            val since = gapSinceUs ?: next.arrivedUs.also { gapSinceUs = it }
            if (nowUs - since < gapWaitUs) return null
            sequence = next.packet.sequence
            entry = pending.remove(sequence) ?: return null
        }
        expected = (sequence + 1) and 0xffff
        lastPolledArrivalUs = entry.arrivedUs
        emitted = true
        gapSinceUs = null
        lastResendUs = null
        return entry.packet
    }

    fun resendDue(nowUs: Long): Missing? {
        val sequence = expected ?: return null
        if (pending.isEmpty() || pending.containsKey(sequence)) return null
        val next = pending.values.minBy { sequenceDifference(it.packet.sequence, sequence) }
        val since = gapSinceUs ?: next.arrivedUs.also { gapSinceUs = it }
        if (nowUs - since < 5_000L || nowUs - since >= gapWaitUs) return null
        val last = lastResendUs
        if (last != null && nowUs - last < 20_000L) return null
        lastResendUs = nowUs
        return Missing(sequence, sequenceDifference(next.packet.sequence, sequence))
    }

    fun flush(seq: Int? = null) {
        require(seq == null || seq in 0..65535)
        pending.clear()
        lastPolledArrivalUs = 0L
        expected = seq
        firstArrivalUs = null
        gapSinceUs = null
        lastResendUs = null
        emitted = false
        fixedSequence = seq != null
    }

    companion object {
        fun sequenceDifference(end: Int, start: Int): Int = (end - start).toShort().toInt()
    }
}
