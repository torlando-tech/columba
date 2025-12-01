package com.lxmf.messenger.reticulum.ble.model

/**
 * Represents the state of the BLE service and its connections.
 */
sealed class BleConnectionState {
    /**
     * BLE service is idle, not scanning or connected.
     */
    object Idle : BleConnectionState()

    /**
     * BLE service is scanning for nearby devices.
     */
    object Scanning : BleConnectionState()

    /**
     * Attempting to connect to a device.
     * @property address MAC address of device being connected to
     */
    data class Connecting(val address: String) : BleConnectionState()

    /**
     * Connected to one or more devices.
     * @property peers List of connected peer addresses
     * @property centralConnections Number of connections where we are central
     * @property peripheralConnections Number of connections where we are peripheral
     */
    data class Connected(
        val peers: List<String>,
        val centralConnections: Int = 0,
        val peripheralConnections: Int = 0,
    ) : BleConnectionState() {
        val totalConnections: Int get() = centralConnections + peripheralConnections
    }

    /**
     * BLE service encountered an error.
     * @property message Error description
     * @property recoverable Whether the error can be recovered from
     */
    data class Error(
        val message: String,
        val recoverable: Boolean = true,
    ) : BleConnectionState()

    /**
     * BLE is disabled on the device.
     */
    object BluetoothDisabled : BleConnectionState()

    /**
     * BLE permissions are not granted.
     * @property missingPermissions List of missing permissions
     */
    data class PermissionDenied(
        val missingPermissions: List<String>,
    ) : BleConnectionState()
}
