package com.sal7one.tvtestproject

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic FPLY inputs verify framing and lifecycle, not cryptography or Mac interoperability. */
@RunWith(AndroidJUnit4::class)
class NativeFairPlaySessionTest {
    @Test
    fun allFourModesHaveStable142And32ByteNativeFramingResponses() {
        withFairPlayJni { fairPlay ->
            for (mode in 0..3) {
                val setup = fairPlaySetupRequest(mode)
                val handshake = fairPlayHandshakeRequest(mode)
                var firstSetupResponse: ByteArray? = null
                repeat(3) {
                    val setupResponse = fairPlay.call("nativeSetup", fairPlay.handle, setup) as ByteArray
                    assertEquals(142, setupResponse.size)
                    assertArrayEquals(byteArrayOf(0x46, 0x50, 0x4c, 0x59, 3, 1, 2, 0, 0, 0, 0,
                        0x82.toByte()), setupResponse.copyOfRange(0, 12))
                    assertEquals(mode, setupResponse[13].toInt() and 0xff)
                    firstSetupResponse?.let { assertArrayEquals(it, setupResponse) }
                    firstSetupResponse = setupResponse.copyOf()
                    repeat(2) {
                        val response = fairPlay.call("nativeSetup", fairPlay.handle, handshake) as ByteArray
                        assertEquals(32, response.size)
                        assertArrayEquals(fairPlayHandshakeResponse(handshake), response)
                    }
                }
            }
        }
    }

    @Test
    fun nativeDecryptRequiresCompletedExchangeAndRestartClearsReadiness() {
        withFairPlayJni { first ->
            withFairPlayJni { second ->
                assertThrows(IllegalStateException::class.java) {
                    first.call("nativeDecryptKey", first.handle, ByteArray(72))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    first.call("nativeSetup", first.handle, fairPlayHandshakeRequest())
                }
                first.call("nativeSetup", first.handle, fairPlaySetupRequest())
                assertThrows(IllegalStateException::class.java) {
                    first.call("nativeDecryptKey", first.handle, ByteArray(72))
                }
                assertArrayEquals(fairPlayHandshakeResponse(fairPlayHandshakeRequest()),
                    first.call("nativeSetup", first.handle, fairPlayHandshakeRequest()) as ByteArray)
                assertThrows("Readiness must be per session", IllegalStateException::class.java) {
                    second.call("nativeDecryptKey", second.handle, ByteArray(72))
                }
                first.call("nativeSetup", first.handle, fairPlaySetupRequest())
                assertThrows("New setup must discard the previous exchange", IllegalStateException::class.java) {
                    first.call("nativeDecryptKey", first.handle, ByteArray(72))
                }
            }
        }
    }

    @Test
    fun nativeDecryptCopiesResultAndCanRaceDestroy() {
        // Deterministic synthetic input exercises the native return/copy/lifetime path only.
        // Comparing repeated results is not an independent crypto known-answer test.
        val encryptedKey = ByteArray(72)
        withFairPlayJni { fairPlay ->
            NativeBoundaryRace().use { race ->
                repeat(12) { iteration ->
                    val handle = fairPlay.create()
                    fairPlay.call("nativeSetup", handle, fairPlaySetupRequest(iteration % 4))
                    fairPlay.call("nativeSetup", handle, fairPlayHandshakeRequest(iteration % 4))
                    val expected = fairPlay.call("nativeDecryptKey", handle, encryptedKey) as ByteArray
                    assertEquals(16, expected.size)
                    assertArrayEquals(expected, fairPlay.call("nativeDecryptKey", handle, encryptedKey) as ByteArray)
                    val entering = CountDownLatch(2)
                    val decrypt = {
                        entering.countDown()
                        repeat(16) {
                            succeedsOrClosed {
                                val actual = fairPlay.call("nativeDecryptKey", handle, encryptedKey) as ByteArray
                                try { assertArrayEquals(expected, actual) } finally { actual.fill(0) }
                            }
                        }
                    }
                    try {
                        race.run(decrypt, decrypt, {
                            assertTrue(entering.await(2, TimeUnit.SECONDS))
                            repeat(4) { fairPlay.release(handle) }
                        })
                        assertThrows(IllegalStateException::class.java) {
                            fairPlay.call("nativeDecryptKey", handle, encryptedKey)
                        }
                    } finally {
                        fairPlay.release(handle)
                        expected.fill(0)
                    }
                }
            }
        }
    }

