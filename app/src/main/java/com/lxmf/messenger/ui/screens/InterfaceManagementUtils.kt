package com.lxmf.messenger.ui.screens

import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import org.json.JSONException
import org.json.JSONObject

// Interface Management UI helper utilities.

/**
 * Check if this interface requires Bluetooth to operate.
 * - AndroidBLE interfaces always require Bluetooth
 * - RNode interfaces require Bluetooth ONLY for classic or ble connection modes
 *   (TCP and USB modes do not require Bluetooth)
 */
@Suppress("SwallowedException")
fun InterfaceEntity.isBleInterface(): Boolean {
    return when (type) {
        "AndroidBLE" -> true
        "RNode" -> {
            try {
                val json = JSONObject(configJson)
                val connectionMode = json.optString("connection_mode", "classic")
                // Only classic and ble modes require Bluetooth
                connectionMode == "classic" || connectionMode == "ble"
            } catch (e: JSONException) {
                // Malformed JSON defaults to requiring Bluetooth (conservative fallback)
                true
            }
        }
        else -> false
    }
}

/**
 * Determine if this BLE interface can currently operate.
 *
 * @param bluetoothOn Whether Bluetooth is turned on
 * @param permissionsGranted Whether BLE permissions are granted
 * @return true if the interface can operate, false otherwise
 */
fun InterfaceEntity.canOperate(
    bluetoothOn: Boolean,
    permissionsGranted: Boolean,
): Boolean {
    if (!isBleInterface()) {
        // Non-BLE interfaces can always operate
        return true
    }

    // BLE interfaces require both Bluetooth on AND permissions granted
    return bluetoothOn && permissionsGranted
}

/**
 * Get an error message explaining why this interface has an issue.
 *
 * @param bluetoothState Current Bluetooth adapter state
 * @param permissionsGranted Whether BLE permissions are granted
 * @param isOnline Whether the interface is currently online (from debug info)
 * @return Error message string, or null if no error
 */
@Suppress("ReturnCount")
fun InterfaceEntity.getErrorMessage(
    bluetoothState: Int,
    permissionsGranted: Boolean,
    isOnline: Boolean? = null,
): String? {
    // Check BLE-related errors for BLE interfaces
    if (isBleInterface()) {
        when {
            bluetoothState != BluetoothAdapter.STATE_ON -> return "Bluetooth Off"
            !permissionsGranted -> return "Permission Required"
        }
    }

    // Check online status for all enabled interfaces
    if (enabled && isOnline == false) {
        return "Interface Offline"
    }

    return null
}

/**
 * Check if Bluetooth is currently in the ON state.
 */
fun Int.isBluetoothOn(): Boolean {
    return this == BluetoothAdapter.STATE_ON
}

/**
 * Determine if the toggle should be enabled for this interface.
 *
 * @param bluetoothState Current Bluetooth adapter state
 * @param permissionsGranted Whether BLE permissions are granted
 * @return true if toggle should be enabled, false if it should be disabled/greyed
 */
fun InterfaceEntity.shouldToggleBeEnabled(
    bluetoothState: Int,
    permissionsGranted: Boolean,
): Boolean {
    val isBle = isBleInterface()
    val btOn = bluetoothState.isBluetoothOn()
    val result =
        if (!isBle) {
            // Non-BLE interfaces can always be toggled
            true
        } else {
            // BLE toggle is enabled only when BT is on AND permissions granted
            btOn && permissionsGranted
        }

    Log.d(
        "InterfaceUtils",
        "shouldToggleBeEnabled(${this.name}): isBLE=$isBle, btState=$bluetoothState, btOn=$btOn, permissions=$permissionsGranted, result=$result",
    )
    return result
}
