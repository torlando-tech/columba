package network.columba.app.service

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

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
 * Reconnection is handled by NativeReticulumProtocol in the app process — the native
 * BLE bridge watches for RNode presence and reconnects automatically. This service
 * exists only so Android keeps the app in its companion-device association.
 *
 * Requires Android 12 (API 31) or higher.
 */
@RequiresApi(Build.VERSION_CODES.S)
class RNodeCompanionService : CompanionDeviceService() {
    companion object {
        private const val TAG = "RNodeCompanionService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RNodeCompanionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RNodeCompanionService destroyed")
    }

    /**
     * Android 13+ (API 33) — called when an associated RNode device appears (connects
     * or comes into BLE range).
     */
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
    }

    /**
     * Android 13+ (API 33) — called when an associated RNode device disappears.
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
    }

    /**
     * Android 12 (API 31-32) legacy callback for device disappearance.
     */
    @Deprecated("Use onDeviceDisappeared(AssociationInfo) for Android 13+")
    override fun onDeviceDisappeared(address: String) {
        Log.d(TAG, "████ RNODE DISAPPEARED (legacy) ████ address=$address")
    }
}
