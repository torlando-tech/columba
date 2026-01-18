package com.lxmf.messenger.reticulum.ble.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import android.util.Log
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Operation queue for Android BLE operations.
 *
 * **Critical**: Android's BLE stack does NOT queue operations internally.
 * If you call multiple operations in succession (e.g., read(), read()), the second
 * will fail silently. This queue ensures serial execution with proper completion tracking.
 *
 * All operations are executed on a dedicated coroutine dispatcher to avoid blocking
 * the main thread or binder threads.
 *
 * Based on patterns from Nordic Semiconductor's Android BLE Library and the
 * ble-reticulum Python implementation.
 *
 * Note: Permission checks are performed at UI layer before BLE operations are initiated.
 */
@SuppressLint("MissingPermission")
class BleOperationQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        private const val TAG = "Columba:BLE:K:Queue"
        private const val DEFAULT_TIMEOUT_MS = BleConstants.OPERATION_TIMEOUT_MS
    }

    /**
     * Sealed class representing different BLE operations.
     */
    sealed class BleOperation {
        /**
         * Connect to a GATT server.
         */
        data class Connect(
            val address: String,
            val autoConnect: Boolean = false,
        ) : BleOperation()

        /**
         * Disconnect from GATT server.
         */
        data class Disconnect(val gatt: BluetoothGatt) : BleOperation()

        /**
         * Discover services on the GATT server.
         */
        data class DiscoverServices(val gatt: BluetoothGatt) : BleOperation()

        /**
         * Request MTU change.
         */
        data class RequestMtu(
            val gatt: BluetoothGatt,
            val mtu: Int = BleConstants.MAX_MTU,
        ) : BleOperation()

        /**
         * Read a characteristic value.
         */
        data class ReadCharacteristic(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
        ) : BleOperation()

        /**
         * Write a characteristic value.
         */
        data class WriteCharacteristic(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val data: ByteArray,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) : BleOperation()

        /**
         * Read a descriptor value.
         */
        data class ReadDescriptor(
            val gatt: BluetoothGatt,
            val descriptor: BluetoothGattDescriptor,
        ) : BleOperation()

        /**
         * Write a descriptor value.
         */
        data class WriteDescriptor(
            val gatt: BluetoothGatt,
            val descriptor: BluetoothGattDescriptor,
            val data: ByteArray,
        ) : BleOperation()

        /**
         * Enable notifications/indications on a characteristic.
         */
        data class SetCharacteristicNotification(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val enable: Boolean,
        ) : BleOperation()
    }

    /**
     * Result of a BLE operation.
     */
    sealed class OperationResult {
        data class Success(val data: Any? = null) : OperationResult()

        data class Failure(val error: Throwable) : OperationResult()

        /**
         * Operation was queued successfully and will complete via callback.
         * Do not call completeOperation() - the callback handler will do it.
         */
        object Pending : OperationResult()
    }

    private val operationQueue = Channel<suspend () -> OperationResult?>(Channel.UNLIMITED)
    private val operationInProgress = AtomicBoolean(false)
    private val pendingOperations = mutableMapOf<String, ContinuationHolder>()
    private val mutex = Mutex()

    /**
     * Channel to signal when async operations complete.
     * This ensures the queue processor waits for callbacks before processing next operation.
     */
    private val operationCompletion = Channel<Unit>(Channel.RENDEZVOUS)

    private data class ContinuationHolder(
        val continuation: CancellableContinuation<OperationResult>,
        val timeoutJob: Job,
    )

    init {
        // Start queue processor
        scope.launch {
            for (operation in operationQueue) {
                try {
                    operationInProgress.set(true)
                    val result = operation()

                    // If operation is Pending (async), wait for callback to complete it
                    // before processing next operation. This prevents concurrent BLE operations.
                    if (result is OperationResult.Pending) {
                        Log.d(TAG, "Operation is async (Pending), waiting for callback completion...")
                        operationCompletion.receive()
                        Log.d(TAG, "Async operation completed, ready for next operation")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing operation", e)
                } finally {
                    operationInProgress.set(false)
                }
            }
        }
    }

    /**
     * Enqueue a BLE operation and suspend until it completes.
     *
     * @param operation The operation to execute
     * @param timeoutMs Timeout in milliseconds (default: 5000ms)
     * @return Result of the operation
     */
    suspend fun enqueue(
        operation: BleOperation,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): OperationResult =
        suspendCancellableCoroutine { continuation ->
            val operationId = UUID.randomUUID().toString()

            val timeoutJob =
                scope.launch {
                    delay(timeoutMs)
                    mutex.withLock {
                        pendingOperations.remove(operationId)?.let {
                            it.continuation.resumeWithException(
                                TimeoutException("Operation timed out after ${timeoutMs}ms: $operation"),
                            )
                        }
                    }
                    // CRITICAL: Signal queue processor that operation completed (via timeout)
                    // Without this, the queue stays blocked on operationCompletion.receive()
                    // and ALL subsequent operations for ALL connections will timeout
                    operationCompletion.trySend(Unit)
                }

            scope.launch {
                mutex.withLock {
                    pendingOperations[operationId] = ContinuationHolder(continuation, timeoutJob)
                }

                operationQueue.send {
                    try {
                        val result = executeOperation(operation)
                        // Only complete immediately if not Pending
                        // Pending operations will be completed by their callbacks
                        if (result !is OperationResult.Pending) {
                            completeOperation(operationId, result)
                        }
                        result // Return result so queue processor knows if it should wait
                    } catch (e: Exception) {
                        // Complete the operation with failure
                        completeOperation(operationId, OperationResult.Failure(e))
                        // Signal completion to unblock queue processor
                        // This prevents deadlock when an exception occurs during a Pending operation
                        operationCompletion.trySend(Unit)
                        null // Return null on exception
                    }
                }
            }

            continuation.invokeOnCancellation {
                scope.launch {
                    mutex.withLock {
                        pendingOperations.remove(operationId)?.timeoutJob?.cancel()
                    }
                }
            }
        }

    /**
     * Complete an operation by resuming its continuation.
     *
     * @param operationId ID of the operation
     * @param result Result to return
     */
    suspend fun completeOperation(
        operationId: String,
        result: OperationResult,
    ) {
        mutex.withLock {
            pendingOperations.remove(operationId)?.let { holder ->
                holder.timeoutJob.cancel()
                when (result) {
                    is OperationResult.Success -> holder.continuation.resume(result)
                    is OperationResult.Failure -> holder.continuation.resumeWithException(result.error)
                    is OperationResult.Pending -> {
                        // This should never happen - Pending results should not call completeOperation
                        Log.e(TAG, "Attempted to complete operation with Pending result - this is a bug")
                    }
                }
            }
        }

        // Signal queue processor that async operation completed
        // This unblocks the queue to process the next operation
        operationCompletion.trySend(Unit)
    }

    /**
     * Complete an operation from a callback (when you don't have the operation ID).
     * This is a convenience method for callbacks.
     *
     * @param key A unique key identifying the operation (e.g., "write_${char.uuid}")
     * @param result Result to return
     */
    suspend fun completeOperationByKey(
        key: String,
        result: OperationResult,
    ) {
        mutex.withLock {
            // Find and complete the first pending operation (FIFO)
            pendingOperations.entries.firstOrNull()?.let { (id, holder) ->
                pendingOperations.remove(id)
                holder.timeoutJob.cancel()
                when (result) {
                    is OperationResult.Success -> holder.continuation.resume(result)
                    is OperationResult.Failure -> holder.continuation.resumeWithException(result.error)
                    is OperationResult.Pending -> {
                        // This should never happen - Pending results should not call completeOperationByKey
                        Log.e(TAG, "Attempted to complete operation with Pending result - this is a bug")
                    }
                }
            }
        }

        // Signal queue processor that async operation completed
        // This unblocks the queue to process the next operation
        operationCompletion.trySend(Unit)
    }

    /**
     * Execute a BLE operation.
     * This method actually calls the Android BLE APIs.
     *
     * Note: The actual completion happens in callbacks (onCharacteristicRead, etc.)
     * which must call completeOperation().
     */
    private suspend fun executeOperation(operation: BleOperation): OperationResult {
        return try {
            when (operation) {
                is BleOperation.Connect -> {
                    // Connection is initiated elsewhere, this just queues it
                    OperationResult.Success()
                }
                is BleOperation.Disconnect -> {
                    operation.gatt.disconnect()
                    OperationResult.Success()
                }
                is BleOperation.DiscoverServices -> {
                    val success = operation.gatt.discoverServices()
                    if (success) {
                        // Return Pending - completion will happen in onServicesDiscovered callback
                        OperationResult.Pending
                    } else {
                        OperationResult.Failure(Exception("Failed to start service discovery"))
                    }
                }
                is BleOperation.RequestMtu -> {
                    val success = operation.gatt.requestMtu(operation.mtu)
                    if (success) {
                        // Return Pending - completion will happen in onMtuChanged callback
                        OperationResult.Pending
                    } else {
                        OperationResult.Failure(Exception("Failed to request MTU"))
                    }
                }
                is BleOperation.ReadCharacteristic -> {
                    val success = operation.gatt.readCharacteristic(operation.characteristic)
                    if (success) {
                        // Return Pending - completion will happen in onCharacteristicRead callback
                        OperationResult.Pending
                    } else {
                        OperationResult.Failure(Exception("Failed to read characteristic"))
                    }
                }
                is BleOperation.WriteCharacteristic -> {
                    // Use modern API on Android 13+ (API 33), old API for backward compatibility
                    val success =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // Android 13+: New API returns Int (status code)
                            val result =
                                operation.gatt.writeCharacteristic(
                                    operation.characteristic,
                                    operation.data,
                                    operation.writeType,
                                )
                            result == BluetoothGatt.GATT_SUCCESS
                        } else {
                            // Android 12 and below: Old API returns Boolean
                            @Suppress("DEPRECATION")
                            operation.characteristic.writeType = operation.writeType
                            @Suppress("DEPRECATION")
                            operation.characteristic.value = operation.data
                            @Suppress("DEPRECATION")
                            operation.gatt.writeCharacteristic(operation.characteristic)
                        }

                    if (success) {
                        // Return Pending - completion will happen in onCharacteristicWrite callback
                        OperationResult.Pending
                    } else {
                        OperationResult.Failure(Exception("Failed to write characteristic"))
                    }
                }
                is BleOperation.ReadDescriptor -> {
                    val success = operation.gatt.readDescriptor(operation.descriptor)
                    if (success) {
                        // Return Pending - completion will happen in onDescriptorRead callback
                        OperationResult.Pending
                    } else {
                        OperationResult.Failure(Exception("Failed to read descriptor"))
                    }
                }
                is BleOperation.WriteDescriptor -> {
                    Log.d(TAG, "Attempting to write descriptor ${operation.descriptor.uuid}")
                    Log.d(TAG, "  Characteristic: ${operation.descriptor.characteristic?.uuid}")
                    Log.d(TAG, "  Data size: ${operation.data.size} bytes")
                    Log.d(TAG, "  Descriptor permissions: ${operation.descriptor.permissions}")

                    // Use modern API on Android 13+ (API 33), old API for backward compatibility
                    var success = false
                    var errorCode = 0

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+: New API returns Int (status code)
                        errorCode = operation.gatt.writeDescriptor(operation.descriptor, operation.data)
                        Log.d(TAG, "writeDescriptor() returned: $errorCode (GATT_SUCCESS=${BluetoothGatt.GATT_SUCCESS})")
                        success = (errorCode == BluetoothGatt.GATT_SUCCESS)

                        // Handle ERROR_GATT_WRITE_REQUEST_BUSY (201) - BLE stack still processing previous operation
                        if (errorCode == BleConstants.ERROR_GATT_WRITE_REQUEST_BUSY) {
                            Log.w(
                                TAG,
                                "ERROR_GATT_WRITE_REQUEST_BUSY (201), waiting ${BleConstants.POST_MTU_SETTLE_DELAY_MS}ms and retrying once...",
                            )
                            delay(BleConstants.POST_MTU_SETTLE_DELAY_MS)

                            errorCode = operation.gatt.writeDescriptor(operation.descriptor, operation.data)
                            Log.d(TAG, "writeDescriptor() retry returned: $errorCode")
                            success = (errorCode == BluetoothGatt.GATT_SUCCESS)
                        }
                    } else {
                        // Android 12 and below: Old API returns Boolean
                        @Suppress("DEPRECATION")
                        operation.descriptor.value = operation.data
                        @Suppress("DEPRECATION")
                        val result = operation.gatt.writeDescriptor(operation.descriptor)
                        Log.d(TAG, "writeDescriptor() returned: $result")
                        success = result
                    }

                    if (success) {
                        Log.d(TAG, "Descriptor write queued successfully, waiting for callback...")
                        // Return Pending - completion will happen in onDescriptorWrite callback
                        OperationResult.Pending
                    } else {
                        val errorMsg = "Failed to queue descriptor write for ${operation.descriptor.uuid} (error: $errorCode)"
                        Log.e(TAG, errorMsg)
                        OperationResult.Failure(Exception(errorMsg))
                    }
                }
                is BleOperation.SetCharacteristicNotification -> {
                    val success =
                        operation.gatt.setCharacteristicNotification(
                            operation.characteristic,
                            operation.enable,
                        )
                    if (success) {
                        OperationResult.Success()
                    } else {
                        OperationResult.Failure(Exception("Failed to set characteristic notification"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing operation: $operation", e)
            OperationResult.Failure(e)
        }
    }

    /**
     * Check if an operation is currently in progress.
     */
    fun isOperationInProgress(): Boolean = operationInProgress.get()

    /**
     * Get the number of pending operations.
     */
    suspend fun getPendingOperationCount(): Int {
        return mutex.withLock { pendingOperations.size }
    }

    /**
     * Clear all pending operations.
     * This should be called when disconnecting or shutting down.
     */
    suspend fun clearPendingOperations() {
        mutex.withLock {
            pendingOperations.values.forEach { holder ->
                holder.timeoutJob.cancel()
                holder.continuation.resumeWithException(
                    CancellationException("Operation cancelled: queue cleared"),
                )
            }
            pendingOperations.clear()
        }
    }

    /**
     * Shutdown the operation queue.
     * Cancels all pending operations and closes the queue.
     */
    fun shutdown() {
        scope.cancel()
        operationQueue.close()
    }
}

/**
 * Timeout exception for BLE operations.
 */
class TimeoutException(message: String) : Exception(message)
