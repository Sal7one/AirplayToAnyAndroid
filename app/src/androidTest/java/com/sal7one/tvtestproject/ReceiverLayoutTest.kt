package com.sal7one.tvtestproject

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReceiverLayoutTest {
    @Test fun arabicLocaleKeepsBrandStatusAndControlsOnTheLeft() = checkLayout(Locale.forLanguageTag("ar"), false)

    @Test fun longStartupErrorKeepsButtonsVisibleInBothLayoutDirections() {
        checkLayout(Locale.ENGLISH, true)
        checkLayout(Locale.forLanguageTag("ar"), true)
    }

    private fun checkLayout(locale: Locale, error: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try {
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
                    val host = FrameLayout(context).apply { layoutDirection = configuration.layoutDirection }
                    val root = LayoutInflater.from(context).inflate(R.layout.activity_main, host, false) as ViewGroup
                    host.addView(root)
                    root.findViewById<TextView>(R.id.receiver_status).text = if (error) {
                        "Receiver failed: bind failed: EADDRINUSE (Address already in use)"
                    } else context.getString(R.string.receiver_ready)
                    root.findViewById<TextView>(R.id.receiver_device_label).text = "Phone A1B2C3"
                    host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, width, height)
                    assertEquals(View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
                    for (id in listOf(R.id.receiver_name, R.id.receiver_device_label, R.id.receiver_status, R.id.receiver_stop, R.id.receiver_restart, R.id.receiver_speed)) {
                        val view = root.findViewById<View>(id)
                        val bounds = Rect(0, 0, view.width, view.height)
                        root.offsetDescendantRectToMyCoords(view, bounds)
                        assertTrue("$locale ${width}x$height: control is not on the left: $bounds",
                            bounds.left >= 0 && bounds.right <= width * 0.53f)
                        assertTrue("$locale ${width}x$height: control is clipped: $bounds", bounds.top >= 0 && bounds.bottom <= height)
                        if (id == R.id.receiver_stop || id == R.id.receiver_restart) {
                            val viewport = root.findViewById<View>(R.id.receiver_content)
                            val visible = Rect(0, 0, viewport.width, viewport.height)
                            root.offsetDescendantRectToMyCoords(viewport, visible)
                            assertTrue("$locale ${width}x$height: action row clipped by content viewport",
                                visible.contains(bounds))
                        }
                    }
                    val details = root.findViewById<View>(R.id.receiver_details)
                    val actions = root.findViewById<View>(R.id.receiver_actions)
                    assertTrue("Error details overlap the controls", details.bottom <= actions.top)
                    assertTrue("Error details have no readable viewport", details.height >= 32 * context.resources.displayMetrics.density)
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
    }
}
