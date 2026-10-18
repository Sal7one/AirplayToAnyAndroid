package com.sal7one.tvtestproject

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sal7one.tvtestproject.receiver.PlaybackMode
import com.sal7one.tvtestproject.receiver.PlaybackPreferences
import com.sal7one.tvtestproject.receiver.ReceiverAction
import com.sal7one.tvtestproject.receiver.ReceiverPresentation
import com.sal7one.tvtestproject.receiver.ReceiverState
import com.sal7one.tvtestproject.receiver.ReceiverStatus
import com.sal7one.tvtestproject.ui.PictureMode
import com.sal7one.tvtestproject.ui.PicturePreferences
import com.sal7one.tvtestproject.ui.PictureSizing

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var receiverName: TextView
    private lateinit var receiverDevice: TextView
    private lateinit var streamName: TextView
    private lateinit var receiverStatus: TextView
    private lateinit var statusPanel: View
    private lateinit var stopButton: MaterialButton
    private lateinit var restartButton: MaterialButton
    private lateinit var streamControls: View
    private lateinit var stateLabel: TextView
    private lateinit var statusDot: View
    private lateinit var network: TextView
    private lateinit var peer: TextView
    private lateinit var streamStatus: TextView
    private lateinit var streamStop: MaterialButton
    private lateinit var streamRestart: MaterialButton
    private lateinit var streamReconnect: MaterialButton
    private lateinit var streamResume: MaterialButton
    private lateinit var receiverSpeed: MaterialButton
    private lateinit var streamSpeed: MaterialButton
    private lateinit var pictureModes: MaterialButtonToggleGroup
    private lateinit var pictureFit: MaterialButton
    private lateinit var pictureFill: MaterialButton
    private var pictureMode = PictureMode.FIT
    private var receiver: ReceiverService.LocalBinder? = null
    private var bound = false
    private var surface: Surface? = null
    private var currentStatus = ReceiverStatus(ReceiverState.STARTING, "")
    private var pendingAction: ReceiverAction? = null
    private var disconnectPending = false
    private var confirmation: AlertDialog? = null
    private var confirmationSession: ReceiverStatus? = null
    private val hideControls = Runnable { hideStreamControls() }
    private val actionTimeout = Runnable {
        pendingAction = null
        disconnectPending = false
        renderStatus(currentStatus)
        receiverStatus.text = getString(R.string.receiver_action_timeout)
        streamStatus.text = getString(R.string.receiver_action_timeout)
        if (currentStatus.state == ReceiverState.STREAMING) showStreamControls()
    }
    private val statusListener: (ReceiverStatus) -> Unit = { renderStatus(it, acknowledgeAction = true) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ReceiverService.LocalBinder ?: return
            receiver = binder
            showReceiverName(binder.name)
            binder.addStatusListener(statusListener)
            binder.attachSurface(surface)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiver = null
            renderStatus(ReceiverStatus(ReceiverState.ERROR, getString(R.string.receiver_service_disconnected)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = findViewById(R.id.fullscreen_surface)
        receiverName = findViewById(R.id.receiver_name)
        receiverDevice = findViewById(R.id.receiver_device_label)
        streamName = findViewById(R.id.stream_name)
        receiverStatus = findViewById(R.id.receiver_status)
        statusPanel = findViewById(R.id.receiver_panel)
        stopButton = findViewById(R.id.receiver_stop)
        restartButton = findViewById(R.id.receiver_restart)
        streamControls = findViewById(R.id.stream_controls)
        stateLabel = findViewById(R.id.receiver_state)
        statusDot = findViewById(R.id.receiver_status_dot)
        network = findViewById(R.id.receiver_network)
        peer = findViewById(R.id.receiver_peer)
        streamStatus = findViewById(R.id.stream_status)
        streamStop = findViewById(R.id.stream_stop)
        streamRestart = findViewById(R.id.stream_restart)
        streamReconnect = findViewById(R.id.stream_reconnect)
        streamResume = findViewById(R.id.stream_resume)
        receiverSpeed = findViewById(R.id.receiver_speed)
        streamSpeed = findViewById(R.id.stream_speed)
        pictureModes = findViewById(R.id.stream_picture_mode)
        pictureFit = findViewById(R.id.stream_picture_fit)
        pictureFill = findViewById(R.id.stream_picture_fill)
        pictureMode = PicturePreferences.read(this)
        pictureModes.check(if (pictureMode == PictureMode.FIT) R.id.stream_picture_fit else R.id.stream_picture_fill)
        stylePictureButtons()
        pictureModes.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                pictureMode = if (checkedId == R.id.stream_picture_fill) PictureMode.FILL else PictureMode.FIT
                PicturePreferences.write(this, pictureMode)
                applyPictureSizing()
                scheduleHide()
            }
        }
        surfaceView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyPictureSizing()
        }
        findViewById<TextView>(R.id.receiver_version).text =
            packageManager.getPackageInfo(packageName, 0).versionName
        surfaceView.holder.addCallback(this)
        showReceiverName(DeviceIdentity.displayName(this))
        restartButton.setOnClickListener { requestAction(ReceiverAction.RESTART) }
        streamRestart.setOnClickListener { requestAction(ReceiverAction.RESTART) }
        streamReconnect.setOnClickListener { requestReconnect() }
        stopButton.setOnClickListener { requestAction(ReceiverPresentation.from(currentStatus).primaryAction) }
        streamStop.setOnClickListener { requestAction(ReceiverAction.STOP) }
        streamResume.setOnClickListener { hideStreamControls() }
        receiverSpeed.setOnClickListener { showPlaybackModes() }
        streamSpeed.setOnClickListener { showPlaybackModes() }
        surfaceView.setOnClickListener { if (currentStatus.state == ReceiverState.STREAMING) showStreamControls() }
        updatePlaybackModeLabels()
        for (button in listOf(streamStop, streamRestart, streamReconnect, streamResume, streamSpeed, pictureFit, pictureFill)) {
            button.setOnFocusChangeListener { _, focused -> if (focused) scheduleHide() }
        }
        restartButton.requestFocus()
        runServiceAction { ReceiverService.start(this) }
        bound = bindService(Intent(this, ReceiverService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        receiver?.attachSurface(surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        receiver?.attachSurface(surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        receiver?.detachSurface(surface)
        surface = null
    }

    override fun onDestroy() {
        confirmation?.dismiss()
        streamControls.removeCallbacks(hideControls)
        statusPanel.removeCallbacks(actionTimeout)
        receiver?.removeStatusListener(statusListener)
        receiver?.detachSurface(surface)
        receiver = null
        surfaceView.holder.removeCallback(this)
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentStatus.state == ReceiverState.STREAMING && confirmation == null) {
            val revealKey = event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (streamControls.visibility != View.VISIBLE && revealKey) {
                if (event.action == KeyEvent.ACTION_UP) showStreamControls()
                return true
            }
            if (streamControls.visibility == View.VISIBLE) {
                scheduleHide()
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.action == KeyEvent.ACTION_UP) hideStreamControls()
                    return true
                }
            }
        } else if (confirmation == null && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showPlaybackModes()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showStreamControls() {
        streamControls.visibility = View.VISIBLE
        streamResume.requestFocus()
        scheduleHide()
    }

    private fun scheduleHide() {
        streamControls.removeCallbacks(hideControls)
        if (confirmation == null && streamControls.visibility == View.VISIBLE && currentStatus.state == ReceiverState.STREAMING) {
            streamControls.postDelayed(hideControls, 8_000)
        }
    }

    private fun hideStreamControls() {
        streamControls.removeCallbacks(hideControls)
        streamControls.visibility = View.GONE
        surfaceView.requestFocus()
    }

    private fun applyPictureSizing() {
        val transform = PictureSizing.transform(
            surfaceView.width, surfaceView.height, currentStatus.videoWidth, currentStatus.videoHeight, pictureMode,
        )
        surfaceView.pivotX = surfaceView.width / 2f
        surfaceView.pivotY = surfaceView.height / 2f
        surfaceView.scaleX = transform.scaleX
        surfaceView.scaleY = transform.scaleY
    }

    private fun stylePictureButtons() {
        val states = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_checked), intArrayOf())
        val fill = ColorStateList(states, intArrayOf(
            ContextCompat.getColor(this, R.color.mirror_accent),
            ContextCompat.getColor(this, R.color.mirror_cyan),
            ContextCompat.getColor(this, R.color.mirror_surface),
        ))
        val content = ColorStateList(states, intArrayOf(
            ContextCompat.getColor(this, R.color.mirror_background),
            ContextCompat.getColor(this, R.color.mirror_background),
            ContextCompat.getColor(this, R.color.mirror_text),
        ))
        for (button in listOf(pictureFit, pictureFill)) {
            button.backgroundTintList = fill
            button.setTextColor(content)
        }
    }

    private fun playbackModeLabel(mode: PlaybackMode): String = getString(when (mode) {
        PlaybackMode.RESPONSIVE -> R.string.receiver_playback_responsive
        PlaybackMode.BALANCED -> R.string.receiver_playback_balanced
        PlaybackMode.SMOOTH -> R.string.receiver_playback_smooth
    })

    private fun updatePlaybackModeLabels() {
        val label = playbackModeLabel(PlaybackPreferences.read(this))
        for (button in listOf(receiverSpeed, streamSpeed)) {
            button.text = label
            button.contentDescription = getString(R.string.receiver_playback_mode_description, label)
        }
    }

    private fun showPlaybackModes() {
        if (confirmation != null) return
        val modes = PlaybackMode.entries
        val selected = PlaybackPreferences.read(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.receiver_playback_mode)
            .setSingleChoiceItems(modes.map(::playbackModeLabel).toTypedArray(), modes.indexOf(selected)) { choice, position ->
                PlaybackPreferences.write(this, modes[position])
                updatePlaybackModeLabels()
                Toast.makeText(this, R.string.receiver_playback_mode_next_connection, Toast.LENGTH_SHORT).show()
                choice.dismiss()
            }
            .setNegativeButton(R.string.receiver_cancel, null)
            .create()
        dialog.setOnShowListener {
            val choices = dialog.listView
            choices.post {
                if (confirmation === dialog && dialog.isShowing) {
                    choices.requestFocusFromTouch()
                    choices.setSelection(modes.indexOf(selected))
                }
            }
        }
        showDialog(dialog)
    }

    private fun showDialog(dialog: AlertDialog, session: ReceiverStatus? = null) {
        streamControls.removeCallbacks(hideControls)
        confirmation = dialog
        confirmationSession = session
        dialog.setOnDismissListener {
            if (confirmation === dialog) {
                confirmation = null
                confirmationSession = null
                scheduleHide()
            }
        }
        dialog.show()
    }

    private fun focusCancel(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    private fun requestAction(action: ReceiverAction) {
        if (confirmation != null) return
        val model = ReceiverPresentation.from(currentStatus, pendingAction)
        if (model.busy) return
        if (model.needsConfirmation) {
            val requestedPeer = currentStatus.peer
            val dialog = AlertDialog.Builder(this)
                .setTitle(if (action == ReceiverAction.RESTART) R.string.receiver_restart_confirm else R.string.receiver_stop_confirm)
                .setMessage(if (action == ReceiverAction.RESTART)
                    getString(R.string.receiver_restart_message, receiver?.name ?: DeviceIdentity.displayName(this))
                    else getString(R.string.receiver_interrupt_message))
                .setNegativeButton(R.string.receiver_cancel, null)
                .setPositiveButton(if (action == ReceiverAction.RESTART) R.string.receiver_restart else R.string.receiver_stop) { _, _ ->
                    if (pendingAction == null && currentStatus.peer == requestedPeer) performAction(action)
                }.create()
            showDialog(dialog, currentStatus)
            focusCancel(dialog)
        } else performAction(action)
    }

    private fun requestReconnect() {
        if (confirmation != null || currentStatus.state != ReceiverState.STREAMING ||
            ReceiverPresentation.from(currentStatus, pendingAction).busy) return
        val requestedPeer = currentStatus.peer
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.receiver_reconnect_confirm)
            .setMessage(getString(R.string.receiver_reconnect_message, receiver?.name ?: DeviceIdentity.displayName(this)))
            .setNegativeButton(R.string.receiver_cancel, null)
            .setPositiveButton(R.string.receiver_disconnect) { _, _ ->
                if (pendingAction != null || currentStatus.state != ReceiverState.STREAMING ||
                    currentStatus.peer != requestedPeer) return@setPositiveButton
                disconnectPending = true
                pendingAction = ReceiverAction.RESTART
                renderStatus(currentStatus)
                statusPanel.removeCallbacks(actionTimeout)
                statusPanel.postDelayed(actionTimeout, 20_000)
                runServiceAction {
                    val service = checkNotNull(receiver) { getString(R.string.receiver_service_disconnected) }
                    service.disconnectClient()
                }
            }.create()
        showDialog(dialog, currentStatus)
        focusCancel(dialog)
    }

    private fun performAction(action: ReceiverAction) {
        disconnectPending = false
        pendingAction = action
        renderStatus(currentStatus)
        statusPanel.removeCallbacks(actionTimeout)
        statusPanel.postDelayed(actionTimeout, 20_000)
        runServiceAction {
            when (action) {
                ReceiverAction.START -> ReceiverService.start(this)
                ReceiverAction.STOP -> ReceiverService.stop(this)
                ReceiverAction.RESTART -> ReceiverService.restart(this)
            }
        }
    }

    private fun runServiceAction(action: () -> Unit) {
        try {
            action()
        } catch (error: RuntimeException) {
            pendingAction = null
            disconnectPending = false
            statusPanel.removeCallbacks(actionTimeout)
            renderStatus(ReceiverStatus(
                ReceiverState.ERROR,
                getString(R.string.receiver_start_failed, error.message ?: error.javaClass.simpleName),
            ))
        }
    }

    private fun renderStatus(status: ReceiverStatus, acknowledgeAction: Boolean = false) {
        val wasStreaming = currentStatus.state == ReceiverState.STREAMING
        currentStatus = status
        confirmationSession?.let { session ->
            if (status.peer != session.peer || status.state !in listOf(ReceiverState.CONNECTED, ReceiverState.STREAMING, ReceiverState.ERROR)) {
                confirmation?.dismiss()
            }
        }
        val disconnectComplete = status.state == ReceiverState.READY || status.state == ReceiverState.STOPPED ||
            status.state == ReceiverState.ERROR
        if (acknowledgeAction && (if (disconnectPending) disconnectComplete else pendingAction?.isComplete(status.state) == true)) {
            pendingAction = null
            disconnectPending = false
            statusPanel.removeCallbacks(actionTimeout)
        }
        val model = ReceiverPresentation.from(status, pendingAction)
        applyPictureSizing()
        showReceiverName(receiver?.name ?: DeviceIdentity.displayName(this))
        val label = getString(when (status.state) {
            ReceiverState.STOPPED -> R.string.receiver_state_stopped
            ReceiverState.STARTING -> R.string.receiver_state_starting
            ReceiverState.READY -> R.string.receiver_state_ready
            ReceiverState.CONNECTED -> R.string.receiver_state_connected
            ReceiverState.STREAMING -> R.string.receiver_state_streaming
            ReceiverState.ERROR -> R.string.receiver_state_error
        })
        stateLabel.text = label
        val color = ContextCompat.getColor(this, when (status.state) {
            ReceiverState.ERROR -> R.color.mirror_error
            ReceiverState.STOPPED -> R.color.mirror_muted
            ReceiverState.CONNECTED, ReceiverState.STREAMING -> R.color.mirror_cyan
            else -> R.color.mirror_accent
        })
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        receiverStatus.setTextColor(color)
        receiverStatus.text = status.message.ifBlank { label }
        network.text = status.addresses.joinToString("\n").ifBlank { getString(R.string.receiver_network_unavailable) }
        peer.text = status.peer ?: getString(R.string.receiver_no_session)
        streamStatus.text = listOfNotNull(label, status.peer).joinToString("  /  ")
        statusPanel.visibility = if (model.streaming) View.GONE else View.VISIBLE
        if (model.streaming != wasStreaming || !model.streaming) {
            hideStreamControls()
        }
        stopButton.setText(when {
            model.busy -> R.string.receiver_action_pending
            status.state == ReceiverState.ERROR -> R.string.receiver_retry
            model.primaryAction == ReceiverAction.START -> R.string.receiver_start
            else -> R.string.receiver_stop
        })
        stopButton.isEnabled = !model.busy
        restartButton.isEnabled = model.canRestart
        streamStop.isEnabled = !model.busy
        streamRestart.isEnabled = model.canRestart
        streamReconnect.isEnabled = !model.busy && status.state == ReceiverState.STREAMING
        if (!model.streaming && (currentFocus !is MaterialButton || currentFocus?.isEnabled != true)) {
            (if (model.canRestart) restartButton else stopButton).requestFocus()
        }
    }

    private fun showReceiverName(name: String) {
        receiverName.text = DeviceIdentity.displayName()
        receiverDevice.text = name.removePrefix("${DeviceIdentity.displayName()} - ")
        receiverDevice.contentDescription = name
        streamName.text = name
    }
}
