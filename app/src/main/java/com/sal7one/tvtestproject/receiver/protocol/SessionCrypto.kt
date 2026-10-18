package com.sal7one.tvtestproject.receiver.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One SETUP's transport secrets. Input is the 16-byte key decrypted by FairPlay, not ekey.
 * Protocol references:
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_handlers.h
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_rtp_mirror.c
 * https://github.com/FDH2/UxPlay/blob/master/lib/mirror_buffer.c
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_buffer.c
 * https://github.com/FDH2/UxPlay/blob/master/lib/crypto.c
 *
 * Mirror CTR is continuous across payloads; audio CBC starts at eiv for every packet,
 * decrypts complete blocks only, and leaves the packet's trailing bytes clear.
 */
class SessionCrypto(
    fairPlayKey: ByteArray,
    audioIv: ByteArray,
    verifiedSharedSecret: ByteArray? = null,
) : AutoCloseable {
    private val audioKey = deriveAudioKey(fairPlayKey, verifiedSharedSecret)
    private val iv = audioIv.copyOf().also { require(it.size == 16) { "Audio IV must be 16 bytes" } }
    private var closed = false

    @Synchronized
    fun createMirrorDecryptor(streamConnectionId: Long): MirrorDecryptor {
        check(!closed) { "Session crypto is closed" }
        val material = deriveMirrorKeyIv(audioKey, streamConnectionId)
        return try {
            MirrorDecryptor(material.key, material.iv)
        } finally {
            material.key.fill(0)
            material.iv.fill(0)
        }
    }

    /** Pass only the RTP payload, excluding RTP/retransmission headers. Safe for reordered packets. */
    @Synchronized
    fun decryptAudioPacket(payload: ByteArray): ByteArray {
        check(!closed) { "Session crypto is closed" }
        val result = payload.copyOf()
        val encryptedLength = payload.size / 16 * 16
        if (encryptedLength != 0) {
            val cipher = aesCipher("AES/CBC/NoPadding", Cipher.DECRYPT_MODE, audioKey, iv)
            val clear = cipher.doFinal(payload, 0, encryptedLength)
            clear.copyInto(result)
            clear.fill(0)
        }
        return result
    }

    @Synchronized
    override fun close() {
        closed = true
        audioKey.fill(0)
        iv.fill(0)
    }

    class KeyIv(val key: ByteArray, val iv: ByteArray)

    /** A separate instance for each mirror stream. Never share one between connections. */
    class MirrorDecryptor internal constructor(key: ByteArray, iv: ByteArray) : AutoCloseable {
        private var cipher: Cipher? = aesCipher("AES/CTR/NoPadding", Cipher.DECRYPT_MODE, key, iv)

        @Synchronized
        fun decrypt(payload: ByteArray): ByteArray {
            val active = checkNotNull(cipher) { "Mirror crypto is closed" }
            if (payload.isEmpty()) return ByteArray(0)
            // JCA CTR update retains the unused keystream bytes of a partial block.
            return (active.update(payload) ?: ByteArray(0)).also {
                check(it.size == payload.size) { "AES provider buffered a CTR payload" }
            }
        }

        @Synchronized
        override fun close() {
            cipher = null
        }
    }

    companion object {
        fun deriveAudioKey(fairPlayKey: ByteArray, verifiedSharedSecret: ByteArray? = null): ByteArray {
            require(fairPlayKey.size == 16) { "FairPlay key must be 16 bytes" }
            if (verifiedSharedSecret == null) return fairPlayKey.copyOf()
            require(verifiedSharedSecret.size == 32) { "Pairing secret must be 32 bytes" }
            return sha512(fairPlayKey, verifiedSharedSecret).copyOf(16)
        }

        /** The Long holds all uint64 bits, including values above Long.MAX_VALUE. */
        fun deriveMirrorKeyIv(audioKey: ByteArray, streamConnectionId: Long): KeyIv {
            require(audioKey.size == 16) { "Audio key must be 16 bytes" }
            val id = java.lang.Long.toUnsignedString(streamConnectionId)
            return KeyIv(
                sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII), audioKey).copyOf(16),
                sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII), audioKey).copyOf(16),
            )
        }

        internal fun sha512(vararg chunks: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run {
            chunks.forEach { update(it) }
            digest()
        }

        internal fun aesCipher(transformation: String, mode: Int, key: ByteArray, iv: ByteArray): Cipher =
            Cipher.getInstance(transformation).apply {
                require(key.size == 16 && iv.size == 16) { "AES-128 requires 16-byte key and IV" }
                init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
    }
}
