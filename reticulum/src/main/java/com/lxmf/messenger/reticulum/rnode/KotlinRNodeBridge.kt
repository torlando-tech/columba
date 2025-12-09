package com.lxmf.messenger.reticulum.rnode

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connection mode for RNode devices.
 */
enum class RNodeConnectionMode {
    /** Bluetooth Classic (SPP/RFCOMM) - wider compatibility */
    CLASSIC,

    /** Bluetooth Low Energy (GATT) - lower power */
    BLE,
}

/**
 * Listener interface for RNode error events.
 * Implement this to receive errors from RNode devices (e.g., for UI display).
 */
interface RNodeErrorListener {
    /**
     * Called when RNode reports an error.
     *
     * @param errorCode The error code from RNode (e.g., 0x40 for invalid config)
     * @param errorMessage Human-readable error message
     */
    fun onRNodeError(
        errorCode: Int,
        errorMessage: String,
    )
}

/**
 * Listener interface for RNode online status changes.
 * Implement this to receive notifications when RNode connects or disconnects.
 * This enables event-driven UI updates for the network interfaces display.
 */
interface RNodeOnlineStatusListener {
    /**
     * Called when RNode online status changes.
     *
     * @param isOnline True if RNode is now online, false if offline
     */
    fun onRNodeOnlineStatusChanged(isOnline: Boolean)
}

/**
 * Kotlin RNode Bridge for Bluetooth communication.
 *
 * This bridge provides serial communication with RNode devices over both
 * Bluetooth Classic (SPP/RFCOMM) and Bluetooth Low Energy (BLE GATT).
 * It exposes a simple byte-level API that can be called from Python via Chaquopy.
 *
 * Key features:
 * - Dual-mode: Bluetooth Classic (SPP) and BLE (Nordic UART Service)
 * - Thread-safe read/write operations
 * - Non-blocking read with internal buffer
 * - Automatic device enumeration (filters for "RNode *" devices)
 * - Python callback for received data
 *
 * Usage from Python:
 *   bridge = get_kotlin_rnode_bridge()
 *   bridge.connect("RNode 5A3F", "classic")  # or "ble"
 *   bridge.write(bytes([0xC0, 0x00, ...]))  # KISS frame
 *   data = bridge.read()  # Non-blocking
 *   bridge.disconnect()
 *
 * @property context Application context for Bluetooth access
 */
