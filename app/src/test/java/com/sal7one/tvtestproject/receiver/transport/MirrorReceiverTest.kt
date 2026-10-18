package com.sal7one.tvtestproject.receiver.transport

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MirrorReceiverTest {
    @Test fun mirrorHeaderUsesLittleEndianSizeAndTimestamp() {
        val raw = ByteArray(128)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(4096).put(1).put(16).put(22).put(1)
            .putLong(0x12345678abcdef01L)
        val parsed = MirrorHeader.parse(raw)
        assertEquals(4096, parsed.payloadSize)
        assertEquals(1, parsed.type)
        assertEquals(22, parsed.flags)
        assertEquals(0x12345678abcdef01L, parsed.timestamp)
    }

    @Test fun rejectsUnsignedOversizedLengthsBeforeAllocation() {
        val raw = ByteArray(128)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(-1)
        assertThrows(IllegalArgumentException::class.java) { MirrorHeader.parse(raw) }
        assertThrows(IllegalArgumentException::class.java) { MirrorHeader.parse(ByteArray(127)) }
    }

    @Test fun exactReadsDoNotConsumeFollowingFrame() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3), MirrorReceiver.readExactly(input, 3))
        assertArrayEquals(byteArrayOf(4, 5), MirrorReceiver.readExactly(input, 2))
        assertThrows(EOFException::class.java) { MirrorReceiver.readExactly(input, 1) }
    }

    @Test fun emptyStreamIsCleanEofBeforeHeader() {
        assertNull(MirrorReceiver.readHeader(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun completePacketCanEndCleanlyWithoutConsumingAnotherHeader() {
        val header = ByteArray(128)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(3)
        val payload = byteArrayOf(1, 2, 3)
        val input = ByteArrayInputStream(header + payload)

        assertArrayEquals(header, MirrorReceiver.readHeader(input))
        assertArrayEquals(payload, MirrorReceiver.readExactly(input, MirrorHeader.parse(header).payloadSize))
        assertNull(MirrorReceiver.readHeader(input))
    }

    @Test fun partialHeadersRemainErrorsWithExactByteCounts() {
        for (received in 1 until 128) {
            val error = assertThrows(EOFException::class.java) {
                MirrorReceiver.readHeader(ByteArrayInputStream(ByteArray(received)))
            }
            assertEquals("Truncated mirror header: received $received of 128 bytes", error.message)
        }
    }

    @Test fun missingOrPartialPayloadRemainsAnErrorAfterHeader() {
        for (received in 0 until 4) {
            val error = assertThrows(EOFException::class.java) {
                MirrorReceiver.readExactly(ByteArrayInputStream(ByteArray(received)), 4)
            }
            assertEquals("Truncated mirror payload: received $received of 4 bytes", error.message)
        }
    }

    @Test fun fragmentedReadsPreservePacketBoundaries() {
        val header = ByteArray(128) { it.toByte() }
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val input = object : ByteArrayInputStream(header + payload + header) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 3))
        }

        assertArrayEquals(header, MirrorReceiver.readHeader(input))
        assertArrayEquals(payload, MirrorReceiver.readExactly(input, payload.size))
        assertArrayEquals(header, MirrorReceiver.readHeader(input))
        assertNull(MirrorReceiver.readHeader(input))
    }

    @Test fun zeroLengthPayloadDoesNotConsumeNextHeader() {
        val header = ByteArray(128)
        val input = ByteArrayInputStream(header)

        assertArrayEquals(byteArrayOf(), MirrorReceiver.readExactly(input, 0))
        assertArrayEquals(header, MirrorReceiver.readHeader(input))
        assertNull(MirrorReceiver.readHeader(input))
    }
}
