package com.lxmf.messenger.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages a local-only WiFi hotspot for APK sharing without requiring
 * an existing WiFi network. Uses [WifiManager.startLocalOnlyHotspot]
 * (Android 8.0+) which creates a temporary hotspot that does not
 * provide internet access — only local connectivity.
 *
 * The hotspot is automatically torn down when [stop] is called or
 * the reservation is closed.
 */
class LocalHotspotManager(private val context: Context) {
    companion object {
        private const val TAG = "LocalHotspotManager"

        /** Minimum API level required for local-only hotspot. */
        const val MIN_API_LEVEL = Build.VERSION_CODES.O // 26

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_API_LEVEL
    }

    /**
     * Information about the started hotspot.
     */
    data class HotspotInfo(
        val ssid: String,
        val password: String,
    )

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var onSystemStoppedListener: (() -> Unit)? = null
    val isActive: Boolean get() = reservation != null

    /**
     * Start a local-only hotspot. The [callback] receives the hotspot
     * credentials on success, or an exception on failure.
     *
     * If the hotspot is already active, the callback is invoked immediately
     * with the existing credentials.
     *
     * [onSystemStopped] is invoked if Android tears down the hotspot
     * externally (e.g. another app requests a hotspot, user toggles it
     * off in Settings, or the system reclaims the channel). Callers
     * should use this to update UI state.
     *
     * Must be called from the main thread (or supply a Looper-backed Handler).
     * The callback is delivered on the main thread.
     *
     * Common failure reasons:
     * - [ERROR_TETHERING_DISALLOWED] — user/admin policy blocks tethering
     * - [ERROR_INCOMPATIBLE_MODE] — WiFi is in a state that conflicts
     * - [ERROR_NO_CHANNEL] — no suitable WiFi channel available
     */
    fun start(
        onSystemStopped: () -> Unit = {},
        callback: (Result<HotspotInfo>) -> Unit,
    ) {
        if (!isSupported()) {
            callback(Result.failure(UnsupportedOperationException(
                "Local-only hotspot requires Android 8.0 or higher"
            )))
            return
        }

        if (reservation != null) {
            Log.w(TAG, "Hotspot already active, re-delivering existing credentials")
            onSystemStoppedListener = onSystemStopped
            val info = extractHotspotInfo(reservation!!)
            callback(Result.success(info))
            return
        }

        onSystemStoppedListener = onSystemStopped

        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        val info = extractHotspotInfo(res)
                        Log.i(TAG, "Local hotspot started: SSID=${info.ssid}")
                        callback(Result.success(info))
                    }

                    override fun onStopped() {
                        Log.i(TAG, "Local hotspot stopped by system")
                        reservation = null
                        onSystemStoppedListener?.invoke()
                    }

                    override fun onFailed(reason: Int) {
                        Log.e(TAG, "Local hotspot failed with reason: $reason")
                        reservation = null
                        val message = when (reason) {
                            ERROR_TETHERING_DISALLOWED ->
                                "Hotspot is not allowed by device policy"
                            ERROR_INCOMPATIBLE_MODE ->
                                "WiFi is in a mode that prevents hotspot creation"
                            ERROR_NO_CHANNEL ->
                                "No WiFi channel available for hotspot"
                            ERROR_GENERIC ->
                                "Could not start hotspot"
                            else ->
                                "Hotspot failed (error $reason)"
                        }
                        callback(Result.failure(HotspotException(message, reason)))
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission for hotspot", e)
            callback(Result.failure(e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting hotspot", e)
            callback(Result.failure(e))
        }
    }

    /**
     * Stop the hotspot and release the reservation.
     * Safe to call even if not running.
     */
    fun stop() {
        try {
            reservation?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing hotspot reservation", e)
        }
        reservation = null
        onSystemStoppedListener = null
        Log.i(TAG, "Local hotspot stopped")
    }

    @Suppress("DEPRECATION")
    private fun extractHotspotInfo(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: Use SoftApConfiguration with getWifiSsid()
            val config = reservation.softApConfiguration
            val ssid = config.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
            val password = config.passphrase ?: ""
            HotspotInfo(ssid, password)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30-32: Use SoftApConfiguration with getSsid()
            val config = reservation.softApConfiguration
            val ssid = config.ssid?.removeSurrounding("\"") ?: ""
            val password = config.passphrase ?: ""
            HotspotInfo(ssid, password)
        } else {
            // API 26-29: Use deprecated WifiConfiguration
            val config = reservation.wifiConfiguration
            val ssid = config?.SSID?.removeSurrounding("\"") ?: ""
            val password = config?.preSharedKey?.removeSurrounding("\"") ?: ""
            HotspotInfo(ssid, password)
        }
    }

    class HotspotException(message: String, val reason: Int) : Exception(message)
}
