package com.lxmf.messenger.reticulum.ble.server

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE GATT Server for peripheral mode.
 *
 * Creates a GATT server with the Reticulum service and accepts incoming connections from
 * central devices. Handles characteristic reads/writes and sends notifications.
 *
 * **GATT Service Structure (Protocol v2.2):**
 * ```
 * Reticulum Service (37145b00-442d-4a94-917f-8f42c5da28e3)
 * ├── RX Characteristic (37145b00-442d-4a94-917f-8f42c5da28e5)
 * │   ├── Properties: WRITE, WRITE_WITHOUT_RESPONSE
 * │   └── Permissions: WRITE
 * │   └── Purpose: Centrals write data here → we receive (incl. identity handshake)
 * ├── TX Characteristic (37145b00-442d-4a94-917f-8f42c5da28e4)
 * │   ├── Properties: READ, NOTIFY
 * │   ├── Permissions: READ
 * │   └── CCCD Descriptor (00002902-...)
 * │       └── Purpose: We notify centrals here → they receive
 * └── Identity Characteristic (37145b00-442d-4a94-917f-8f42c5da28e6)
 *     ├── Properties: READ
 *     ├── Permissions: READ
 *     └── Purpose: Provides 16-byte transport identity hash for stable peer tracking
 * ```
 *
 * Note: Permission checks are performed at UI layer before BLE operations are initiated.
 *
 * @property context Application context
 * @property bluetoothManager Bluetooth manager
 * @property scope Coroutine scope for async operations
 */
