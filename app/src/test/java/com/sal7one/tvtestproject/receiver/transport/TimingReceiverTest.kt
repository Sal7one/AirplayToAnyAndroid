package com.sal7one.tvtestproject.receiver.transport

import org.junit.Assert.*
import org.junit.Test

class TimingReceiverTest {
    @Test fun requestUsesUxPlayNtpLayoutAndBigEndianTimestamp() {
        val transmit = 0x0102030405060708L
        val request = RaopTimingPacket.request(transmit)
        assertEquals(32, request.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xd2.toByte(), 0, 7), request.copyOfRange(0, 4))
        assertArrayEquals(ByteArray(20), request.copyOfRange(4, 24))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), request.copyOfRange(24, 32))
        val parsed = RaopTimingPacket.parse(request)!!
        assertTrue(parsed.isRequest)
        assertEquals(transmit, parsed.transmit)
        assertEquals(7, parsed.sequence)
    }

    @Test fun responseIsRealNtpWithSeparateOriginReceiveAndTransmit() {
        val request = RaopTimingPacket.parse(RaopTimingPacket.request(1234))!!
        val reply = RaopTimingPacket.reply(request, 2345, 3456)
        assertEquals(0xd3, reply[1].toInt() and 0xff)
        val parsed = RaopTimingPacket.parse(reply)!!
        assertFalse(parsed.isRequest)
        assertEquals(request.sequence, parsed.sequence)
        assertEquals(1234L, parsed.originate)
        assertEquals(2345L, parsed.receive)
        assertEquals(3456L, parsed.transmit)
        assertFalse(reply.contentEquals(RaopTimingPacket.request(1234)))
    }

    @Test fun requestCanCarryPreviousTimingReferences() {
        val parsed = RaopTimingPacket.parse(RaopTimingPacket.request(1234, 2345, 3456))!!
        assertEquals(2345L, parsed.originate)
        assertEquals(3456L, parsed.receive)
    }

    @Test fun rejectsWrongTypesVersionsTruncationAndZeroTransmit() {
        val valid = RaopTimingPacket.request(1234)
        assertNull(RaopTimingPacket.parse(valid, 31))
        assertNull(RaopTimingPacket.parse(valid, 33))
        assertNull(RaopTimingPacket.parse(valid.copyOf().also { it[0] = 0x90.toByte() }))
        assertNull(RaopTimingPacket.parse(valid.copyOf().also { it[1] = 0xd4.toByte() }))
        assertNull(RaopTimingPacket.parse(valid.copyOf().also { it.fill(0, 24, 32); it[1] = 0xd3.toByte() }))
        assertNull(RaopTimingPacket.parse(valid.copyOf(129)))
    }

    @Test fun acceptsZeroTimestampRequestAndGeneratesRealTimingReply() {
        val request = RaopTimingPacket.parse(RaopTimingPacket.request(0))!!
        val response = RaopTimingPacket.parse(RaopTimingPacket.reply(request, 4567, 5678))!!
        assertFalse(response.isRequest)
        assertEquals(0L, response.originate)
        assertEquals(4567L, response.receive)
        assertEquals(5678L, response.transmit)
    }
}
