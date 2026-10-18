package com.sal7one.tvtestproject.receiver.media

import org.junit.Assert.*
import org.junit.Test

class VideoTimingMetricsTest {
    @Test fun snapshotsAreBoundedAndResetBetweenWindows() {
        val metrics = VideoTimingMetrics(100)
        assertNull(metrics.snapshotIfDue(0))
        metrics.recordInput(10, 1)
        metrics.recordInput(30, 12)
        metrics.recordOutput(0)
        metrics.recordOutput(60)
        assertNull(metrics.snapshotIfDue(99))
        assertEquals(VideoTimingMetrics.Snapshot(2, 2, 12, 20, 30, 1, 60), metrics.snapshotIfDue(100))
        assertEquals(VideoTimingMetrics.Snapshot(0, 0, 0, 0, 0, 0, 0), metrics.snapshotIfDue(200))
    }

    @Test fun invalidCountersAreRejected() {
        val metrics = VideoTimingMetrics()
        assertThrows(IllegalArgumentException::class.java) { metrics.recordInput(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { metrics.recordOutput(-1) }
    }
}
