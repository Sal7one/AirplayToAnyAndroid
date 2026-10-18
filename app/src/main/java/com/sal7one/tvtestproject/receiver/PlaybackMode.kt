package com.sal7one.tvtestproject.receiver

/** Receiver-side shared A/V cushion, not a promise of glass-to-glass latency. */
enum class PlaybackMode(val playoutDelayUs: Long) {
    RESPONSIVE(100_000L), BALANCED(120_000L), SMOOTH(150_000L);

    fun maximumFps(hardwareSupports60: Boolean): Int =
        if (this == SMOOTH && hardwareSupports60) 60 else 30

    companion object {
        fun fromStored(value: String?): PlaybackMode = entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
