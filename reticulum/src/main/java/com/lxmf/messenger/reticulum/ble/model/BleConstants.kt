package com.lxmf.messenger.reticulum.ble.model

import java.util.UUID

/**
 * BLE constants and configuration for Reticulum interface.
 *
 * These UUIDs and constants match the ble-reticulum Python implementation
 * to ensure compatibility.
 */
object BleConstants {
    /**
     * Reticulum BLE Service UUID.
     * All Reticulum BLE devices advertise and scan for this service.
     * Protocol v2.2 standard UUID.
     */
    val SERVICE_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e3")

    /**
     * RX Characteristic UUID (we write data here to send to peer).
     * Properties: WRITE, WRITE_WITHOUT_RESPONSE
     * Protocol v2.2 standard UUID.
     */
    val CHARACTERISTIC_RX_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e5")

    /**
     * TX Characteristic UUID (peer notifies us here to send data to us).
     * Properties: READ, NOTIFY
     * Protocol v2.2 standard UUID.
     */
    val CHARACTERISTIC_TX_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e4")

    /**
     * Identity Characteristic UUID (provides stable node identity).
     * Properties: READ
     * Value: 16-byte Reticulum Transport identity hash
     * Protocol v2.2 standard UUID.
     *
     * This characteristic enables stable peer tracking across MAC address rotations.
     * Particularly important for Android devices which rotate BLE MAC addresses
     * every ~15 minutes for privacy.
     */
    val CHARACTERISTIC_IDENTITY_UUID: UUID = UUID.fromString("37145b00-442d-4a94-917f-8f42c5da28e6")

    /**
     * Client Characteristic Configuration Descriptor (CCCD) UUID.
     * Standard Bluetooth UUID for enabling notifications.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Connection Parameters
    /**
     * Maximum number of simultaneous peer connections.
     * Android typically supports ~8 total BLE connections across all apps.
     */
    const val MAX_CONNECTIONS = 7

    /**
     * Minimum RSSI (signal strength) to consider for connection.
     * Devices weaker than this are ignored.
     */
    const val MIN_RSSI_DBM = -85

    /**
     * Maximum MTU (Maximum Transmission Unit) to request.
     * Android supports up to 517 bytes (512 data + 5 header).
     * Requesting larger MTU reduces packet fragmentation.
     */
    const val MAX_MTU = 517

    /**
     * Minimum MTU guaranteed by BLE spec.
     * Used as fallback if MTU negotiation fails.
     */
    const val MIN_MTU = 23

    /**
     * Default MTU if negotiation doesn't happen.
     * Typical value is 185 bytes for many Android devices.
     */
    const val DEFAULT_MTU = 185

    // Scanning Parameters
    /**
     * Discovery interval in milliseconds (active scanning).
     * How often to start a new scan when actively discovering.
     */
    const val DISCOVERY_INTERVAL_MS = 5000L // 5 seconds

    /**
     * Discovery interval in milliseconds (idle mode).
     * Reduced scan frequency when no new devices are found.
     */
    const val DISCOVERY_INTERVAL_IDLE_MS = 30000L // 30 seconds

    /**
     * Scan duration in milliseconds.
     * How long each individual scan should last.
     */
    const val SCAN_DURATION_MS = 10000L // 10 seconds

    // Connection Timeouts
    /**
     * Connection attempt timeout in milliseconds.
     * If connection doesn't complete in this time, consider it failed.
     */
    const val CONNECTION_TIMEOUT_MS = 30000L // 30 seconds

    /**
     * Operation timeout in milliseconds.
     * Timeout for individual GATT operations (read/write/discover).
     */
    const val OPERATION_TIMEOUT_MS = 5000L // 5 seconds

    // Retry Configuration
    /**
     * Maximum number of connection failures before blacklisting a device.
     */
    const val MAX_CONNECTION_FAILURES = 3

    /**
     * Base retry backoff in milliseconds.
     * Exponential backoff starts from this value.
     */
    const val CONNECTION_RETRY_BACKOFF_MS = 30000L // 30 seconds

    /**
     * Maximum retry backoff in milliseconds.
     * Exponential backoff caps at this value (8x base).
     */
    const val MAX_CONNECTION_RETRY_BACKOFF_MS = CONNECTION_RETRY_BACKOFF_MS * 8

