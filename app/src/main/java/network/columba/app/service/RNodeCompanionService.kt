package network.columba.app.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import network.columba.app.rns.host.ReticulumService

/**
 * Companion Device Service for RNode Bluetooth devices.
 *
 * This service enables Android to recognize Columba as the companion app for associated
 * RNode devices. When a user taps "Connect" on an RNode in system Bluetooth settings,
 * Android will launch Columba.
 *
 * The service binding is automatically managed by the system based on device presence:
 * - Bound when the associated RNode is within BLE range or connected via Bluetooth
 * - Unbound when the device moves out of range or disconnects
 *
 * Android uses these presence transitions to tell us the radio is back. The service
 * forwards them to [ReticulumService], whose backend re-arms the RNode reconnect
 * supervisor. Without this signal a radio that left BLE range for hours could sit
 * offline until the app was reopened (the interface's retry loop can be parked in a
 * backoff, and the bridge's direct GATT attempts fail while the radio is absent).
 *
 * Requires Android 12 (API 31) or higher.
 */
@RequiresApi(Build.VERSION_CODES.S)
class RNodeCompanionService : CompanionDeviceService() {
    companion object {
        private const val TAG = "RNodeCompanionService"

        // BLE appearance and BT connection are delivered as separate events for
        // the same physical reconnect. Forward only the first one.
        private const val PRESENCE_DEBOUNCE_MS = 3_000L
    }

    @Volatile
    private var lastPresenceRequestElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RNodeCompanionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RNodeCompanionService destroyed")
    }

    /**
     * Android 16+ (API 36) presence stream. One callback distinguishes BLE
     * appearance from a Bluetooth connection; both mean the radio is reachable.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED,
            -> requestRNodeReconnect("presence event ${event.event}")
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED,
            -> Log.d(TAG, "████ RNODE ABSENT ████ event=${event.event}")
            else -> Log.d(TAG, "RNode presence event=${event.event}")
        }
    }

    /**
     * Android 13+ (API 33) callback when an associated RNode appears. Kept for
     * devices where the API 36 presence stream is not delivered.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("MissingPermission")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val deviceName = associationInfo.displayName ?: "Unknown"
        Log.d(TAG, "████ RNODE APPEARED ████ name=$deviceName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val device = associationInfo.associatedDevice?.bluetoothDevice
            if (device != null) {
                Log.d(TAG, "Device: ${device.name ?: device.address}")
            }
        }
        requestRNodeReconnect("device appeared")
    }

    /**
     * Android 13+ (API 33) callback when an associated RNode disappears.
     */
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.d(TAG, "████ RNODE DISAPPEARED ████")
    }

    /**
     * Android 12 (API 31-32) legacy callback for device appearance.
     */
    @Deprecated("Use onDeviceAppeared(AssociationInfo) for Android 13+")
    override fun onDeviceAppeared(address: String) {
        Log.d(TAG, "████ RNODE APPEARED (legacy) ████ address=$address")
        requestRNodeReconnect("legacy device appeared")
    }

    /**
     * Android 12 (API 31-32) legacy callback for device disappearance.
     */
    @Deprecated("Use onDeviceDisappeared(AssociationInfo) for Android 13+")
    override fun onDeviceDisappeared(address: String) {
        Log.d(TAG, "████ RNODE DISAPPEARED (legacy) ████ address=$address")
    }

    /**
     * Tell the Reticulum service the radio is reachable. The service ignores the
     * request when every live RNode is already online, so repeated BLE/BT events
     * cannot tear down a working link.
     */
    private fun requestRNodeReconnect(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPresenceRequestElapsedMs < PRESENCE_DEBOUNCE_MS) {
            Log.d(TAG, "Suppressing duplicate RNode presence request ($reason)")
            return
        }
        lastPresenceRequestElapsedMs = now
        Log.i(TAG, "RNode reachable ($reason); asking Reticulum service to re-arm reconnect")

        val intent =
            Intent(this, ReticulumService::class.java).apply {
                action = ReticulumService.ACTION_RNODE_PRESENCE
            }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Missing companion FGS exemption (association removed/updated) or a
            // restricted background start; the interface's own retry loop remains
            // the fallback, so this is not fatal.
            Log.w(TAG, "Could not start ReticulumService for RNode presence", e)
        }
    }
}
