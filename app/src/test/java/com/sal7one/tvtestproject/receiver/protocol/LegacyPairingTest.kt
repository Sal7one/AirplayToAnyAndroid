package com.sal7one.tvtestproject.receiver.protocol

import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyPairingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clientEd = Ed25519PrivateKeyParameters(protocolHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"), 0)
    private val clientEc = X25519PrivateKeyParameters(protocolHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"), 0)

    @Test fun identityPersistsIsRandomAndMatchesEd25519RfcVector() {
        val file = File(temporary.root, "identity")
        val identity = DeviceKeys.loadOrCreate(file)
        assertArrayEquals(identity.publicKey, DeviceKeys.loadOrCreate(file).publicKey)
        assertFalse(identity.publicKey.contentEquals(DeviceKeys.loadOrCreate(File(temporary.root, "second")).publicKey))
        assertEquals(40L, file.length())
        val copy = identity.publicKey.also { it.fill(0) }
        assertFalse(copy.contentEquals(identity.publicKey))
        // RFC 8032 section 7.1 test 1. Known seed is used only in this test fixture.
        val fixture = temporary.newFile("rfc-identity")
        fixture.writeBytes("FMTVKEY1".toByteArray() + clientEd.encoded)
        val rfc = DeviceKeys.loadOrCreate(fixture)
        assertArrayEquals(protocolHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), rfc.publicKey)
        assertArrayEquals(protocolHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155 5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"), rfc.sign(ByteArray(0)))
    }

    @Test fun rejectsCorruptPersistentIdentityWithoutReplacingIt() {
        val fixture = temporary.newFile("corrupt")
        fixture.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { DeviceKeys.loadOrCreate(fixture) }
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.readBytes())
    }

    @Test fun x25519AgreementMatchesRfc7748Vector() {
        val secret = ByteArray(32)
        clientEc.generateSecret(X25519PublicKeyParameters(protocolHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), 0), secret, 0)
        assertArrayEquals(protocolHex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), secret)
    }

    @Test fun realLegacyExchangeVerifiesBothSignaturesAndReleasesSecretOnlyAtEnd() {
        val keys = DeviceKeys.loadOrCreate(File(temporary.root, "receiver"))
        LegacyPairing(keys).use { pairing ->
            assertArrayEquals(keys.publicKey, pairing.pairSetup(clientEd.generatePublicKey().encoded))
            val peer = handshake(pairing, keys)
            assertFalse(pairing.isVerified)
            assertNull(pairing.sharedSecret)
            assertArrayEquals(ByteArray(0), pairing.pairVerify(peer.finish))
            assertTrue(pairing.isVerified)
            assertArrayEquals(peer.secret, pairing.sharedSecret)
            pairing.sharedSecret!!.fill(0)
            assertArrayEquals(peer.secret, pairing.sharedSecret)
        }
    }

    @Test fun tamperedSignaturesFailAndSessionCannotBeReused() {
        val keys = DeviceKeys.loadOrCreate(File(temporary.root, "receiver"))
        LegacyPairing(keys).use { pairing ->
            pairing.pairSetup(ByteArray(32))
            val peer = handshake(pairing, keys)
            peer.finish[15] = (peer.finish[15].toInt() xor 1).toByte()
            assertThrows(LegacyPairing.PairingException::class.java) { pairing.pairVerify(peer.finish) }
            assertFalse(pairing.isVerified)
            assertNull(pairing.sharedSecret)
            assertThrows(LegacyPairing.PairingException::class.java) { pairing.pairSetup(ByteArray(32)) }
        }
    }

    @Test fun directVerifyWithoutSetupStillRequiresARealClientSignature() {
        val keys = DeviceKeys.loadOrCreate(File(temporary.root, "receiver"))
        LegacyPairing(keys).use { pairing ->
            val peer = handshake(pairing, keys)
            assertFalse(pairing.isVerified)
            assertNull(pairing.sharedSecret)
            pairing.pairVerify(peer.finish)
            assertTrue(pairing.isVerified)
            assertArrayEquals(peer.secret, pairing.sharedSecret)
            assertThrows(LegacyPairing.PairingException::class.java) { pairing.pairVerify(peer.finish) }
            assertNull(pairing.sharedSecret)
        }
    }

    @Test fun directVerifyRejectsTamperedProofAndRepeatedFirstStep() {
        val keys = DeviceKeys.loadOrCreate(File(temporary.root, "receiver"))
        LegacyPairing(keys).use { pairing ->
            val peer = handshake(pairing, keys)
            peer.finish[15] = (peer.finish[15].toInt() xor 1).toByte()
            assertThrows(LegacyPairing.PairingException::class.java) { pairing.pairVerify(peer.finish) }
            assertFalse(pairing.isVerified)
            assertNull(pairing.sharedSecret)
        }
        LegacyPairing(keys).use { pairing ->
            handshake(pairing, keys)
            assertThrows(LegacyPairing.PairingException::class.java) { handshake(pairing, keys) }
            assertFalse(pairing.isVerified)
            assertNull(pairing.sharedSecret)
        }
    }

    @Test fun rejectsLowOrderKeysBadFramingAndOutOfOrderMessages() {
        val keys = DeviceKeys.loadOrCreate(File(temporary.root, "receiver"))
        LegacyPairing(keys).use {
            assertThrows(LegacyPairing.PairingException::class.java) { it.pairVerify(ByteArray(68)) }
            assertNull(it.sharedSecret)
        }
        LegacyPairing(keys).use {
            it.pairSetup(ByteArray(32))
            val lowOrder = byteArrayOf(1, 0, 0, 0) + ByteArray(32) + clientEd.generatePublicKey().encoded
            assertThrows(LegacyPairing.PairingException::class.java) { it.pairVerify(lowOrder) }
        }
        LegacyPairing(keys).use {
            it.pairSetup(ByteArray(32))
            val identityPoint = ByteArray(32).also { point -> point[0] = 1 }
            val lowOrderSigningKey = byteArrayOf(1, 0, 0, 0) + clientEc.generatePublicKey().encoded + identityPoint
            assertThrows(LegacyPairing.PairingException::class.java) { it.pairVerify(lowOrderSigningKey) }
        }
        LegacyPairing(keys).use {
            assertThrows(LegacyPairing.PairingException::class.java) { it.pairSetup(ByteArray(31)) }
        }
        LegacyPairing(keys).use {
            it.pairSetup(ByteArray(32))
            val badReserved = byteArrayOf(1, 1, 0, 0) + clientEc.generatePublicKey().encoded + clientEd.generatePublicKey().encoded
            assertThrows(LegacyPairing.PairingException::class.java) { it.pairVerify(badReserved) }
        }
    }

    @Test fun sessionsHaveIndependentEphemeralKeysAndCannotReplayClientProof() {
        val keys = DeviceKeys.loadOrCreate(File(temporary.root, "receiver"))
        LegacyPairing(keys).use { first -> LegacyPairing(keys).use { second ->
            first.pairSetup(ByteArray(32)); second.pairSetup(ByteArray(32))
            val a = handshake(first, keys)
            val b = handshake(second, keys)
            assertFalse(a.secret.contentEquals(b.secret))
            first.pairVerify(a.finish)
            assertThrows(LegacyPairing.PairingException::class.java) { second.pairVerify(a.finish) }
            assertTrue(first.isVerified)
            assertNull(second.sharedSecret)
        } }
    }

    private class Peer(val secret: ByteArray, val finish: ByteArray)
    private fun handshake(pairing: LegacyPairing, keys: DeviceKeys): Peer {
        val ec = clientEc.generatePublicKey().encoded
        val response = pairing.pairVerify(byteArrayOf(1, 0, 0, 0) + ec + clientEd.generatePublicKey().encoded)
        assertEquals(96, response.size)
        val serverEc = response.copyOfRange(0, 32)
        val secret = ByteArray(32)
        clientEc.generateSecret(X25519PublicKeyParameters(serverEc, 0), secret, 0)
        fun derive(salt: String) = MessageDigest.getInstance("SHA-512").digest(salt.toByteArray(Charsets.US_ASCII) + secret).copyOf(16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(derive("Pair-Verify-AES-Key"), "AES"), IvParameterSpec(derive("Pair-Verify-AES-IV")))
        }
        val serverSignature = cipher.update(response, 32, 64)
        // Verify BC's server signature independently using the host JCA Ed25519 provider.
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(protocolHex("302a300506032b6570032100") + keys.publicKey))
        assertTrue(Signature.getInstance("Ed25519").run { initVerify(publicKey); update(serverEc + ec); verify(serverSignature) })
        val clientSignature = Ed25519Signer().run {
            init(true, clientEd)
            val message = ec + serverEc
            update(message, 0, message.size)
            generateSignature()
        }
        return Peer(secret, ByteArray(4) + cipher.update(clientSignature))
    }
}
