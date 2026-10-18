package com.sal7one.tvtestproject.ui

enum class PictureMode {
    FIT, FILL;

    companion object {
        fun fromStored(value: String?): PictureMode = entries.firstOrNull { it.name == value } ?: FIT
    }
}

data class PictureTransform(val scaleX: Float, val scaleY: Float)

object PictureSizing {
    fun transform(
        viewportWidth: Int,
        viewportHeight: Int,
        videoWidth: Int?,
        videoHeight: Int?,
        mode: PictureMode,
    ): PictureTransform {
        if (viewportWidth <= 0 || viewportHeight <= 0 || videoWidth == null || videoHeight == null ||
            videoWidth <= 0 || videoHeight <= 0) return PictureTransform(1f, 1f)

        val horizontal = viewportWidth.toDouble() / videoWidth
        val vertical = viewportHeight.toDouble() / videoHeight
        val scale = if (mode == PictureMode.FIT) minOf(horizontal, vertical) else maxOf(horizontal, vertical)
        // SurfaceView still fills its parent and keeps its existing producer. Only its
        // displayed surface is transformed, so switching modes does not reset MediaCodec.
        return PictureTransform(
            (videoWidth * scale / viewportWidth).toFloat(),
            (videoHeight * scale / viewportHeight).toFloat(),
        )
    }
}
