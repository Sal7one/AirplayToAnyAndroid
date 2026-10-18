package com.sal7one.tvtestproject

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.tvtestproject.receiver.media.NativeAlacDecoder
import com.sal7one.tvtestproject.receiver.protocol.NativeFairPlaySession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Calls JNI directly: Kotlin argument checks and synchronized wrapper methods are not exercised. */
@RunWith(AndroidJUnit4::class)
class NativeBoundaryTest {
    @Test
    fun forgedHandlesAreRejectedAndUnknownCloseIsIdempotent() {
        val packet = alacBoundaryAsset("ramp352.alac")
        val pcm = alacBoundaryAsset("ramp352.pcm")
        withAlacJni { alac ->
            withFairPlayJni { fairPlay ->
                val forged = listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE - 1, 0x0123456789abcdefL)
                for (handle in forged) {
                    assertThrows("ALAC decode: $handle", IllegalStateException::class.java) {
                        alac.call("nativeDecode", handle, packet)
                    }
                    assertThrows("ALAC flush: $handle", IllegalStateException::class.java) {
                        alac.call("nativeFlush", handle)
                    }
                    assertThrows("FairPlay setup: $handle", IllegalStateException::class.java) {
                        fairPlay.call("nativeSetup", handle, fairPlaySetupRequest())
                    }
                    assertThrows("FairPlay decrypt: $handle", IllegalStateException::class.java) {
                        fairPlay.call("nativeDecryptKey", handle, ByteArray(72))
                    }
                    repeat(2) {
                        alac.release(handle)
                        fairPlay.release(handle)
                    }
                }
                assertArrayEquals(pcm, alac.call("nativeDecode", alac.handle, packet) as ByteArray)
                assertEquals(142, (fairPlay.call("nativeSetup", fairPlay.handle,
                    fairPlaySetupRequest()) as ByteArray).size)
            }
        }
    }

    @Test
    fun liveIdsAreDisjointAndForeignCloseCannotReleaseTheirOwner() {
        val packet = alacBoundaryAsset("ramp352.alac")
        val pcm = alacBoundaryAsset("ramp352.pcm")
        withAlacJni { alac ->
            withFairPlayJni { fairPlay ->
                assertTrue("ALAC registry ID must be positive", alac.handle > 0)
                assertTrue("FairPlay registry ID must be positive", fairPlay.handle > 0)
                assertNotEquals("Library handle namespaces must not overlap", alac.handle, fairPlay.handle)
                assertThrows(IllegalStateException::class.java) {
                    alac.call("nativeDecode", fairPlay.handle, packet)
                }
                assertThrows(IllegalStateException::class.java) {
                    alac.call("nativeFlush", fairPlay.handle)
                }
                assertThrows(IllegalStateException::class.java) {
                    fairPlay.call("nativeSetup", alac.handle, fairPlaySetupRequest())
                }
                assertThrows(IllegalStateException::class.java) {
                    fairPlay.call("nativeDecryptKey", alac.handle, ByteArray(72))
                }
                repeat(2) {
                    alac.release(fairPlay.handle)
                    fairPlay.release(alac.handle)
                }
                assertArrayEquals(pcm, alac.call("nativeDecode", alac.handle, packet) as ByteArray)
                alac.call("nativeFlush", alac.handle)
                assertEquals(142, (fairPlay.call("nativeSetup", fairPlay.handle,
                    fairPlaySetupRequest()) as ByteArray).size)
                assertArrayEquals(fairPlayHandshakeResponse(fairPlayHandshakeRequest()),
                    fairPlay.call("nativeSetup", fairPlay.handle, fairPlayHandshakeRequest()) as ByteArray)
            }
        }
    }

    @Test
    fun repeatedNativeCreateCloseNeverAliasesStaleIds() {
        val packet = alacBoundaryAsset("ramp352.alac")
        val pcm = alacBoundaryAsset("ramp352.pcm")
        withAlacJni { alac ->
            withFairPlayJni { fairPlay ->
                val seen = hashSetOf(alac.handle, fairPlay.handle)
                var previousAlac = alac.handle
                var previousFairPlay = fairPlay.handle
                var staleAlac: Long? = null
                var staleFairPlay: Long? = null
                repeat(64) {
                    val alacId = alac.create(44_100, 2, 352)
                    val fairPlayId = fairPlay.create()
                    assertTrue("ALAC IDs must increase", alacId > previousAlac)
                    assertTrue("FairPlay IDs must increase", fairPlayId > previousFairPlay)
                    assertTrue("ALAC reused a registry ID", seen.add(alacId))
                    assertTrue("FairPlay reused a registry ID", seen.add(fairPlayId))
                    staleAlac?.let { stale ->
                        assertThrows(IllegalStateException::class.java) {
                            alac.call("nativeDecode", stale, packet)
                        }
                        assertThrows(IllegalStateException::class.java) { alac.call("nativeFlush", stale) }
                        alac.release(stale)
                    }
                    staleFairPlay?.let { stale ->
                        assertThrows(IllegalStateException::class.java) {
                            fairPlay.call("nativeSetup", stale, fairPlaySetupRequest())
                        }
                        assertThrows(IllegalStateException::class.java) {
                            fairPlay.call("nativeDecryptKey", stale, ByteArray(72))
                        }
                        fairPlay.release(stale)
                    }
                    assertArrayEquals(pcm, alac.call("nativeDecode", alacId, packet) as ByteArray)
                    assertEquals(142, (fairPlay.call("nativeSetup", fairPlayId,
                        fairPlaySetupRequest()) as ByteArray).size)
                    alac.release(alacId)
                    fairPlay.release(fairPlayId)
                    assertThrows(IllegalStateException::class.java) { alac.call("nativeFlush", alacId) }
                    assertThrows(IllegalStateException::class.java) {
                        fairPlay.call("nativeSetup", fairPlayId, fairPlaySetupRequest())
                    }
                    previousAlac = alacId
                    previousFairPlay = fairPlayId
                    staleAlac = alacId
                    staleFairPlay = fairPlayId
                }
            }
        }
    }

    @Test
    fun alacNativeRejectsNullEmptyAndOversizedPacketsAndRecovers() {
        val packet = alacBoundaryAsset("ramp352.alac")
        val pcm = alacBoundaryAsset("ramp352.pcm")
        withAlacJni { alac ->
            val malformed = listOf<ByteArray?>(null, ByteArray(0), ByteArray(65_537),
                ByteArray(1_048_576), byteArrayOf(0xe0.toByte()))
            for (input in malformed) {
                assertThrows("Packet length ${input?.size}", IllegalArgumentException::class.java) {
                    alac.call("nativeDecode", alac.handle, input)
                }
                alac.call("nativeFlush", alac.handle)
                assertArrayEquals(pcm, alac.call("nativeDecode", alac.handle, packet) as ByteArray)
            }
        }
    }

    @Test
    fun alacNativeCreateValidatesConfigurationWithoutKotlinChecks() {
        withAlacJni { alac ->
            val invalid = listOf(Triple(0, 2, 352), Triple(48_000, 2, 352),
                Triple(44_100, 0, 352), Triple(44_100, 1, 352), Triple(44_100, 3, 352),
                Triple(44_100, 2, 351), Triple(44_100, 2, 4097), Triple(44_100, 2, Int.MAX_VALUE))
            for ((rate, channels, frames) in invalid) {
                assertThrows("$rate/$channels/$frames", IllegalArgumentException::class.java) {
                    alac.create(rate, channels, frames)
                }
            }
            val recovered = alac.create(44_100, 2, 352)
            assertTrue(recovered > 0)
            assertArrayEquals(alacBoundaryAsset("ramp352.pcm"),
                alac.call("nativeDecode", recovered, alacBoundaryAsset("ramp352.alac")) as ByteArray)
        }
    }

    @Test
    fun independentRegistriesLimitLiveSessionsTo64AndRecoverReleasedSlots() {
        withAlacJni { alac ->
            withFairPlayJni { fairPlay ->
                // Each wrapper already owns one slot; every additional raw allocation is tracked.
                val alacIds = mutableListOf<Long>()
                val fairPlayIds = mutableListOf<Long>()
                repeat(63) {
                    alacIds += alac.create(44_100, 2, 352)
                    fairPlayIds += fairPlay.create()
                }
                assertThrows(IllegalStateException::class.java) { alac.create(44_100, 2, 352) }
                assertThrows(IllegalStateException::class.java) { fairPlay.create() }
                alac.release(alacIds.first())
                fairPlay.release(fairPlayIds.first())
                val recoveredAlac = alac.create(44_100, 2, 352)
                val recoveredFairPlay = fairPlay.create()
                assertTrue(recoveredAlac > alacIds.last())
                assertTrue(recoveredFairPlay > fairPlayIds.last())
                assertThrows(IllegalStateException::class.java) { alac.create(44_100, 2, 352) }
                assertThrows(IllegalStateException::class.java) { fairPlay.create() }
                assertArrayEquals(alacBoundaryAsset("ramp352.pcm"),
                    alac.call("nativeDecode", recoveredAlac, alacBoundaryAsset("ramp352.alac")) as ByteArray)
                assertEquals(142, (fairPlay.call("nativeSetup", recoveredFairPlay,
                    fairPlaySetupRequest()) as ByteArray).size)
            }
        }
    }

    @Test
    fun rawNativeDecodeAndFlushCanRaceCloseWithoutUsingFreedState() {
        val packet = alacBoundaryAsset("ramp352.alac")
        val pcm = alacBoundaryAsset("ramp352.pcm")
        withAlacJni { alac ->
            NativeBoundaryRace().use { race ->
                repeat(12) {
                    val handle = alac.create(44_100, 2, 352)
                    assertArrayEquals(pcm, alac.call("nativeDecode", handle, packet) as ByteArray)
                    val entering = CountDownLatch(2)
                    race.run(
                        {
                            entering.countDown()
                            repeat(32) {
                                succeedsOrClosed {
                                    assertArrayEquals(pcm, alac.call("nativeDecode", handle, packet) as ByteArray)
                                }
                            }
                        },
                        {
                            entering.countDown()
                            repeat(32) { succeedsOrClosed { alac.call("nativeFlush", handle) } }
                        },
                        {
                            assertTrue(entering.await(2, TimeUnit.SECONDS))
                            repeat(4) { alac.release(handle) }
                        },
                    )
                    assertThrows(IllegalStateException::class.java) {
                        alac.call("nativeDecode", handle, packet)
                    }
                    assertThrows(IllegalStateException::class.java) { alac.call("nativeFlush", handle) }
                    alac.release(handle)
                }
            }
        }
    }
}

