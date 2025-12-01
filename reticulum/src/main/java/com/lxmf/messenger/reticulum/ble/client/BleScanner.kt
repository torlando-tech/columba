package com.lxmf.messenger.reticulum.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.lxmf.messenger.reticulum.ble.model.BleConstants
import com.lxmf.messenger.reticulum.ble.model.BleDevice
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

/**
 * Manages BLE scanning with adaptive intervals and smart polling.
 *
 * Implements the scanning strategy from ble-reticulum Python implementation:
 * - Start with frequent scanning (5s intervals) when discovering new devices
 * - Back off to infrequent scanning (30s intervals) when environment is stable
 * - Respect Android's scan frequency limits (5 scans per 30 seconds)
 * - Filter for Reticulum service UUID
 * - Track discovered devices with RSSI updates
 *
 * Note: Permission checks are performed at UI layer before BLE operations are initiated.
 *
 * @property context Application context
 * @property bluetoothAdapter Bluetooth adapter instance
 * @property scope Coroutine scope for async operations
 */
@SuppressLint("MissingPermission")
class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    companion object {
        private const val TAG = "Columba:Kotlin:BleScanner"

        // Scan intervals
        private const val ACTIVE_SCAN_INTERVAL_MS = BleConstants.DISCOVERY_INTERVAL_MS
        private const val IDLE_SCAN_INTERVAL_MS = BleConstants.DISCOVERY_INTERVAL_IDLE_MS
        private const val SCAN_DURATION_MS = BleConstants.SCAN_DURATION_MS

