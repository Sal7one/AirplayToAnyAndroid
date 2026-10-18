package com.sal7one.tvtestproject.receiver.protocol

import org.junit.Assert.*
import org.junit.Test

internal fun protocolHex(value: String): ByteArray {
    val compact = value.filterNot { it.isWhitespace() }
    require(compact.length % 2 == 0) { "Invalid hex fixture length" }
    return compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class SessionCryptoTest {
    private val key = protocolHex("2b7e151628aed2a6abf7158809cf4f3c")
    private val plain = protocolHex("6bc1bee22e409f96e93d7e117393172a ae2d8a571e03ac9c9eb76fac45af8e51 30c81c46a35ce411e5fbc1191a0a52ef f69f2445df4f9b17ad2b417be66c3710")

    @Test fun audioCbcMatchesNistVectorResetsAndPreservesClearTail() {
        // NIST SP 800-38A F.2.1, AES-128 CBC.
        val iv = protocolHex("000102030405060708090a0b0c0d0e0f")
        val cipher = protocolHex("7649abac8119b246cee98e9b12e9197d 5086cb9b507219ee95db113a917678b2 73bed6b8e3c1743b7116e69e22229516 3ff1caa1681fac09120eca307586e1a7")
        val tail = byteArrayOf(1, 2, 3)
        SessionCrypto(key, iv).use {
            assertArrayEquals(plain + tail, it.decryptAudioPacket(cipher + tail))
            assertArrayEquals(plain + tail, it.decryptAudioPacket(cipher + tail))
            assertArrayEquals(tail, it.decryptAudioPacket(tail))
            assertArrayEquals(ByteArray(0), it.decryptAudioPacket(ByteArray(0)))
        }
    }

    @Test fun mirrorCtrMatchesNistVectorAcrossArbitraryPacketBoundaries() {
        // NIST SP 800-38A F.5.1, AES-128 CTR.
        val iv = protocolHex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val cipher = protocolHex("874d6191b620e3261bef6864990db6ce 9806f66b7970fdff8617187bb9fffdff 5ae4df3edbd5d35e5b4f09020db03eab 1e031dda2fbe03d1792170a0f3009cee")
        for (split in 0..cipher.size) {
            SessionCrypto.MirrorDecryptor(key, iv).use {
                val result = it.decrypt(cipher.copyOfRange(0, split)) + it.decrypt(cipher.copyOfRange(split, cipher.size))
                assertArrayEquals("Split at $split", plain, result)
            }
        }
        SessionCrypto.MirrorDecryptor(key, iv).use {
            val result = cipher.fold(ByteArray(0)) { output, byte -> output + it.decrypt(byteArrayOf(byte)) }
            assertArrayEquals(plain, result)
        }
    }

    @Test fun derivesKeysWithSha512AndUnsignedDecimalStreamId() {
        // Fixed independently using CPython hashlib.sha512, with key=00..0f, secret=00..1f.
        val audio = SessionCrypto.deriveAudioKey(ByteArray(16) { it.toByte() }, ByteArray(32) { it.toByte() })
        assertArrayEquals(protocolHex("210498799841dddb97e4614a4e6854a5"), audio)
        val mirror = SessionCrypto.deriveMirrorKeyIv(audio, -1L)
        assertArrayEquals(protocolHex("6bb84d726b8526a4dab0cfdbb7b3b771"), mirror.key)
        assertArrayEquals(protocolHex("9d3b8c8a44a0971e889abc4ae83d82a2"), mirror.iv)
        assertArrayEquals(key, SessionCrypto.deriveAudioKey(key))
    }

    @Test fun validatesKeySizesAndClosedState() {
        assertThrows(IllegalArgumentException::class.java) { SessionCrypto(ByteArray(15), ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { SessionCrypto(key, ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { SessionCrypto.deriveAudioKey(key, ByteArray(31)) }
        val session = SessionCrypto(key, ByteArray(16))
        val mirror = session.createMirrorDecryptor(1)
        mirror.close()
        assertThrows(IllegalStateException::class.java) { mirror.decrypt(byteArrayOf(0)) }
        session.close()
        assertThrows(IllegalStateException::class.java) { session.decryptAudioPacket(byteArrayOf(0)) }
        assertThrows(IllegalStateException::class.java) { session.createMirrorDecryptor(1) }
    }
}
