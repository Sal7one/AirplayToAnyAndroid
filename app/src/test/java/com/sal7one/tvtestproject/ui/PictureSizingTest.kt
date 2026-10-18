package com.sal7one.tvtestproject.ui

import org.junit.Assert.*
import org.junit.Test

class PictureSizingTest {
    @Test fun fitKeepsAllOfAFourByThreePicture() {
        val result = PictureSizing.transform(1920, 1080, 1440, 1080, PictureMode.FIT)
        assertEquals(0.75f, result.scaleX, 0.0001f)
        assertEquals(1f, result.scaleY, 0.0001f)
        assertAspectRatio(1920, 1080, result, 4f / 3f)
    }

    @Test fun fillCropsFourByThreePictureInsteadOfStretchingIt() {
        val result = PictureSizing.transform(1920, 1080, 1440, 1080, PictureMode.FILL)
        assertEquals(1f, result.scaleX, 0.0001f)
        assertEquals(4f / 3f, result.scaleY, 0.0001f)
        assertAspectRatio(1920, 1080, result, 4f / 3f)
    }

    @Test fun fitAddsHorizontalBarsForUltrawideSource() {
        val result = PictureSizing.transform(1920, 1080, 3440, 1440, PictureMode.FIT)
        assertEquals(1f, result.scaleX, 0.0001f)
        assertTrue(result.scaleY < 1f)
        assertAspectRatio(1920, 1080, result, 3440f / 1440f)
    }

    @Test fun fillCropsUltrawideSourceHorizontally() {
        val result = PictureSizing.transform(1920, 1080, 3440, 1440, PictureMode.FILL)
        assertTrue(result.scaleX > 1f)
        assertEquals(1f, result.scaleY, 0.0001f)
        assertAspectRatio(1920, 1080, result, 3440f / 1440f)
    }

    @Test fun sameAspectRatioNeedsNoTransformAtAnyResolution() {
        for (mode in PictureMode.entries) {
            assertEquals(PictureTransform(1f, 1f), PictureSizing.transform(1920, 1080, 3840, 2160, mode))
        }
    }

    @Test fun portraitSourceIsNotStretchedIntoLandscape() {
        for (mode in PictureMode.entries) {
            assertAspectRatio(1280, 720, PictureSizing.transform(1280, 720, 900, 1600, mode), 900f / 1600f)
        }
    }

    @Test fun unknownOrInvalidDimensionsDoNotGuessAnAspectRatio() {
        for (mode in PictureMode.entries) {
            for ((width, height) in listOf(null to null, 1920 to null, null to 1080, 0 to 1080, 1920 to -1)) {
                assertEquals(PictureTransform(1f, 1f), PictureSizing.transform(1920, 1080, width, height, mode))
            }
            assertEquals(PictureTransform(1f, 1f), PictureSizing.transform(0, 1080, 1920, 1080, mode))
            assertEquals(PictureTransform(1f, 1f), PictureSizing.transform(1920, -1, 1920, 1080, mode))
        }
    }

    @Test fun layoutResizeRecomputesFromActualViewport() {
        val first = PictureSizing.transform(1920, 1080, 1440, 1080, PictureMode.FIT)
        val resized = PictureSizing.transform(1440, 1080, 1440, 1080, PictureMode.FIT)
        assertNotEquals(first, resized)
        assertEquals(PictureTransform(1f, 1f), resized)
    }

    @Test fun unknownStoredModesFallBackToFit() {
        assertEquals(PictureMode.FIT, PictureMode.fromStored(null))
        assertEquals(PictureMode.FIT, PictureMode.fromStored("invalid"))
        for (mode in PictureMode.entries) assertEquals(mode, PictureMode.fromStored(mode.name))
    }

    private fun assertAspectRatio(width: Int, height: Int, result: PictureTransform, expected: Float) {
        assertEquals(expected, width * result.scaleX / (height * result.scaleY), 0.0001f)
    }
}
