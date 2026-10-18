package com.sal7one.tvtestproject.receiver.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * RAOP NTP timing: 32 bytes, type 0x52/0x53, BE Q32.32 timestamps.
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_ntp.c
 */
class TimingReceiver(
    private val peer: InetAddress,
    private val remotePort: Int,
    private val clock: SessionClock,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val lifecycle = Any()
    private val socket: DatagramSocket
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile var failure: Throwable? = null
        private set
    val port: Int get() = socket.localPort

    init {
        require(remotePort in 1..65535)
        socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 100
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    fun start() {
        synchronized(lifecycle) {
            check(!closed) { "Timing receiver is closed" }
            if (running) return
            running = true
            thread = Thread({ receiveLoop() }, "raop-timing-$port").apply { isDaemon = true; start() }
        }
    }

    private fun receiveLoop() {
        val bytes = ByteArray(129)
        val datagram = DatagramPacket(bytes, bytes.size)
        val pending = LinkedHashMap<Long, Long>()
        val seenTransmit = ArrayDeque<Long>()
        var lastRemoteTransmit = 0L
        var lastLocalReceive = 0L
        var nextRequestUs = clock.nowUs()
        var requestsSent = 0
        try {
            while (running) {
                val nowUs = clock.nowUs()
                if (nowUs >= nextRequestUs) {
                    val token = clock.localNtpTimestamp(nowUs)
                    val request = RaopTimingPacket.request(token, lastRemoteTransmit, lastLocalReceive)
                    pending[token] = nowUs
                    while (pending.size > 8) pending.remove(pending.keys.first())
                    socket.send(DatagramPacket(request, request.size, peer, remotePort))
                    requestsSent++
                    nextRequestUs = nowUs + if (requestsSent < 8) 250_000L else 3_000_000L
                }
                try {
                    datagram.length = bytes.size
                    socket.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val receivedAtUs = clock.nowUs()
                if (datagram.address != peer || datagram.port != remotePort) continue
                val packet = RaopTimingPacket.parse(bytes, datagram.length) ?: continue
                if (packet.isRequest) {
                    // Reply with distinct originate, receive and transmit stamps, not an echo.
                    val response = RaopTimingPacket.reply(packet,
                        clock.localNtpTimestamp(receivedAtUs), clock.localNtpTimestamp())
                    socket.send(DatagramPacket(response, response.size, peer, remotePort))
                } else {
                    val sentAtUs = pending.remove(packet.originate) ?: continue
                    if (packet.transmit in seenTransmit) continue
                    if (clock.observeTiming(packet.receive, packet.transmit, sentAtUs, receivedAtUs)) {
                        seenTransmit.addLast(packet.transmit)
                        if (seenTransmit.size > 8) seenTransmit.removeFirst()
                        lastRemoteTransmit = packet.transmit
                        lastLocalReceive = clock.localNtpTimestamp(receivedAtUs)
                    }
                }
            }
        } catch (error: Exception) {
            if (running) {
                failure = error
                running = false
                closed = true
                socket.close()
                onFailure(error)
            }
        }
    }

    override fun close() {
        val worker = synchronized(lifecycle) {
            closed = true
            running = false
            socket.close()
            thread
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(1000L)
                check(!worker.isAlive) { "Timing worker did not stop" }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Timing shutdown interrupted")
            }
        }
    }
}

internal data class RaopTimingPacket(
    val isRequest: Boolean,
    val sequence: Int,
    val originate: Long,
    val receive: Long,
    val transmit: Long,
) {
    companion object {
        fun parse(bytes: ByteArray, length: Int = bytes.size): RaopTimingPacket? {
            if (length !in 32..128 || length > bytes.size || bytes[0] != 0x80.toByte()) return null
            val type = bytes[1].toInt() and 0x7f
            if (type != 0x52 && type != 0x53) return null
            val transmit = SessionClock.readNtp(bytes, 24)
            // Shairport's RAOP request has zero timestamps; only a reply requires nonzero T3.
            if (type == 0x53 && transmit == 0L) return null
            return RaopTimingPacket(type == 0x52, unsigned16(bytes, 2), SessionClock.readNtp(bytes, 8),
                SessionClock.readNtp(bytes, 16), transmit)
        }

        fun request(transmit: Long, previousRemoteTransmit: Long = 0L,
            previousLocalReceive: Long = 0L): ByteArray = ByteArray(32).also {
            it[0] = 0x80.toByte()
            it[1] = 0xd2.toByte()
            // UxPlay sends the fixed timing sequence 0x0007; matching uses the T1 timestamp.
            it[3] = 0x07
            SessionClock.writeNtp(it, 8, previousRemoteTransmit)
            SessionClock.writeNtp(it, 16, previousLocalReceive)
            SessionClock.writeNtp(it, 24, transmit)
        }

        fun reply(request: RaopTimingPacket, receive: Long, transmit: Long): ByteArray {
            require(request.isRequest)
            return ByteArray(32).also {
                it[0] = 0x80.toByte()
                it[1] = 0xd3.toByte()
                it[2] = (request.sequence ushr 8).toByte()
                it[3] = request.sequence.toByte()
                SessionClock.writeNtp(it, 8, request.transmit)
                SessionClock.writeNtp(it, 16, receive)
                SessionClock.writeNtp(it, 24, transmit)
            }
        }
    }
}
