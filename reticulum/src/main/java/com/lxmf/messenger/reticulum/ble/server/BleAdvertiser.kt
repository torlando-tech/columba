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
import com.lxmf.messenger.reticulum.ble.model.BlePowerSettings
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
    }

    // Power-tunable advertising refresh interval
    @Volatile
    var advertisingRefreshIntervalMs: Long = 60_000L
        private set

    fun updatePowerSettings(settings: BlePowerSettings) {
        val oldInterval = advertisingRefreshIntervalMs
        advertisingRefreshIntervalMs = settings.advertisingRefreshIntervalMs
        Log.d(TAG, "Power settings updated: refreshInterval=${advertisingRefreshIntervalMs}ms")
        // Restart the refresh job so the new interval takes effect immediately
        if (_isAdvertising.value && oldInterval != advertisingRefreshIntervalMs) {
            startRefreshJob()
        }
    }

    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser

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
     * Start BLE advertising with the Reticulum service UUID.
     * The device name is used only for internal logging; it is NOT
     * included in the scan response and the phone's Bluetooth name
     * is never changed.
     *
     * @param deviceName Label used for logging (not advertised)
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

                currentDeviceName = deviceName

                Log.d(TAG, "Advertising with device name: $deviceName")

                // Build advertise settings
                val settings =
                    AdvertiseSettings
                        .Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // Balance power and latency
                        .setConnectable(true) // Must be connectable for GATT server
                        .setTimeout(0) // Advertise indefinitely
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // High power for better range and reliability
                        .build()

                // Build advertise data (what we broadcast)
                // Move device name to scan response to fit within 31-byte advertising payload limit
                // With 128-bit service UUID (19 bytes) + flags (3 bytes) = 22 bytes, no room for name
                val advertiseData =
                    AdvertiseData
                        .Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)) // Reticulum service
                        .build()

                // Build scan response data (sent when central requests more info)
                // Do not include device name to avoid changing the phone's Bluetooth name.
                // Devices discover us via the service UUID in the advertise data.
                val scanResponseData =
                    AdvertiseData
                        .Builder()
                        .setIncludeDeviceName(false)
                        .build()

                // Start advertising
                bluetoothLeAdvertiser.startAdvertising(
                    settings,
                    advertiseData,
                    scanResponseData,
                    advertiseCallback,
                )

                Log.d(TAG, "Starting advertising as '$deviceName'...")

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
     * Immediately stop advertising without coroutines.
     * Called from Main thread during forced shutdown (before System.exit).
     * Safe to call multiple times (idempotent).
     */
    fun stopImmediate() {
        try {
            stopRefreshJob()
            if (bluetoothLeAdvertiser != null) {
                try {
                    bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied in stopImmediate", e)
                }
            }
            _isAdvertising.value = false
            retryAttempts = 0
            currentDeviceName = null
            Log.d(TAG, "Advertiser stopped immediately")
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopImmediate", e)
        }
    }

    /**
     * Check if BLUETOOTH_ADVERTISE permission is granted.
     */
    private fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime permission needed on Android 11 and below
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
                    delay(advertisingRefreshIntervalMs)
                    if (_isAdvertising.value && !isRefreshing) {
                        Log.d(TAG, "Proactive advertising refresh")
                        refreshAdvertising()
                    }
                }
            }
        Log.d(TAG, "Advertising refresh job started (interval: ${advertisingRefreshIntervalMs}ms)")
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
        if (currentDeviceName == null) return
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
                startAdvertisingInternal()
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
    private fun startAdvertisingInternal() {
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "Cannot start advertising - advertiser not available")
            return
        }

        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

        val advertiseData =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()

        val scanResponseData =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .build()

        bluetoothLeAdvertiser.startAdvertising(
            settings,
            advertiseData,
            scanResponseData,
            advertiseCallback,
        )
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
