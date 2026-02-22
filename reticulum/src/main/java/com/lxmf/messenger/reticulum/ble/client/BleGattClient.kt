package com.lxmf.messenger.reticulum.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import com.lxmf.messenger.reticulum.ble.util.BleOperationQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * BLE GATT Client for central mode operations.
 *
 * Handles connecting to BLE peripheral devices running Reticulum, discovering services,
 * negotiating MTU, and reading/writing data via RX/TX characteristics.
 *
 * **Key responsibilities:**
 * - Connect/disconnect from GATT servers
 * - Discover Reticulum service and characteristics
 * - Request MTU negotiation (up to 517 bytes)
 * - Enable notifications on TX characteristic
 * - Write data to RX characteristic
 * - Receive data from TX characteristic notifications
 * - Handle GATT errors (especially status 133) with retry logic
 * - Use operation queue for serial GATT operations
 *
 * Note: Permission checks are performed at UI layer before BLE operations are initiated.
 *
 * Thread-safety: All public methods are suspend functions using coroutines.
 * GATT callbacks run on main thread (Android requirement).
 *
 * @property context Application context
 * @property bluetoothAdapter Bluetooth adapter
 * @property operationQueue Operation queue for serial GATT operations
 * @property scope Coroutine scope
 */
@SuppressLint("MissingPermission")
class BleGattClient(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val operationQueue: BleOperationQueue,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "Columba:BLE:K:Client"
        private const val MAX_CONNECTION_RETRIES = 3
        private const val CONNECTION_TIMEOUT_MS = BleConstants.CONNECTION_TIMEOUT_MS
        private val CCCD_UUID = BleConstants.CCCD_UUID
    }

    /**
     * Connection data for a single GATT connection.
     */
    private data class ConnectionData(
        val gatt: BluetoothGatt,
        val address: String,
        var mtu: Int = BleConstants.MIN_MTU,
        var rxCharacteristic: BluetoothGattCharacteristic? = null,
        var txCharacteristic: BluetoothGattCharacteristic? = null,
        var identityHash: String? = null, // 32-char hex string (16 bytes)
        var retryCount: Int = 0,
        var connectionJob: Job? = null,
        var handshakeInProgress: Boolean = false, // Track if handshake is already started
        var keepaliveJob: Job? = null, // Keepalive job to prevent supervision timeout
        var consecutiveKeepaliveWriteFailures: Int = 0, // Track consecutive WRITE failures (not reset by receives)
    )

    // Active connections: address -> ConnectionData
    private val connections = mutableMapOf<String, ConnectionData>()
    private val connectionsMutex = Mutex()

    // Track manual disconnects to prevent duplicate callbacks
    // When disconnect() is called manually, the GATT onConnectionStateChange callback
    // may also fire. We track manual disconnects to ensure only one callback fires.
    private val manualDisconnects = mutableSetOf<String>()
    private val manualDisconnectsMutex = Mutex()

    // Local transport identity (16 bytes, set by Python bridge)
    @Volatile
    private var transportIdentityHash: ByteArray? = null

    // Callbacks
    var onConnected: ((String, Int) -> Unit)? = null // address, mtu
    var onDisconnected: ((String, String?) -> Unit)? = null // address, reason
    var onConnectionFailed: ((String, String) -> Unit)? = null // address, error
    var onDataReceived: ((String, ByteArray) -> Unit)? = null // address, data
    var onMtuChanged: ((String, Int) -> Unit)? = null // address, mtu
    var onIdentityReceived: ((String, String) -> Unit)? = null // address, identityHash (32-char hex)

    /**
     * GATT callback handler.
     * Runs on main thread (Android requirement).
     */
    private inner class GattCallback(
        private val address: String,
    ) : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            scope.launch {
                handleConnectionStateChange(address, gatt, status, newState)
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int,
        ) {
            scope.launch {
                handleServicesDiscovered(address, gatt, status)
            }
        }

        override fun onMtuChanged(
            gatt: BluetoothGatt,
            mtu: Int,
            status: Int,
        ) {
            scope.launch {
                handleMtuChanged(address, mtu, status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            scope.launch {
                handleCharacteristicWrite(address, characteristic, status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            scope.launch {
                handleCharacteristicRead(address, characteristic, value, status)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            scope.launch {
                handleCharacteristicChanged(address, characteristic, value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            scope.launch {
                handleDescriptorWrite(address, descriptor, status)
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            scope.launch {
                handleDescriptorRead(address, descriptor, status, value)
            }
        }
    }

    /**
     * Connect to a BLE device.
     *
     * @param address MAC address of the device
     * @return Result with Unit on success, exception on failure
     */
    suspend fun connect(address: String): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                // Check if already connected
                connectionsMutex.withLock {
                    if (connections.containsKey(address)) {
                        Log.d(TAG, "Already connected to $address")
                        return@withContext Result.success(Unit)
                    }
                }

                // Check permissions
                if (!hasConnectPermission()) {
                    return@withContext Result.failure(
                        SecurityException("Missing BLUETOOTH_CONNECT permission"),
                    )
                }

                // Get remote device
                val device: BluetoothDevice =
                    try {
                        bluetoothAdapter.getRemoteDevice(address)
                    } catch (e: IllegalArgumentException) {
                        return@withContext Result.failure(
                            IllegalArgumentException("Invalid Bluetooth address: $address", e),
                        )
                    }

                Log.d(TAG, "Connecting to $address...")

                // Create connection with timeout
                val connectionJob =
                    scope.launch {
                        delay(CONNECTION_TIMEOUT_MS)
                        handleConnectionTimeout(address)
                    }

                // Connect to GATT server
                val gatt =
                    device.connectGatt(
                        context,
                        false, // autoConnect = false for faster connection
                        GattCallback(address),
                        BluetoothDevice.TRANSPORT_LE,
                    )

                if (gatt == null) {
                    connectionJob.cancel()
                    return@withContext Result.failure(
                        IllegalStateException("Failed to create GATT connection"),
                    )
                }

                // Store connection data
                connectionsMutex.withLock {
                    connections[address] =
                        ConnectionData(
                            gatt = gatt,
                            address = address,
                            connectionJob = connectionJob,
                        )
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to $address", e)

                // Notify failure callback so UI layer knows about the error
                onConnectionFailed?.invoke(address, e.message ?: "Unknown connection error")

                Result.failure(e)
            }
        }

    /**
     * Disconnect from a BLE device.
     *
     * @param address MAC address of the device
     */
    suspend fun disconnect(address: String) {
        try {
            // Stop keepalive first
            stopKeepalive(address)

            // Mark as manual disconnect BEFORE calling gatt.disconnect()
            // This prevents the GATT onConnectionStateChange callback from firing
            // a duplicate onDisconnected callback
            manualDisconnectsMutex.withLock {
                manualDisconnects.add(address)
            }

            val connData =
                withContext(Dispatchers.Main) {
                    connectionsMutex.withLock {
                        connections.remove(address)
                    }
                }

            if (connData != null) {
                withContext(Dispatchers.Main) {
                    connData.connectionJob?.cancel()
                    connData.gatt.disconnect()
                    connData.gatt.close()
                }
                Log.d(TAG, "Disconnected from $address (manual)")
                onDisconnected?.invoke(address, null)
            }

            // Clean up manual disconnect tracking after callback is fired
            manualDisconnectsMutex.withLock {
                manualDisconnects.remove(address)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when disconnecting from $address", e)
            manualDisconnectsMutex.withLock { manualDisconnects.remove(address) }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from $address", e)
            manualDisconnectsMutex.withLock { manualDisconnects.remove(address) }
        }
    }

    /**
     * Send data to a connected device.
     *
     * @param address Device address
     * @param data Data to send
     * @return Result with Unit on success
     */
    suspend fun sendData(
        address: String,
        data: ByteArray,
    ): Result<Unit> {
        val connData =
            connectionsMutex.withLock { connections[address] }
                ?: return Result.failure(IllegalStateException("Not connected to $address"))

        val rxChar =
            connData.rxCharacteristic
                ?: return Result.failure(IllegalStateException("RX characteristic not found for $address"))

        return try {
            // Queue write operation
            operationQueue.enqueue(
                BleOperationQueue.BleOperation.WriteCharacteristic(
                    gatt = connData.gatt,
                    characteristic = rxChar,
                    data = data,
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ),
            )

            Log.v(TAG, "Sent ${data.size} bytes to $address")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data to $address", e)
            Result.failure(e)
        }
    }

    /**
     * Set the local transport identity hash for Protocol v2.2 identity handshake.
     * This identity will be sent to peripherals during connection handshake.
     *
     * @param identityHash 16-byte Reticulum Transport identity hash
     * @throws IllegalArgumentException if identityHash is not exactly 16 bytes
     */
    fun setTransportIdentity(identityHash: ByteArray) {
        require(identityHash.size == 16) {
            "Transport identity hash must be exactly 16 bytes, got ${identityHash.size}"
        }
        transportIdentityHash = identityHash
        Log.d(TAG, "Transport identity set: ${identityHash.joinToString("") { "%02x".format(it) }}")
    }

    /**
     * Get current MTU for a connection.
     */
    suspend fun getMtu(address: String): Int? = connectionsMutex.withLock { connections[address]?.mtu }

    /**
     * Determine if we should initiate connection based on MAC address comparison.
     * Protocol v2.2 connection direction logic to prevent simultaneous connections.
     *
     * Rule: Only connect if our MAC address is numerically less than peer's MAC address.
     * This ensures that only one side initiates the connection.
     *
     * @param peerAddress Peer device MAC address
     * @return true if we should connect (our MAC < peer MAC), false if we should wait
     */
    fun shouldConnect(peerAddress: String): Boolean {
        try {
            val localAddress = bluetoothAdapter.address ?: return true // Fallback: always connect if we can't get local MAC

            // Convert MAC addresses to integers for comparison
            // Remove colons and parse as hex
            val localMacInt = localAddress.replace(":", "").toLong(16)
            val peerMacInt = peerAddress.replace(":", "").toLong(16)

            val shouldConnect = localMacInt < peerMacInt

            Log.d(
                TAG,
                "MAC comparison: local=$localAddress (${localMacInt.toString(16)}) " +
                    "vs peer=$peerAddress (${peerMacInt.toString(16)}) -> " +
                    (if (shouldConnect) "CONNECT" else "WAIT"),
            )

            return shouldConnect
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compare MAC addresses, defaulting to connect", e)
            return true // Fallback: connect anyway
        }
    }

    /**
     * Check if connected to a device.
     */
    suspend fun isConnected(address: String): Boolean = connectionsMutex.withLock { connections.containsKey(address) }

    /**
     * Get list of connected device addresses.
     */
    suspend fun getConnectedDevices(): List<String> = connectionsMutex.withLock { connections.keys.toList() }

    // ========== GATT Callback Handlers ==========

    private suspend fun handleConnectionStateChange(
        address: String,
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        when {
            status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                Log.d(TAG, "Connected to $address, discovering services...")

                // Cancel timeout
                connectionsMutex.withLock {
                    connections[address]?.connectionJob?.cancel()
                }

                // Request high connection priority for better stability and throughput
                withContext(Dispatchers.Main) {
                    try {
                        val priorityResult =
                            gatt.requestConnectionPriority(
                                BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                            )
                        Log.d(TAG, "Requested CONNECTION_PRIORITY_HIGH for $address: $priorityResult")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Permission denied requesting connection priority for $address", e)
                    }
                }

                // Small delay to let BLE stack apply parameters
                delay(100)

                // Discover services (post to main thread for older Android versions)
                withContext(Dispatchers.Main) {
                    Handler(Looper.getMainLooper()).post {
                        gatt.discoverServices()
                    }
                }
            }

            status == BleConstants.GATT_ERROR_133 -> {
                // Status 133: Connection error, retry with backoff
                handleStatus133Error(address, gatt)
            }

            newState == BluetoothProfile.STATE_DISCONNECTED -> {
                val reason =
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Normal disconnect"
                    } else {
                        "Error (status: $status)"
                    }

                // Check if this was a manual disconnect (callback will be fired by disconnect())
                val isManualDisconnect =
                    manualDisconnectsMutex.withLock {
                        manualDisconnects.contains(address)
                    }

                if (isManualDisconnect) {
                    Log.d(TAG, "Disconnected from $address: $reason (manual, skipping callback)")
                    // Clean up but don't fire callback - disconnect() will handle it
                    connectionsMutex.withLock {
                        connections.remove(address)
                    }
                    gatt.close()
                } else {
                    Log.d(TAG, "Disconnected from $address: $reason")
                    connectionsMutex.withLock {
                        connections.remove(address)
                    }
                    gatt.close()
                    onDisconnected?.invoke(address, reason)
                }
            }

            else -> {
                Log.w(TAG, "Connection state change for $address: status=$status, state=$newState")
            }
        }
    }

    private suspend fun handleServicesDiscovered(
        address: String,
        gatt: BluetoothGatt,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed for $address: status=$status")

            // Force cleanup: remove from map and close GATT immediately
            connectionsMutex.withLock {
                connections.remove(address)
            }
            withContext(Dispatchers.Main) {
                gatt.disconnect()
                gatt.close()
            }

            onConnectionFailed?.invoke(address, "Service discovery failed (status: $status)")
            operationQueue.completeOperationByKey(
                "services",
                BleOperationQueue.OperationResult.Failure(Exception("Service discovery failed: $status")),
            )
            return
        }

        // Find Reticulum service
        val service = gatt.getService(BleConstants.SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Reticulum service not found on $address")

            // Force cleanup: remove from map and close GATT immediately
            connectionsMutex.withLock {
                connections.remove(address)
            }
            withContext(Dispatchers.Main) {
                gatt.disconnect()
                gatt.close()
            }

            onConnectionFailed?.invoke(address, "Reticulum service not found")
            operationQueue.completeOperationByKey(
                "services",
                BleOperationQueue.OperationResult.Failure(Exception("Reticulum service not found")),
            )
            return
        }

        // Find RX, TX, and Identity characteristics
        val rxChar = service.getCharacteristic(BleConstants.CHARACTERISTIC_RX_UUID)
        val txChar = service.getCharacteristic(BleConstants.CHARACTERISTIC_TX_UUID)
        val identityChar = service.getCharacteristic(BleConstants.CHARACTERISTIC_IDENTITY_UUID)

        if (rxChar == null || txChar == null) {
            Log.e(TAG, "Required characteristics (RX/TX) not found on $address")

            // Force cleanup: remove from map and close GATT immediately
            connectionsMutex.withLock {
                connections.remove(address)
            }
            withContext(Dispatchers.Main) {
                gatt.disconnect()
                gatt.close()
            }

            onConnectionFailed?.invoke(address, "Required characteristics not found")
            operationQueue.completeOperationByKey(
                "services",
                BleOperationQueue.OperationResult.Failure(Exception("Required characteristics not found")),
            )
            return
        }

        // Store characteristics
        connectionsMutex.withLock {
            connections[address]?.apply {
                this.rxCharacteristic = rxChar
                this.txCharacteristic = txChar
            }
        }

        Log.d(TAG, "Services discovered on $address, reading identity...")

        // Complete service discovery operation successfully
        operationQueue.completeOperationByKey("services", BleOperationQueue.OperationResult.Success())

        // Read identity characteristic (if available)
        if (identityChar != null) {
            try {
                Log.i(TAG, ">>> HANDSHAKE STEP 1/3: Reading Identity characteristic from $address...")
                // Await the operation to catch timeouts and failures
                operationQueue.enqueue(
                    BleOperationQueue.BleOperation.ReadCharacteristic(
                        gatt = gatt,
                        characteristic = identityChar,
                    ),
                )
                // If we reach here, read succeeded and handleCharacteristicRead() already
                // called requestMtuAndContinue(), so we're done
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read identity characteristic from $address (non-fatal)", e)
                Log.i(TAG, ">>> HANDSHAKE: Skipping identity (Protocol v1 fallback)")
                // Continue without identity - will request MTU after timeout
                requestMtuAndContinue(address, gatt)
            }
        } else {
            Log.w(TAG, ">>> HANDSHAKE: Identity characteristic not found on $address")
            Log.w(TAG, "    This is a Protocol v1 device (no identity support)")
            Log.w(TAG, "    Continuing with MAC-based tracking...")
            // Continue without identity
            requestMtuAndContinue(address, gatt)
        }
    }

    private suspend fun requestMtuAndContinue(
        address: String,
        gatt: BluetoothGatt,
    ) {
        // Check if handshake is already in progress to prevent duplicate attempts
        val shouldProceed =
            connectionsMutex.withLock {
                val connData = connections[address]
                if (connData == null) {
                    Log.w(TAG, "Connection data not found for $address when requesting MTU")
                    return@withLock false
                }

                if (connData.handshakeInProgress) {
                    Log.w(TAG, "Handshake already in progress for $address, skipping duplicate attempt")
                    return@withLock false
                }

                // Mark handshake as in progress
                connData.handshakeInProgress = true
                true
            }

        if (!shouldProceed) {
            return
        }

        val connData = connectionsMutex.withLock { connections[address] }
        if (connData == null) {
            Log.w(TAG, "Connection data not found for $address when requesting MTU")
            return
        }

        val txChar = connData.txCharacteristic
        if (txChar == null) {
            Log.e(TAG, "TX characteristic not available for $address")
            return
        }

        Log.i(TAG, ">>> HANDSHAKE STEP 2/3: Requesting MTU for $address...")

        // Request MTU
        try {
            operationQueue.enqueue(
                BleOperationQueue.BleOperation.RequestMtu(
                    gatt = gatt,
                    mtu = BleConstants.MAX_MTU,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request MTU for $address", e)
            // Continue anyway with default MTU
            enableNotifications(address, gatt, txChar)
        }
    }

    private suspend fun handleMtuChanged(
        address: String,
        mtu: Int,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // MTU includes 3-byte ATT header, so usable MTU is mtu - 3
            val usableMtu = mtu - 3

            connectionsMutex.withLock {
                connections[address]?.mtu = usableMtu
            }

            Log.d(TAG, "MTU changed for $address: $usableMtu bytes (requested: ${BleConstants.MAX_MTU})")
            onMtuChanged?.invoke(address, usableMtu)

            // Complete MTU operation in queue
            operationQueue.completeOperationByKey("mtu", BleOperationQueue.OperationResult.Success())
        } else {
            // MTU negotiation failed - use reasonable default instead of minimum
            val fallbackMtu = BleConstants.DEFAULT_MTU - 3 // 182 bytes usable

            connectionsMutex.withLock {
                connections[address]?.mtu = fallbackMtu
            }

            Log.w(TAG, "MTU negotiation failed for $address (status: $status), using default MTU: $fallbackMtu")
            onMtuChanged?.invoke(address, fallbackMtu)

            // Mark as success (not failure) so connection continues with fallback MTU
            operationQueue.completeOperationByKey("mtu", BleOperationQueue.OperationResult.Success())
        }

        // Enable notifications on TX characteristic
        val connData = connectionsMutex.withLock { connections[address] }
        val txCharacteristic = connData?.txCharacteristic
        if (connData != null && txCharacteristic != null) {
            // Allow BLE stack to settle after MTU negotiation before writing descriptor
            // Without this delay, we get ERROR_GATT_WRITE_REQUEST_BUSY (201)
            delay(BleConstants.POST_MTU_SETTLE_DELAY_MS)
            enableNotifications(address, connData.gatt, txCharacteristic)
        }
    }

    private suspend fun enableNotifications(
        address: String,
        gatt: BluetoothGatt,
        txChar: BluetoothGattCharacteristic,
    ) {
        try {
            Log.i(TAG, ">>> HANDSHAKE STEP 3/3: Enabling TX notifications for $address...")

            // Verify TX characteristic properties
            val properties = txChar.properties
            val supportsNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val supportsIndicate = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

            Log.d(TAG, "TX characteristic properties: notify=$supportsNotify, indicate=$supportsIndicate")

            if (!supportsNotify && !supportsIndicate) {
                throw Exception("TX characteristic does not support notifications or indications")
            }

            // Enable local notifications first
            val localNotifyResult =
                operationQueue.enqueue(
                    BleOperationQueue.BleOperation.SetCharacteristicNotification(
                        gatt = gatt,
                        characteristic = txChar,
                        enable = true,
                    ),
                )

            if (localNotifyResult is BleOperationQueue.OperationResult.Failure) {
                throw localNotifyResult.error
            }

            Log.d(TAG, "Local notifications enabled for $address, writing CCCD...")

            // Write CCCD descriptor to enable remote notifications
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                // Check descriptor permissions
                val permissions = cccd.permissions
                Log.d(TAG, "CCCD descriptor found for $address - UUID: ${cccd.uuid}, permissions: $permissions")

                // Add a small delay to allow the BLE stack to settle after MTU change
                delay(50)

                Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to CCCD for $address")
                val descriptorResult =
                    operationQueue.enqueue(
                        BleOperationQueue.BleOperation.WriteDescriptor(
                            gatt = gatt,
                            descriptor = cccd,
                            data = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        ),
                    )

                if (descriptorResult is BleOperationQueue.OperationResult.Failure) {
                    throw descriptorResult.error
                }
            } else {
                Log.w(TAG, "CCCD descriptor not found for TX characteristic on $address")
                throw Exception("CCCD descriptor not found")
            }

            Log.d(TAG, "Notifications enabled for $address")

            // Protocol v2.2: Send identity handshake to peripheral
            val ourIdentity = transportIdentityHash
            if (ourIdentity != null) {
                try {
                    Log.i(TAG, ">>> HANDSHAKE STEP 4/4: Sending central identity handshake to $address...")
                    val connData = connectionsMutex.withLock { connections[address] }
                    val rxChar = connData?.rxCharacteristic

                    if (rxChar != null && connData != null) {
                        operationQueue.enqueue(
                            BleOperationQueue.BleOperation.WriteCharacteristic(
                                gatt = connData.gatt,
                                characteristic = rxChar,
                                data = ourIdentity,
                                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                            ),
                        )
                        Log.i(TAG, ">>> Identity handshake sent (16 bytes)")
                    } else {
                        Log.w(TAG, ">>> RX characteristic not available, skipping identity handshake")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send identity handshake to $address (non-fatal): ${e.message}", e)
                }
            } else {
                Log.w(TAG, ">>> Local transport identity not set, skipping handshake (Protocol v1 fallback)")
            }

            // Connection complete!
            val connData = connectionsMutex.withLock { connections[address] }
            val mtu = connData?.mtu ?: BleConstants.MIN_MTU
            val identityHash = connData?.identityHash

            Log.i(TAG, "=== CONNECTION ESTABLISHED ===")
            Log.i(TAG, "  Peer: $address")
            Log.i(TAG, "  MTU: $mtu bytes")
            Log.i(TAG, "  Peer Identity: ${identityHash ?: "NOT AVAILABLE (Protocol v1)"}")
            Log.i(TAG, "  Our Identity: ${ourIdentity?.joinToString("") { "%02x".format(it) }?.take(16) ?: "NOT SET"}")
            Log.i(TAG, "==============================")

            // Clear handshake in progress flag
            connectionsMutex.withLock {
                connections[address]?.handshakeInProgress = false
            }

            // Fire onConnected callback now that full handshake is complete
            onConnected?.invoke(address, mtu)
            Log.d(TAG, ">>> onConnected callback fired after full handshake (MTU=$mtu)")

            // Start keepalive to prevent supervision timeout
            startKeepalive(address)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable notifications for $address", e)

            // Clear handshake in progress flag on error
            connectionsMutex.withLock {
                connections[address]?.handshakeInProgress = false
            }

            // Force cleanup: this connection is stuck and unusable
            // Remove from map and close GATT immediately to free connection slot
            val connData =
                connectionsMutex.withLock {
                    connections.remove(address)
                }

            if (connData != null) {
                withContext(Dispatchers.Main) {
                    connData.gatt.disconnect()
                    connData.gatt.close()
                }
            }

            onConnectionFailed?.invoke(address, "Failed to enable notifications: ${e.message}")
        }
    }

    private suspend fun handleCharacteristicWrite(
        address: String,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.v(TAG, "Write successful to $address")
            // Complete operation in queue
            operationQueue.completeOperationByKey("write", BleOperationQueue.OperationResult.Success())
        } else {
            Log.e(TAG, "Write failed to $address (status: $status)")
            operationQueue.completeOperationByKey(
                "write",
                BleOperationQueue.OperationResult.Failure(Exception("Write failed: $status")),
            )
        }
    }

    private suspend fun handleCharacteristicRead(
        address: String,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (characteristic.uuid == BleConstants.CHARACTERISTIC_IDENTITY_UUID) {
                // Identity characteristic read
                if (value.size == 16) {
                    // Convert 16 bytes to 32-character hex string
                    val identityHash = value.joinToString("") { "%02x".format(it) }

                    Log.i(TAG, "Received identity from $address: $identityHash")

                    // Store identity
                    connectionsMutex.withLock {
                        connections[address]?.identityHash = identityHash
                    }

                    // Notify callback
                    onIdentityReceived?.invoke(address, identityHash)

                    // Complete operation and continue with MTU negotiation
                    operationQueue.completeOperationByKey("read", BleOperationQueue.OperationResult.Success())

                    Log.i(TAG, ">>> IDENTITY READ COMPLETE for $address - proceeding to MTU negotiation")

                    // Continue connection setup
                    val connData = connectionsMutex.withLock { connections[address] }
                    if (connData != null) {
                        // NOTE: onConnected callback will fire after full handshake completes
                        // (after MTU negotiation and notifications are enabled)
                        requestMtuAndContinue(address, connData.gatt)
                    }
                } else {
                    Log.w(TAG, ">>> HANDSHAKE ERROR: Invalid identity size from $address")
                    Log.w(TAG, "    Expected: 16 bytes")
                    Log.w(TAG, "    Received: ${value.size} bytes")
                    Log.w(TAG, "    Falling back to MAC-based tracking...")
                    operationQueue.completeOperationByKey(
                        "read",
                        BleOperationQueue.OperationResult.Failure(
                            Exception("Invalid identity size: ${value.size}"),
                        ),
                    )
                    // Continue anyway without identity
                    val connData = connectionsMutex.withLock { connections[address] }
                    if (connData != null) {
                        requestMtuAndContinue(address, connData.gatt)
                    }
                }
            } else {
                Log.v(TAG, "Read successful from $address for characteristic ${characteristic.uuid}")
                operationQueue.completeOperationByKey("read", BleOperationQueue.OperationResult.Success())
            }
        } else {
            if (characteristic.uuid == BleConstants.CHARACTERISTIC_IDENTITY_UUID) {
                Log.w(TAG, ">>> HANDSHAKE ERROR: Identity read failed from $address (status: $status)")
                Log.w(TAG, "    This may indicate:")
                Log.w(TAG, "      - Protocol v1 device (identity not implemented)")
                Log.w(TAG, "      - Reticulum not initialized on peer yet")
                Log.w(TAG, "      - GATT connection issue")
                Log.w(TAG, "    Falling back to MAC-based tracking...")
            } else {
                Log.e(TAG, "Read failed from $address (status: $status)")
            }

            operationQueue.completeOperationByKey(
                "read",
                BleOperationQueue.OperationResult.Failure(Exception("Read failed: $status")),
            )
            // Note: For identity read failures, we rely on:
            // 1. Initial service discovery fallback paths (lines 492, 499) if read never queued
            // 2. Late success callback (line 764) if read eventually succeeds
            // Not calling requestMtuAndContinue() here prevents duplicate handshake race condition
        }
    }

    private fun handleCharacteristicChanged(
        address: String,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid == BleConstants.CHARACTERISTIC_TX_UUID) {
            Log.v(TAG, "Received ${value.size} bytes from $address")
            onDataReceived?.invoke(address, value)
        }
    }

    private suspend fun handleDescriptorWrite(
        address: String,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.v(TAG, "Descriptor write successful for $address")
            operationQueue.completeOperationByKey("descriptor", BleOperationQueue.OperationResult.Success())
        } else {
            val errorMsg =
                when (status) {
                    1 -> "GATT_FAILURE/GATT_INVALID_HANDLE - Server rejected descriptor write"
                    13 -> "GATT_INVALID_HANDLE - Descriptor handle invalid"
                    15 -> "GATT_INSUFFICIENT_ENCRYPTION - Encryption required"
                    143 -> "GATT_REQUEST_NOT_SUPPORTED - Operation not supported"
                    BleConstants.ERROR_GATT_WRITE_REQUEST_BUSY -> "ERROR_GATT_WRITE_REQUEST_BUSY - BLE stack busy (should have been retried)"
                    else -> "Unknown GATT error code: $status"
                }
            Log.e(TAG, "Descriptor write failed for $address: $errorMsg (status: $status)")
            Log.e(TAG, "  Descriptor UUID: ${descriptor.uuid}")
            operationQueue.completeOperationByKey(
                "descriptor",
                BleOperationQueue.OperationResult.Failure(Exception("Descriptor write failed: $errorMsg")),
            )
        }
    }

    private suspend fun handleDescriptorRead(
        address: String,
        descriptor: BluetoothGattDescriptor,
        status: Int,
        value: ByteArray,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.v(TAG, "Descriptor read successful for $address: ${value.size} bytes")
            Log.v(TAG, "  Descriptor UUID: ${descriptor.uuid}")
            operationQueue.completeOperationByKey(
                "descriptor_read",
                BleOperationQueue.OperationResult.Success(value),
            )
        } else {
            val errorMsg =
                when (status) {
                    1 -> "GATT_FAILURE/GATT_INVALID_HANDLE - Server rejected descriptor read"
                    13 -> "GATT_INVALID_HANDLE - Descriptor handle invalid"
                    15 -> "GATT_INSUFFICIENT_ENCRYPTION - Encryption required"
                    143 -> "GATT_REQUEST_NOT_SUPPORTED - Operation not supported"
                    else -> "Unknown GATT error code: $status"
                }
            Log.e(TAG, "Descriptor read failed for $address: $errorMsg (status: $status)")
            Log.e(TAG, "  Descriptor UUID: ${descriptor.uuid}")
            operationQueue.completeOperationByKey(
                "descriptor_read",
                BleOperationQueue.OperationResult.Failure(Exception("Descriptor read failed: $errorMsg")),
            )
        }
    }

    private suspend fun handleStatus133Error(
        address: String,
        gatt: BluetoothGatt,
    ) {
        Log.e(TAG, "GATT error 133 for $address")

        val connData = connectionsMutex.withLock { connections[address] }
        if (connData == null) {
            gatt.close()
            return
        }

        // Close the connection
        gatt.close()

        // Check retry count
        if (connData.retryCount < MAX_CONNECTION_RETRIES) {
            // Retry with exponential backoff
            val retryCount = connData.retryCount + 1
            val backoffMs = BleConstants.CONNECTION_RETRY_BACKOFF_MS * (1L shl retryCount)

            Log.d(TAG, "Retrying connection to $address (attempt $retryCount/$MAX_CONNECTION_RETRIES) in ${backoffMs}ms")

            connectionsMutex.withLock {
                connections.remove(address)
            }

            scope.launch {
                delay(backoffMs)
                val result = connect(address)
                if (result.isFailure) {
                    onConnectionFailed?.invoke(address, "Retry failed: ${result.exceptionOrNull()?.message}")
                }
            }

            connectionsMutex.withLock {
                connections[address]?.retryCount = retryCount
            }
        } else {
            // Max retries exceeded
            Log.e(TAG, "Max connection retries exceeded for $address")
            connectionsMutex.withLock {
                connections.remove(address)
            }
            onConnectionFailed?.invoke(address, "GATT error 133: max retries exceeded")
        }
    }

    private suspend fun handleConnectionTimeout(address: String) {
        Log.e(TAG, "Connection timeout for $address")

        val connData = connectionsMutex.withLock { connections.remove(address) }
        if (connData != null) {
            withContext(Dispatchers.Main) {
                connData.gatt.disconnect()
                connData.gatt.close()
            }
            onConnectionFailed?.invoke(address, "Connection timeout")
        }
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

    // ========== Connection Keepalive ==========

    /**
     * Start connection keepalive to prevent Android BLE idle disconnects.
     * Sends a 1-byte keepalive packet every 7 seconds to keep the connection alive.
     *
     * Android has multiple timeout mechanisms:
     * - GATT supervision timeout: 20-30 seconds of inactivity (status code 8)
     * - L2CAP idle timer: ~20 seconds with no active logical channels
     *
     * The L2CAP idle timer is more aggressive - even if data is being RECEIVED,
     * the connection can be killed if outgoing GATT writes are failing. This
     * keepalive mechanism tracks WRITE failures separately and disconnects early
     * to allow reconnection rather than waiting for L2CAP cleanup.
     *
     * @param address MAC address of the device
     */
    private fun startKeepalive(address: String) {
        scope.launch {
            connectionsMutex.withLock {
                connections[address]?.let { connData ->
                    // Cancel any existing keepalive
                    connData.keepaliveJob?.cancel()

                    // Start new keepalive job
                    connData.keepaliveJob =
                        scope.launch {
                            // Send immediate first keepalive to establish bidirectional traffic
                            try {
                                val keepalivePacket = byteArrayOf(0x00)
                                val result = sendData(address, keepalivePacket)
                                if (result.isSuccess) {
                                    Log.v(TAG, "Initial keepalive sent to $address")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Initial keepalive error for $address", e)
                            }

                            // Continue with regular interval
                            while (isActive) {
                                delay(BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS)

                                try {
                                    // Send 1-byte keepalive packet (0x00 = ping)
                                    val keepalivePacket = byteArrayOf(0x00)
                                    val result = sendData(address, keepalivePacket)

                                    if (result.isSuccess) {
                                        Log.v(TAG, "Keepalive sent to $address")
                                        // Reset WRITE failure counter on successful write
                                        connectionsMutex.withLock {
                                            connections[address]?.consecutiveKeepaliveWriteFailures = 0
                                        }
                                    } else {
                                        // Track write failures - these indicate GATT session degradation
                                        // even if we're still receiving data from peer
                                        val writeFailures =
                                            connectionsMutex.withLock {
                                                val conn = connections[address]
                                                if (conn != null) {
                                                    conn.consecutiveKeepaliveWriteFailures++
                                                    conn.consecutiveKeepaliveWriteFailures
                                                } else {
                                                    0
                                                }
                                            }
                                        Log.w(
                                            TAG,
                                            "Keepalive WRITE failed for $address ($writeFailures/${BleConstants.MAX_KEEPALIVE_WRITE_FAILURES} failures)",
                                        )

                                        // Disconnect early on write failures - don't wait for L2CAP cleanup
                                        // The connection is degraded if writes are failing, even if receives work
                                        if (writeFailures >= BleConstants.MAX_KEEPALIVE_WRITE_FAILURES) {
                                            Log.e(
                                                TAG,
                                                "GATT writes to $address are failing (L2CAP may be degraded), disconnecting to allow reconnection",
                                            )
                                            // Launch disconnect in separate coroutine to avoid blocking keepalive loop
                                            scope.launch {
                                                disconnect(address)
                                            }
                                            break
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Keepalive error for $address", e)
                                }
                            }
                        }

                    Log.d(TAG, "Started keepalive for $address (interval: ${BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS}ms)")
                }
            }
        }
    }

    /**
     * Stop keepalive for a connection.
     *
     * @param address MAC address of the device
     */
    private suspend fun stopKeepalive(address: String) {
        connectionsMutex.withLock {
            connections[address]?.keepaliveJob?.cancel()
            connections[address]?.keepaliveJob = null
        }
        Log.v(TAG, "Stopped keepalive for $address")
    }

    /**
     * Send an immediate keepalive to a specific peripheral device.
     *
     * This is used during deduplication to keep the L2CAP link alive when
     * closing the peripheral connection. Android's Bluetooth stack starts a
     * 1-second idle timer when GATT connections are closed, which can tear
     * down the underlying ACL link. By sending traffic just before closing
     * peripheral, we keep the L2CAP layer active for the central connection.
     *
     * @param address MAC address of the peripheral to send keepalive to
     * @return true if keepalive was sent successfully
     */
    suspend fun sendImmediateKeepalive(address: String): Boolean =
        try {
            val keepalivePacket = byteArrayOf(0x00)
            val result = sendData(address, keepalivePacket)
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
     * Shutdown the client and disconnect all devices.
     */
    suspend fun shutdown() {
        val addresses = connectionsMutex.withLock { connections.keys.toList() }
        addresses.forEach { disconnect(it) }
        scope.cancel()
    }
}
