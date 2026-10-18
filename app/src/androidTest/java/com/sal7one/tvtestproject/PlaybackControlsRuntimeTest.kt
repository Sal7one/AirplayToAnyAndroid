package com.sal7one.tvtestproject

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sal7one.tvtestproject.receiver.PlaybackMode
import com.sal7one.tvtestproject.receiver.PlaybackPreferences
import com.sal7one.tvtestproject.receiver.ReceiverState
import com.sal7one.tvtestproject.receiver.ReceiverStatus
import com.sal7one.tvtestproject.receiver.VideoConfig
import com.sal7one.tvtestproject.receiver.media.H264Config
import com.sal7one.tvtestproject.receiver.media.VideoPipeline
import com.sal7one.tvtestproject.ui.PictureMode
import com.sal7one.tvtestproject.ui.PicturePreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated-emulator UI tests. They start/stop the local receiver, never a remote sender. */
@RunWith(AndroidJUnit4::class)
class PlaybackControlsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before @After fun stopReceiver() {
        onMain { context.stopService(Intent(context, ReceiverService::class.java)) }
        instrumentation.waitForIdleSync()
    }

    @Test fun remoteSpeedChoiceAndPictureModePersistWithoutReplacingTheSurface() {
        val oldSpeed = PlaybackPreferences.read(context)
        val oldPicture = PicturePreferences.read(context)
        try {
            PlaybackPreferences.write(context, PlaybackMode.BALANCED)
            PicturePreferences.write(context, PictureMode.FIT)
            withActivity { activity, present ->
                val view = activity.findViewById<SurfaceView>(R.id.fullscreen_surface)
                val producerSurface = view.holder.surface
                val changes = SurfaceChanges()
                onMain {
                    view.holder.addCallback(changes)
                    present(streaming(1920, 1080))
                    revealControls(activity)
                    activity.findViewById<View>(R.id.stream_speed).performClick()
                }
                instrumentation.waitForIdleSync()
                var focusPolls = 0
                await("Speed dialog did not focus the saved choice") {
                    dialog(activity)?.let {
                        if (focusPolls++ % 50 == 0) android.util.Log.i("FossControlsTest",
                            "speed_choice window=${it.window?.decorView?.hasWindowFocus()} touch=${it.listView.isInTouchMode} " +
                                "focus=${it.listView.hasFocus()} selected=${it.listView.selectedItemPosition} checked=${it.listView.checkedItemPosition}")
                        it.window?.decorView?.hasWindowFocus() == true &&
                            it.listView.selectedItemPosition == PlaybackMode.BALANCED.ordinal
                    } == true
                }
                onMain {
                    val choices = dialog(activity)!!.listView
                    assertEquals(PlaybackMode.BALANCED.ordinal, choices.checkedItemPosition)
                    assertTrue("Speed choices must take D-pad focus", choices.hasFocus())
                    assertEquals(PlaybackMode.BALANCED.ordinal, choices.selectedItemPosition)
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("D-pad speed selection was not persisted") {
                    PlaybackPreferences.read(context) == PlaybackMode.RESPONSIVE && dialog(activity) == null
                }
                onMain {
                    assertEquals(context.getString(R.string.receiver_playback_responsive),
                        activity.findViewById<MaterialButton>(R.id.stream_speed).text.toString())
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.stream_controls).visibility)
                    activity.findViewById<View>(R.id.stream_picture_fill).performClick()
                }
                instrumentation.waitForIdleSync()
                assertEquals(PictureMode.FILL, PicturePreferences.read(context))
                assertSame(producerSurface, view.holder.surface)
                assertTrue(view.holder.surface.isValid)
                assertEquals("Menu changed surface geometry", 0, changes.changed.get())
                assertEquals("Menu destroyed the producer surface", 0, changes.destroyed.get())
            }
            withActivity { activity, _ ->
                onMain {
                    assertEquals(R.id.stream_picture_fill,
                        activity.findViewById<MaterialButtonToggleGroup>(R.id.stream_picture_mode).checkedButtonId)
                    assertEquals(context.getString(R.string.receiver_playback_responsive),
                        activity.findViewById<MaterialButton>(R.id.receiver_speed).text.toString())
                }
            }
        } finally {
            PlaybackPreferences.write(context, oldSpeed)
            PicturePreferences.write(context, oldPicture)
        }
    }

    @Test fun reconnectConfirmationCannotStackOrApplyToANewSession() {
        withActivity { activity, present ->
            lateinit var first: AlertDialog
            lateinit var second: AlertDialog
            onMain {
                present(streaming(1920, 1080))
                revealControls(activity)
                activity.findViewById<View>(R.id.stream_reconnect).performClick()
                first = checkNotNull(dialog(activity))
                assertTrue(first.getButton(AlertDialog.BUTTON_NEGATIVE).hasFocus())
                activity.findViewById<View>(R.id.stream_restart).performClick()
                activity.findViewById<View>(R.id.stream_stop).performClick()
                activity.findViewById<View>(R.id.stream_speed).performClick()
                assertSame("A second action replaced the pending confirmation", first, dialog(activity))
                present(ReceiverStatus(ReceiverState.READY, "Ready"))
                assertFalse("Old confirmation survived the session ending", first.isShowing)
            }
            await("Dismissed confirmation was retained") { dialog(activity) == null }
            onMain {
                present(streaming(1920, 1080).copy(peer = "192.0.2.20"))
                revealControls(activity)
                activity.findViewById<View>(R.id.stream_reconnect).performClick()
                second = checkNotNull(dialog(activity))
                present(streaming(1920, 1080).copy(peer = "192.0.2.21"))
                assertFalse("Confirmation could disconnect a different peer", second.isShowing)
            }
            await("Peer-change confirmation was retained") { dialog(activity) == null }
        }
    }

    @Test fun fitFillKeepsRealDecoderReferenceFramesAndSurfaceProducer() {
        val oldPicture = PicturePreferences.read(context)
        PicturePreferences.write(context, PictureMode.FIT)
        try {
            withActivity { activity, present ->
                val view = activity.findViewById<SurfaceView>(R.id.fullscreen_surface)
                // Give the 16:9 fixture a 4:3 viewport so both transforms really change.
                onMain { view.layoutParams = view.layoutParams.apply { width = view.height * 4 / 3 } }
                await("Test viewport did not resize") { view.width == view.height * 4 / 3 && view.holder.surface.isValid }
                instrumentation.waitForIdleSync()
                val (config, units) = fixture()
                val changes = SurfaceChanges()
                val producerSurface = view.holder.surface
                onMain {
                    view.holder.addCallback(changes)
                    present(streaming(config.width, config.height))
                }
                val rendered = AtomicInteger()
                val error = AtomicReference<String?>()
                VideoPipeline(onError = { error.set(it) }, onRenderAttempt = { rendered.incrementAndGet() }).use { decoder ->
                    decoder.attachSurface(view.holder.surface)
                    assertTrue(decoder.configure(config))
                    assertTrue(decoder.enqueue(units.first(), System.nanoTime() / 1000 + 20_000, true))
                    await("Initial IDR did not render") { rendered.get() == 1 || error.get() != null }
                    assertNull(error.get())
                    onMain {
                        revealControls(activity)
                        activity.findViewById<View>(R.id.stream_picture_fill).performClick()
                        assertTrue("Fill did not enlarge the wide picture", view.scaleX > 1f)
                        assertEquals(1f, view.scaleY, 0.001f)
                        activity.findViewById<View>(R.id.stream_picture_fit).performClick()
                        assertEquals(1f, view.scaleX, 0.001f)
                        assertTrue("Fit did not letterbox the wide picture", view.scaleY < 1f)
                    }
                    for (unit in units.drop(1)) {
                        assertTrue(decoder.enqueue(unit, System.nanoTime() / 1000 + 20_000, false))
                        SystemClock.sleep(40)
                    }
                    assertColoredPixels(view)
                    assertNull(error.get())
                    assertEquals("Sizing reset the decoder revision", 1, rendered.get())
                    assertSame(producerSurface, view.holder.surface)
                    assertTrue(view.holder.surface.isValid)
                    assertEquals("Sizing changed producer dimensions", 0, changes.changed.get())
                    assertEquals("Sizing destroyed the producer surface", 0, changes.destroyed.get())
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        java.io.File(context.cacheDir, "playback-controls-qa.png").outputStream().use {
                            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { screenshot.recycle() }
                }
            }
        } finally { PicturePreferences.write(context, oldPicture) }
    }

    private fun withActivity(block: (MainActivity, (ReceiverStatus) -> Unit) -> Unit) {
        // A phone AVD boots in touch mode; exercise the TV's real D-pad selection behavior.
        @Suppress("DEPRECATION")
        instrumentation.setInTouchMode(false)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await("Receiver did not bind") { field(activity, "receiver") != null }
            val binder = field(activity, "receiver") as ReceiverService.LocalBinder
            await("Receiver startup did not settle") { binder.status.state == ReceiverState.READY }
            val view = activity.findViewById<SurfaceView>(R.id.fullscreen_surface)
            await("Activity surface is unavailable") { view.holder.surface.isValid && view.width > 0 }
            @Suppress("UNCHECKED_CAST")
            val listener = field(activity, "statusListener") as (ReceiverStatus) -> Unit
            // Isolate presentation from discovery callbacks; do not add fake RTSP success
            // or a test-only production hook. The video test below uses real MediaCodec.
            onMain { binder.removeStatusListener(listener) }
            block(activity, listener)
        } finally { onMain { activity.finish() }; instrumentation.waitForIdleSync() }
    }

    private fun streaming(width: Int, height: Int) = ReceiverStatus(
        ReceiverState.STREAMING, "Mirroring", "192.0.2.10", videoWidth = width, videoHeight = height,
    )

    private fun revealControls(activity: MainActivity) {
        if (activity.findViewById<View>(R.id.stream_controls).visibility != View.VISIBLE) {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.stream_controls).visibility)
    }

    private fun dialog(activity: MainActivity) = field(activity, "confirmation") as AlertDialog?
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail(message)
    }

    private class SurfaceChanges : SurfaceHolder.Callback {
        val changed = AtomicInteger()
        val destroyed = AtomicInteger()
        override fun surfaceCreated(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { changed.incrementAndGet() }
        override fun surfaceDestroyed(holder: SurfaceHolder) { destroyed.incrementAndGet() }
    }

    private fun fixture(): Pair<VideoConfig, List<ByteArray>> {
        val bytes = instrumentation.context.assets.open("black-to-color.h264").use { it.readBytes() }
        val starts = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index <= bytes.size - 3) {
            val code = if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) 3
                else if (index <= bytes.size - 4 && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) 4 else 0
            } else 0
            if (code > 0) { starts.add(index to code); index += code } else index++
        }
        val nals = starts.mapIndexed { i, (start, code) ->
            bytes.copyOfRange(start + code, starts.getOrNull(i + 1)?.first ?: bytes.size)
        }
        fun type(nal: ByteArray) = nal[0].toInt() and 31
        val config = H264Config.fromParameterSets(nals.filter { type(it) == 7 }, nals.filter { type(it) == 8 }, 4)
        val units = mutableListOf<MutableList<ByteArray>>()
        for (nal in nals) {
            if (type(nal) == 9) units.add(mutableListOf())
            if (type(nal) !in listOf(7, 8, 9)) units.last().add(nal)
        }
        assertTrue(units.first().any { type(it) == 5 })
        assertFalse("Fixture has another recovery IDR", units.drop(1).flatten().any { type(it) == 5 })
        return config to units.map { unit ->
            ByteArrayOutputStream().apply {
                for (nal in unit) {
                    for (shift in listOf(24, 16, 8, 0)) write(nal.size ushr shift and 255)
                    write(nal)
                }
            }.toByteArray()
        }
    }

    private fun assertColoredPixels(view: SurfaceView) {
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            do {
                val copied = CountDownLatch(1)
                var status = -1
                PixelCopy.request(view.holder.surface, bitmap, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue(copied.await(2, TimeUnit.SECONDS))
                if (status == PixelCopy.SUCCESS) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    if (pixels.count { maxOf(Color.red(it), Color.green(it), Color.blue(it)) -
                            minOf(Color.red(it), Color.green(it), Color.blue(it)) > 80 } > pixels.size / 3) return
                }
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            fail("Only black/stale pixels remained after changing picture size without another IDR")
        } finally { bitmap.recycle() }
    }
}
