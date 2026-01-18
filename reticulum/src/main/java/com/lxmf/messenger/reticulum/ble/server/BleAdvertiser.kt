package com.lxmf.messenger.reticulum.ble.server

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.lxmf.messenger.reticulum.ble.model.BleConstants
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
import kotlinx.coroutines.withContext

/**
 * Manages BLE advertising for peripheral mode.
 *
 * Advertises the Reticulum service UUID so that other devices can discover and connect to us.
 * Handles advertising failures with automatic retry and exponential backoff.
 *
 * Note: Permission checks are performed at UI layer before BLE operations are initiated.
 *
 * @property context Application context
 * @property bluetoothAdapter Bluetooth adapter
 * @property scope Coroutine scope for async operations
 */
@SuppressLint("MissingPermission")
class BleAdvertiser(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "Columba:BLE:K:Adv"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_BACKOFF_MS = 2000L // 2 seconds
        private const val ADVERTISING_REFRESH_INTERVAL_MS = 60_000L // 1 minute
    }

    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser

    // Local transport identity (16 bytes, set by Python bridge)
    @Volatile
    private var transportIdentityHash: ByteArray? = null

    // State
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private var currentDeviceName: String? = null
    private var retryAttempts = 0

    // Proactive refresh job to keep advertising alive
    // Android silently stops advertising when app goes to background/screen off
    private var refreshJob: Job? = null
    private var isRefreshing = false

    // Callbacks
    var onAdvertisingStarted: ((String) -> Unit)? = null
    var onAdvertisingStopped: (() -> Unit)? = null
    var onAdvertisingFailed: ((Int, String) -> Unit)? = null // errorCode, message

    /**
     * Advertise callback for handling advertising state changes.
     */
    private val advertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Advertising started successfully: $currentDeviceName")
                scope.launch {
                    _isAdvertising.value = true
                    retryAttempts = 0

                    // Start proactive refresh job (only if not already in a refresh)
                    if (!isRefreshing) {
                        startRefreshJob()
                    }

                    onAdvertisingStarted?.invoke(currentDeviceName ?: "Unknown")
                }
            }

            override fun onStartFailure(errorCode: Int) {
                val errorMessage = getAdvertiseErrorMessage(errorCode)
                Log.e(TAG, "Advertising failed: $errorMessage (code: $errorCode)")

                scope.launch {
                    _isAdvertising.value = false

                    // Retry with backoff
                    if (retryAttempts < MAX_RETRY_ATTEMPTS) {
                        retryAttempts++
                        val backoffMs = RETRY_BACKOFF_MS * retryAttempts

                        Log.d(TAG, "Retrying advertising in ${backoffMs}ms (attempt $retryAttempts/$MAX_RETRY_ATTEMPTS)")

                        delay(backoffMs)
                        currentDeviceName?.let { name ->
                            startAdvertising(name)
                        }
                    } else {
                        Log.e(TAG, "Max advertising retry attempts reached")
                        onAdvertisingFailed?.invoke(errorCode, errorMessage)
                    }
                }
            }
        }

    /**
     * Set the local transport identity hash for Protocol v2.2 device naming.
     * The device will advertise as "RNS-{32-hex-identity}" when identity is set.
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
     * Start BLE advertising with Protocol v2.2 identity-based naming.
     * If transport identity is set, advertises as "RNS-{32-hex-identity}".
     * Otherwise falls back to provided device name.
     *
     * @param deviceName Fallback device name (used if identity not set)
     * @return Result indicating success or failure
     */
    suspend fun startAdvertising(deviceName: String = BleConstants.DEFAULT_DEVICE_NAME_PREFIX): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                // Check if already advertising
                if (_isAdvertising.value) {
                    Log.d(TAG, "Already advertising")
                    return@withContext Result.success(Unit)
                }

                // Check Bluetooth is enabled
                if (!bluetoothAdapter.isEnabled) {
                    return@withContext Result.failure(
                        IllegalStateException("Bluetooth is disabled"),
                    )
                }

                // Check permissions
                if (!hasAdvertisePermission()) {
                    return@withContext Result.failure(
                        SecurityException("Missing BLUETOOTH_ADVERTISE permission"),
                    )
                }

                // Check advertiser availability
                if (bluetoothLeAdvertiser == null) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Bluetooth LE Advertiser not available. " +
                                "This device may not support peripheral mode.",
                        ),
                    )
                }

                // Check if peripheral mode is supported
                if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Multiple advertisement not supported. " +
                                "Peripheral mode requires Android 5.0+ with hardware support.",
                        ),
                    )
                }

                // Determine actual device name to use
                // Protocol v2.2: Use "RNS-{truncated-identity}" if identity is set
                // Truncate to first N bytes to fit BLE advertising payload constraints (31-byte limit)
                val actualDeviceName =
                    transportIdentityHash?.let { identity ->
                        "RNS-${identity.take(BleConstants.IDENTITY_BYTES_IN_ADVERTISED_NAME).joinToString("") { "%02x".format(it) }}"
                    } ?: deviceName

                currentDeviceName = actualDeviceName

                Log.d(TAG, "Advertising with device name: $actualDeviceName")
                if (transportIdentityHash != null) {
                    Log.d(TAG, "  (Protocol v2.2 identity-based naming)")
                } else {
                    Log.w(TAG, "  (Protocol v1 fallback - identity not set)")
                }

                // Build advertise settings
                val settings =
                    AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // Balance power and latency
                        .setConnectable(true) // Must be connectable for GATT server
                        .setTimeout(0) // Advertise indefinitely
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // High power for better range and reliability
                        .build()

                // Build advertise data (what we broadcast)
                // Move device name to scan response to fit within 31-byte advertising payload limit
                // With 128-bit service UUID (19 bytes) + flags (3 bytes) = 22 bytes, no room for name
                val advertiseData =
                    AdvertiseData.Builder()
                        .setIncludeDeviceName(false) // Name moved to scan response due to payload size
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)) // Reticulum service
                        .build()

                // Build scan response data (sent when central requests more info)
                // Scan response has separate 31-byte payload, perfect for device name
                val scanResponseData =
                    AdvertiseData.Builder()
                        .setIncludeDeviceName(true) // Name in scan response (31-byte budget)
                        .build()

                // Temporarily set Bluetooth name
                val originalName = bluetoothAdapter.name
                try {
                    bluetoothAdapter.name = actualDeviceName
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot set Bluetooth name (permission denied), using default")
                }

                // Start advertising
                bluetoothLeAdvertiser.startAdvertising(
                    settings,
                    advertiseData,
                    scanResponseData,
                    advertiseCallback,
                )

                Log.d(TAG, "Starting advertising as '$actualDeviceName'...")

                // Restore original name after a delay (advertisement already started)
                scope.launch {
                    delay(1000)
                    try {
                        bluetoothAdapter.name = originalName
                    } catch (e: SecurityException) {
                        // Ignore
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting advertising", e)
                Result.failure(e)
            }
        }

    /**
     * Stop BLE advertising.
     */
    suspend fun stopAdvertising() {
        try {
            // Stop refresh job first
            stopRefreshJob()

            withContext(Dispatchers.Main) {
                if (_isAdvertising.value && bluetoothLeAdvertiser != null) {
                    bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                    _isAdvertising.value = false
                    retryAttempts = 0
                    currentDeviceName = null

                    Log.d(TAG, "Advertising stopped")
                    onAdvertisingStopped?.invoke()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when stopping advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }
    }

    /**
     * Check if BLUETOOTH_ADVERTISE permission is granted.
     */
    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime permission needed on Android 11 and below
        }
    }

    /**
     * Get human-readable error message for advertise error code.
     */
    private fun getAdvertiseErrorMessage(errorCode: Int): String =
        when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                "Advertise data too large"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                "Too many advertisers (limit: typically 4)"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                "Advertising already started"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                "Internal error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                "Feature unsupported (peripheral mode not available)"
            else ->
                "Unknown error ($errorCode)"
        }

    /**
     * Start periodic refresh job to keep advertising alive.
     *
     * Android silently stops BLE advertising when:
     * - App goes to background
     * - Screen turns off
     * - Device enters Doze mode
     *
     * By proactively refreshing advertising every minute, we ensure
     * the device remains discoverable even after Android kills it.
     */
    private fun startRefreshJob() {
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                while (isActive) {
                    delay(ADVERTISING_REFRESH_INTERVAL_MS)
                    if (_isAdvertising.value && !isRefreshing) {
                        Log.d(TAG, "Proactive advertising refresh")
                        refreshAdvertising()
                    }
                }
            }
        Log.d(TAG, "Advertising refresh job started (interval: ${ADVERTISING_REFRESH_INTERVAL_MS}ms)")
    }

    /**
     * Stop the refresh job.
     */
    private fun stopRefreshJob() {
        refreshJob?.cancel()
        refreshJob = null
        Log.d(TAG, "Advertising refresh job stopped")
    }

    /**
     * Refresh advertising by stopping and restarting.
     * This ensures advertising is actually active even if Android silently stopped it.
     */
    private suspend fun refreshAdvertising() {
        val name = currentDeviceName ?: return
        if (bluetoothAdapter.isEnabled != true) {
            Log.w(TAG, "Cannot refresh advertising - Bluetooth disabled")
            return
        }

        isRefreshing = true
        try {
            // Stop current advertising
            withContext(Dispatchers.Main) {
                try {
                    bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping advertising during refresh: ${e.message}")
                }
            }

            delay(100) // Brief delay for cleanup

            // Restart advertising
            withContext(Dispatchers.Main) {
                startAdvertisingInternal(name)
            }
            Log.d(TAG, "Advertising refreshed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing advertising", e)
        } finally {
            isRefreshing = false
        }
    }

    /**
     * Internal method to start advertising without the "already advertising" check.
     * Used by refresh to restart advertising.
     */
    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal(deviceName: String) {
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "Cannot start advertising - advertiser not available")
            return
        }

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

        val advertiseData =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()

        val scanResponseData =
            AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

        // Temporarily set Bluetooth name
        val originalName = bluetoothAdapter.name
        try {
            bluetoothAdapter.name = deviceName
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot set Bluetooth name during refresh")
        }

        bluetoothLeAdvertiser.startAdvertising(
            settings,
            advertiseData,
            scanResponseData,
            advertiseCallback,
        )

        // Restore original name after a delay
        scope.launch {
            delay(1000)
            try {
                bluetoothAdapter.name = originalName
            } catch (e: SecurityException) {
                // Ignore
            }
        }
    }

    /**
     * Shutdown the advertiser.
     */
    fun shutdown() {
        stopRefreshJob()
        scope.launch {
            stopAdvertising()
        }
        scope.cancel()
    }
}