@SuppressLint("MissingPermission")
class BleGattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "Columba:BLE:K:Server"
    }

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Local transport identity (16 bytes, set by Python bridge)
    @Volatile
    private var transportIdentityHash: ByteArray? = null

    // Connected centrals: device address -> device object
    private val connectedCentrals = mutableMapOf<String, BluetoothDevice>()
    private val centralsMutex = Mutex()

    // MTU tracking per central
    private val centralMtus = mutableMapOf<String, Int>()
    private val mtuMutex = Mutex()

    // Identity tracking (Protocol v2.2)
    // Maps central address -> 16-byte identity (raw bytes received in handshake)
    private val addressToIdentity = mutableMapOf<String, ByteArray>()

    // Maps 16-char identity hash -> central address (for identity-based keying)
    private val identityToAddress = mutableMapOf<String, String>()
    private val identityMutex = Mutex()

    // Peripheral keepalive jobs (prevent supervision timeout from connected centrals)
    private val peripheralKeepaliveJobs = mutableMapOf<String, Job>()
    private val peripheralKeepaliveWriteFailures = mutableMapOf<String, Int>()
    private val keepaliveMutex = Mutex()

    // State
    private val _isServerOpen = MutableStateFlow(false)
    val isServerOpen: StateFlow<Boolean> = _isServerOpen.asStateFlow()

    // Service registration completion tracker
    private var serviceAddedDeferred: CompletableDeferred<Result<Unit>>? = null

    // Callbacks
    var onCentralConnected: ((String, Int) -> Unit)? = null // address, mtu
    var onCentralDisconnected: ((String) -> Unit)? = null // address
    var onDataReceived: ((String, ByteArray) -> Unit)? = null // address, data
    var onMtuChanged: ((String, Int) -> Unit)? = null // address, mtu
    var onIdentityReceived: ((String, String) -> Unit)? = null // address, identityHash (16-char hex)

    /**
     * GATT server callback handler.
     */
    private val gattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                val stateStr =
                    when (newState) {
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                        else -> "UNKNOWN($newState)"
                    }
                Log.d(TAG, "onConnectionStateChange: device=${device.address}, status=$status, newState=$stateStr")
                scope.launch {
                    handleConnectionStateChange(device, status, newState)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                Log.d(TAG, "onCharacteristicReadRequest: device=${device.address}, char=${characteristic.uuid}")
                scope.launch {
                    handleCharacteristicReadRequest(device, requestId, offset, characteristic)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                Log.d(TAG, "onCharacteristicWriteRequest: device=${device.address}, char=${characteristic.uuid}, size=${value.size}")
                scope.launch {
                    handleCharacteristicWriteRequest(
                        device,
                        requestId,
                        characteristic,
                        preparedWrite,
                        responseNeeded,
                        offset,
                        value,
                    )
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) {
                Log.d(TAG, "onDescriptorReadRequest: device=${device.address}, desc=${descriptor.uuid}")
                scope.launch {
                    handleDescriptorReadRequest(device, requestId, offset, descriptor)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                Log.d(TAG, "onDescriptorWriteRequest: device=${device.address}, desc=${descriptor.uuid}")
                scope.launch {
                    handleDescriptorWriteRequest(
                        device,
                        requestId,
                        descriptor,
                        preparedWrite,
                        responseNeeded,
                        offset,
                        value,
                    )
                }
            }

            override fun onMtuChanged(
                device: BluetoothDevice,
                mtu: Int,
            ) {
                scope.launch {
                    handleMtuChanged(device, mtu)
                }
            }

            override fun onNotificationSent(
                device: BluetoothDevice,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.v(TAG, "Notification sent to ${device.address}")
                } else {
                    Log.e(TAG, "Notification failed to ${device.address} (status: $status)")
                }
            }

            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT service added successfully: ${service.uuid}")
                    Log.i(TAG, "  - RX characteristic: ${BleConstants.CHARACTERISTIC_RX_UUID}")
                    Log.i(TAG, "  - TX characteristic: ${BleConstants.CHARACTERISTIC_TX_UUID}")
                    Log.i(TAG, "  - Identity characteristic: ${BleConstants.CHARACTERISTIC_IDENTITY_UUID}")
                    serviceAddedDeferred?.complete(Result.success(Unit))
                } else {
                    Log.e(TAG, "Failed to add GATT service (status: $status)")
                    serviceAddedDeferred?.complete(
                        Result.failure(IllegalStateException("Failed to add GATT service (status: $status)")),
                    )
                }
            }
        }

    /**
     * Open the GATT server.
     *
     * @return Result indicating success or failure
     */
    suspend fun open(): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                // Check if already open
                if (_isServerOpen.value) {
                    Log.d(TAG, "GATT server already open")
                    return@withContext Result.success(Unit)
                }

                // Check permissions
                if (!hasConnectPermission()) {
                    return@withContext Result.failure(
                        SecurityException("Missing BLUETOOTH_CONNECT permission"),
                    )
                }

                // Open GATT server
                val server = bluetoothManager.openGattServer(context, gattServerCallback)
                if (server == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Failed to open GATT server"),
                    )
                }
                gattServer = server

                // Create and add Reticulum service
                val service = createReticulumService()

                // Create deferred to wait for service registration callback
                serviceAddedDeferred = CompletableDeferred()

                val added = server.addService(service)
                if (!added) {
                    serviceAddedDeferred = null
                    gattServer?.close()
                    gattServer = null
                    return@withContext Result.failure(
                        IllegalStateException("Failed to add Reticulum service to GATT server"),
                    )
                }

                // Wait for onServiceAdded callback (with 5 second timeout)
                Log.d(TAG, "Waiting for GATT service registration...")
                val serviceResult =
                    withTimeoutOrNull(5000) {
                        serviceAddedDeferred?.await()
                    }

                // Clear the deferred
                serviceAddedDeferred = null

                if (serviceResult == null) {
                    gattServer?.close()
                    gattServer = null
                    return@withContext Result.failure(
                        IllegalStateException("Timeout waiting for GATT service registration"),
                    )
                }

                if (serviceResult.isFailure) {
                    gattServer?.close()
                    gattServer = null
                    return@withContext serviceResult
                }

                // Store TX characteristic reference
                txCharacteristic = service.getCharacteristic(BleConstants.CHARACTERISTIC_TX_UUID)

                _isServerOpen.value = true
                Log.d(TAG, "GATT server opened and service registered successfully")

                Result.success(Unit)
            } catch (e: SecurityException) {
                // Permission error - provide user-friendly guidance
                Log.e(TAG, "BLUETOOTH permission denied when opening GATT server", e)
                Log.e(TAG, "User needs to grant Bluetooth permissions in Settings")

                // Clean up
                gattServer?.close()
                gattServer = null

                Result.failure(SecurityException("Bluetooth permission required. Please grant permission in app settings.", e))
            } catch (e: Exception) {
                Log.e(TAG, "Error opening GATT server", e)

                // Clean up
                gattServer?.close()
                gattServer = null

                Result.failure(e)
            }
        }

    /**
     * Close the GATT server.
     */
    suspend fun close() =
        withContext(Dispatchers.Main) {
            try {
                // Clear connected centrals
                centralsMutex.withLock {
                    connectedCentrals.clear()
                }
                mtuMutex.withLock {
                    centralMtus.clear()
                }

                // Close server
                gattServer?.close()
                gattServer = null
                txCharacteristic = null

                _isServerOpen.value = false
                Log.d(TAG, "GATT server closed")
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied when closing server", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing GATT server", e)
            }
        }

    /**
     * Send data to a specific central (or all centrals if address is null).
     *
     * @param data Data to send
     * @param centralAddress Address of specific central (null = send to all)
     * @return Result indicating success or failure
     */
    suspend fun notifyCentrals(
        data: ByteArray,
        centralAddress: String? = null,
    ): Result<Unit> {
        val server =
            gattServer ?: return Result.failure(
                IllegalStateException("GATT server not open"),
            )

        val txChar =
            txCharacteristic ?: return Result.failure(
                IllegalStateException("TX characteristic not available"),
            )

        return withContext(Dispatchers.Main) {
            try {
                if (!hasConnectPermission()) {
                    return@withContext Result.failure(
                        SecurityException("Missing BLUETOOTH_CONNECT permission"),
                    )
                }

                val targets =
                    centralsMutex.withLock {
                        if (centralAddress != null) {
                            connectedCentrals[centralAddress]?.let { listOf(it) }.orEmpty()
                        } else {
                            connectedCentrals.values.toList()
                        }
                    }

                if (targets.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("No connected centrals to notify"),
                    )
                }

                // Set characteristic value
                txChar.value = data

                // Send notification to each central
                targets.forEach { device ->
                    val success = server.notifyCharacteristicChanged(device, txChar, false)
                    if (success) {
                        Log.v(TAG, "Notified ${device.address} with ${data.size} bytes")
                    } else {
                        Log.e(TAG, "Failed to notify ${device.address}")
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending notification", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get list of connected central addresses.
     */
    suspend fun getConnectedCentrals(): List<String> = centralsMutex.withLock { connectedCentrals.keys.toList() }

    /**
     * Get MTU for a specific central.
     */
    suspend fun getMtu(centralAddress: String): Int? = mtuMutex.withLock { centralMtus[centralAddress] }

    /**
     * Disconnect a specific central device.
     *
     * Note: Android's cancelConnection() does NOT reliably trigger onConnectionStateChange.
     * We must manually clean up state and fire the disconnect callback.
     */
    suspend fun disconnectCentral(address: String) =
        withContext(Dispatchers.Main) {
            try {
                if (!hasConnectPermission()) {
                    Log.w(TAG, "Cannot disconnect central, missing permission")
                    return@withContext
                }

                val device = centralsMutex.withLock { connectedCentrals[address] }
                if (device != null) {
                    gattServer?.cancelConnection(device)
                    Log.d(TAG, "Connection cancelled for $address")

                    // Stop keepalive BEFORE removing from connectedCentrals to prevent orphaned jobs
                    // This is critical because cancelConnection() doesn't trigger onConnectionStateChange
                    stopPeripheralKeepalive(address)

                    // Manually clean up since cancelConnection doesn't reliably trigger callback
                    centralsMutex.withLock {
                        connectedCentrals.remove(address)
                    }
                    mtuMutex.withLock {
                        centralMtus.remove(address)
                    }
                    identityMutex.withLock {
                        addressToIdentity.remove(address)
                    }
                    Log.i(TAG, "Cleaned up central state for $address after disconnect")

                    // Fire disconnect callback so bridge can clean up
                    onCentralDisconnected?.invoke(address)
                } else {
                    Log.w(TAG, "Cannot disconnect unknown central: $address")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied when disconnecting central", e)
            }
        }

    /**
     * Check if a central is connected.
     */
    suspend fun isConnected(centralAddress: String): Boolean = centralsMutex.withLock { connectedCentrals.containsKey(centralAddress) }

    /**
     * Check if a central has completed identity handshake.
     */
    suspend fun hasIdentity(centralAddress: String): Boolean = identityMutex.withLock { addressToIdentity.containsKey(centralAddress) }

    /**
     * Complete a peripheral connection using identity obtained from another source.
     *
     * In dual-connection scenarios, both devices connect to each other as central.
     * Neither writes identity to the other's GATT server (they read via GATT client).
     * This creates a deadlock where onCentralConnected never fires.
     *
     * This method allows the bridge to inject identity received via GATT client
     * to complete the peripheral side of the connection.
     *
     * @param address Central's MAC address
     * @param identityHash 32-char hex identity string (Protocol v2.2)
     * @return true if connection was completed, false if not applicable
     */
    suspend fun completeConnectionWithIdentity(
        address: String,
        identityHash: String,
    ): Boolean {
        // Check if this central is connected but hasn't completed identity handshake
        val isConnected = centralsMutex.withLock { connectedCentrals.containsKey(address) }
        val hasIdentity = identityMutex.withLock { addressToIdentity.containsKey(address) }

        if (!isConnected) {
            Log.d(TAG, "completeConnectionWithIdentity: $address not connected as central")
            return false
        }

        if (hasIdentity) {
            Log.d(TAG, "completeConnectionWithIdentity: $address already has identity")
            return false
        }

        // Convert hex string to bytes and store
        val identityBytes = identityHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        identityMutex.withLock {
            addressToIdentity[address] = identityBytes
            identityToAddress[identityHash] = address
        }

        // Get MTU for this connection
        val mtu = mtuMutex.withLock { centralMtus[address] ?: BleConstants.MIN_MTU }

        Log.i(TAG, "Completing peripheral connection via external identity: $address, MTU=$mtu, identity=$identityHash")

        // Fire the connection callback (same as if identity was received via write)
        onCentralConnected?.invoke(address, mtu)

        // Start keepalive
        startPeripheralKeepalive(address)

        return true
    }

    /**
     * Set the local transport identity hash.
     * This should be called by the Python bridge after Reticulum initialization.
     *
     * @param identityHash 16-byte Reticulum Transport identity hash
     */
    fun setTransportIdentity(identityHash: ByteArray) {
        require(identityHash.size == 16) {
            "Transport identity hash must be exactly 16 bytes (got ${identityHash.size})"
        }
        transportIdentityHash = identityHash
        Log.i(TAG, "Transport identity set: ${identityHash.joinToString("") { "%02x".format(it) }}")
    }

    // ========== GATT Server Callback Handlers ==========

    private suspend fun handleConnectionStateChange(
        device: BluetoothDevice,
        status: Int,
        newState: Int,
    ) {
        val address = device.address

        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.d(TAG, "Central connected: $address")

                centralsMutex.withLock {
                    connectedCentrals[address] = device
                }

                mtuMutex.withLock {
                    centralMtus[address] = BleConstants.MIN_MTU
                }

                // Fire connection callback immediately (Protocol logic moved to Python)
                // Identity will be received later via RX characteristic write
                val mtu = BleConstants.MIN_MTU
                Log.i(TAG, "GATT connection established from $address, MTU=$mtu (identity pending)")
                onCentralConnected?.invoke(address, mtu)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.d(TAG, "Central disconnected: $address (status: $status)")

                // Stop peripheral keepalive
                stopPeripheralKeepalive(address)

                centralsMutex.withLock {
                    connectedCentrals.remove(address)
                }

                mtuMutex.withLock {
                    centralMtus.remove(address)
                }

                // Clean up identity mappings
                identityMutex.withLock {
                    val identity = addressToIdentity.remove(address)
                    if (identity != null) {
                        val identityHash = identity.joinToString("") { "%02x".format(it) }.take(16)
                        identityToAddress.remove(identityHash)
                        Log.d(TAG, "Cleaned up identity mapping for $address (hash: $identityHash)")
                    }
                }

                onCentralDisconnected?.invoke(address)
            }
        }
    }

    private suspend fun handleCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) {
                return@withContext
            }

            when (characteristic.uuid) {
                BleConstants.CHARACTERISTIC_TX_UUID -> {
                    // TX characteristic read - return empty for now
                    // (notifications are the primary mechanism)
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        ByteArray(0),
                    )
                }
                BleConstants.CHARACTERISTIC_IDENTITY_UUID -> {
                    // Identity characteristic read - return transport identity hash
                    val identity = transportIdentityHash
                    if (identity != null) {
                        Log.d(TAG, "Serving identity to ${device.address}: ${identity.joinToString("") { "%02x".format(it) }}")
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            identity,
                        )
                    } else {
                        Log.w(TAG, "Identity not available yet (Reticulum not initialized)")
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null,
                        )
                    }
                }
                else -> {
                    Log.w(TAG, "Read request for unknown characteristic: ${characteristic.uuid}")
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in handleCharacteristicReadRequest", e)
        }
    }

    private suspend fun handleCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) {
                return@withContext
            }

            when (characteristic.uuid) {
                BleConstants.CHARACTERISTIC_RX_UUID -> {
                    // RX characteristic write - data received from central
                    Log.i(TAG, "Received ${value.size} bytes from ${device.address}")

                    // DEFENSIVE FIX: Android's onConnectionStateChange is unreliable and sometimes
                    // doesn't fire. If we receive data from a device that's not in connectedCentrals,
                    // retroactively register it. This prevents orphaned connections where keepalives
                    // flow but data can't be sent back (because the address isn't tracked).
                    val isKnown = centralsMutex.withLock { connectedCentrals.containsKey(device.address) }
                    if (!isKnown) {
                        Log.w(
                            TAG,
                            "DEFENSIVE RECOVERY: Data received from ${device.address} but " +
                                "onConnectionStateChange was never called! Retroactively registering connection.",
                        )

                        // Simulate what onConnectionStateChange(STATE_CONNECTED) would have done
                        centralsMutex.withLock {
                            connectedCentrals[device.address] = device
                        }
                        mtuMutex.withLock {
                            centralMtus[device.address] = BleConstants.MIN_MTU
                        }

                        // Fire the connection callback so the bridge can register the peer
                        val mtu = BleConstants.MIN_MTU
                        Log.i(
                            TAG,
                            "DEFENSIVE: Firing retroactive onCentralConnected for ${device.address}, MTU=$mtu",
                        )
                        onCentralConnected?.invoke(device.address, mtu)
                    }

                    // Send response if needed
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            value,
                        )
                    }

                    // Re-enabled 2026-01-30: Kotlin-side identity detection needed for dual connection handling.
                    // When phone A connects as central to phone B, phone B receives A's identity
                    // via the GATT server data write. This needs to populate KotlinBLEBridge.identityToAddress
                    // so that when B tries to connect as central to A, the duplicate identity check
                    // can detect the existing peripheral connection and allow the dual connection.
                    //
                    // Python ALSO detects the identity (via onDataReceived → _handle_identity_handshake),
                    // but that's OK - both can coexist and Python handles duplicate notifications.
                    val existingIdentity =
                        identityMutex.withLock {
                            addressToIdentity[device.address]
                        }

                    if (existingIdentity == null && value.size == 16) {
                        // Likely identity handshake - store for Kotlin's address resolution
                        val identityHash = value.joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Received 16-byte identity handshake from ${device.address}: ${identityHash.take(16)}...")

                        identityMutex.withLock {
                            addressToIdentity[device.address] = value
                            identityToAddress[identityHash] = device.address
                        }

                        // Notify identity callback (Python will also detect via data callback)
                        onIdentityReceived?.invoke(device.address, identityHash)
                    }

                    // Start keepalive on first data received
                    // This prevents supervision timeout regardless of whether it's identity or data
                    val hasKeepalive = keepaliveMutex.withLock { peripheralKeepaliveJobs.containsKey(device.address) }
                    if (!hasKeepalive) {
                        startPeripheralKeepalive(device.address)
                    }

                    // Pass ALL data to Python - it handles identity detection (and other data)
                    onDataReceived?.invoke(device.address, value)
                }
                else -> {
                    Log.w(TAG, "Write request for unknown characteristic: ${characteristic.uuid}")
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null,
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in handleCharacteristicWriteRequest", e)
        }
    }

    private suspend fun handleDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) {
                return@withContext
            }

            when (descriptor.uuid) {
                BleConstants.CCCD_UUID -> {
                    // CCCD read - return current value
                    val value = descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value,
                    )
                }
                else -> {
                    Log.w(TAG, "Read request for unknown descriptor: ${descriptor.uuid}")
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in handleDescriptorReadRequest", e)
        }
    }

    private suspend fun handleDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) = withContext(Dispatchers.Main) {
        try {
            if (!hasConnectPermission()) {
                return@withContext
            }

            when (descriptor.uuid) {
                BleConstants.CCCD_UUID -> {
                    // CCCD write - central enabling/disabling notifications
                    val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

                    Log.d(TAG, "Central ${device.address} ${if (enabled) "enabled" else "disabled"} notifications")

                    // Store descriptor value
                    descriptor.value = value

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            value,
                        )
                    }
                    // No initial notification needed - matches Python implementation behavior
                    // The CCCD write response is sufficient to signal readiness
                }
                else -> {
                    Log.w(TAG, "Write request for unknown descriptor: ${descriptor.uuid}")
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null,
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied in handleDescriptorWriteRequest", e)
        }
    }

    private suspend fun handleMtuChanged(
        device: BluetoothDevice,
        mtu: Int,
    ) {
        // MTU includes 3-byte ATT header, so usable MTU is mtu - 3
        val usableMtu = mtu - 3

        mtuMutex.withLock {
            centralMtus[device.address] = usableMtu
        }

        Log.d(TAG, "MTU changed for ${device.address}: $usableMtu bytes")
        onMtuChanged?.invoke(device.address, usableMtu)
    }

    /**
     * Create the Reticulum GATT service.
     */
    private fun createReticulumService(): BluetoothGattService {
        val service =
            BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )

        // Create RX characteristic (centrals write here)
        val rxChar =
            BluetoothGattCharacteristic(
                BleConstants.CHARACTERISTIC_RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        rxChar.value = ByteArray(0) // Initialize with empty value

        // Create TX characteristic (we notify centrals here)
        val txChar =
            BluetoothGattCharacteristic(
                BleConstants.CHARACTERISTIC_TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE, // Add INDICATE for reliable delivery
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        txChar.value = ByteArray(0) // Initialize with empty value

        // Add CCCD descriptor to TX characteristic (for enabling notifications)
        val cccd =
            BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE // Initialize to disabled
        txChar.addDescriptor(cccd)

        // Create Identity characteristic (provides stable node identity)
        val identityChar =
            BluetoothGattCharacteristic(
                BleConstants.CHARACTERISTIC_IDENTITY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        // Value is set dynamically when read (from transportIdentityHash)

        // Add characteristics to service
        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        service.addCharacteristic(identityChar)

        Log.d(TAG, "Created Reticulum GATT service with RX, TX, and Identity characteristics")

        return service
    }

    /**
     * Check if BLUETOOTH_CONNECT permission is granted.
     */
    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime permission needed on Android 11 and below
        }

    // ========== Peripheral Mode Keepalive ==========

    /**
     * Start peripheral keepalive for a connected central device.
     * Sends periodic notification packets to prevent Android BLE idle disconnects.
     *
     * When Android is in peripheral mode (GATT server), connected centrals may
     * timeout if no data is exchanged. This keepalive sends a 1-byte notification
     * every 7 seconds to keep the connection alive.
     *
     * Tracks notification failures and disconnects early if the GATT session appears
     * degraded, rather than waiting for L2CAP cleanup.
     *
     * @param address MAC address of the connected central
     */
    private fun startPeripheralKeepalive(address: String) {
        scope.launch {
            keepaliveMutex.withLock {
                // Cancel any existing keepalive
                peripheralKeepaliveJobs[address]?.cancel()
                peripheralKeepaliveWriteFailures[address] = 0

                // Start new keepalive job
                peripheralKeepaliveJobs[address] =
                    scope.launch {
                        // Send immediate first keepalive to establish bidirectional traffic
                        try {
                            val keepalivePacket = byteArrayOf(0x00)
                            val result = notifyCentrals(keepalivePacket, address)
                            if (result.isSuccess) {
                                Log.v(TAG, "Initial peripheral keepalive sent to $address")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Initial peripheral keepalive error for $address", e)
                        }

                        // Continue with regular interval
                        @Suppress("LoopWithTooManyJumpStatements")
                        while (isActive) {
                            delay(BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS)

                            try {
                                // Send 1-byte keepalive via TX characteristic notification
                                val keepalivePacket = byteArrayOf(0x00)
                                val result = notifyCentrals(keepalivePacket, address)

                                if (result.isSuccess) {
                                    Log.v(TAG, "Peripheral keepalive sent to $address")
                                    // Reset failure counter on success
                                    keepaliveMutex.withLock {
                                        peripheralKeepaliveWriteFailures[address] = 0
                                    }
                                } else {
                                    val error = result.exceptionOrNull()?.message ?: "unknown"
                                    if (error.contains("No connected centrals")) {
                                        Log.w(TAG, "Keepalive for $address: target no longer tracked, stopping")
                                        break // Exit loop, job ends naturally
                                    }

                                    // Track notification failures
                                    val writeFailures =
                                        keepaliveMutex.withLock {
                                            val current = peripheralKeepaliveWriteFailures[address] ?: 0
                                            peripheralKeepaliveWriteFailures[address] = current + 1
                                            current + 1
                                        }
                                    Log.w(
                                        TAG,
                                        "Peripheral keepalive NOTIFY failed for $address ($writeFailures/${BleConstants.MAX_KEEPALIVE_WRITE_FAILURES} failures)",
                                    )

                                    // Disconnect early on notification failures
                                    if (writeFailures >= BleConstants.MAX_KEEPALIVE_WRITE_FAILURES) {
                                        Log.e(
                                            TAG,
                                            "GATT notifications to $address are failing (L2CAP may be degraded), disconnecting to allow reconnection",
                                        )
                                        scope.launch {
                                            disconnectCentral(address)
                                        }
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Peripheral keepalive error for $address", e)
                            }
                        }
                    }

                Log.d(TAG, "Started peripheral keepalive for $address (interval: ${BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS}ms)")
            }
        }
    }

    /**
     * Stop peripheral keepalive for a central device.
     *
     * @param address MAC address of the central
     */
    private suspend fun stopPeripheralKeepalive(address: String) {
        keepaliveMutex.withLock {
            peripheralKeepaliveJobs[address]?.cancel()
            peripheralKeepaliveJobs.remove(address)
            peripheralKeepaliveWriteFailures.remove(address)
        }
        Log.v(TAG, "Stopped peripheral keepalive for $address")
    }

    /**
     * Send an immediate keepalive to a specific central device.
     *
     * This is used during deduplication to keep the L2CAP link alive when
     * closing the central connection. Android's Bluetooth stack starts a
     * 1-second idle timer when GATT connections are closed, which can tear
     * down the underlying ACL link even if a peripheral connection still
     * exists. By sending traffic just before closing central, we keep the
     * L2CAP layer active.
     *
     * @param address MAC address of the central to send keepalive to
     * @return true if keepalive was sent successfully
     */
    suspend fun sendImmediateKeepalive(address: String): Boolean =
        try {
            val keepalivePacket = byteArrayOf(0x00)
            val result = notifyCentrals(keepalivePacket, address)
            if (result.isSuccess) {
                Log.d(TAG, "Immediate keepalive sent to $address (deduplication)")
                true
            } else {
                Log.w(TAG, "Failed to send immediate keepalive to $address")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending immediate keepalive to $address", e)
            false
        }

    /**
     * Shutdown the server.
     */
    suspend fun shutdown() {
        // Stop all keepalives
        keepaliveMutex.withLock {
            peripheralKeepaliveJobs.values.forEach { it.cancel() }
            peripheralKeepaliveJobs.clear()
            peripheralKeepaliveWriteFailures.clear()
        }

        close()
        scope.cancel()
    }
}
