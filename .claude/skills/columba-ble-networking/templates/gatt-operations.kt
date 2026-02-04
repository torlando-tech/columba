package com.lxmf.messenger.reticulum.ble.templates

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import com.lxmf.messenger.reticulum.ble.util.BleOperationQueue
import kotlinx.coroutines.*

/**
 * Template: Complete GATT operation patterns for Columba BLE.
 *
 * This template shows the correct way to perform all GATT operations using
 * the BleOperationQueue to ensure serial execution.
 *
 * Copy and adapt for your use case.
 */
@Suppress("unused")
class GattOperationsTemplate(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val operationQueue: BleOperationQueue,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "GattOperationsTemplate"
    }

    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val connections = mutableMapOf<String, BluetoothGatt>()

    /**
     * Template: Connect to a device.
     *
     * Key points:
     * - Run on Main dispatcher
     * - Use callback for async handling
     * - Implement timeout
     * - Store GATT reference
     */
    suspend fun connectToDevice(address: String): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val device = bluetoothAdapter.getRemoteDevice(address)

            // Implement connection timeout
            val timeoutJob = scope.launch {
                delay(BleConstants.CONNECTION_TIMEOUT_MS)
                handleConnectionTimeout(address)
            }

            // Connect (MUST be on Main thread)
            val gatt = device.connectGatt(
                context,
                false,  // autoConnect: false = faster, true = more reliable
                GattCallback(address, timeoutJob),
                BluetoothDevice.TRANSPORT_LE
            )

            if (gatt == null) {
                timeoutJob.cancel()
                return@withContext Result.failure(IllegalStateException("Failed to create GATT"))
            }

            connections[address] = gatt
            Result.success(Unit)

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: BLUETOOTH_CONNECT required")
            Result.failure(e)
        }
    }

    /**
     * Template: Discover services.
     *
     * Key points:
     * - Post to main thread (Android quirk)
     * - Use operation queue
     * - Wait for callback
     */
    private suspend fun discoverServices(gatt: BluetoothGatt) {
        // Post to main thread (prevents deadlock on some devices)
        withContext(Dispatchers.Main) {
            Handler(Looper.getMainLooper()).post {
                gatt.discoverServices()
            }
        }

        // Alternative: Use operation queue
        operationQueue.enqueue(
            BleOperationQueue.BleOperation.DiscoverServices(gatt)
        )
    }

    /**
     * Template: Request MTU.
     *
     * Key points:
     * - Request max MTU (517)
     * - Wait for callback
     * - Handle failure gracefully
     */
    private suspend fun requestMtu(gatt: BluetoothGatt) {
        try {
            operationQueue.enqueue(
                BleOperationQueue.BleOperation.RequestMtu(
                    gatt = gatt,
                    mtu = BleConstants.MAX_MTU
                )
            )
            // Callback: onMtuChanged()
        } catch (e: Exception) {
            Log.w(TAG, "MTU negotiation failed, using default")
            // Continue with default MTU
        }
    }

    /**
     * Template: Enable notifications.
     *
     * Key points:
     * - Two-step process: local + remote
     * - Local: setCharacteristicNotification()
     * - Remote: Write CCCD descriptor
     * - Use operation queue
     */
    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        // Step 1: Enable local notifications
        operationQueue.enqueue(
            BleOperationQueue.BleOperation.SetCharacteristicNotification(
                gatt = gatt,
                characteristic = characteristic,
                enable = true
            )
        )

        // Step 2: Write CCCD descriptor (notify remote peer)
        val cccd = characteristic.getDescriptor(BleConstants.CCCD_UUID)
        if (cccd != null) {
            operationQueue.enqueue(
                BleOperationQueue.BleOperation.WriteDescriptor(
                    gatt = gatt,
                    descriptor = cccd,
                    data = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            )
        }
    }

    /**
     * Template: Write data to characteristic.
     *
     * Key points:
     * - Use operation queue (CRITICAL!)
     * - Choose write type (default vs no response)
     * - Wait for callback
     */
    suspend fun writeData(
        address: String,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Result<Unit> {
        val gatt = connections[address]
            ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            operationQueue.enqueue(
                BleOperationQueue.BleOperation.WriteCharacteristic(
                    gatt = gatt,
                    characteristic = characteristic,
                    data = data,
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT  // Or WRITE_TYPE_NO_RESPONSE
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Template: Disconnect safely.
     *
     * Key points:
     * - Disconnect first
     * - THEN close (releases resources)
     * - Remove from connections map
     */
    suspend fun disconnect(address: String) {
        val gatt = withContext(Dispatchers.Main) {
            connections.remove(address)
        }

        if (gatt != null) {
            withContext(Dispatchers.Main) {
                gatt.disconnect()
                gatt.close()  // CRITICAL: Must close to avoid leak
            }
            Log.d(TAG, "Disconnected from $address")
        }
    }

    /**
     * Template: GATT Callback with proper error handling.
     */
    private inner class GattCallback(
        private val address: String,
        private val timeoutJob: Job
    ) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                when {
                    // Success - connected
                    status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                        timeoutJob.cancel()
                        Log.d(TAG, "Connected to $address")

                        // Discover services (on main thread!)
                        withContext(Dispatchers.Main) {
                            Handler(Looper.getMainLooper()).post {
                                gatt.discoverServices()
                            }
                        }
                    }

                    // GATT Error 133 - retry with backoff
                    status == 133 -> {
                        timeoutJob.cancel()
                        handleStatus133(address, gatt)
                    }

                    // Disconnected
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        timeoutJob.cancel()
                        gatt.close()
                        connections.remove(address)
                        Log.d(TAG, "Disconnected from $address (status: $status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Find Reticulum service
                    val service = gatt.getService(BleConstants.SERVICE_UUID)
                    if (service == null) {
                        Log.e(TAG, "Reticulum service not found on $address")
                        disconnect(address)
                        return@launch
                    }

                    // Request MTU
                    requestMtu(gatt)
                } else {
                    Log.e(TAG, "Service discovery failed: $status")
                    disconnect(address)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val usableMtu = mtu - 3  // Subtract ATT header
                    Log.d(TAG, "MTU changed to $usableMtu for $address")

                    // Update fragmenter
                    // fragmenters[address]?.updateMtu(usableMtu)

                    // Setup notifications
                    // setupNotifications(gatt)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            scope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.v(TAG, "Write successful")
                    operationQueue.completeOperationByKey("write",
                        BleOperationQueue.OperationResult.Success())
                } else {
                    Log.e(TAG, "Write failed: $status")
                    operationQueue.completeOperationByKey("write",
                        BleOperationQueue.OperationResult.Failure(Exception("Write failed: $status")))
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Dispatch immediately - don't block binder thread
            scope.launch {
                handleDataReceived(address, value)
            }
        }
    }

    /**
     * Template: Handle Status 133 with exponential backoff.
     */
    private suspend fun handleStatus133(address: String, gatt: BluetoothGatt) {
        gatt.close()  // MUST close

        val state = retryStates.getOrPut(address) { DeviceRetryState() }
        state.retryCount++

        if (state.retryCount <= MAX_RETRIES) {
            // Exponential backoff: 30s, 60s, 120s
            val backoffMs = BASE_BACKOFF_MS * (1L shl (state.retryCount - 1))

            Log.d(TAG, "Status 133 for $address, retry ${state.retryCount}/$MAX_RETRIES in ${backoffMs}ms")

            delay(backoffMs)
            connectToDevice(address)  // Retry
        } else {
            // Blacklist device
            val blacklistDuration = BASE_BACKOFF_MS * 10  // 5 minutes
            state.blacklistedUntil = System.currentTimeMillis() + blacklistDuration

            Log.w(TAG, "Device $address blacklisted for ${blacklistDuration / 1000}s")
        }
    }

    /**
     * Template: Check if device is blacklisted.
     */
    private fun isBlacklisted(address: String): Boolean {
        val state = retryStates[address] ?: return false
        val blacklistEnd = state.blacklistedUntil ?: return false

        return if (System.currentTimeMillis() < blacklistEnd) {
            true  // Still blacklisted
        } else {
            // Blacklist expired - clear state
            state.blacklistedUntil = null
            state.retryCount = 0
            false
        }
    }

    /**
     * Template: Connection timeout handler.
     */
    private suspend fun handleConnectionTimeout(address: String) {
        Log.e(TAG, "Connection timeout for $address")

        val gatt = withContext(Dispatchers.Main) {
            connections.remove(address)
        }

        if (gatt != null) {
            withContext(Dispatchers.Main) {
                gatt.disconnect()
                gatt.close()
            }
        }

        // Treat timeout same as Status 133
        handleStatus133(address, gatt!!)
    }

    /**
     * Template: Placeholder for data handling.
     */
    private suspend fun handleDataReceived(address: String, data: ByteArray) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Received ${data.size} bytes from $address")
            // Process data...
        }
    }
}
