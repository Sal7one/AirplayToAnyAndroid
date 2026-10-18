package com.sal7one.tvtestproject.receiver.protocol

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Random persistent receiver identity, stored in an app-private file supplied by the caller.
 * No Android dependency and no global provider registration. Uses BC lightweight APIs.
 * The seed is not derived from the MAC/device ID; a corrupt existing identity fails closed.
 */
class DeviceKeys private constructor(private val identity: Ed25519PrivateKeyParameters) {
    val publicKey: ByteArray get() = identity.generatePublicKey().encoded

    internal fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, identity)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        private val magic = "FMTVKEY1".toByteArray(Charsets.US_ASCII)

        /** Pass File(context.noBackupFilesDir, "airplay-identity.key"); keep it out of logs/backups. */
        @Synchronized
        fun loadOrCreate(file: File, random: SecureRandom = SecureRandom()): DeviceKeys {
            if (file.exists()) return load(file)
            val parent = file.absoluteFile.parentFile ?: throw IOException("Missing identity directory")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create identity directory")
            val key = Ed25519PrivateKeyParameters(random)
            val seed = key.encoded
            val temporary = File.createTempFile(".airplay-identity-", ".tmp", parent)
            try {
                restrictPermissions(temporary)
                FileOutputStream(temporary).use {
                    it.write(magic)
                    it.write(seed)
                    it.fd.sync()
                }
                // Atomic publication prevents partial identities after an interrupted write.
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                restrictPermissions(file)
                return DeviceKeys(key)
            } finally {
                seed.fill(0)
                temporary.delete()
            }
        }

        private fun load(file: File): DeviceKeys {
            if (!file.isFile || file.length() != magic.size + 32L) throw IOException("Invalid receiver identity")
            val bytes = file.inputStream().use { input ->
                val data = ByteArray(magic.size + 32)
                var offset = 0
                while (offset < data.size) {
                    val count = input.read(data, offset, data.size - offset)
                    if (count <= 0) throw IOException("Truncated receiver identity")
                    offset += count
                }
                if (input.read() != -1) throw IOException("Invalid receiver identity length")
                data
            }
            try {
                if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) throw IOException("Invalid receiver identity format")
                restrictPermissions(file)
                return DeviceKeys(Ed25519PrivateKeyParameters(bytes, magic.size))
            } finally {
                bytes.fill(0)
            }
        }

        private fun restrictPermissions(file: File) {
            if (!file.setReadable(false, false) || !file.setWritable(false, false) ||
                !file.setReadable(true, true) || !file.setWritable(true, true)) {
                throw IOException("Cannot restrict receiver identity permissions")
            }
        }
    }
}
