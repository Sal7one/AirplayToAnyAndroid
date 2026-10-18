package com.sal7one.tvtestproject

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sal7one.tvtestproject.ui.PictureMode
import com.sal7one.tvtestproject.ui.PicturePreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReceiverControlsTest {
    @Test fun pictureModeSurvivesNewPreferenceReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = PicturePreferences.read(context)
        try {
            for (mode in PictureMode.entries) {
                PicturePreferences.write(context, mode)
                assertEquals(mode, PicturePreferences.read(context.applicationContext))
            }
        } finally { PicturePreferences.write(context, previous) }
    }

    @Test fun streamActionsFitSmallTvViewportsAndStayFocusableInArabic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try {
                for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ar"))) {
                    for ((width, height) in listOf(1920 to 1080, 960 to 540, 720 to 480)) {
                        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                            setLocale(locale)
                            setLayoutDirection(locale)
                            densityDpi = 240
                            screenWidthDp = width * 160 / densityDpi
                            screenHeightDp = height * 160 / densityDpi
                            smallestScreenWidthDp = screenHeightDp
                            orientation = Configuration.ORIENTATION_LANDSCAPE
                        }
                        val context = ContextThemeWrapper(
                            instrumentation.targetContext.createConfigurationContext(configuration), R.style.Theme_TvTestProject,
                        )
                        val host = FrameLayout(context)
                        val root = LayoutInflater.from(context).inflate(R.layout.activity_main, host, false) as ViewGroup
                        host.addView(root)
                        root.findViewById<View>(R.id.receiver_panel).visibility = View.GONE
                        root.findViewById<View>(R.id.stream_controls).visibility = View.VISIBLE
                        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        host.layout(0, 0, width, height)
                        val controls = listOf(R.id.stream_picture_fit, R.id.stream_picture_fill, R.id.stream_speed,
                            R.id.stream_stop, R.id.stream_restart, R.id.stream_reconnect, R.id.stream_resume)
                        val visibleBounds = mutableListOf<Rect>()
                        for (id in controls) {
                            val button = root.findViewById<MaterialButton>(id)
                            val bounds = Rect(0, 0, button.width, button.height)
                            root.offsetDescendantRectToMyCoords(button, bounds)
                            assertTrue("$locale ${width}x$height: button clipped: $bounds",
                                bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height)
                            assertTrue(button.isFocusable)
                            assertFalse(button.contentDescription.isNullOrBlank())
                            assertNotNull(button.tooltipText)
                            if (id == R.id.stream_reconnect) {
                                assertTrue("Reconnect icon and label need a gap", button.iconPadding > 0)
                                val textWidth = button.layout?.getLineWidth(0) ?: 0f
                                assertTrue("Reconnect label is clipped",
                                    textWidth <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight)
                            }
                            // ToggleGroup intentionally shares the Fit/Fill stroke. Treat
                            // that segmented control as one item when checking row overlap.
                            if (id != R.id.stream_picture_fill) {
                                val item = if (id == R.id.stream_picture_fit) root.findViewById<View>(R.id.stream_picture_mode) else button
                                val itemBounds = Rect(0, 0, item.width, item.height)
                                root.offsetDescendantRectToMyCoords(item, itemBounds)
                                for (other in visibleBounds) assertFalse("Streaming controls overlap: $other and $itemBounds", Rect.intersects(other, itemBounds))
                                visibleBounds.add(itemBounds)
                            }
                        }
                        val modes = root.findViewById<MaterialButtonToggleGroup>(R.id.stream_picture_mode)
                        assertTrue(modes.isSingleSelection)
                        assertTrue(modes.isSelectionRequired)
                        modes.check(R.id.stream_picture_fill)
                        assertEquals(listOf(R.id.stream_picture_fill), modes.checkedButtonIds)
                        modes.check(R.id.stream_picture_fit)
                        assertEquals(listOf(R.id.stream_picture_fit), modes.checkedButtonIds)
                        assertEquals(R.id.stream_picture_fill, root.findViewById<View>(R.id.stream_picture_fit).nextFocusRightId)
                        assertEquals(R.id.stream_reconnect, root.findViewById<View>(R.id.stream_speed).nextFocusRightId)
                        assertEquals(View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
    }
}
