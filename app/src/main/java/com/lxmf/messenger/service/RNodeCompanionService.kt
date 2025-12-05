package com.lxmf.messenger.service

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
     * Called when an associated RNode device appears (connects or comes into BLE range).
     * Android 13+ (API 33) version with AssociationInfo.
     */
    @SuppressLint("MissingPermission")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "RNode device appeared: ${associationInfo.displayName ?: "Unknown"}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = associationInfo.associatedDevice?.bluetoothDevice
            if (device != null) {
                Log.d(TAG, "Device: ${device.name ?: device.address}")
            }
        }
    }

    /**
     * Called when an associated RNode device disappears (disconnects or goes out of BLE range).
     * Android 13+ (API 33) version with AssociationInfo.
     */
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "RNode device disappeared: ${associationInfo.displayName ?: "Unknown"}")
    }

    /**
     * Legacy callback for Android 12 (API 31-32).
     * Called when an associated device appears.
     */
    @Deprecated("Use onDeviceAppeared(AssociationInfo) for Android 13+")
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "RNode device appeared (legacy): $address")
    }

    /**
     * Legacy callback for Android 12 (API 31-32).
     * Called when an associated device disappears.
     */
    @Deprecated("Use onDeviceDisappeared(AssociationInfo) for Android 13+")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "RNode device disappeared (legacy): $address")
    }
}