@SuppressLint("MissingPermission")
@Suppress("LargeClass")
class KotlinRNodeBridge(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Columba:RNodeBridge"

        // Standard SPP UUID for serial port profile (Bluetooth Classic)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Nordic UART Service UUIDs (BLE)
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write to RNode
        private val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Read from RNode
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Buffer sizes
        private const val READ_BUFFER_SIZE = 4096
        private const val STREAM_BUFFER_SIZE = 2048

        // BLE timeouts and retry settings
        private const val BLE_SCAN_TIMEOUT_MS = 10000L
        private const val BLE_CONNECT_TIMEOUT_MS = 15000L
        private const val BLE_CONNECT_MAX_RETRIES = 3
        private const val BLE_RETRY_DELAY_MS = 1000L

        /**
         * Convert GATT status code to human-readable string for debugging.
         */
        private fun gattStatusToString(status: Int): String =
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> "SUCCESS(0)"
                BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED(2)"
                BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED(3)"
                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTH(5)"
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "REQUEST_NOT_SUPPORTED(6)"
                BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION(15)"
                BluetoothGatt.GATT_INVALID_OFFSET -> "INVALID_OFFSET(7)"
                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "INVALID_ATTR_LENGTH(13)"
                BluetoothGatt.GATT_CONNECTION_CONGESTED -> "CONNECTION_CONGESTED(143)"
                BluetoothGatt.GATT_FAILURE -> "FAILURE(257)"
                8 -> "GATT_CONN_TIMEOUT(8)"
                19 -> "GATT_CONN_TERMINATE_PEER_USER(19)"
                22 -> "GATT_CONN_TERMINATE_LOCAL_HOST(22)"
                34 -> "GATT_CONN_LMP_TIMEOUT(34)"
                133 -> "GATT_ERROR(133)" // Common Android BLE bug
                else -> "UNKNOWN($status)"
            }

        @Volatile
        private var instance: KotlinRNodeBridge? = null

        /**
         * Get or create singleton instance.
         */
        fun getInstance(context: Context): KotlinRNodeBridge {
            return instance ?: synchronized(this) {
                instance ?: KotlinRNodeBridge(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // Current connection mode
    private var connectionMode: RNodeConnectionMode? = null

    // Bluetooth Classic connection state
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

    // BLE connection state
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleRxCharacteristic: BluetoothGattCharacteristic? = null
    private var bleTxCharacteristic: BluetoothGattCharacteristic? = null
    private var bleMtu: Int = 20 // Default BLE MTU (will be negotiated higher)
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    @Volatile
    private var bleScanResult: BluetoothDevice? = null

    @Volatile
    private var bleConnected = false

    @Volatile
    private var bleServicesDiscovered = false

    @Volatile
    private var bleMtuCallbackReceived = false // Tracks if onMtuChanged callback fired

    @Volatile
    private var bleRssi: Int = -100 // Current RSSI (-100 = unknown)

    // Common state
    private var connectedDeviceName: String? = null

    // Thread-safe state flags
    private val isConnected = AtomicBoolean(false)
    private val isReading = AtomicBoolean(false)

    // Read buffer for non-blocking reads
    private val readBuffer = ConcurrentLinkedQueue<Byte>()
    private val writeMutex = Mutex()

    // BLE write synchronization - Android BLE is async, we must wait for each write to complete
    // Latch-based synchronization with null check prevents stale callbacks from corrupting state
    @Volatile
    private var bleWriteLatch: CountDownLatch? = null
    private val bleWriteStatus = AtomicInteger(BluetoothGatt.GATT_SUCCESS)
    private val bleWriteLock = Object()

    // Python callbacks
    @Volatile
    private var onDataReceived: PyObject? = null

    @Volatile
    private var onConnectionStateChanged: PyObject? = null

    @Volatile
    private var onErrorReceived: PyObject? = null

    // Kotlin error listeners (for UI notification)
    private val errorListeners = mutableListOf<RNodeErrorListener>()

    // Kotlin online status listeners (for UI notification)
    private val onlineStatusListeners = mutableListOf<RNodeOnlineStatusListener>()

    /**
     * Set callback for received data.
     * Called on background thread when data arrives from RNode.
     *
     * @param callback Python callable: callback(data: bytes)
     */
    fun setOnDataReceived(callback: PyObject) {
        onDataReceived = callback
    }

    /**
     * Set callback for connection state changes.
     *
     * @param callback Python callable: callback(connected: bool, device_name: str)
     */
    fun setOnConnectionStateChanged(callback: PyObject) {
        onConnectionStateChanged = callback
    }

    /**
     * Set callback for RNode error events.
     * Called when RNode reports an error (e.g., invalid configuration).
     *
     * @param callback Python callable: callback(error_code: int, error_message: str)
     */
    fun setOnErrorReceived(callback: PyObject) {
        onErrorReceived = callback
    }

    /**
     * Register a Kotlin listener for RNode error events.
     * Listeners will be called on a background thread when errors occur.
     *
     * @param listener The listener to register
     */
    fun addErrorListener(listener: RNodeErrorListener) {
        synchronized(errorListeners) {
            if (!errorListeners.contains(listener)) {
                errorListeners.add(listener)
            }
        }
    }

    /**
     * Unregister a Kotlin error listener.
     *
     * @param listener The listener to remove
     */
    fun removeErrorListener(listener: RNodeErrorListener) {
        synchronized(errorListeners) {
            errorListeners.remove(listener)
        }
    }

    /**
     * Notify error callbacks (both Python and Kotlin).
     * Called from Python via the bridge to surface errors to Kotlin layer.
     *
     * @param errorCode The error code from RNode
     * @param errorMessage Human-readable error message
     */
    fun notifyError(
        errorCode: Int,
        errorMessage: String,
    ) {
        Log.w(TAG, "RNode error ($errorCode): $errorMessage")

        // Notify Python callback if set
        onErrorReceived?.callAttr("__call__", errorCode, errorMessage)

        // Notify Kotlin listeners
        synchronized(errorListeners) {
            errorListeners.forEach { listener ->
                try {
                    listener.onRNodeError(errorCode, errorMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Error listener threw exception", e)
                }
            }
        }
    }

    /**
     * Register a Kotlin listener for online status changes.
     * Listeners will be called on a background thread when status changes.
     *
     * @param listener The listener to register
     */
    fun addOnlineStatusListener(listener: RNodeOnlineStatusListener) {
        synchronized(onlineStatusListeners) {
            if (!onlineStatusListeners.contains(listener)) {
                onlineStatusListeners.add(listener)
            }
        }
    }

    /**
     * Unregister a Kotlin online status listener.
     *
     * @param listener The listener to remove
     */
    fun removeOnlineStatusListener(listener: RNodeOnlineStatusListener) {
        synchronized(onlineStatusListeners) {
            onlineStatusListeners.remove(listener)
        }
    }

    /**
     * Notify online status change callbacks.
     * Called from Python via the bridge when RNode online status changes.
     * This enables event-driven UI updates for network interfaces display.
     *
     * @param isOnline True if RNode is now online, false if offline
     */
    fun notifyOnlineStatusChanged(isOnline: Boolean) {
        Log.d(TAG, "████ RNODE ONLINE STATUS ████ online=$isOnline")

        // Notify Kotlin listeners
        synchronized(onlineStatusListeners) {
            onlineStatusListeners.forEach { listener ->
                try {
                    listener.onRNodeOnlineStatusChanged(isOnline)
                } catch (e: Exception) {
                    Log.e(TAG, "Online status listener threw exception", e)
                }
            }
        }
    }

    /**
     * Get list of paired RNode devices.
     * Filters bonded devices for names starting with "RNode ".
     *
     * @return List of device names (e.g., ["RNode 5A3F", "RNode B2C1"])
     */
    fun getPairedRNodes(): List<String> {
        val adapter =
            bluetoothAdapter ?: run {
                Log.w(TAG, "Bluetooth adapter not available")
                return emptyList()
            }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return emptyList()
        }

        return try {
            adapter.bondedDevices
                .filter { device ->
                    val name = device.name ?: ""
                    name.startsWith("RNode ")
                }
                .mapNotNull { it.name }
                .also { devices ->
                    Log.d(TAG, "Found ${devices.size} paired RNode devices: $devices")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
            emptyList()
        }
    }

    /**
     * Connect to a paired RNode device by name using Bluetooth Classic.
     * Convenience method that defaults to Classic mode.
     *
     * @param deviceName Device name (e.g., "RNode 5A3F")
     * @return true if connection successful, false otherwise
     */
    fun connect(deviceName: String): Boolean {
        return connect(deviceName, "classic")
    }

    /**
     * Connect to an RNode device by name with specified mode.
     *
     * @param deviceName Device name (e.g., "RNode 5A3F")
     * @param mode Connection mode: "classic" for Bluetooth Classic, "ble" for BLE
     * @return true if connection successful, false otherwise
     */
    @Suppress("ReturnCount")
    fun connect(
        deviceName: String,
        mode: String,
    ): Boolean {
        val requestedMode =
            when (mode.lowercase()) {
                "classic", "spp", "rfcomm" -> RNodeConnectionMode.CLASSIC
                "ble", "gatt" -> RNodeConnectionMode.BLE
                else -> {
                    Log.e(TAG, "Unknown connection mode: $mode. Use 'classic' or 'ble'")
                    return false
                }
            }

        if (isConnected.get()) {
            Log.w(TAG, "Already connected to $connectedDeviceName")
            if (connectedDeviceName == deviceName && connectionMode == requestedMode) {
                return true // Already connected to this device with same mode
            }
            disconnect() // Disconnect from current device first
        }

        val adapter =
            bluetoothAdapter ?: run {
                Log.e(TAG, "Bluetooth adapter not available")
                return false
            }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            return false
        }

        return when (requestedMode) {
            RNodeConnectionMode.CLASSIC -> connectClassic(deviceName, adapter)
            RNodeConnectionMode.BLE -> connectBle(deviceName, adapter)
        }
    }

    /**
     * Connect via Bluetooth Classic (SPP/RFCOMM).
     */
    private fun connectClassic(
        deviceName: String,
        adapter: BluetoothAdapter,
    ): Boolean {
        // Find the device by name in bonded devices
        val device: BluetoothDevice? =
            try {
                adapter.bondedDevices.find { it.name == deviceName }
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
                null
            }

        if (device == null) {
            Log.e(TAG, "Device not found: $deviceName. Make sure it's paired in system Bluetooth settings.")
            return false
        }

        return try {
            Log.i(TAG, "Connecting to $deviceName (${device.address}) via Bluetooth Classic...")

            // Create RFCOMM socket
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket = socket

            // Cancel discovery to speed up connection
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not cancel discovery", e)
            }

            // Connect (blocking call)
            socket.connect()

            // Setup streams
            inputStream = BufferedInputStream(socket.inputStream, STREAM_BUFFER_SIZE)
            outputStream = BufferedOutputStream(socket.outputStream, STREAM_BUFFER_SIZE)
            connectedDeviceName = deviceName
            connectionMode = RNodeConnectionMode.CLASSIC
            isConnected.set(true)

            Log.i(TAG, "Connected to $deviceName via Bluetooth Classic")

            // Start read thread
            startClassicReadThread()

            // Notify Python
            onConnectionStateChanged?.callAttr("__call__", true, deviceName)

            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect to $deviceName via Classic", e)
            cleanupClassic()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission", e)
            cleanupClassic()
            false
        }
    }

    /**
     * Connect via Bluetooth Low Energy (GATT).
     * Includes retry logic to handle transient BLE failures (especially GATT error 133).
     */
    @Suppress("ReturnCount")
    private fun connectBle(
        deviceName: String,
        adapter: BluetoothAdapter,
    ): Boolean {
        Log.d(TAG, "████ RNODE BLE CONNECT ████ deviceName=$deviceName")

        // First check bonded devices
        var device: BluetoothDevice? =
            try {
                adapter.bondedDevices.find { it.name == deviceName }
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
                null
            }

        // If not bonded, try to scan for the device
        if (device == null) {
            Log.d(TAG, "Device not bonded, scanning for BLE device: $deviceName")
            device = scanForBleDevice(deviceName)
        }

        if (device == null) {
            Log.e(TAG, "████ RNODE BLE FAILED ████ device not found: $deviceName")
            return false
        }

        // Retry loop for BLE connection (handles transient failures like GATT error 133)
        for (attempt in 1..BLE_CONNECT_MAX_RETRIES) {
            if (attempt > 1) {
                Log.i(TAG, "BLE connection attempt $attempt/$BLE_CONNECT_MAX_RETRIES...")
                Thread.sleep(BLE_RETRY_DELAY_MS)
            }

            val success = attemptBleConnection(device, deviceName)
            if (success) {
                return true
            }

            if (attempt < BLE_CONNECT_MAX_RETRIES) {
                Log.w(TAG, "BLE connection failed, will retry...")
            }
        }

        Log.e(TAG, "████ RNODE BLE FAILED ████ failed after $BLE_CONNECT_MAX_RETRIES attempts")
        return false
    }

    /**
     * Single attempt to connect via BLE GATT.
     */
    private fun attemptBleConnection(
        device: BluetoothDevice,
        deviceName: String,
    ): Boolean {
        return try {
            // Reset BLE state
            bleConnected = false
            bleServicesDiscovered = false
            bleMtuCallbackReceived = false
            bleRxCharacteristic = null
            bleTxCharacteristic = null

            // Connect GATT
            Log.d(TAG, "Connecting GATT to ${device.address}...")
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

            // Wait for connection and service discovery
            val startTime = System.currentTimeMillis()
            while (!bleServicesDiscovered && (System.currentTimeMillis() - startTime) < BLE_CONNECT_TIMEOUT_MS) {
                Thread.sleep(100)
            }

            if (!bleServicesDiscovered) {
                Log.e(TAG, "BLE connection timeout - services not discovered")
                cleanupBle()
                return false
            }

            if (bleRxCharacteristic == null || bleTxCharacteristic == null) {
                Log.e(TAG, "Nordic UART Service characteristics not found")
                cleanupBle()
                return false
            }

            connectedDeviceName = deviceName
            connectionMode = RNodeConnectionMode.BLE
            isConnected.set(true)

            Log.d(TAG, "████ RNODE BLE SUCCESS ████ deviceName=$deviceName MTU=$bleMtu")

            // Notify Python
            onConnectionStateChanged?.callAttr("__call__", true, deviceName)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $deviceName via BLE", e)
            cleanupBle()
            false
        }
    }

    /**
     * Scan for a BLE device by name.
     */
    private fun scanForBleDevice(deviceName: String): BluetoothDevice? {
        val scanner =
            bleScanner ?: run {
                Log.e(TAG, "BLE scanner not available")
                return null
            }

        bleScanResult = null

        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val name = result.device.name ?: return
                    if (name == deviceName) {
                        Log.d(TAG, "Found BLE device: $name (${result.device.address})")
                        bleScanResult = result.device
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed: $errorCode")
                }
            }

        // Start scan with filter for Nordic UART Service
        val filter =
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)

            // Wait for result
            val startTime = System.currentTimeMillis()
            while (bleScanResult == null && (System.currentTimeMillis() - startTime) < BLE_SCAN_TIMEOUT_MS) {
                Thread.sleep(100)
            }

            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission", e)
        }

        return bleScanResult
    }

    /**
     * BLE GATT callback for connection events.
     */
    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "BLE connected, requesting MTU...")
                        bleConnected = true
                        bleMtuCallbackReceived = false
                        // Request higher MTU for better throughput
                        gatt.requestMtu(512)
                        // Timeout: if onMtuChanged doesn't fire within 2 seconds,
                        // proceed with service discovery anyway to prevent hang
                        scope.launch {
                            delay(2000)
                            if (!bleMtuCallbackReceived && bleConnected) {
                                Log.w(TAG, "MTU callback timeout, proceeding with service discovery")
                                gatt.discoverServices()
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "BLE disconnected (status=${gattStatusToString(status)})")
                        bleConnected = false
                        if (isConnected.get() && connectionMode == RNodeConnectionMode.BLE) {
                            handleDisconnect()
                        }
                    }
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                bleMtuCallbackReceived = true
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    bleMtu = mtu
                    Log.d(TAG, "BLE MTU changed to $mtu")
                } else {
                    Log.w(TAG, "MTU change failed with status $status, using default MTU")
                }
                // Discover services after MTU negotiation
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    return
                }

                Log.d(TAG, "BLE services discovered")

                // Find Nordic UART Service
                val nusService = gatt.getService(NUS_SERVICE_UUID)
                if (nusService == null) {
                    Log.e(TAG, "Nordic UART Service not found")
                    return
                }

                // Get characteristics
                bleRxCharacteristic = nusService.getCharacteristic(NUS_RX_CHAR_UUID)
                bleTxCharacteristic = nusService.getCharacteristic(NUS_TX_CHAR_UUID)

                if (bleRxCharacteristic == null || bleTxCharacteristic == null) {
                    Log.e(TAG, "NUS characteristics not found")
                    return
                }

                // Enable notifications on TX characteristic (data from RNode)
                gatt.setCharacteristicNotification(bleTxCharacteristic, true)
                val descriptor = bleTxCharacteristic!!.getDescriptor(CCCD_UUID)
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }

                bleServicesDiscovered = true
                Log.i(TAG, "BLE NUS service ready")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == NUS_TX_CHAR_UUID) {
                    val data = characteristic.value
                    if (data != null && data.isNotEmpty()) {
                        Log.v(TAG, "BLE received ${data.size} bytes")

                        // Add to read buffer
                        for (byte in data) {
                            readBuffer.offer(byte)
                        }

                        // Notify Python callback
                        onDataReceived?.callAttr("__call__", data)
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                // Signal write completion to waiting thread
                // Use write ID to prevent race condition where delayed callback
                // corrupts status for a different write operation
                synchronized(bleWriteLock) {
                    val currentLatch = bleWriteLatch
                    if (currentLatch != null) {
                        bleWriteStatus.set(status)
                        currentLatch.countDown()
                    } else {
                        // Stale callback - latch was already cleared (write timed out or completed)
                        Log.w(TAG, "Ignoring stale BLE write callback (no latch)")
                    }
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "BLE write failed: $status")
                }
            }

            override fun onReadRemoteRssi(
                gatt: BluetoothGatt,
                rssi: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    bleRssi = rssi
                    Log.v(TAG, "BLE RSSI: $rssi dBm")
                } else {
                    Log.w(TAG, "Failed to read RSSI: status=$status")
                }
            }
        }

    /**
     * Clean up BLE resources.
     */
    private fun cleanupBle() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing BLE GATT", e)
        }
        bluetoothGatt = null
        bleRxCharacteristic = null
        bleTxCharacteristic = null
        bleConnected = false
        bleServicesDiscovered = false
        bleMtuCallbackReceived = false
    }

    /**
     * Disconnect from the current RNode device.
     */
    fun disconnect() {
        if (!isConnected.get()) {
            Log.d(TAG, "Not connected")
            return
        }

        val deviceName = connectedDeviceName
        val mode = connectionMode
        Log.i(TAG, "Disconnecting from $deviceName (mode=$mode)...")

        isConnected.set(false)

        when (mode) {
            RNodeConnectionMode.CLASSIC -> cleanupClassic()
            RNodeConnectionMode.BLE -> cleanupBle()
            null -> {
                cleanupClassic()
                cleanupBle()
            }
        }

        connectionMode = null
        connectedDeviceName = null
        readBuffer.clear()

        Log.i(TAG, "Disconnected from $deviceName")

        // Notify Python
        onConnectionStateChanged?.callAttr("__call__", false, deviceName ?: "")
    }

    /**
     * Check if currently connected to an RNode.
     *
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean {
        return when (connectionMode) {
            RNodeConnectionMode.CLASSIC -> isConnected.get() && bluetoothSocket?.isConnected == true
            RNodeConnectionMode.BLE -> isConnected.get() && bleConnected
            null -> false
        }
    }

    /**
     * Get the current connection mode.
     *
     * @return "classic", "ble", or null if not connected
     */
    fun getConnectionMode(): String? {
        return when (connectionMode) {
            RNodeConnectionMode.CLASSIC -> "classic"
            RNodeConnectionMode.BLE -> "ble"
            null -> null
        }
    }

    /**
     * Get the name of the currently connected device.
     *
     * @return Device name or null if not connected
     */
    fun getConnectedDeviceName(): String? {
        return if (isConnected.get()) connectedDeviceName else null
    }

    /**
     * Get the current RSSI (signal strength) of the BLE connection.
     *
     * @return RSSI in dBm, or -100 if not connected or not available
     */
    fun getRssi(): Int {
        return if (isConnected.get() && connectionMode == RNodeConnectionMode.BLE) {
            bleRssi
        } else {
            -100
        }
    }

    /**
     * Request an RSSI reading from the BLE connection.
     * The result will be available via getRssi() after the callback completes.
     *
     * @return true if the request was initiated, false if not connected via BLE
     */
    fun requestRssiUpdate(): Boolean {
        if (!isConnected.get() || connectionMode != RNodeConnectionMode.BLE) {
            return false
        }
        return try {
            bluetoothGatt?.readRemoteRssi() ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to read RSSI", e)
            false
        }
    }

    /**
     * Write data to the RNode.
     * Thread-safe - can be called from any thread.
     *
     * @param data Bytes to write (typically KISS-framed data)
     * @return Number of bytes written, or -1 on error
     */
    suspend fun write(data: ByteArray): Int {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot write - not connected")
            return -1
        }

        return writeMutex.withLock {
            when (connectionMode) {
                RNodeConnectionMode.CLASSIC -> writeClassic(data)
                RNodeConnectionMode.BLE -> writeBle(data)
                null -> {
                    Log.e(TAG, "No connection mode set")
                    -1
                }
            }
        }
    }

    /**
     * Write data via Bluetooth Classic.
     */
    private suspend fun writeClassic(data: ByteArray): Int {
        return try {
            withContext(Dispatchers.IO) {
                outputStream?.let { stream ->
                    stream.write(data)
                    stream.flush()
                    Log.v(TAG, "Wrote ${data.size} bytes (Classic)")
                    data.size
                } ?: run {
                    Log.e(TAG, "Output stream is null")
                    -1
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Classic write failed", e)
            handleDisconnect()
            -1
        }
    }

    /**
     * Write data via BLE.
     */
    private suspend fun writeBle(data: ByteArray): Int {
        val gatt =
            bluetoothGatt ?: run {
                Log.e(TAG, "GATT is null")
                return -1
            }

        val rxChar =
            bleRxCharacteristic ?: run {
                Log.e(TAG, "RX characteristic is null")
                return -1
            }

        return try {
            withContext(Dispatchers.IO) {
                // BLE has MTU limits - may need to chunk data
                val maxPayload = bleMtu - 3 // MTU minus ATT header
                var totalWritten = 0

                for (chunk in data.toList().chunked(maxPayload)) {
                    val chunkData = chunk.toByteArray()
                    rxChar.value = chunkData
                    if (!gatt.writeCharacteristic(rxChar)) {
                        Log.e(TAG, "BLE write failed")
                        return@withContext -1
                    }
                    totalWritten += chunkData.size
                    // Small delay between chunks for flow control
                    if (data.size > maxPayload) {
                        delay(10)
                    }
                }

                Log.v(TAG, "Wrote $totalWritten bytes (BLE)")
                totalWritten
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE write failed", e)
            -1
        }
    }

    /**
     * Synchronous write for simpler Python integration.
     * Blocks until write completes.
     *
     * @param data Bytes to write
     * @return Number of bytes written, or -1 on error
     */
    fun writeSync(data: ByteArray): Int {
        if (!isConnected.get()) {
            Log.w(
                TAG,
                "Cannot write - not connected (mode=$connectionMode, " +
                    "bleConnected=$bleConnected, bleServicesDiscovered=$bleServicesDiscovered)",
            )
            return -1
        }

        return when (connectionMode) {
            RNodeConnectionMode.CLASSIC -> writeSyncClassic(data)
            RNodeConnectionMode.BLE -> writeSyncBle(data)
            null -> {
                Log.e(TAG, "No connection mode set")
                -1
            }
        }
    }

    /**
     * Synchronous write via Bluetooth Classic.
     */
    private fun writeSyncClassic(data: ByteArray): Int {
        return synchronized(this) {
            try {
                outputStream?.let { stream ->
                    stream.write(data)
                    stream.flush()
                    Log.v(TAG, "Wrote ${data.size} bytes (Classic sync)")
                    data.size
                } ?: run {
                    Log.e(TAG, "Output stream is null")
                    -1
                }
            } catch (e: IOException) {
                Log.e(TAG, "Classic write failed", e)
                scope.launch { handleDisconnect() }
                -1
            }
        }
    }

    /**
     * Synchronous write via BLE.
     *
     * Android BLE is asynchronous - writeCharacteristic() only queues the write.
     * We must wait for onCharacteristicWrite() callback before sending the next write,
     * otherwise the Android BLE stack will silently drop operations.
     */
    private fun writeSyncBle(data: ByteArray): Int {
        val gatt =
            bluetoothGatt ?: run {
                Log.e(TAG, "GATT is null")
                return -1
            }

        val rxChar =
            bleRxCharacteristic ?: run {
                Log.e(TAG, "RX characteristic is null")
                return -1
            }

        return synchronized(this) {
            try {
                val maxPayload = bleMtu - 3
                var totalWritten = 0

                for (chunk in data.toList().chunked(maxPayload)) {
                    val chunkData = chunk.toByteArray()

                    // Create latch BEFORE starting write so callback can find it
                    // Latch null check in callback prevents stale callbacks from corrupting state
                    val latch = CountDownLatch(1)
                    synchronized(bleWriteLock) {
                        bleWriteLatch = latch
                        bleWriteStatus.set(BluetoothGatt.GATT_SUCCESS)
                    }

                    rxChar.value = chunkData
                    if (!gatt.writeCharacteristic(rxChar)) {
                        Log.e(TAG, "BLE write failed to queue")
                        synchronized(bleWriteLock) {
                            bleWriteLatch = null
                        }
                        return@synchronized -1
                    }

                    // Wait for onCharacteristicWrite callback (up to 5 seconds)
                    val completed = latch.await(5, TimeUnit.SECONDS)
                    synchronized(bleWriteLock) {
                        bleWriteLatch = null
                    }

                    if (!completed) {
                        Log.e(TAG, "BLE write timed out waiting for callback")
                        return@synchronized -1
                    }

                    if (bleWriteStatus.get() != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "BLE write callback reported failure: ${bleWriteStatus.get()}")
                        return@synchronized -1
                    }

                    totalWritten += chunkData.size
                }

                Log.v(TAG, "Wrote $totalWritten bytes (BLE sync)")
                totalWritten
            } catch (e: Exception) {
                Log.e(TAG, "BLE write failed", e)
                synchronized(bleWriteLock) {
                    bleWriteLatch = null
                }
                -1
            }
        }
    }

    /**
     * Read available data from the buffer (non-blocking).
     * Data is accumulated from the background read thread.
     *
     * @return Available bytes, or empty array if no data
     */
    fun read(): ByteArray {
        if (readBuffer.isEmpty()) {
            return ByteArray(0)
        }

        val data = mutableListOf<Byte>()
        while (true) {
            val byte = readBuffer.poll() ?: break
            data.add(byte)
        }

        if (data.isNotEmpty()) {
            Log.v(TAG, "Read ${data.size} bytes from buffer")
        }

        return data.toByteArray()
    }

    /**
     * Get number of bytes available in the read buffer.
     *
     * @return Number of buffered bytes
     */
    fun available(): Int {
        return readBuffer.size
    }

    /**
     * Blocking read with timeout.
     * Reads up to maxBytes or until timeout.
     *
     * @param maxBytes Maximum bytes to read
     * @param timeoutMs Timeout in milliseconds
     * @return Bytes read, or empty array on timeout/error
     */
    fun readBlocking(
        maxBytes: Int,
        timeoutMs: Long,
    ): ByteArray {
        if (!isConnected.get()) {
            return ByteArray(0)
        }

        val startTime = System.currentTimeMillis()
        val data = mutableListOf<Byte>()

        while (data.size < maxBytes && (System.currentTimeMillis() - startTime) < timeoutMs) {
            val byte = readBuffer.poll()
            if (byte != null) {
                data.add(byte)
            } else {
                // Brief sleep to avoid busy-waiting
                Thread.sleep(1)
            }
        }

        return data.toByteArray()
    }

    /**
     * Start the background read thread for Bluetooth Classic.
     */
    private fun startClassicReadThread() {
        if (isReading.getAndSet(true)) {
            return // Already reading
        }

        scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)

            Log.d(TAG, "Classic read thread started")

            try {
                while (isConnected.get() && connectionMode == RNodeConnectionMode.CLASSIC) {
                    val stream = inputStream ?: break

                    try {
                        // Check if data is available (non-blocking check)
                        val available = stream.available()
                        if (available > 0) {
                            val bytesRead = stream.read(buffer, 0, minOf(available, buffer.size))
                            if (bytesRead > 0) {
                                Log.v(TAG, "Classic received $bytesRead bytes")

                                // Add to buffer
                                for (i in 0 until bytesRead) {
                                    readBuffer.offer(buffer[i])
                                }

                                // Notify Python callback
                                onDataReceived?.let { callback ->
                                    val data = buffer.copyOf(bytesRead)
                                    callback.callAttr("__call__", data)
                                }
                            } else if (bytesRead == -1) {
                                // End of stream - connection closed
                                Log.i(TAG, "End of stream - connection closed by remote")
                                break
                            }
                        } else {
                            // No data available, brief sleep
                            delay(10)
                        }
                    } catch (e: IOException) {
                        if (isConnected.get()) {
                            Log.e(TAG, "Classic read error", e)
                        }
                        break
                    }
                }
            } finally {
                isReading.set(false)
                Log.d(TAG, "Classic read thread stopped")

                if (isConnected.get() && connectionMode == RNodeConnectionMode.CLASSIC) {
                    handleDisconnect()
                }
            }
        }
    }

    /**
     * Handle unexpected disconnection.
     */
    private fun handleDisconnect() {
        if (isConnected.getAndSet(false)) {
            val deviceName = connectedDeviceName
            val mode = connectionMode
            Log.w(TAG, "Connection lost to $deviceName (mode=$mode)")

            when (mode) {
                RNodeConnectionMode.CLASSIC -> cleanupClassic()
                RNodeConnectionMode.BLE -> cleanupBle()
                null -> {}
            }

            connectionMode = null
            connectedDeviceName = null
            readBuffer.clear()

            onConnectionStateChanged?.callAttr("__call__", false, deviceName ?: "")
        }
    }

    /**
     * Clean up Bluetooth Classic resources.
     */
    private fun cleanupClassic() {
        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing input stream", e)
        }

        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing output stream", e)
        }

        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket", e)
        }

        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }

    /**
     * Shutdown the bridge and release resources.
     */
    fun shutdown() {
        disconnect()
        scope.cancel()
    }
}