        // Smart polling
        private const val NEW_DEVICE_THRESHOLD = 3 // Devices discovered before considering "active"
        private const val IDLE_SCANS_THRESHOLD = 3 // Scans with no new devices before going idle
    }

    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

    // State
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, BleDevice>> = _discoveredDevices.asStateFlow()

    private val devicesMutex = Mutex()
    private val devices = mutableMapOf<String, BleDevice>()

    // Smart polling state
    private var scanJob: Job? = null
    private var newDevicesInLastScan = 0
    private var scansWithoutNewDevices = 0
    private var currentScanInterval = ACTIVE_SCAN_INTERVAL_MS

    // Callbacks
    var onDeviceDiscovered: ((BleDevice) -> Unit)? = null
    var onScanStarted: (() -> Unit)? = null
    var onScanStopped: (() -> Unit)? = null
    var onScanFailed: ((Int) -> Unit)? = null

    /**
     * Scan callback for BLE scan results.
     */
    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                scope.launch {
                    handleScanResult(result)
                }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                scope.launch {
                    results.forEach { handleScanResult(it) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                scope.launch {
                    _isScanning.value = false
                    onScanFailed?.invoke(errorCode)
                }
            }
        }

    /**
     * Start continuous scanning with adaptive intervals.
     *
     * @param minRssi Minimum RSSI threshold (devices weaker than this are ignored)
     * @return Result indicating success or failure
     */
    suspend fun startScanning(minRssi: Int = BleConstants.MIN_RSSI_DBM): Result<Unit> {
        return withContext(Dispatchers.Main) {
            try {
                // Check if already scanning
                if (_isScanning.value) {
                    return@withContext Result.success(Unit)
                }

                // Check Bluetooth is enabled
                if (!bluetoothAdapter.isEnabled) {
                    return@withContext Result.failure(
                        IllegalStateException("Bluetooth is disabled"),
                    )
                }

                // Check permissions
                if (!hasRequiredPermissions()) {
                    return@withContext Result.failure(
                        SecurityException("Missing required Bluetooth permissions"),
                    )
                }

                // Check scanner availability
                if (bluetoothLeScanner == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Bluetooth LE Scanner not available"),
                    )
                }

                // Reset smart polling state
                newDevicesInLastScan = 0
                scansWithoutNewDevices = 0
                currentScanInterval = ACTIVE_SCAN_INTERVAL_MS

                // Start periodic scanning
                scanJob =
                    scope.launch {
                        while (isActive) {
                            performScan(minRssi)
                            delay(currentScanInterval)
                            adjustScanInterval()
                        }
                    }

                Log.d(TAG, "Started BLE scanning with adaptive intervals")
                onScanStarted?.invoke()

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start scanning", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Stop scanning.
     */
    suspend fun stopScanning() {
        try {
            scanJob?.cancel()
            scanJob = null

            withContext(Dispatchers.Main) {
                if (_isScanning.value && bluetoothLeScanner != null) {
                    bluetoothLeScanner.stopScan(scanCallback)
                    _isScanning.value = false
                    Log.d(TAG, "Stopped BLE scanning")
                    onScanStopped?.invoke()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when stopping scan", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    /**
     * Perform a single scan.
     */
    private suspend fun performScan(minRssi: Int) {
        withContext(Dispatchers.Main) {
            try {
                val scanner = bluetoothLeScanner ?: return@withContext

                // Create scan filter for Reticulum service UUID
                val scanFilter =
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                        .build()

                // Configure scan settings
                val scanSettings =
                    ScanSettings.Builder()
                        .setScanMode(determineScanMode())
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                        .setReportDelay(0) // Report immediately
                        .build()

                // Reset new devices counter
                newDevicesInLastScan = 0

                // Start scan
                scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
                _isScanning.value = true

                Log.d(
                    TAG,
                    "Started scan (interval: ${currentScanInterval}ms, " +
                        "mode: ${determineScanMode()}, minRssi: $minRssi)",
                )

                // Scan for fixed duration
                delay(SCAN_DURATION_MS)

                // Stop scan
                scanner.stopScan(scanCallback)
                _isScanning.value = false

                Log.d(
                    TAG,
                    "Scan completed. New devices: $newDevicesInLastScan, " +
                        "Total devices: ${devices.size}",
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied during scan", e)
                _isScanning.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error during scan", e)
                _isScanning.value = false
            }
        }
    }

    /**
     * Handle a scan result (device discovered or updated).
     */
    private suspend fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val address = device.address
        val name = result.scanRecord?.deviceName ?: device.name

        // Ignore devices below RSSI threshold
        if (rssi < BleConstants.MIN_RSSI_DBM) {
            return
        }

        // Protocol v2.2: Identity is read from GATT Identity characteristic during handshake,
        // not from device name. Device name may contain identity for human readability.

        devicesMutex.withLock {
            val existingDevice = devices[address]

            if (existingDevice == null) {
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() }
                // New device discovered
                val bleDevice =
                    BleDevice(
                        address = address,
                        name = name,
                        rssi = rssi,
                        serviceUuids = serviceUuids,
                        identityHash = null, // Identity read from GATT characteristic, not device name
                    )
                devices[address] = bleDevice
                newDevicesInLastScan++

                Log.d(TAG, "Discovered new device: $address ($name) RSSI: $rssi dBm")
                onDeviceDiscovered?.invoke(bleDevice)
            } else {
                // Update existing device
                val updated =
                    existingDevice.copy(
                        rssi = rssi,
                        lastSeen = System.currentTimeMillis(),
                    )
                devices[address] = updated

                Log.v(
                    TAG,
                    "Updated device: $address RSSI: $rssi dBm " +
                        "(was ${existingDevice.rssi} dBm)",
                )
            }

            // Update state flow
            _discoveredDevices.value = devices.toMap()
        }
    }

    /**
     * Adjust scan interval based on discovery activity.
     *
     * Smart polling strategy:
     * - If discovering new devices: use active interval (5s)
     * - If no new devices for 3 scans: use idle interval (30s)
     */
    private fun adjustScanInterval() {
        if (newDevicesInLastScan > 0) {
            // Activity detected - use active interval
            scansWithoutNewDevices = 0
            currentScanInterval = ACTIVE_SCAN_INTERVAL_MS
            Log.d(TAG, "Scan interval: ACTIVE ($ACTIVE_SCAN_INTERVAL_MS ms)")
        } else {
            // No new devices
            scansWithoutNewDevices++

            if (scansWithoutNewDevices >= IDLE_SCANS_THRESHOLD) {
                // Environment is stable - use idle interval
                currentScanInterval = IDLE_SCAN_INTERVAL_MS
                Log.d(TAG, "Scan interval: IDLE ($IDLE_SCAN_INTERVAL_MS ms)")
            }
        }
    }

    /**
     * Determine scan mode based on current state.
     *
     * - LOW_LATENCY: When actively discovering (high power but fast)
     * - BALANCED: Normal mode
     * - LOW_POWER: When idle (saves battery)
     */
    private fun determineScanMode(): Int {
        return when {
            newDevicesInLastScan > NEW_DEVICE_THRESHOLD -> ScanSettings.SCAN_MODE_LOW_LATENCY
            scansWithoutNewDevices < IDLE_SCANS_THRESHOLD -> ScanSettings.SCAN_MODE_BALANCED
            else -> ScanSettings.SCAN_MODE_LOW_POWER
        }
    }

    /**
     * Check if required Bluetooth permissions are granted.
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11 and below
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get discovered device by address.
     */
    suspend fun getDevice(address: String): BleDevice? {
        return devicesMutex.withLock { devices[address] }
    }

    /**
     * Get all discovered devices sorted by priority score.
     */
    suspend fun getDevicesSortedByPriority(): List<BleDevice> {
        return devicesMutex.withLock {
            devices.values.sortedByDescending { it.calculatePriorityScore() }
        }
    }

    /**
     * Get a snapshot of discovered devices as a map (address -> device).
     * Thread-safe synchronous access for use in AIDL/IPC contexts.
     *
     * Note: This creates a copy of the devices map. For best performance,
     * prefer getDevicesSortedByPriority() in coroutine contexts.
     */
    @Synchronized
    fun getDevicesSnapshot(): Map<String, BleDevice> {
        return devices.toMap()
    }

    /**
     * Clear discovered devices.
     */
    suspend fun clearDevices() {
        devicesMutex.withLock {
            devices.clear()
            _discoveredDevices.value = emptyMap()
        }
    }

    /**
     * Shutdown the scanner.
     */
    fun shutdown() {
        scope.launch {
            stopScanning()
        }
        scope.cancel()
    }
}
