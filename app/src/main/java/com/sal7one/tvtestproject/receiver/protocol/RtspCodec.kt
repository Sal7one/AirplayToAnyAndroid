package com.sal7one.tvtestproject.receiver.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale

data class RtspRequest(
    val method: String,
    val path: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

data class RtspResponse(
    val statusCode: Int = 200,
    val reason: String = "OK",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val version: String = "RTSP/1.0",
    val cSeq: String? = null,
    val contentType: String? = null,
)

/**
 * No private read-ahead buffer: every read stops exactly at this request's boundary,
 * including when a socket read contains pipelined HTTP/RTSP requests.
 * Generic InputStreams must have a bounded blocking read (e.g. socket.soTimeout).
 * read(Socket) installs that timeout automatically, after a separate first-byte idle wait.
 * A timeout/parse error makes the
 * current connection unrecoverable; the caller must close it, not retry read().
 */
object RtspCodec {
    class ProtocolException(message: String) : IOException(message)

    data class Limits(
        val maxHeaderBytes: Int = 32 * 1024,
        val maxLineBytes: Int = 8 * 1024,
        val maxHeaders: Int = 100,
        val maxBodyBytes: Int = 2 * 1024 * 1024,
        val requestTimeoutMillis: Long = 15_000,
        val readTimeoutMillis: Int = 5_000,
        val idleTimeoutMillis: Int = 0,
    ) {
        init {
            require(maxHeaderBytes > 0 && maxLineBytes > 0 && maxHeaders > 0 && maxBodyBytes >= 0)
            require(requestTimeoutMillis in 1..3_600_000 && readTimeoutMillis > 0 && idleTimeoutMillis >= 0)
        }
    }

    private val versions = setOf("RTSP/1.0", "HTTP/1.0", "HTTP/1.1")
    private fun token(value: String): Boolean = value.isNotEmpty() && value.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
    }

    fun read(socket: Socket, limits: Limits = Limits()): RtspRequest? {
        return read(socket, socket.getInputStream(), limits)
    }

    /** Reuse one BufferedInputStream for the connection; do not replace it between requests. */
    fun read(socket: Socket, input: InputStream, limits: Limits = Limits()): RtspRequest? =
        readInternal(input, limits) { socket.soTimeout = it }

    fun read(input: InputStream, limits: Limits = Limits()): RtspRequest? = readInternal(input, limits) { }

    private fun readInternal(input: InputStream, limits: Limits, setTimeout: (Int) -> Unit): RtspRequest? {
        setTimeout(limits.idleTimeoutMillis)
        val firstByte = input.read()
        if (firstByte == -1) return null
        val deadline = System.nanoTime() + limits.requestTimeoutMillis * 1_000_000
        var firstPending = true
        var headerBytes = 0
        fun checkTime() {
            if (System.nanoTime() - deadline >= 0) throw SocketTimeoutException("Request deadline exceeded")
        }
        fun beforeRead() {
            checkTime()
            val remainingMillis = ((deadline - System.nanoTime() + 999_999) / 1_000_000).coerceAtLeast(1)
            setTimeout(minOf(limits.readTimeoutMillis.toLong(), remainingMillis).toInt())
        }
        fun byte(): Int {
            val result = if (firstPending) {
                firstPending = false
                firstByte
            } else {
                beforeRead()
                input.read()
            }
            checkTime()
            if (++headerBytes > limits.maxHeaderBytes) throw ProtocolException("Request headers too large")
            return result
        }
        fun line(allowEof: Boolean = false): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = byte()
                if (next == -1) {
                    if (allowEof && bytes.size() == 0) return null
                    throw EOFException("Truncated request headers")
                }
                if (next == 13) {
                    if (byte() != 10) throw ProtocolException("Expected CRLF")
                    return bytes.toString("ISO-8859-1")
                }
                if (next == 10 || next == 0 || next < 32 && next != 9 || next == 127) {
                    throw ProtocolException("Invalid request header byte")
                }
                if (bytes.size() >= limits.maxLineBytes) throw ProtocolException("Request line too large")
                bytes.write(next)
            }
        }

        val requestLine = line(allowEof = true) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !token(parts[0]) || parts[1].isEmpty() ||
            parts[1].any { it.code <= 32 || it.code >= 127 } || parts[2] !in versions) {
            throw ProtocolException("Invalid request line")
        }
        val headers = linkedMapOf<String, String>()
        while (true) {
            val current = checkNotNull(line())
            if (current.isEmpty()) break
            if (headers.size >= limits.maxHeaders) throw ProtocolException("Too many request headers")
            val colon = current.indexOf(':')
            if (colon <= 0 || !token(current.substring(0, colon))) throw ProtocolException("Invalid request header")
            val name = current.substring(0, colon).lowercase(Locale.ROOT)
            val value = current.substring(colon + 1).trim(' ', '\t')
            if (headers.put(name, value) != null) throw ProtocolException("Duplicate request header")
        }
        if ("transfer-encoding" in headers) throw ProtocolException("Transfer-Encoding is unsupported")
        val length = headers["content-length"]?.let {
            if (it.isEmpty() || it.any { ch -> ch !in '0'..'9' }) throw ProtocolException("Invalid Content-Length")
            it.toLongOrNull() ?: throw ProtocolException("Content-Length overflow")
        } ?: 0
        if (length > limits.maxBodyBytes) throw ProtocolException("Request body too large")
        headers["cseq"]?.let {
            if (it.isEmpty() || it.length > 20 || it.any { ch -> ch !in '0'..'9' }) throw ProtocolException("Invalid CSeq")
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            beforeRead()
            val count = input.read(body, offset, body.size - offset)
            checkTime()
            if (count < 0) throw EOFException("Truncated request body")
            if (count == 0) throw IOException("Input stream made no progress")
            offset += count
        }
        return RtspRequest(parts[0], parts[1], parts[2], headers, body)
    }

    fun write(output: OutputStream, response: RtspResponse) {
        require(response.version in versions && response.statusCode in 100..599) { "Invalid response status" }
        fun validValue(value: String) = value.all { it.code in 32..126 || it == '\t' }
        require(response.reason.isNotEmpty() && validValue(response.reason)) { "Invalid response reason" }
        val headers = linkedMapOf<String, Pair<String, String>>()
        response.headers.forEach { (name, value) ->
            require(token(name) && validValue(value)) { "Invalid response header" }
            val lower = name.lowercase(Locale.ROOT)
            require(lower !in headers && lower != "transfer-encoding") { "Duplicate/unsupported response header" }
            headers[lower] = name to value
        }
        response.cSeq?.let {
            require(it.isNotEmpty() && it.length <= 20 && it.all { ch -> ch in '0'..'9' }) { "Invalid response CSeq" }
            headers["cseq"] = "CSeq" to it
        }
        response.contentType?.let {
            require(it.isNotEmpty() && validValue(it)) { "Invalid response Content-Type" }
            headers["content-type"] = "Content-Type" to it
        }
        if (response.body.isNotEmpty() && "content-type" !in headers) {
            headers["content-type"] = "Content-Type" to "application/octet-stream"
        }
        headers["content-length"] = "Content-Length" to response.body.size.toString()
        val head = buildString {
            append(response.version).append(' ').append(response.statusCode).append(' ').append(response.reason).append("\r\n")
            headers.values.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        require(head.length <= 32 * 1024) { "Response headers too large" }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }
}
