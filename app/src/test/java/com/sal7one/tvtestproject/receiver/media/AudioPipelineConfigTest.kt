package com.sal7one.tvtestproject.receiver.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Pure configuration/timing helpers; deliberately no Android/device testing. */
class AudioPipelineConfigTest {
    @Test fun decoderDiscoveryIsLazyAndReusedWithoutCachingFormatDecisions() {
        var enumerations = 0
        var inspections = 0
        var validations = 0
        val inventory = AudioDecoderInventory(
            enumerate = { enumerations++; listOf("AAC") },
            inspect = { _: String -> inspections++; setOf(2) },
        )
        assertEquals(0, enumerations)
        assertEquals(0, inspections)
        fun supports(profile: Int): Boolean = inventory.decoders.any {
            validations++
            it.capabilities?.contains(profile) == true
        }
        repeat(10) {
            assertTrue(supports(2))
            assertFalse(supports(39))
        }
        assertEquals(1, enumerations)
        assertEquals(1, inspections)
        assertEquals(20, validations)
    }

    @Test fun decoderInventorySnapshotsTheListAndCachesUnsupportedTypes() {
        val discovered = mutableListOf("video", "AAC")
        var inspections = 0
        val inventory = AudioDecoderInventory(
            enumerate = { discovered },
            inspect = { name -> inspections++; if (name == "AAC") setOf(2) else null },
        )
        assertEquals(listOf("video", "AAC"), inventory.decoders.map { it.info })
        discovered.clear()
        repeat(3) {
            assertEquals(listOf(null, setOf(2)), inventory.decoders.map { it.capabilities })
        }
        assertEquals(2, inspections)
    }

    @Test fun anEmptyDecoderInventoryIsStillCached() {
        var enumerations = 0
        val inventory = AudioDecoderInventory(
            enumerate = { enumerations++; emptyList<String>() },
            inspect = { _: String -> setOf(2) },
        )
        repeat(3) { assertTrue(inventory.decoders.isEmpty()) }
        assertEquals(1, enumerations)
    }

    @Test fun failedDecoderEnumerationCanRetry() {
        var enumerations = 0
        val inventory = AudioDecoderInventory(
            enumerate = {
                check(++enumerations > 1) { "Temporary discovery failure" }
                listOf("AAC")
            },
            inspect = { _: String -> setOf(2) },
        )
        assertThrows(IllegalStateException::class.java) { inventory.decoders }
        repeat(3) { assertEquals(setOf(2), inventory.decoders.single().capabilities) }
        assertEquals(2, enumerations)
    }

    @Test fun failedCapabilityInspectionCanRetryWithoutRediscoveringCodecs() {
        var enumerations = 0
        var inspections = 0
        val inventory = AudioDecoderInventory(
            enumerate = { enumerations++; listOf("AAC") },
            inspect = { _: String ->
                check(++inspections > 1) { "Temporary capability failure" }
                setOf(2)
            },
        )
        assertThrows(IllegalStateException::class.java) { inventory.decoders.single().capabilities }
        repeat(3) { assertEquals(setOf(2), inventory.decoders.single().capabilities) }
        assertEquals(1, enumerations)
        assertEquals(2, inspections)
    }

    @Test fun concurrentCapabilityQueriesInitializeMetadataOnlyOnce() {
        val enumerations = AtomicInteger()
        val inspections = AtomicInteger()
        val inventory = AudioDecoderInventory(
            enumerate = { enumerations.incrementAndGet(); listOf("AAC") },
            inspect = { _: String -> inspections.incrementAndGet(); setOf(2) },
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = List(8) {
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    inventory.decoders.single().capabilities?.contains(2) == true
                }
            }
            start.countDown()
            results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, enumerations.get())
            assertEquals(1, inspections.get())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun decibelsConvertToAmplitude() {
        assertEquals(1f, AirPlayAudioVolume.linear(0f), 0f)
        assertEquals(0.5011872f, AirPlayAudioVolume.linear(-6f), 0.000001f)
        assertEquals(0.03162278f, AirPlayAudioVolume.linear(-30f), 0.000001f)
    }

    @Test fun muteIsDistinctFromQuietVolume() {
        assertEquals(0f, AirPlayAudioVolume.linear(-144f), 0f)
        assertEquals(0f, AirPlayAudioVolume.linear(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0.03162278f, AirPlayAudioVolume.linear(-100f), 0.000001f)
    }

    @Test fun volumeClampsAndRejectsNan() {
        assertEquals(1f, AirPlayAudioVolume.linear(10f), 0f)
        assertEquals(1f, AirPlayAudioVolume.linear(Float.POSITIVE_INFINITY), 0f)
        assertThrows(IllegalArgumentException::class.java) { AirPlayAudioVolume.linear(Float.NaN) }
    }

    @Test fun lateSamplesAreRoundedUpAndBounded() {
        assertEquals(0, AudioPlaybackTiming.lateFrames(0, 44_100, 1024))
        assertEquals(1, AudioPlaybackTiming.lateFrames(1, 44_100, 1024))
        assertEquals(441, AudioPlaybackTiming.lateFrames(10_000, 44_100, 1024))
        assertEquals(480, AudioPlaybackTiming.lateFrames(10_000, 48_000, 512))
        assertEquals(512, AudioPlaybackTiming.lateFrames(120_000, 48_000, 512))
        assertEquals(512, AudioPlaybackTiming.lateFrames(Long.MAX_VALUE, 48_000, 512))
        assertEquals(0, AudioPlaybackTiming.lateFrames(120_000, 48_000, 0))
    }

    @Test fun lateSampleCalculationRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) { AudioPlaybackTiming.lateFrames(-1, 48_000, 512) }
        assertThrows(IllegalArgumentException::class.java) { AudioPlaybackTiming.lateFrames(1, 0, 512) }
        assertThrows(IllegalArgumentException::class.java) { AudioPlaybackTiming.lateFrames(1, 48_000, -1) }
    }

    @Test fun writeAheadIncludesHardwarePresentationLatency() {
        val now = 1_000_000L
        assertTrue(AudioPlaybackTiming.shouldWait(now + 50_000, now, now, 40_000))
        assertFalse(AudioPlaybackTiming.shouldWait(now + 130_000, now, now + 100_000, 40_000))
        assertTrue(AudioPlaybackTiming.shouldWait(now + 150_000, now, now + 100_000, 40_000))
        assertFalse(AudioPlaybackTiming.shouldWait(now + 40_000, now, now - 100_000, 40_000))
    }
}
