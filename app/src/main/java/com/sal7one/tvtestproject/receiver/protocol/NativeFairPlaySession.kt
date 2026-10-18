package com.sal7one.tvtestproject.receiver.protocol

/** One explicitly closed native session. Calls are serialized; native handles are also validated. */
class NativeFairPlaySession : AutoCloseable {
    private var handle = nativeCreate().also { check(it != 0L) { "FairPlay allocation failed" } }

    @Synchronized
    fun setup(request: ByteArray): ByteArray {
        check(handle != 0L) { "FairPlay session is closed" }
        require(request.size == 16 || request.size == 164) { "Invalid FairPlay request length" }
        return nativeSetup(handle, request)
    }

    @Synchronized
    fun decryptKey(encryptedKey: ByteArray): ByteArray {
        check(handle != 0L) { "FairPlay session is closed" }
        require(encryptedKey.size == 72) { "Invalid encrypted key length" }
        return nativeDecryptKey(handle, encryptedKey)
    }

    @Synchronized
    override fun close() {
        val owned = handle
        handle = 0
        if (owned != 0L) nativeDestroy(owned)
    }

    private external fun nativeCreate(): Long
    private external fun nativeSetup(handle: Long, request: ByteArray): ByteArray
    private external fun nativeDecryptKey(handle: Long, encryptedKey: ByteArray): ByteArray
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("fossmirror") }
    }
}
