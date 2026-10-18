package com.sal7one.tvtestproject.ui

import android.content.Context

object PicturePreferences {
    private const val PREFERENCES = "mirror_picture"
    private const val MODE = "mode"

    fun read(context: Context): PictureMode = PictureMode.fromStored(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(MODE, null),
    )

    fun write(context: Context, mode: PictureMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(MODE, mode.name).apply()
    }
}