    @Test
    fun nativeNullAndInvalidLengthArraysAreArgumentErrorsOnALiveHandle() {
        withFairPlayJni { fairPlay ->
            assertThrows(IllegalArgumentException::class.java) {
                fairPlay.call("nativeSetup", fairPlay.handle, null)
            }
            for (length in listOf(0, 1, 15, 17, 163, 165, 65_537)) {
                assertThrows("Setup length $length", IllegalArgumentException::class.java) {
                    fairPlay.call("nativeSetup", fairPlay.handle, ByteArray(length))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                fairPlay.call("nativeDecryptKey", fairPlay.handle, null)
            }
            for (length in listOf(0, 1, 16, 71, 73, 65_537)) {
                assertThrows("Encrypted key length $length", IllegalArgumentException::class.java) {
                    fairPlay.call("nativeDecryptKey", fairPlay.handle, ByteArray(length))
                }
            }
            assertEquals(142, (fairPlay.call("nativeSetup", fairPlay.handle,
                fairPlaySetupRequest()) as ByteArray).size)
            assertArrayEquals(fairPlayHandshakeResponse(fairPlayHandshakeRequest()),
                fairPlay.call("nativeSetup", fairPlay.handle, fairPlayHandshakeRequest()) as ByteArray)
        }
    }

    @Test
    fun malformedNativeSetupIsAnArgumentErrorAndDoesNotPoisonLaterFraming() {
        withFairPlayJni { fairPlay ->
            val malformed = listOf(
                ByteArray(16), ByteArray(164),
                fairPlaySetupRequest().apply { this[0] = 0 },
                fairPlaySetupRequest().apply { this[4] = 2 },
                fairPlaySetupRequest(4), fairPlaySetupRequest(255),
                fairPlayHandshakeRequest().apply { this[0] = 0 },
                fairPlayHandshakeRequest().apply { this[4] = 4 },
                fairPlayHandshakeRequest(4), fairPlayHandshakeRequest(255),
            )
            for ((index, request) in malformed.withIndex()) {
                fairPlay.call("nativeSetup", fairPlay.handle, fairPlaySetupRequest())
                assertThrows("Malformed setup $index", IllegalArgumentException::class.java) {
                    fairPlay.call("nativeSetup", fairPlay.handle, request)
                }
                assertThrows(IllegalStateException::class.java) {
                    fairPlay.call("nativeDecryptKey", fairPlay.handle, ByteArray(72))
                }
                fairPlay.call("nativeSetup", fairPlay.handle, fairPlaySetupRequest())
                assertArrayEquals(fairPlayHandshakeResponse(fairPlayHandshakeRequest()),
                    fairPlay.call("nativeSetup", fairPlay.handle, fairPlayHandshakeRequest()) as ByteArray)
            }
        }
    }

    @Test
    fun rawNativeSetupCanRaceDestroyWithoutUsingFreedState() {
        val setup = fairPlaySetupRequest()
        val handshake = fairPlayHandshakeRequest()
        val expectedHandshake = fairPlayHandshakeResponse(handshake)
        withFairPlayJni { fairPlay ->
            NativeBoundaryRace().use { race ->
                repeat(12) {
                    val handle = fairPlay.create()
                    val expectedSetup = fairPlay.call("nativeSetup", handle, setup) as ByteArray
                    assertEquals(142, expectedSetup.size)
                    val entering = CountDownLatch(2)
                    race.run(
                        {
                            entering.countDown()
                            repeat(32) {
                                succeedsOrClosed {
                                    assertArrayEquals(expectedSetup,
                                        fairPlay.call("nativeSetup", handle, setup) as ByteArray)
                                }
                            }
                        },
                        {
                            entering.countDown()
                            repeat(32) {
                                succeedsOrClosed {
                                    assertArrayEquals(expectedHandshake,
                                        fairPlay.call("nativeSetup", handle, handshake) as ByteArray)
                                }
                            }
                        },
                        {
                            assertTrue(entering.await(2, TimeUnit.SECONDS))
                            repeat(4) { fairPlay.release(handle) }
                        },
                    )
                    assertThrows(IllegalStateException::class.java) {
                        fairPlay.call("nativeSetup", handle, setup)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        fairPlay.call("nativeDecryptKey", handle, ByteArray(72))
                    }
                    fairPlay.release(handle)
                }
            }
        }
    }
}

internal fun fairPlaySetupRequest(mode: Int = 0): ByteArray = ByteArray(16).apply {
    byteArrayOf(0x46, 0x50, 0x4c, 0x59, 3, 1, 1, 0, 0, 0, 0, 4, 2).copyInto(this)
    this[14] = mode.toByte()
}

internal fun fairPlayHandshakeRequest(mode: Int = 0): ByteArray =
    ByteArray(164) { ((it * 37 + 11) and 0xff).toByte() }.apply {
        byteArrayOf(0x46, 0x50, 0x4c, 0x59, 3, 1, 3, 0, 0, 0, 0, 0x98.toByte()).copyInto(this)
        this[12] = mode.toByte()
    }

internal fun fairPlayHandshakeResponse(request: ByteArray): ByteArray =
    byteArrayOf(0x46, 0x50, 0x4c, 0x59, 3, 1, 4, 0, 0, 0, 0, 20) + request.copyOfRange(144, 164)
