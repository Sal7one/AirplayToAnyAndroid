package com.sal7one.tvtestproject

import android.content.Context
import android.content.res.Configuration
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** Installation identity is independent of Wi-Fi/Ethernet interface order and address changes. */
object DeviceIdentity {
    private const val DISPLAY_NAME = "FossMirrorTV"
    private const val PREFERENCES = "receiver_identity"
    private const val DEVICE_ID = "device_id"
    private const val PAIRING_ID = "pairing_id"
    private val deviceIdPattern = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")

    fun displayName(): String = DISPLAY_NAME

    fun displayName(context: Context): String = displayName(deviceId(context),
        context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION)

    fun displayName(deviceId: String, television: Boolean): String {
        require(deviceIdPattern.matches(deviceId)) { "Invalid receiver device ID" }
        val suffix = deviceId.replace(":", "").takeLast(6).uppercase(Locale.ROOT)
        return "$DISPLAY_NAME - ${if (television) "TV" else "Phone"} $suffix"
    }

    fun deviceId(context: Context): String = airplayDeviceId(mac6Bytes(context))

    @Synchronized
    fun mac6Bytes(context: Context): ByteArray {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.getString(DEVICE_ID, null)
        if (saved != null && deviceIdPattern.matches(saved)) {
            return saved.split(':').map { it.toInt(16).toByte() }.toByteArray()
        }
        val address = ByteArray(6).also { SecureRandom().nextBytes(it) }
        address[0] = ((address[0].toInt() or 0x02) and 0xfe).toByte()
        check(preferences.edit().putString(DEVICE_ID, airplayDeviceId(address)).commit()) {
            "Could not persist receiver device identity"
        }
        return address
    }

    @Synchronized
    fun pairingId(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(PAIRING_ID, null)?.let { saved ->
            try {
                return UUID.fromString(saved).toString()
            } catch (_: IllegalArgumentException) {
                // Replace an invalid persisted value, never advertise a malformed pairing identifier.
            }
        }
        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(PAIRING_ID, generated).commit()) {
            "Could not persist receiver pairing identity"
        }
        return generated
    }

    fun airplayDeviceId(mac: ByteArray): String {
        require(mac.size == 6) { "Device identity must have six bytes" }
        return mac.joinToString(":") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    fun raopServiceInstanceName(mac: ByteArray): String =
        raopServiceInstanceName(airplayDeviceId(mac), DISPLAY_NAME)

    fun raopServiceInstanceName(deviceId: String, name: String): String {
        require(deviceIdPattern.matches(deviceId)) { "Invalid receiver device ID" }
        return "${deviceId.replace(":", "").uppercase(Locale.ROOT)}@$name"
    }
}