    // Fragmentation
    /**
     * Fragment header size in bytes.
     * Format: [Type: 1][Sequence: 2][Total: 2] = 5 bytes
     */
    const val FRAGMENT_HEADER_SIZE = 5

    /**
     * Fragment types.
     */
    const val FRAGMENT_TYPE_LONE: Byte = 0x00
    const val FRAGMENT_TYPE_START: Byte = 0x01
    const val FRAGMENT_TYPE_CONTINUE: Byte = 0x02
    const val FRAGMENT_TYPE_END: Byte = 0x03

    /**
     * Reassembly timeout in milliseconds.
     * Incomplete packet reassembly buffers are cleared after this time.
     */
    const val REASSEMBLY_TIMEOUT_MS = 30000L // 30 seconds

    // Service Configuration
    /**
     * Notification channel ID for BLE foreground service.
     */
    const val NOTIFICATION_CHANNEL_ID = "ble_service_channel"

    /**
     * Notification ID for BLE foreground service.
     */
    const val NOTIFICATION_ID = 2 // ID 1 might be used by ReticulumService

    /**
     * Default device name prefix for BLE advertising.
     */
    const val DEFAULT_DEVICE_NAME_PREFIX = "Reticulum-"

    /**
     * Number of identity hash bytes to include in advertised device name.
     *
     * BLE advertising has a 31-byte payload limit. With flags (3 bytes) and
     * a 128-bit service UUID (19 bytes), only ~9 bytes remain for the device name.
     *
     * Full identity is 16 bytes (32 hex chars), but we truncate to 3 bytes (6 hex chars)
     * to fit within BLE advertising constraints. This results in device names like
     * "RNS-1b9d2b" (10 characters total).
     *
     * The full identity is still exchanged via the GATT Identity characteristic
     * during connection handshake (Protocol v2.2).
     */
    const val IDENTITY_BYTES_IN_ADVERTISED_NAME = 3

    // Error Codes
    /**
     * GATT error code 133.
     * Undocumented but common error indicating connection issues or stack problems.
     * Usually requires retry with exponential backoff.
     */
    const val GATT_ERROR_133 = 133

    /**
     * GATT error code 201 - ERROR_GATT_WRITE_REQUEST_BUSY.
     * The GATT stack is busy processing a previous operation.
     * Common when trying to write descriptor immediately after MTU change.
     * Usually resolved by waiting 150ms and retrying.
     */
    const val ERROR_GATT_WRITE_REQUEST_BUSY = 201

    /**
     * Additional common GATT error codes for reference.
     */
    const val GATT_SUCCESS = 0
    const val GATT_WRITE_NOT_PERMITTED = 3
    const val GATT_INVALID_HANDLE = 13
    const val GATT_INSUFFICIENT_ENCRYPTION = 15
    const val GATT_REQUEST_NOT_SUPPORTED = 143

    // Timing Constants
    /**
     * Delay in milliseconds to allow BLE stack to settle after MTU negotiation.
     * The BLE stack may still be processing internally even after onMtuChanged callback fires.
     * This delay prevents ERROR_GATT_WRITE_REQUEST_BUSY (201) errors.
     */
    const val POST_MTU_SETTLE_DELAY_MS = 150L

    /**
     * Connection keepalive interval in milliseconds.
     * Send periodic packet every 7 seconds to prevent Android BLE idle disconnects.
     *
     * Android has multiple timeout mechanisms:
     * - GATT supervision timeout: 20-30 seconds of inactivity (status code 8)
     * - L2CAP idle timer: ~20 seconds with no active logical channels
     *
     * The L2CAP idle timer (`l2cu_no_dynamic_ccbs`) is more aggressive and can
     * trigger even when data is being received if outgoing GATT operations fail.
     * 7 seconds provides sufficient margin for both timeout mechanisms.
     */
    const val CONNECTION_KEEPALIVE_INTERVAL_MS = 7000L // 7 seconds

    /**
     * Maximum consecutive keepalive write failures before disconnecting.
     * Write failures indicate the GATT session is degraded even if data is still
     * being received. Disconnect early to allow reconnection.
     */
    const val MAX_KEEPALIVE_WRITE_FAILURES = 2
}
