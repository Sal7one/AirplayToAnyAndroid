package com.sal7one.tvtestproject.receiver

import android.content.Context

object PlaybackPreferences {
    fun read(context: Context): PlaybackMode = PlaybackMode.fromStored(
        context.getSharedPreferences("receiver_playback", Context.MODE_PRIVATE).getString("mode", null),
    )

    fun write(context: Context, mode: PlaybackMode) {
        context.getSharedPreferences("receiver_playback", Context.MODE_PRIVATE)
            .edit().putString("mode", mode.name).apply()
    }
}
