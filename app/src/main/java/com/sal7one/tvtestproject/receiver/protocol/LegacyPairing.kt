package com.sal7one.tvtestproject.receiver.protocol

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * Legacy (non-PIN) /pair-setup and /pair-verify. Create one per RTSP connection.
 * Wire layout and signature ordering verified against:
 * https://github.com/FDH2/UxPlay/blob/master/lib/pairing.c
 * https://github.com/FDH2/UxPlay/blob/master/lib/raop_handlers.h
 *
 * This proves possession of the client's supplied Ed25519 key, NOT membership in an
 * approved-client list. PIN/SRP and HomeKit/TLV pairing are intentionally unsupported.
 * Map PairingException to a non-success authentication response and close the session.
 */
class LegacyPairing(private val deviceKeys: DeviceKeys, private val random: SecureRandom = SecureRandom()) : AutoCloseable {
    class PairingException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)
    private enum class State { INITIAL, SETUP, VERIFYING, VERIFIED, FAILED, CLOSED }
    private var state = State.INITIAL
    private var secret: ByteArray? = null
    private var clientEd: ByteArray? = null
    private var clientEc: ByteArray? = null
    private var serverEc: ByteArray? = null
    private var verifyCipher: Cipher? = null

    val isVerified: Boolean @Synchronized get() = state == State.VERIFIED

    /** Only available after the second pair-verify signature has actually verified. */
    val sharedSecret: ByteArray? @Synchronized get() = if (state == State.VERIFIED) secret?.copyOf() else null

    @Synchronized
    fun pairSetup(body: ByteArray): ByteArray = guarded {
        if (state != State.INITIAL || body.size != 32) throw PairingException("Invalid legacy pair-setup")
        // UxPlay treats the 32-byte setup body as opaque; peer identity arrives in verify.
        state = State.SETUP
        deviceKeys.publicKey
    }

    /** Step 1 returns 32-byte server X25519 key + 64-byte encrypted server signature. Step 2 returns empty. */
    @Synchronized
    fun pairVerify(body: ByteArray): ByteArray = guarded {
        if (body.size != 68 || body[1] != 0.toByte() || body[2] != 0.toByte() || body[3] != 0.toByte()) {
            throw PairingException("Invalid legacy pair-verify framing")
        }
        when (body[0].toInt()) {
            1 -> startVerification(body)
            0 -> finishVerification(body)
            else -> throw PairingException("Unknown legacy pair-verify step")
        }
    }

    private fun startVerification(body: ByteArray): ByteArray {
        // Observed macOS clients use the advertised signing key and verify directly after /info.
        if (state != State.INITIAL && state != State.SETUP) throw PairingException("Legacy pair-verify out of order")
        val theirsEc = body.copyOfRange(4, 36)
        val theirsEd = body.copyOfRange(36, 68)
        if (!Ed25519.validatePublicKeyFull(theirsEd, 0)) throw PairingException("Invalid client signing key")
        val ephemeral = X25519PrivateKeyParameters(random)
        val oursEc = ephemeral.generatePublicKey().encoded
        val shared = ByteArray(32)
        try {
            // Bouncy Castle rejects an all-zero X25519 agreement (low-order public keys).
            ephemeral.generateSecret(X25519PublicKeyParameters(theirsEc, 0), shared, 0)
            val key = SessionCrypto.sha512("Pair-Verify-AES-Key".toByteArray(Charsets.US_ASCII), shared).copyOf(16)
            val iv = SessionCrypto.sha512("Pair-Verify-AES-IV".toByteArray(Charsets.US_ASCII), shared).copyOf(16)
            val cipher = try {
                SessionCrypto.aesCipher("AES/CTR/NoPadding", Cipher.ENCRYPT_MODE, key, iv)
            } finally {
                key.fill(0)
                iv.fill(0)
            }
            val signature = deviceKeys.sign(oursEc + theirsEc)
            val encrypted = try { cipher.update(signature) } finally { signature.fill(0) }
            check(encrypted != null && encrypted.size == 64) { "AES provider buffered signature" }
            secret = shared.copyOf()
            clientEd = theirsEd
            clientEc = theirsEc
            serverEc = oursEc
            verifyCipher = cipher
            state = State.VERIFYING
            return oursEc + encrypted
        } finally {
            shared.fill(0)
        }
    }

    private fun finishVerification(body: ByteArray): ByteArray {
        if (state != State.VERIFYING) throw PairingException("Legacy pair-verify out of order")
        // Same CTR stream, already advanced by the server's 64-byte signature.
        val signature = checkNotNull(verifyCipher).update(body, 4, 64)
        if (signature == null || signature.size != 64) throw PairingException("Invalid decrypted signature")
        try {
            val message = checkNotNull(clientEc) + checkNotNull(serverEc)
            val verified = Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(checkNotNull(clientEd), 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
            if (!verified) throw PairingException("Client signature verification failed")
        } finally {
            signature.fill(0)
        }
        verifyCipher = null
        state = State.VERIFIED
        return ByteArray(0)
    }

    private inline fun guarded(block: () -> ByteArray): ByteArray {
        try {
            return block()
        } catch (error: Exception) {
            clearEphemeral()
            if (state != State.CLOSED) state = State.FAILED
            if (error is PairingException) throw error
            throw PairingException("Legacy pairing failed", error)
        }
    }

    private fun clearEphemeral() {
        secret?.fill(0)
        secret = null
        clientEd = null
        clientEc = null
        serverEc = null
        verifyCipher = null
    }

    @Synchronized
    override fun close() {
        clearEphemeral()
        state = State.CLOSED
    }
}
