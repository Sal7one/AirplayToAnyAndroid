package com.sal7one.tvtestproject.receiver.media

/** Worker-owned rolling counters. No frames, payloads, or per-frame histories are retained. */
internal class VideoTimingMetrics(private val intervalUs: Long = 5_000_000L) {
    data class Snapshot(val inputs: Long, val outputs: Long, val maxQueueDepth: Int,
        val averageQueueUs: Long, val maxQueueUs: Long, val lateOutputs: Long, val maxLateUs: Long)

    private var windowStartUs: Long? = null
    private var inputs = 0L
    private var outputs = 0L
    private var queueTotalUs = 0L
    private var maxQueueUs = 0L
    private var maxQueueDepth = 0
    private var lateOutputs = 0L
    private var maxLateUs = 0L

    init { require(intervalUs > 0) }

    fun recordInput(queueUs: Long, depth: Int) {
        require(queueUs >= 0 && depth >= 0)
        inputs++
        queueTotalUs += queueUs
        maxQueueUs = maxOf(maxQueueUs, queueUs)
        maxQueueDepth = maxOf(maxQueueDepth, depth)
    }

    fun recordOutput(latenessUs: Long) {
        require(latenessUs >= 0)
        outputs++
        if (latenessUs > 0) lateOutputs++
        maxLateUs = maxOf(maxLateUs, latenessUs)
    }

    fun snapshotIfDue(nowUs: Long): Snapshot? {
        val startUs = windowStartUs
        if (startUs == null) { windowStartUs = nowUs; return null }
        if (nowUs - startUs < intervalUs) return null
        return Snapshot(inputs, outputs, maxQueueDepth,
            if (inputs == 0L) 0 else queueTotalUs / inputs, maxQueueUs, lateOutputs, maxLateUs).also {
            windowStartUs = nowUs
            inputs = 0
            outputs = 0
            queueTotalUs = 0
            maxQueueUs = 0
            maxQueueDepth = 0
            lateOutputs = 0
            maxLateUs = 0
        }
    }
}
