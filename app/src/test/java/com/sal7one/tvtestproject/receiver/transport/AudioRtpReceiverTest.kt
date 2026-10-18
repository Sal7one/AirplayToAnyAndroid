package com.sal7one.tvtestproject.receiver.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AudioRtpReceiverTest {
    private fun wire(sequence: Int = 0xffff, timestamp: Long = 0xfffffff0L,
        payload: ByteArray = byteArrayOf(0x8c.toByte(), 1, 2, 3)): ByteArray =
        ByteBuffer.allocate(12 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x80.toByte()).put(0xe0.toByte()).putShort(sequence.toShort())
            .putInt(timestamp.toInt()).putInt(0x12345678).put(payload).array()

    private fun packet(sequence: Int) = RaopRtpPacket(sequence, sequence * 480L, 0L, byteArrayOf(1))

    @Test fun parsesUnsignedSequenceTimestampAndDirectCodecPayload() {
        val parsed = RaopRtpPacket.parse(wire())!!
        assertEquals(65535, parsed.sequence)
        assertEquals(0xfffffff0L, parsed.timestamp)
        assertEquals(0x12345678L, parsed.ssrc)
        assertArrayEquals(byteArrayOf(0x8c.toByte(), 1, 2, 3), parsed.payload)
    }

    @Test fun parsesCsrcExtensionAndPaddingWithoutLeakingHeaders() {
        val basic = wire(payload = byteArrayOf(11, 22, 33))
        val extended = ByteArray(12 + 4 + 4 + 8 + 3 + 4)
        basic.copyInto(extended, 0, 0, 12)
        extended[0] = 0xb1.toByte()
        extended[16] = 0xbe.toByte()
        extended[17] = 0xde.toByte()
        extended[19] = 2
        basic.copyInto(extended, 28, 12)
        extended[extended.lastIndex] = 4
        assertArrayEquals(byteArrayOf(11, 22, 33), RaopRtpPacket.parse(extended)!!.payload)
    }

    @Test fun rejectsMalformedHeadersAndPadding() {
        assertNull(RaopRtpPacket.parse(ByteArray(11)))
        assertNull(RaopRtpPacket.parse(wire().also { it[0] = 0x40 }))
        assertNull(RaopRtpPacket.parse(wire().also { it[1] = 97 }))
        assertNull(RaopRtpPacket.parse(wire().also { it[0] = 0x8f.toByte() }))
        assertNull(RaopRtpPacket.parse(wire().also { it[0] = 0x90.toByte(); it[15] = 0xff.toByte() }))
        assertNull(RaopRtpPacket.parse(wire().also { it[0] = 0xa0.toByte(); it[15] = 0 }))
        assertNull(RaopRtpPacket.parse(wire().also { it[0] = 0xa0.toByte(); it[15] = 5 }))
        assertNull(RaopRtpPacket.parse(wire(), 16, 1))
        assertNull(RaopRtpPacket.parse(wire(), -1))
    }

    @Test fun handlesNoDataAndEmptyPayloads() {
        assertTrue(RaopRtpPacket.parse(wire(payload = byteArrayOf(0, 0x68, 0x34, 0)))!!.isNoData())
        assertEquals(0, RaopRtpPacket.parse(wire(payload = byteArrayOf()))!!.payload.size)
    }

    @Test fun syncUsesFirstRtpAndBigEndianNtpAtUxPlayOffsets() {
        val sync = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .put(0x90.toByte()).put(0xd4.toByte()).putShort(4)
            .putInt(0x11223344).putLong(0x0102030405060708L).putInt(0x1122508d).array()
        val parsed = RaopControlPacket.parse(sync) as RaopControlPacket.Sync
        assertEquals(0x11223344L, parsed.rtp)
        assertEquals(0x0102030405060708L, parsed.ntp)
        assertEquals(0x1122508dL, parsed.nextRtp)
        assertNull(RaopControlPacket.parse(sync, 19))
    }

    @Test fun unwrapsResentRtpAtOffsetFour() {
        val wrapped = byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 7) + wire(42)
        val parsed = RaopControlPacket.parse(wrapped) as RaopControlPacket.Resent
        assertEquals(42, parsed.packet.sequence)
        assertArrayEquals(byteArrayOf(0x8c.toByte(), 1, 2, 3), parsed.packet.payload)
        assertNull(RaopControlPacket.parse(wrapped, 8))
    }

    @Test fun resendRequestHasSourceSupportedEightByteLayout() {
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xd5.toByte(), 0x12, 0x34,
            0xff.toByte(), 0xff.toByte(), 0, 3), RaopControlPacket.resendRequest(0x1234, 65535, 3))
        assertEquals(0, unsigned16(RaopControlPacket.resendRequest(65536, 0, 1), 2))
    }

    @Test fun reorderDedupAndLateSuppressionAcrossSequenceWrap() {
        val buffer = RaopJitterBuffer()
        assertTrue(buffer.offer(packet(65535), 0))
        assertTrue(buffer.offer(packet(1), 1000))
        assertTrue(buffer.offer(packet(0), 2000))
        assertFalse(buffer.offer(packet(1), 3000))
        assertNull(buffer.poll(19_999))
        assertEquals(65535, buffer.poll(20_000)!!.sequence)
        assertEquals(0, buffer.poll(20_000)!!.sequence)
        assertEquals(1, buffer.poll(20_000)!!.sequence)
        assertNull(buffer.poll(20_000))
        assertFalse(buffer.offer(packet(65535), 21_000))
    }

    @Test fun reordersInitialPacketBeforeStartupDeadline() {
        val buffer = RaopJitterBuffer()
        buffer.offer(packet(12), 0)
        buffer.offer(packet(10), 1000)
        buffer.offer(packet(11), 2000)
        assertEquals(10, buffer.poll(20_000)!!.sequence)
        assertEquals(11, buffer.poll(20_000)!!.sequence)
        assertEquals(12, buffer.poll(20_000)!!.sequence)
    }

    @Test fun startupBufferingIsIncludedInFirstPresentationDeadline() {
        val buffer = RaopJitterBuffer()
        val clock = SessionClock()
        buffer.offer(packet(1), 1_000_000)
        assertNull(buffer.poll(1_019_999))
        val first = buffer.poll(1_020_000)!!
        assertEquals(1_000_000L, buffer.lastPolledArrivalUs)
        assertEquals(1_120_000L, clock.audioPresentationTimeUs(first.timestamp, 48000,
            buffer.lastPolledArrivalUs))
    }

    @Test fun requestsMissingRangeAndThrottlesRetries() {
        val buffer = RaopJitterBuffer()
        buffer.flush(65535)
        buffer.offer(packet(2), 0)
        assertNull(buffer.resendDue(4999))
        assertEquals(RaopJitterBuffer.Missing(65535, 3), buffer.resendDue(5000))
        assertNull(buffer.resendDue(24_999))
        assertEquals(RaopJitterBuffer.Missing(65535, 3), buffer.resendDue(25_000))
        assertNull(buffer.poll(59_999))
        assertEquals(2, buffer.poll(60_000)!!.sequence)
        assertNull(buffer.resendDue(60_001))
    }

    @Test fun resendFillsGapBeforeDeadline() {
        val buffer = RaopJitterBuffer()
        buffer.flush(9)
        buffer.offer(packet(10), 0)
        assertNull(buffer.poll(20_000))
        buffer.offer(packet(9), 30_000)
        assertEquals(9, buffer.poll(30_000)!!.sequence)
        assertEquals(10, buffer.poll(30_000)!!.sequence)
    }

    @Test fun overflowAndFlushKeepBufferBounded() {
        val buffer = RaopJitterBuffer(16)
        buffer.offer(packet(0), 0)
        for (seq in 1..15) buffer.offer(packet(seq), seq.toLong())
        assertEquals(16, buffer.size)
        buffer.offer(packet(16), 1000)
        assertEquals(1, buffer.size)
        assertEquals(16, buffer.poll(21_000)!!.sequence)
        buffer.flush(100)
        assertFalse(buffer.offer(packet(99), 30_000))
        buffer.offer(packet(100), 30_000)
        assertEquals(100, buffer.poll(50_000)!!.sequence)
        buffer.flush()
        assertEquals(0, buffer.size)
        assertTrue(buffer.offer(packet(1), 60_000))
    }

    @Test fun decryptsCbcBlocksWithJcaAndPreservesUnencryptedTailAndPerPacketIv() {
        val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        val iv = IvParameterSpec(ByteArray(16) { (31 - it).toByte() })
        val plaintext = ByteArray(32) { (0x8c + it).toByte() }
        val clearTail = byteArrayOf(91, 92, 93)
        val encryption = Cipher.getInstance("AES/CBC/NoPadding")
        encryption.init(Cipher.ENCRYPT_MODE, key, iv)
        val payload = encryption.doFinal(plaintext) + clearTail
        var invocations = 0
        val decrypt: (ByteArray) -> ByteArray = { blocks ->
            invocations++
            assertEquals(32, blocks.size)
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, iv)
                doFinal(blocks)
            }
        }
        repeat(2) { assertArrayEquals(plaintext + clearTail, AudioRtpReceiver.decryptPayload(payload, decrypt)) }
        assertEquals(2, invocations)
        assertArrayEquals(clearTail, AudioRtpReceiver.decryptPayload(clearTail) { fail("No full AES block"); it })
    }

    @Test(expected = IOException::class)
    fun rejectsDecryptCallbackThatReturnsWrongLength() {
        AudioRtpReceiver.decryptPayload(ByteArray(17)) { ByteArray(15) }
    }

    @Test fun parserRejectsOrBoundsDeterministicMalformedInputs() {
        val random = java.util.Random(42)
        repeat(5000) {
            val bytes = ByteArray(random.nextInt(512)).also { random.nextBytes(it) }
            val packet = RaopRtpPacket.parse(bytes)
            if (packet != null) {
                assertTrue(packet.sequence in 0..65535)
                assertTrue(packet.payload.size <= bytes.size - 12)
            }
            RaopControlPacket.parse(bytes)
        }
    }
}
