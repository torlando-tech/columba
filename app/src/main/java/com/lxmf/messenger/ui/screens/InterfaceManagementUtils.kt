package com.lxmf.messenger.ui.screens

import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.lxmf.messenger.data.database.entity.InterfaceEntity

/**
 * Helper utilities for Interface Management UI.
 */

/**
 * Check if this interface is a BLE (Bluetooth Low Energy) interface.
 */
fun InterfaceEntity.isBleInterface(): Boolean {
    return this.type == "AndroidBLE"
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
 * Get an error message explaining why this BLE interface cannot operate.
 *
 * @param bluetoothState Current Bluetooth adapter state
 * @param permissionsGranted Whether BLE permissions are granted
 * @return Error message string, or null if no error
 */
fun InterfaceEntity.getErrorMessage(
    bluetoothState: Int,
    permissionsGranted: Boolean,
): String? {
    if (!isBleInterface()) {
        // Non-BLE interfaces don't have BT-related errors
        return null
    }

    return when {
        bluetoothState != BluetoothAdapter.STATE_ON -> "Bluetooth Off"
        !permissionsGranted -> "Permission Required"
        else -> null
    }
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