/** Reflection is intentional: null arrays, forged IDs and concurrent calls must reach real JNI. */
internal class RawJniSession(private val receiver: Any, private val closeMethod: String) : AutoCloseable {
    private val methods = receiver.javaClass.declaredMethods
        .filter { Modifier.isNative(it.modifiers) }
        .associateBy { it.name }
        .also { nativeMethods -> nativeMethods.values.forEach { it.isAccessible = true } }
    val handle: Long = receiver.javaClass.getDeclaredField("handle")
        .apply { isAccessible = true }.getLong(receiver)
    private val allocations = mutableListOf<Long>()

    fun call(name: String, vararg arguments: Any?): Any? = try {
        methods.getValue(name).invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    fun create(vararg arguments: Any?): Long =
        (call("nativeCreate", *arguments) as Long).also { allocations += it }

    fun release(handle: Long) {
        call(closeMethod, handle)
    }

    override fun close() {
        var failure: Throwable? = null
        // Keep trying every release even if one JNI call fails; outer use closes the wrapper's ID.
        for (allocated in allocations.asReversed()) {
            try {
                release(allocated)
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

internal fun <T> withAlacJni(block: (RawJniSession) -> T): T =
    NativeAlacDecoder(44_100, 2, 352).use { owner ->
        RawJniSession(owner, "nativeClose").use(block)
    }

internal fun <T> withFairPlayJni(block: (RawJniSession) -> T): T =
    NativeFairPlaySession().use { owner ->
        RawJniSession(owner, "nativeDestroy").use(block)
    }

internal fun alacBoundaryAsset(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets.open("alac/$name").use { it.readBytes() }

internal fun succeedsOrClosed(operation: () -> Unit) {
    try {
        operation()
    } catch (_: IllegalStateException) {
        // Close may win lookup, or an already-retained native instance may finish successfully.
    }
}

/** Reused workers, a start gate and a shared deadline keep the stress tests short and bounded. */
internal class NativeBoundaryRace : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(3) { action ->
        Thread(action, "jni-boundary-race").apply { isDaemon = true }
    }

    fun run(vararg actions: () -> Unit) {
        require(actions.size == 3)
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        val futures = actions.map { action ->
            executor.submit(Callable {
                ready.countDown()
                assertTrue("Native race start gate timed out", start.await(2, TimeUnit.SECONDS))
                action()
            })
        }
        try {
            assertTrue("Native race workers did not start", ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            for (future in futures) {
                try {
                    future.get(maxOf(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                } catch (error: ExecutionException) {
                    throw (error.cause ?: error)
                }
            }
        } finally {
            start.countDown()
            futures.filterNot { it.isDone }.forEach { it.cancel(true) }
        }
    }

    override fun close() {
        executor.shutdownNow()
        assertTrue("Native race workers did not terminate", executor.awaitTermination(2, TimeUnit.SECONDS))
    }
}
