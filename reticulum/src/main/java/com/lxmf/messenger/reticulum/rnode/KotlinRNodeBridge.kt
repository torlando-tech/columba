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
import java.util.concurrent.atomic.AtomicBoolean

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
class KotlinRNodeBridge(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Columba:RNodeBridge"

        // Standard SPP UUID for serial port profile (Bluetooth Classic)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Nordic UART Service UUIDs (BLE)
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")  // Write to RNode
        private val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")  // Read from RNode
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Buffer sizes
        private const val READ_BUFFER_SIZE = 4096
        private const val STREAM_BUFFER_SIZE = 2048

        // BLE timeouts
        private const val BLE_SCAN_TIMEOUT_MS = 10000L
        private const val BLE_CONNECT_TIMEOUT_MS = 15000L

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
    private var bleMtu: Int = 20  // Default BLE MTU (will be negotiated higher)
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }
    @Volatile
    private var bleScanResult: BluetoothDevice? = null
    @Volatile
    private var bleConnected = false
    @Volatile
    private var bleServicesDiscovered = false

    // Common state
    private var connectedDeviceName: String? = null

    // Thread-safe state flags
    private val isConnected = AtomicBoolean(false)
    private val isReading = AtomicBoolean(false)

    // Read buffer for non-blocking reads
    private val readBuffer = ConcurrentLinkedQueue<Byte>()
    private val writeMutex = Mutex()

    // Python callbacks
    @Volatile
    private var onDataReceived: PyObject? = null

    @Volatile
    private var onConnectionStateChanged: PyObject? = null

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
     * Get list of paired RNode devices.
     * Filters bonded devices for names starting with "RNode ".
     *
     * @return List of device names (e.g., ["RNode 5A3F", "RNode B2C1"])
     */
    fun getPairedRNodes(): List<String> {
        val adapter = bluetoothAdapter ?: run {
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
    fun connect(deviceName: String, mode: String): Boolean {
        val requestedMode = when (mode.lowercase()) {
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

        val adapter = bluetoothAdapter ?: run {
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
    private fun connectClassic(deviceName: String, adapter: BluetoothAdapter): Boolean {
        // Find the device by name in bonded devices
        val device: BluetoothDevice? = try {
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
     */
    private fun connectBle(deviceName: String, adapter: BluetoothAdapter): Boolean {
        Log.i(TAG, "Connecting to $deviceName via BLE...")

        // First check bonded devices
        var device: BluetoothDevice? = try {
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
            Log.e(TAG, "BLE device not found: $deviceName")
            return false
        }

        return try {
            // Reset BLE state
            bleConnected = false
            bleServicesDiscovered = false
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

            Log.i(TAG, "Connected to $deviceName via BLE (MTU=$bleMtu)")

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
        val scanner = bleScanner ?: run {
            Log.e(TAG, "BLE scanner not available")
            return null
        }

        bleScanResult = null

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
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
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
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
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE connected, discovering services...")
                    bleConnected = true
                    // Request higher MTU for better throughput
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "BLE disconnected")
                    bleConnected = false
                    if (isConnected.get() && connectionMode == RNodeConnectionMode.BLE) {
                        handleDisconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleMtu = mtu
                Log.d(TAG, "BLE MTU changed to $mtu")
            }
            // Discover services after MTU negotiation
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "BLE write failed: $status")
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
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "GATT is null")
            return -1
        }

        val rxChar = bleRxCharacteristic ?: run {
            Log.e(TAG, "RX characteristic is null")
            return -1
        }

        return try {
            withContext(Dispatchers.IO) {
                // BLE has MTU limits - may need to chunk data
                val maxPayload = bleMtu - 3  // MTU minus ATT header
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

                Log.v(TAG, "Wrote ${totalWritten} bytes (BLE)")
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
            Log.w(TAG, "Cannot write - not connected")
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
     */
    private fun writeSyncBle(data: ByteArray): Int {
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "GATT is null")
            return -1
        }

        val rxChar = bleRxCharacteristic ?: run {
            Log.e(TAG, "RX characteristic is null")
            return -1
        }

        return synchronized(this) {
            try {
                val maxPayload = bleMtu - 3
                var totalWritten = 0

                for (chunk in data.toList().chunked(maxPayload)) {
                    val chunkData = chunk.toByteArray()
                    rxChar.value = chunkData
                    if (!gatt.writeCharacteristic(rxChar)) {
                        Log.e(TAG, "BLE write failed")
                        return@synchronized -1
                    }
                    totalWritten += chunkData.size
                    if (data.size > maxPayload) {
                        Thread.sleep(10)
                    }
                }

                Log.v(TAG, "Wrote ${totalWritten} bytes (BLE sync)")
                totalWritten
            } catch (e: Exception) {
                Log.e(TAG, "BLE write failed", e)
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
    fun readBlocking(maxBytes: Int, timeoutMs: Long): ByteArray {
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
        scope.launch {
            // Give time for cleanup
            delay(100)
        }
    }
}
