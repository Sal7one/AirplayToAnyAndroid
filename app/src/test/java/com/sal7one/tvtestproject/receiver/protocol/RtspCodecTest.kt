package com.sal7one.tvtestproject.receiver.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class RtspCodecTest {
    @Test fun readsExactBinaryBodyAndPreservesPipeline() {
        val body = byteArrayOf(0, 13, 10, -1, 36)
        val raw = "POST /pair-setup HTTP/1.1\r\nCSeq: 1\r\nContent-Length: 5\r\n\r\n".toByteArray() + body +
            "OPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\n".toByteArray()
        val input = ByteArrayInputStream(raw)
        val first = checkNotNull(RtspCodec.read(input))
        assertEquals("HTTP/1.1", first.version)
        assertEquals("1", first.header("CSEQ"))
        assertArrayEquals(body, first.body)
        val second = checkNotNull(RtspCodec.read(input))
        assertEquals("OPTIONS", second.method)
        assertEquals("*", second.path)
        assertEquals("2", second.header("cseq"))
        assertNull(RtspCodec.read(input))
    }

    @Test fun handlesFragmentedReadsWithoutReadingPastBody() {
        val wire = "SETUP rtsp://host/stream RTSP/1.0\r\nContent-Length: 4\r\n\r\nabcd".toByteArray()
        val input = object : ByteArrayInputStream(wire) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        assertArrayEquals("abcd".toByteArray(), checkNotNull(RtspCodec.read(input)).body)
    }

    @Test fun rejectsTruncationConflictingFramingInjectionAndLimits() {
        val bad = listOf(
            "POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 999999999999999999999\r\n\r\n",
            "POST / HTTP/1.1\r\nContent-Length: 1\r\ncontent-length: 2\r\n\r\nxx",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "OPTIONS * RTSP/1.0\r\n folded: bad\r\n\r\n",
            "OPTIONS * RTSP/1.0\n\n", "POST / HTTP/2.0\r\n\r\n",
            "OPTIONS * RTSP/1.0\r\nCSeq: a\r\n\r\n",
        )
        bad.forEach { assertThrows(RtspCodec.ProtocolException::class.java) { RtspCodec.read(ByteArrayInputStream(it.toByteArray())) } }
        assertThrows(EOFException::class.java) { RtspCodec.read(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\nx".toByteArray())) }
        assertThrows(EOFException::class.java) { RtspCodec.read(ByteArrayInputStream("OPT".toByteArray())) }
        assertThrows(RtspCodec.ProtocolException::class.java) {
            RtspCodec.read(ByteArrayInputStream("OPTIONS * RTSP/1.0\r\n\r\n".toByteArray()), RtspCodec.Limits(maxHeaderBytes = 10))
        }
        assertThrows(RtspCodec.ProtocolException::class.java) {
            RtspCodec.read(ByteArrayInputStream("POST / HTTP/1.0\r\nContent-Length: 2\r\n\r\nxx".toByteArray()), RtspCodec.Limits(maxBodyBytes = 1))
        }
    }

    @Test fun distinguishesIdleWaitFromInFlightDeadlineAndConfiguresSocket() {
        val source = "OPTIONS * RTSP/1.0\r\n\r\n".toByteArray()
        val input = object : ByteArrayInputStream(source) {
            var first = true
            override fun read(): Int { if (first) { first = false; Thread.sleep(60) }; return super.read() }
        }
        val timeouts = mutableListOf<Int>()
        val socket = object : Socket() {
            override fun getInputStream(): InputStream = input
            override fun setSoTimeout(timeout: Int) { timeouts.add(timeout) }
        }
        assertNotNull(RtspCodec.read(socket, RtspCodec.Limits(requestTimeoutMillis = 40, readTimeoutMillis = 5)))
        assertEquals(0, timeouts.first())
        assertTrue(timeouts.drop(1).isNotEmpty())
        assertTrue(timeouts.drop(1).all { it in 1..5 })
    }

    @Test fun timesOutAnInFlightBody() {
        val input = object : ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 1\r\n\r\nx".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(60); return super.read(b, off, len) }
        }
        assertThrows(SocketTimeoutException::class.java) { RtspCodec.read(input, RtspCodec.Limits(requestTimeoutMillis = 20)) }
    }

    @Test fun bufferedSocketOverloadPreservesPipelineAndAllowsDifferentIdlePolicies() {
        val raw = ByteArrayInputStream("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\nOPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\n".toByteArray())
        val input = BufferedInputStream(raw, 1024)
        val timeouts = mutableListOf<Int>()
        val socket = object : Socket() { override fun setSoTimeout(timeout: Int) { timeouts.add(timeout) } }
        assertEquals("1", checkNotNull(RtspCodec.read(socket, input, RtspCodec.Limits(idleTimeoutMillis = 30_000))).header("cseq"))
        assertEquals(30_000, timeouts.first())
        timeouts.clear()
        assertEquals("2", checkNotNull(RtspCodec.read(socket, input, RtspCodec.Limits(idleTimeoutMillis = 0))).header("cseq"))
        assertEquals(0, timeouts.first())
        assertNull(RtspCodec.read(socket, input))
    }

    @Test fun writesCorrectVersionCseqContentTypeAndActualContentLength() {
        val output = ByteArrayOutputStream()
        RtspCodec.write(output, RtspResponse(body = byteArrayOf(0, -1), version = "HTTP/1.1", cSeq = "003",
            contentType = Plist.CONTENT_TYPE, headers = mapOf("Content-Length" to "900")))
        val bytes = output.toByteArray()
        val head = String(bytes, Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("CSeq: 003"))
        assertTrue(head.contains("Content-Length: 2"))
        assertTrue(head.contains("Content-Type: ${Plist.CONTENT_TYPE}"))
        assertArrayEquals(byteArrayOf(0, -1), bytes.copyOfRange(bytes.size - 2, bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            RtspCodec.write(ByteArrayOutputStream(), RtspResponse(headers = mapOf("Foo" to "bar\r\nInjected: value")))
        }
    }
}
