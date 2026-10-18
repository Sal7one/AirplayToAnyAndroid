package com.sal7one.tvtestproject

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sal7one.tvtestproject.receiver.AirPlayServer
import com.sal7one.tvtestproject.receiver.ReceiverAnnouncement
import com.sal7one.tvtestproject.receiver.ReceiverState
import com.sal7one.tvtestproject.receiver.ReceiverStatus
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class ReceiverService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "FossMirrorTV-lifecycle")
    }
    private val listeners = CopyOnWriteArraySet<(ReceiverStatus) -> Unit>()
    private val binder = LocalBinder()
    private lateinit var connectivity: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foreground = false

    @Volatile private var destroyed = false
    @Volatile private var desiredSurface: Surface? = null
    @Volatile private var receiverName = DeviceIdentity.displayName()
    @Volatile private var lastStatus = ReceiverStatus(ReceiverState.STOPPED, "Stopped")

    // These fields belong to the lifecycle worker, not the activity or a process-global engine.
    private var engine: AirPlayServer? = null
    private var announcement: ReceiverAnnouncement? = null
    private var generation = 0L
    private var engineStatus = lastStatus
    private val networkAddresses = mutableMapOf<Network, List<Inet4Address>>()
    private val advertisements = mutableMapOf<Inet4Address, JmDNS>()
    private var discoveryError: String? = null
    private var monitorError: String? = null
    private var surfaceError: String? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    inner class LocalBinder : Binder() {
        val name: String get() = receiverName
        val status: ReceiverStatus get() = lastStatus

        fun addStatusListener(listener: (ReceiverStatus) -> Unit) {
            listeners.add(listener)
            main.post { if (listener in listeners) listener(lastStatus) }
        }

        fun removeStatusListener(listener: (ReceiverStatus) -> Unit) {
            listeners.remove(listener)
        }

        fun attachSurface(surface: Surface?) {
            if (desiredSurface === surface) return
            desiredSurface = surface
            submit { attachCurrentSurface() }
        }

        fun detachSurface(surface: Surface?) {
            if (desiredSurface === surface) attachSurface(null)
        }

        fun disconnectClient() {
            submit {
                val current = engine
                if (current == null) {
                    startEngine()
                    return@submit
                }
                try {
                    current.disconnectClient()
                } catch (error: Exception) {
                    publishDisconnectFailure(error)
                } catch (error: LinkageError) {
                    publishDisconnectFailure(error)
                }
            }
        }
    }

    private fun publishDisconnectFailure(error: Throwable) {
        Log.e(TAG, "Current client disconnect failed", error)
        engineStatus = ReceiverStatus(
            ReceiverState.ERROR,
            getString(R.string.receiver_disconnect_failed, error.message ?: error.javaClass.simpleName),
            engineStatus.peer,
        )
        publishCurrentStatus()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        receiverName = DeviceIdentity.displayName(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.receiver_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        registerNetworkMonitor()
        worker.scheduleWithFixedDelay({
            if (!destroyed && announcement != null) reconcileDiscovery()
        }, DISCOVERY_RETRY_SECONDS, DISCOVERY_RETRY_SECONDS, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            submit {
                val stopped = stopEngine()
                publishCurrentStatus()
                main.post {
                    if (!destroyed && stopped && stopSelfResult(startId)) stopForegroundNotification()
                }
            }
            return START_NOT_STICKY
        }
        ensureForeground()
        if (networkCallback == null) registerNetworkMonitor()
        submit {
            if (intent?.action == ACTION_RESTART && !stopEngine()) {
                publishCurrentStatus()
                return@submit
            }
            if (engine == null) startEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        desiredSurface = null
        networkCallback?.let { callback ->
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (error: Exception) {
                Log.w(TAG, "Could not unregister network monitor", error)
            }
        }
        networkCallback = null
        // close() and JmDNS.close() may block; never wait for them on Android's main thread.
        worker.execute {
            stopEngine()
            networkAddresses.clear()
        }
        worker.shutdown()
        listeners.clear()
        stopForegroundNotification()
        super.onDestroy()
    }

    private fun submit(task: () -> Unit) {
        if (destroyed) return
        try {
            worker.execute { if (!destroyed) task() }
        } catch (_: RejectedExecutionException) {
            // A network callback can race service destruction.
        }
    }

    private fun startEngine() {
        val currentGeneration = ++generation
        engineStatus = ReceiverStatus(ReceiverState.STARTING, getString(R.string.receiver_starting))
        publishCurrentStatus()
        var candidate: AirPlayServer? = null
        try {
            candidate = AirPlayServer(applicationContext) { status ->
                submit {
                    if (generation == currentGeneration && engine != null) {
                        engineStatus = status
                        publishCurrentStatus()
                        reconcileDiscovery()
                    }
                }
            }
            engine = candidate
            val started = candidate.start()
            UxPlayDnsTxt.validate(started)
            if (destroyed) {
                stopEngine()
                return
            }
            announcement = started
            receiverName = started.name
            engineStatus = ReceiverStatus(ReceiverState.READY, getString(R.string.receiver_ready))
            attachCurrentSurface()
            acquireMulticastLock()
            reconcileDiscovery()
        } catch (error: Exception) {
            failStartup(candidate, error)
        } catch (error: LinkageError) {
            failStartup(candidate, error)
        }
    }

    private fun failStartup(candidate: AirPlayServer?, error: Throwable) {
        Log.e(TAG, "Receiver startup failed", error)
        ++generation
        engine = null
        announcement = null
        try {
            candidate?.close()
        } catch (closeError: Exception) {
            Log.w(TAG, "Failed to close receiver after startup failure", closeError)
        } catch (closeError: LinkageError) {
            Log.w(TAG, "Failed to close unavailable native receiver", closeError)
        }
        closeAdvertisements()
        releaseMulticastLock()
        engineStatus = ReceiverStatus(
            ReceiverState.ERROR,
            getString(R.string.receiver_start_failed, error.message ?: error.javaClass.simpleName),
        )
        publishCurrentStatus()
    }

    private fun attachCurrentSurface() {
        try {
            engine?.attachSurface(desiredSurface?.takeIf { it.isValid })
            surfaceError = null
        } catch (error: Exception) {
            Log.e(TAG, "Surface attachment failed", error)
            surfaceError = getString(R.string.receiver_surface_failed, error.message ?: error.javaClass.simpleName)
        }
        publishCurrentStatus()
    }

    private fun stopEngine(): Boolean {
        ++generation
        val previous = engine
        engine = null
        announcement = null
        var failure: Throwable? = null
        try {
            previous?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Receiver close failed", error)
            failure = error
            engine = previous
        } catch (error: LinkageError) {
            Log.w(TAG, "Native receiver close failed", error)
            failure = error
            engine = previous
        } finally {
            closeAdvertisements()
            releaseMulticastLock()
        }
        discoveryError = null
        surfaceError = null
        engineStatus = if (failure == null) {
            ReceiverStatus(ReceiverState.STOPPED, getString(R.string.receiver_stopped))
        } else {
            ReceiverStatus(ReceiverState.ERROR, getString(
                R.string.receiver_stop_failed, failure.message ?: failure.javaClass.simpleName,
            ))
        }
        return failure == null
    }

    private fun registerNetworkMonitor() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val addresses = ipv4Addresses(linkProperties)
                submit {
                    networkAddresses[network] = addresses
                    reconcileDiscovery()
                }
            }

            override fun onLost(network: Network) {
                submit {
                    networkAddresses.remove(network)
                    reconcileDiscovery()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
            networkCallback = callback
            submit {
                monitorError = null
                connectivity.activeNetwork?.let { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                        networkAddresses[network] = ipv4Addresses(connectivity.getLinkProperties(network))
                    }
                }
                reconcileDiscovery()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Network monitor failed", error)
            submit {
                monitorError = getString(
                    R.string.receiver_network_failed,
                    error.message ?: error.javaClass.simpleName,
                )
                publishCurrentStatus()
            }
        }
    }

    private fun ipv4Addresses(properties: LinkProperties?): List<Inet4Address> =
        properties?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }

    private fun reconcileDiscovery() {
        val started = announcement ?: run {
            publishCurrentStatus()
            return
        }
        if (engineStatus.state == ReceiverState.ERROR || engineStatus.state == ReceiverState.STOPPED) {
            closeAdvertisements()
            publishCurrentStatus()
            return
        }
        val addresses = networkAddresses.values.flatten().toSet()
        for (address in advertisements.keys.toList()) {
            if (address !in addresses) advertisements.remove(address)?.let { closeAdvertisement(it) }
        }
        discoveryError = null
        for (address in addresses) {
            if (destroyed) return
            if (address in advertisements) continue
            var dns: JmDNS? = null
            try {
                dns = JmDNS.create(address, started.name)
                dns.registerService(ServiceInfo.create(
                    "_airplay._tcp.local.", started.name, started.port, 0, 0,
                    UxPlayDnsTxt.airplayTxt(started),
                ))
                dns.registerService(ServiceInfo.create(
                    "_raop._tcp.local.",
                    DeviceIdentity.raopServiceInstanceName(started.deviceId, started.name),
                    started.port, 0, 0, UxPlayDnsTxt.raopTxt(started),
                ))
                advertisements[address] = dns
                Log.i(TAG, "Advertised ${started.name} on ${address.hostAddress}:${started.port}")
            } catch (error: Exception) {
                dns?.let { closeAdvertisement(it) }
                discoveryError = getString(
                    R.string.receiver_discovery_failed,
                    error.message ?: error.javaClass.simpleName,
                )
                Log.e(TAG, "Discovery registration failed on $address", error)
            }
        }
        publishCurrentStatus()
    }

    private fun publishCurrentStatus() {
        val status = when {
            engineStatus.state == ReceiverState.ERROR || engineStatus.state == ReceiverState.STOPPED -> engineStatus
            surfaceError != null -> ReceiverStatus(ReceiverState.ERROR, surfaceError!!, engineStatus.peer)
            engineStatus.state == ReceiverState.CONNECTED || engineStatus.state == ReceiverState.STREAMING -> engineStatus
            monitorError != null -> ReceiverStatus(ReceiverState.ERROR, monitorError!!)
            announcement == null -> engineStatus
            discoveryError != null -> ReceiverStatus(ReceiverState.ERROR, discoveryError!!)
            advertisements.isEmpty() -> ReceiverStatus(ReceiverState.STARTING, getString(R.string.receiver_waiting_network))
            else -> engineStatus.copy(state = ReceiverState.READY, message = getString(R.string.receiver_ready))
        }
        val port = announcement?.port
        publish(status.copy(addresses = networkAddresses.values.flatten().mapNotNull { address ->
            address.hostAddress?.let { host -> if (port != null) "$host:$port" else host }
        }.distinct().sorted()))
    }

    private fun closeAdvertisements() {
        val previous = advertisements.values.toList()
        advertisements.clear()
        previous.forEach { closeAdvertisement(it) }
    }

    private fun closeAdvertisement(dns: JmDNS) {
        try {
            dns.unregisterAllServices()
        } catch (error: Exception) {
            Log.w(TAG, "Could not unregister discovery records", error)
        }
        try {
            dns.close()
        } catch (error: Exception) {
            Log.w(TAG, "Could not close discovery socket", error)
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("FossMirrorTV-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not acquire Wi-Fi multicast lock", error)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (error: Exception) {
            Log.w(TAG, "Could not release Wi-Fi multicast lock", error)
        }
        multicastLock = null
    }

    private fun publish(status: ReceiverStatus) {
        main.post {
            if (destroyed) return@post
            if (status == lastStatus) return@post
            lastStatus = status
            for (listener in listeners) listener(status)
            if (foreground) getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun stopForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(receiverName)
            .setContentText(lastStatus.message)
            .setSmallIcon(R.drawable.ic_mirror_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FossReceiver"
        private const val CHANNEL_ID = "foss_airplay_receiver"
        private const val NOTIFICATION_ID = 7001
        private const val DISCOVERY_RETRY_SECONDS = 15L
        private const val ACTION_RESTART = "com.sal7one.tvtestproject.RESTART_RECEIVER"
        private const val ACTION_STOP = "com.sal7one.tvtestproject.STOP_RECEIVER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReceiverService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }
}
