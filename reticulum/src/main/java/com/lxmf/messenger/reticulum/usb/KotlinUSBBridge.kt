package com.lxmf.messenger.reticulum.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.chaquo.python.PyObject
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class representing a USB device for Python interop.
 */
data class UsbDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val driverType: String,
)

/**
 * Listener interface for USB connection state changes.
 */
interface UsbConnectionListener {
    fun onUsbConnected(deviceId: Int)

    fun onUsbDisconnected(deviceId: Int)

    fun onUsbPermissionGranted(deviceId: Int)

    fun onUsbPermissionDenied(deviceId: Int)
}

/**
 * Kotlin USB Bridge for serial communication with RNode devices.
 *
 * This bridge provides USB serial communication with RNode hardware using the
 * usb-serial-for-android library. It supports various USB-to-serial chipsets:
 * - FTDI (FT232R, FT232H, etc.)
 * - Silicon Labs CP210x
 * - Prolific PL2303
 * - CH340/CH341
 * - CDC-ACM (native USB serial on ESP32-S3, NRF52840)
 *
 * Usage from Python:
 *   bridge = get_kotlin_usb_bridge()
 *   devices = bridge.getConnectedUsbDevices()
 *   bridge.connect(device_id)
 *   bridge.write(bytes([0xC0, 0x00, ...]))  # KISS frame
 *   data = bridge.read()  # Non-blocking
 *   bridge.disconnect()
 *
 * @property context Application context for USB access
 */
@Suppress("TooManyFunctions", "LargeClass")
class KotlinUSBBridge(
    private val context: Context,
) : SerialInputOutputManager.Listener {
    companion object {
        private const val TAG = "Columba:USBBridge"
        private const val ACTION_USB_PERMISSION = "com.lxmf.messenger.USB_PERMISSION"

        // Buffer sizes
        private const val WRITE_TIMEOUT_MS = 1000

        // KISS protocol constants
        private const val KISS_FEND: Byte = 0xC0.toByte()
        private const val KISS_FESC: Byte = 0xDB.toByte()
        private const val KISS_TFEND: Byte = 0xDC.toByte()
        private const val KISS_TFESC: Byte = 0xDD.toByte()
        private const val KISS_CMD_BT_PIN: Byte = 0x62.toByte()

        // Default serial parameters for RNode
        private const val DEFAULT_BAUD_RATE = 115200
        private const val DEFAULT_DATA_BITS = UsbSerialPort.DATABITS_8
        private const val DEFAULT_STOP_BITS = UsbSerialPort.STOPBITS_1
        private const val DEFAULT_PARITY = UsbSerialPort.PARITY_NONE

        /**
         * Supported USB VIDs (same as Sideband):
         * - 0x0403: FTDI
         * - 0x10C4: SiLabs CP210x
         * - 0x067B: Prolific PL2303
         * - 0x1A86: CH340/CH341
         * - 0x0525: ESP32/NRF52 CDC (Linux Foundation)
         * - 0x2E8A: Raspberry Pi Pico
         * - 0x303A: Espressif
         */
        private val SUPPORTED_VIDS =
            setOf(
                0x0403,
                0x10C4,
                0x067B,
                0x1A86,
                0x0525,
                0x2E8A,
                0x303A,
                0x239A, // Adafruit/Heltec
            )

        // Custom prober to detect more devices
        private val customProber: UsbSerialProber by lazy {
            val customTable =
                com.hoho.android.usbserial.driver.ProbeTable().apply {
                    // Add ESP32-S3 and NRF52840 CDC-ACM devices
                    addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java) // ESP32-S3 CDC
                    addProduct(0x239A, 0x8029, CdcAcmSerialDriver::class.java) // Adafruit NRF52840
                    addProduct(0x239A, 0x8071, CdcAcmSerialDriver::class.java) // Heltec HT-n5262
                    addProduct(0x239A, 0x80BA, CdcAcmSerialDriver::class.java) // LilyGO T-Echo
                    addProduct(0x1915, 0x520F, CdcAcmSerialDriver::class.java) // Nordic NRF52840
                }
            UsbSerialProber(customTable)
        }

        @Volatile
        private var instance: KotlinUSBBridge? = null

        /**
         * Get or create singleton instance.
         */
        fun getInstance(context: Context): KotlinUSBBridge =
            instance ?: synchronized(this) {
                instance ?: KotlinUSBBridge(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Connection state
    private var currentDriver: UsbSerialDriver? = null
    private var currentPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var ioManagerFuture: java.util.concurrent.Future<*>? = null
    private var connectedDeviceId: Int? = null

    // Direct USB access for testConnection-free reads/writes during DFU.
    // Both port.read() and port.write() call testConnection() (USB GET_STATUS)
    // on zero-byte bulk transfer returns. The nRF52840 DFU bootloader's minimal
    // TinyUSB stack cannot handle GET_STATUS, crashing the USB controller.
    // These fields allow readBlockingDirect()/writeBlockingDirect() to call
    // bulkTransfer() directly, bypassing testConnection entirely.
    private var usbConnection: UsbDeviceConnection? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null

    // Thread-safe state flags
    private val isConnected = AtomicBoolean(false)
    private val rawModeEnabled = AtomicBoolean(false)

    // Read buffer for non-blocking reads
    private val readBuffer = ConcurrentLinkedQueue<Byte>()

    // KISS frame parsing state (for detecting CMD_BT_PIN responses)
    private var kissInFrame = false
    private var kissEscape = false
    private var kissHasCommand = false
    private var kissCommand: Byte = 0
    private val kissDataBuffer = mutableListOf<Byte>()

    // Executor for serial I/O
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // Python callbacks
    @Volatile
    private var onDataReceived: PyObject? = null

    @Volatile
    private var onConnectionStateChanged: PyObject? = null

    @Volatile
    private var onBluetoothPinReceived: PyObject? = null

    // Kotlin listeners
    private val connectionListeners = mutableListOf<UsbConnectionListener>()

    // Permission request callbacks
    private val pendingPermissionCallbacks = mutableMapOf<Int, (Boolean) -> Unit>()

    // USB permission broadcast receiver
    private val usbPermissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let { dev ->
                        Log.d(TAG, "USB permission ${if (granted) "granted" else "denied"} for device ${dev.deviceId}")
                        pendingPermissionCallbacks.remove(dev.deviceId)?.invoke(granted)

                        if (granted) {
                            notifyListeners { it.onUsbPermissionGranted(dev.deviceId) }
                        } else {
                            notifyListeners { it.onUsbPermissionDenied(dev.deviceId) }
                        }
                    }
                }
            }
        }

    // USB attach/detach broadcast receiver
    private val usbEventReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                        device?.let { dev ->
                            Log.d(TAG, "USB device attached: ${dev.deviceName}")
                            // Only notify if it's a supported device
                            if (SUPPORTED_VIDS.contains(dev.vendorId)) {
                                notifyListeners { it.onUsbConnected(dev.deviceId) }
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                        device?.let { dev ->
                            // Capture deviceId early - the UsbDevice parcelable from the intent
                            // contains a snapshot, so this should be stable even if the physical
                            // device is already removed from the system
                            val detachedDeviceId = dev.deviceId
                            Log.d(TAG, "USB device detached: ${dev.deviceName} (deviceId=$detachedDeviceId, connectedDeviceId=$connectedDeviceId)")
                            // If this was our connected device, handle disconnect
                            if (detachedDeviceId == connectedDeviceId) {
                                Log.d(TAG, "Device ID matches - calling handleDisconnect()")
                                handleDisconnect()
                            } else {
                                Log.d(TAG, "Device ID mismatch - NOT calling handleDisconnect()")
                            }
                            notifyListeners { it.onUsbDisconnected(detachedDeviceId) }
                        }
                    }
                }
            }
        }

    init {
        // Register broadcast receivers
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        val eventFilter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(usbEventReceiver, eventFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, permissionFilter)
            context.registerReceiver(usbEventReceiver, eventFilter)
        }
        Log.d(TAG, "KotlinUSBBridge initialized")
    }

    /**
     * Set callback for received data.
     * Called on background thread when data arrives from USB device.
     *
     * @param callback Python callable: callback(data: bytes)
     */
    fun setOnDataReceived(callback: PyObject) {
        onDataReceived = callback
    }

    /**
     * Set callback for connection state changes.
     *
     * @param callback Python callable: callback(connected: bool, device_id: int)
     */
    fun setOnConnectionStateChanged(callback: PyObject) {
        onConnectionStateChanged = callback
    }

    /**
     * Set callback for Bluetooth PIN received during pairing mode.
     * RNode sends the PIN via CMD_BT_PIN (0x62) when entering pairing mode.
     *
     * @param callback Python callable: callback(pin: str)
     */
    fun setOnBluetoothPinReceived(callback: PyObject) {
        onBluetoothPinReceived = callback
    }

    // Kotlin callback for Bluetooth PIN (for use from Android/Kotlin code)
    @Volatile
    private var onBluetoothPinReceivedKotlin: ((String) -> Unit)? = null

    /**
     * Set Kotlin callback for Bluetooth PIN received during pairing mode.
     * This is the Kotlin-friendly version for use from Android code.
     *
     * @param callback Kotlin lambda: (pin: String) -> Unit
     */
    fun setOnBluetoothPinReceivedKotlin(callback: (String) -> Unit) {
        onBluetoothPinReceivedKotlin = callback
    }

    /**
     * Notify Bluetooth PIN callback.
     * Called from Python when RNode sends a PIN during BT pairing mode.
     *
     * @param pin The 6-digit PIN for Bluetooth pairing
     */
    fun notifyBluetoothPin(pin: String) {
        Log.d(TAG, "Bluetooth PIN received: $pin")
        // Notify Python callback
        onBluetoothPinReceived?.callAttr("__call__", pin)
        // Notify Kotlin callback
        onBluetoothPinReceivedKotlin?.invoke(pin)
    }

    /**
     * Add a connection listener.
     */
    fun addConnectionListener(listener: UsbConnectionListener) {
        synchronized(connectionListeners) {
            if (!connectionListeners.contains(listener)) {
                connectionListeners.add(listener)
            }
        }
    }

    /**
     * Remove a connection listener.
     */
    fun removeConnectionListener(listener: UsbConnectionListener) {
        synchronized(connectionListeners) {
            connectionListeners.remove(listener)
        }
    }

    private fun notifyListeners(action: (UsbConnectionListener) -> Unit) {
        synchronized(connectionListeners) {
            connectionListeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Connection listener threw exception", e)
                }
            }
        }
    }

    /**
     * Get list of connected USB serial devices.
     * Returns devices with supported VIDs and detected serial drivers.
     *
     * @return List of UsbDeviceInfo for discovered devices
     */
    fun getConnectedUsbDevices(): List<UsbDeviceInfo> {
        val devices = mutableListOf<UsbDeviceInfo>()

        // Get all USB devices
        val usbDevices = usbManager.deviceList

        usbDevices
            .filter { (_, device) -> SUPPORTED_VIDS.contains(device.vendorId) }
            .forEach { (_, device) ->
                // Try to find a driver for this device
                val driver =
                    UsbSerialProber.getDefaultProber().probeDevice(device)
                        ?: customProber.probeDevice(device)

                if (driver != null) {
                    val driverType = getDriverTypeName(driver)

                    // Check if we have permission before accessing protected attributes
                    val hasPermission = usbManager.hasPermission(device)

                    // These attributes require permission to access
                    val manufacturerName =
                        if (hasPermission) {
                            try {
                                device.manufacturerName
                            } catch (e: SecurityException) {
                                Log.v(TAG, "Cannot read manufacturerName: ${e.message}")
                                null
                            }
                        } else {
                            null
                        }

                    val productName =
                        if (hasPermission) {
                            try {
                                device.productName
                            } catch (e: SecurityException) {
                                Log.v(TAG, "Cannot read productName: ${e.message}")
                                null
                            }
                        } else {
                            null
                        }

                    val serialNumber =
                        if (hasPermission) {
                            try {
                                device.serialNumber
                            } catch (e: SecurityException) {
                                Log.v(TAG, "Cannot read serialNumber: ${e.message}")
                                null
                            }
                        } else {
                            null
                        }

                    devices.add(
                        UsbDeviceInfo(
                            deviceId = device.deviceId,
                            vendorId = device.vendorId,
                            productId = device.productId,
                            deviceName = device.deviceName,
                            manufacturerName = manufacturerName,
                            productName = productName,
                            serialNumber = serialNumber,
                            driverType = driverType,
                        ),
                    )

                    Log.d(
                        TAG,
                        "Found USB device: ${productName ?: device.deviceName} " +
                            "(VID=${device.vendorId.toHexString()}, PID=${device.productId.toHexString()}, " +
                            "driver=$driverType, hasPermission=$hasPermission)",
                    )
                }
            }

        return devices
    }

    private fun getDriverTypeName(driver: UsbSerialDriver): String =
        when (driver) {
            is FtdiSerialDriver -> "FTDI"
            is Cp21xxSerialDriver -> "CP210x"
            is ProlificSerialDriver -> "PL2303"
            is Ch34xSerialDriver -> "CH340"
            is CdcAcmSerialDriver -> "CDC-ACM"
            else -> "Unknown"
        }

    private fun Int.toHexString(): String = "0x${this.toString(16).uppercase()}"

    /**
     * Check if we have permission to access a USB device.
     *
     * @param deviceId The device ID to check
     * @return true if permission is granted, false otherwise
     */
    fun hasPermission(deviceId: Int): Boolean {
        val device = usbManager.deviceList.values.find { it.deviceId == deviceId } ?: return false
        return usbManager.hasPermission(device)
    }

    /**
     * Find a USB device by Vendor ID and Product ID.
     * Returns the current device ID, or -1 if not found.
     *
     * This is useful because device IDs can change between plug/unplug cycles,
     * but VID/PID are stable hardware identifiers.
     *
     * @param vendorId The USB Vendor ID
     * @param productId The USB Product ID
     * @return The current device ID if found, -1 otherwise
     */
    fun findDeviceByVidPid(
        vendorId: Int,
        productId: Int,
    ): Int {
        val device =
            usbManager.deviceList.values.find {
                it.vendorId == vendorId && it.productId == productId
            }
        if (device != null) {
            Log.d(TAG, "Found device by VID/PID: VID=${vendorId.toHexString()}, PID=${productId.toHexString()} -> deviceId=${device.deviceId}")
            return device.deviceId
        }
        Log.d(TAG, "Device not found by VID/PID: VID=${vendorId.toHexString()}, PID=${productId.toHexString()}")
        return -1
    }

    /**
     * Request permission to access a USB device.
     *
     * @param deviceId The device ID to request permission for
     * @param callback Callback invoked with permission result (true = granted)
     */
    fun requestPermission(
        deviceId: Int,
        callback: PyObject,
    ) {
        requestPermission(deviceId) { granted ->
            callback.callAttr("__call__", granted)
        }
    }

    /**
     * Request permission to access a USB device (Kotlin callback version).
     */
    fun requestPermission(
        deviceId: Int,
        callback: (Boolean) -> Unit,
    ) {
        val device =
            usbManager.deviceList.values.find { it.deviceId == deviceId }
                ?: run {
                    Log.e(TAG, "Device not found: $deviceId")
                    callback(false)
                    return
                }

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already have permission for device $deviceId")
            callback(true)
            return
        }

        // Store callback and request permission
        pendingPermissionCallbacks[deviceId] = callback

        // Create an explicit Intent to avoid Android 14+ restrictions
        val intent =
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }

        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val permissionIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                flags,
            )

        Log.d(TAG, "Requesting USB permission for device $deviceId")
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Connect to a USB device by device ID.
     *
     * @param deviceId The device ID to connect to
     * @param baudRate Baud rate (default: 115200)
     * @param startIoManager If true (default), start the SerialInputOutputManager for
     *   async reads. Set to false for DFU bootloader connections where the ioManager's
     *   port.read() → testConnection() → USB GET_STATUS kills the nRF52840's USB
     *   controller. When false, use readBlockingDirect()/writeBlockingDirect() which
     *   call bulkTransfer() directly without testConnection().
     * @return true if connection successful, false otherwise
     */
    @Suppress("ReturnCount")
    fun connect(
        deviceId: Int,
        baudRate: Int = DEFAULT_BAUD_RATE,
        startIoManager: Boolean = true,
    ): Boolean {
        if (isConnected.get()) {
            if (connectedDeviceId == deviceId) {
                Log.d(TAG, "Already connected to device $deviceId")
                return true
            }
            disconnect()
        }

        val device =
            usbManager.deviceList.values.find { it.deviceId == deviceId }
                ?: run {
                    Log.e(TAG, "Device not found: $deviceId")
                    return false
                }

        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "No permission for device $deviceId")
            return false
        }

        // Find driver
        val driver =
            UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: customProber.probeDevice(device)
                ?: run {
                    Log.e(TAG, "No driver found for device $deviceId")
                    return false
                }

        if (driver.ports.isEmpty()) {
            Log.e(TAG, "No ports available for device $deviceId")
            return false
        }

        val port = driver.ports[0]
        val connection =
            usbManager.openDevice(device)
                ?: run {
                    Log.e(TAG, "Failed to open device $deviceId")
                    return false
                }

        return try {
            port.open(connection)
            port.setParameters(baudRate, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY)

            // Set flow control if supported
            try {
                port.dtr = true
                port.rts = true
            } catch (e: UnsupportedOperationException) {
                Log.d(TAG, "Flow control not supported by this driver: ${e.message}")
            }

            currentDriver = driver
            currentPort = port
            connectedDeviceId = deviceId
            usbConnection = connection
            readEndpoint = findBulkEndpoint(device, UsbConstants.USB_DIR_IN)
            writeEndpoint = findBulkEndpoint(device, UsbConstants.USB_DIR_OUT)
            isConnected.set(true)

            if (startIoManager) {
                // Start I/O manager for async reads
                val manager = SerialInputOutputManager(port, this)
                manager.readTimeout = 100 // Small timeout to reduce CPU usage
                ioManager = manager
                ioManagerFuture = ioExecutor.submit(manager)
            }

            Log.i(TAG, "Connected to USB device $deviceId (${getDriverTypeName(driver)}) at $baudRate baud (ioManager=$startIoManager)")

            // Notify Python
            onConnectionStateChanged?.callAttr("__call__", true, deviceId)

            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect to device $deviceId", e)
            try {
                port.close()
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * Disconnect from the current USB device.
     */
    fun disconnect() {
        if (!isConnected.getAndSet(false)) {
            return
        }

        val deviceId = connectedDeviceId
        Log.i(TAG, "Disconnecting from USB device $deviceId")

        // Stop I/O manager
        ioManager?.stop()
        ioManager = null
        ioManagerFuture?.cancel(true)
        ioManagerFuture = null

        // Close port
        try {
            currentPort?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing port", e)
        }

        currentPort = null
        currentDriver = null
        connectedDeviceId = null
        usbConnection = null
        readEndpoint = null
        writeEndpoint = null
        readBuffer.clear()

        // Notify Python
        onConnectionStateChanged?.callAttr("__call__", false, deviceId ?: -1)

        Log.i(TAG, "Disconnected from USB device $deviceId")
    }

    /**
     * Handle unexpected disconnect.
     */
    private fun handleDisconnect() {
        val wasConnected = isConnected.getAndSet(false)
        Log.d(TAG, "handleDisconnect() called - wasConnected=$wasConnected, hasCallback=${onConnectionStateChanged != null}")
        if (wasConnected) {
            val deviceId = connectedDeviceId
            Log.w(TAG, "USB device disconnected unexpectedly: $deviceId")

            // Stop I/O manager
            ioManager?.stop()
            ioManager = null

            // Close port
            try {
                currentPort?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing port", e)
            }

            currentPort = null
            currentDriver = null
            connectedDeviceId = null
            usbConnection = null
            readEndpoint = null
            readBuffer.clear()

            // Notify Python
            Log.d(TAG, "Calling onConnectionStateChanged callback with connected=false, deviceId=$deviceId")
            onConnectionStateChanged?.callAttr("__call__", false, deviceId ?: -1)
            Log.d(TAG, "onConnectionStateChanged callback completed")
        } else {
            Log.d(TAG, "handleDisconnect() skipped - was not connected")
        }
    }

    /**
     * Check if currently connected to a USB device.
     *
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean = isConnected.get() && currentPort != null

    /**
     * Get the currently connected device ID.
     *
     * @return Device ID or null if not connected
     */
    fun getConnectedDeviceId(): Int? = if (isConnected.get()) connectedDeviceId else null

    /**
     * Change the baud rate of the current connection.
     * Used for DFU mode entry (1200 baud touch) and flashing protocols.
     *
     * @param baudRate New baud rate to set
     * @return true if successful, false otherwise
     */
    fun setBaudRate(baudRate: Int): Boolean {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot set baud rate - not connected")
            return false
        }

        val port =
            currentPort ?: run {
                Log.e(TAG, "Port is null")
                return false
            }

        return synchronized(this) {
            try {
                port.setParameters(baudRate, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY)
                Log.d(TAG, "Baud rate changed to $baudRate")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Failed to change baud rate to $baudRate", e)
                false
            }
        }
    }

    /**
     * Set DTR (Data Terminal Ready) line state.
     * Used by ESP32 bootloader entry sequence.
     *
     * @param state true to assert DTR, false to deassert
     * @return true if successful, false otherwise
     */
    fun setDtr(state: Boolean): Boolean {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot set DTR - not connected")
            return false
        }

        val port = currentPort ?: return false

        return try {
            port.dtr = state
            Log.v(TAG, "DTR set to $state")
            true
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "DTR not supported: ${e.message}")
            false
        } catch (e: IOException) {
            Log.e(TAG, "Failed to set DTR", e)
            false
        }
    }

    /**
     * Set RTS (Request To Send) line state.
     * Used by ESP32 bootloader entry sequence.
     *
     * @param state true to assert RTS, false to deassert
     * @return true if successful, false otherwise
     */
    fun setRts(state: Boolean): Boolean {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot set RTS - not connected")
            return false
        }

        val port = currentPort ?: return false

        return try {
            port.rts = state
            Log.v(TAG, "RTS set to $state")
            true
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "RTS not supported: ${e.message}")
            false
        } catch (e: IOException) {
            Log.e(TAG, "Failed to set RTS", e)
            false
        }
    }

    /**
     * Set both DTR and RTS simultaneously.
     * Used for ESP32 bootloader entry timing.
     *
     * @param dtrState DTR line state
     * @param rtsState RTS line state
     */
    fun setDtrRts(
        dtrState: Boolean,
        rtsState: Boolean,
    ) {
        setDtr(dtrState)
        setRts(rtsState)
    }

    /**
     * Perform a blocking read with timeout.
     * Used by flashing protocols that need synchronous communication.
     *
     * @param buffer Buffer to read into
     * @param timeoutMs Read timeout in milliseconds
     * @return Number of bytes read, or -1 on error
     */
    fun readBlocking(
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot read - not connected")
            return -1
        }

        val port =
            currentPort ?: run {
                Log.w(TAG, "readBlocking: port is null")
                return -1
            }

        return try {
            val bytesRead = port.read(buffer, timeoutMs)
            if (bytesRead > 0) {
                val hex =
                    buffer.take(minOf(bytesRead, 32)).joinToString(" ") {
                        String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
                    }
                Log.d(TAG, "readBlocking: got $bytesRead bytes: $hex")
            }
            bytesRead
        } catch (e: IOException) {
            Log.e(TAG, "Blocking read failed", e)
            -1
        }
    }

    /**
     * Find a bulk endpoint on a USB device by direction.
     * CDC-ACM devices have a data interface with bulk IN and OUT endpoints.
     *
     * @param device USB device to search
     * @param direction [UsbConstants.USB_DIR_IN] or [UsbConstants.USB_DIR_OUT]
     */
    @Suppress("NestedBlockDepth")
    private fun findBulkEndpoint(
        device: UsbDevice,
        direction: Int,
    ): UsbEndpoint? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == direction
                ) {
                    return ep
                }
            }
        }
        return null
    }

    /**
     * Perform a blocking read bypassing the serial library's port.read().
     *
     * Unlike [readBlocking], this calls [UsbDeviceConnection.bulkTransfer]
     * directly — no testConnection() and no USB GET_STATUS control transfers.
     * This is critical during nRF52 DFU: the bootloader's CPU is blocked by
     * NVMC flash erase and cannot service control transfers, so GET_STATUS
     * causes a USB bus reset that kills the connection.
     *
     * Only useful in raw mode (ioManager stopped). Falls back to
     * [readBlocking] if the direct USB connection is not available.
     *
     * @param buffer Buffer to read into
     * @param timeoutMs Read timeout in milliseconds
     * @return Number of bytes read, 0 on timeout, or -1 on error
     */
    fun readBlockingDirect(
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int {
        val conn = usbConnection
        val ep = readEndpoint
        if (conn == null || ep == null) {
            // Don't fall back to readBlocking() — it returns -1 instantly when
            // not connected, which causes tight spin loops in polling callers.
            Log.w(TAG, "readBlockingDirect: no USB connection")
            return -1
        }

        return try {
            val bytesRead = conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
            if (bytesRead > 0) {
                val hex =
                    buffer.take(minOf(bytesRead, 32)).joinToString(" ") {
                        String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
                    }
                Log.d(TAG, "readBlockingDirect: got $bytesRead bytes: $hex")
            }
            // bulkTransfer returns -1 on timeout/error; normalize to 0 for callers
            maxOf(0, bytesRead)
        } catch (e: Exception) {
            Log.e(TAG, "Direct bulk read failed", e)
            -1
        }
    }

    /**
     * Write data bypassing the serial library's port.write().
     *
     * Like [readBlockingDirect], this calls [UsbDeviceConnection.bulkTransfer]
     * directly — no testConnection() and no USB GET_STATUS control transfers.
     * The library's port.write() calls testConnection() when bulkTransfer
     * returns 0 (e.g. device buffer full), which crashes the nRF52840
     * bootloader's USB controller.
     *
     * Falls back to [write] if the direct USB connection is not available.
     *
     * @param data Bytes to write
     * @return Number of bytes written, or -1 on error
     */
    fun writeBlockingDirect(data: ByteArray): Int {
        val conn = usbConnection
        val ep = writeEndpoint
        if (conn == null || ep == null) {
            return write(data)
        }

        return try {
            val bytesWritten = conn.bulkTransfer(ep, data, data.size, WRITE_TIMEOUT_MS)
            if (bytesWritten > 0) {
                Log.v(TAG, "writeBlockingDirect: wrote $bytesWritten bytes")
            } else {
                Log.w(TAG, "writeBlockingDirect: bulkTransfer returned $bytesWritten")
            }
            if (bytesWritten < 0) -1 else bytesWritten
        } catch (e: Exception) {
            Log.e(TAG, "Direct bulk write failed", e)
            -1
        }
    }

    /**
     * Enable raw/blocking mode for ESPTool flashing.
     * This stops the SerialInputOutputManager so that readBlocking() can receive data.
     * Call disableRawMode() when done to restart async reads.
     *
     * @param drainPort If true (default), drain any buffered data from the serial
     *   port using port.read(). Set to false for devices whose USB stack cannot
     *   handle the GET_STATUS control transfer that port.read() triggers (e.g.
     *   nRF52840 DFU bootloader) — the drain would crash their USB controller.
     */
    @Suppress("NestedBlockDepth")
    fun enableRawMode(drainPort: Boolean = true) {
        Log.d(TAG, "Enabling raw mode (stopping SerialInputOutputManager, drain=$drainPort)")

        // Set flag FIRST so onNewData ignores any data that arrives
        rawModeEnabled.set(true)

        val manager = ioManager
        val future = ioManagerFuture
        if (manager != null) {
            manager.stop()
            ioManager = null
            ioManagerFuture = null

            // Cancel the future to interrupt the thread if it's blocked
            future?.cancel(true)

            // Wait for the manager thread to actually stop
            waitForManagerStop(future)
            Log.d(TAG, "SerialInputOutputManager stopped")

            // Drain any data that was buffered by the serial port during async
            // operation. Skipped for devices that can't handle testConnection().
            if (drainPort) {
                drainPortAfterManagerStop()
            }
        }
        // Clear any buffered data in our queue
        readBuffer.clear()
    }

    /**
     * Wait for the SerialInputOutputManager to stop, handling expected exceptions.
     */
    @Suppress("SwallowedException")
    private fun waitForManagerStop(future: java.util.concurrent.Future<*>?) {
        try {
            future?.get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.CancellationException) {
            // Expected - we cancelled it, no action needed
            Log.v(TAG, "Manager future cancelled as expected")
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Timeout waiting for SerialInputOutputManager to stop")
        } catch (e: Exception) {
            Log.w(TAG, "Error waiting for SerialInputOutputManager: ${e.message}")
        }
    }

    /**
     * Drain any data buffered by the serial port after stopping the manager.
     */
    private fun drainPortAfterManagerStop() {
        val port = currentPort ?: return
        try {
            val drainBuf = ByteArray(1024)
            var totalDrained = 0
            while (true) {
                val drained = port.read(drainBuf, 50)
                if (drained <= 0) break
                totalDrained += drained
            }
            if (totalDrained > 0) {
                Log.d(TAG, "Drained $totalDrained bytes from port after stopping manager")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error draining port: ${e.message}")
        }
    }

    /**
     * Disable raw mode and restart async reads.
     * Call this after flashing is complete to restore normal operation.
     */
    fun disableRawMode() {
        Log.d(TAG, "Disabling raw mode")
        rawModeEnabled.set(false)

        val port = currentPort ?: return
        if (ioManager != null) {
            Log.d(TAG, "ioManager already exists, not creating new one")
            return
        }
        Log.d(TAG, "Restarting SerialInputOutputManager")
        val manager = SerialInputOutputManager(port, this)
        manager.readTimeout = 100
        ioManager = manager
        ioManagerFuture = ioExecutor.submit(manager)
    }

    /**
     * Enable DFU mode: suppresses KISS parsing and Python callbacks for
     * incoming data, but keeps the ioManager running for reliable reads.
     * Data is buffered in readBuffer and can be polled via read()/available().
     *
     * Unlike enableRawMode(), this does NOT stop the SerialInputOutputManager.
     * Android's bulkTransfer with large timeouts doesn't reliably return data,
     * but the ioManager's short-timeout polling loop works correctly.
     */
    fun enableDfuMode() {
        Log.d(TAG, "Enabling DFU mode (ioManager stays running)")
        rawModeEnabled.set(true)
        readBuffer.clear()
    }

    /**
     * Stop the ioManager without draining the port.
     *
     * Unlike enableRawMode(), this does NOT call port.read() to drain
     * buffered data. This is critical during nRF52 DFU: port.read() calls
     * testConnection() which sends a USB GET_STATUS control transfer. If the
     * nRF52840's CPU is blocked by NVMC flash erase, this control transfer
     * corrupts the USB controller state and eventually causes a bus reset.
     *
     * Call this after reading the DFU Start ACK but before the erase wait,
     * to keep the USB bus idle during the erase — matching the behavior of
     * the reference implementation (pyserial sleeps, no reads during erase).
     */
    fun stopIoManager() {
        Log.d(TAG, "Stopping ioManager (no drain)")
        val manager = ioManager
        val future = ioManagerFuture
        if (manager != null) {
            manager.stop()
            ioManager = null
            ioManagerFuture = null
            future?.cancel(true)
            waitForManagerStop(future)
            Log.d(TAG, "ioManager stopped")
        }
    }

    /**
     * Start (or restart) the ioManager on the current port.
     *
     * Used after DFU flash erase: the ioManager was stopped to prevent
     * testConnection() during erase, but the port itself is still valid.
     * After erase completes, we restart the ioManager to buffer ACK data.
     */
    fun startIoManager() {
        val port =
            currentPort ?: run {
                Log.d(TAG, "startIoManager: no current port")
                return
            }
        if (ioManager != null) {
            Log.d(TAG, "ioManager already running")
            return
        }
        Log.d(TAG, "Starting ioManager on existing port")
        val manager = SerialInputOutputManager(port, this)
        manager.readTimeout = 100
        ioManager = manager
        ioManagerFuture = ioExecutor.submit(manager)
    }

    /**
     * Disable DFU mode: restores normal KISS parsing and Python callbacks.
     */
    fun disableDfuMode() {
        Log.d(TAG, "Disabling DFU mode")
        rawModeEnabled.set(false)
        readBuffer.clear()
    }

    /**
     * Clear the read buffer.
     * Used before starting a flashing operation to ensure clean state.
     */
    fun clearReadBuffer() {
        readBuffer.clear()
        Log.d(TAG, "Read buffer cleared")
    }

    /**
     * Drain any pending data from the serial port.
     * Used to synchronize before flashing operations.
     *
     * @param timeoutMs Maximum time to wait for data
     */
    fun drain(timeoutMs: Int = 100) {
        val tempBuffer = ByteArray(1024)
        var totalDrained = 0
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val bytesRead = readBlocking(tempBuffer, 10)
            if (bytesRead <= 0) break
            totalDrained += bytesRead
        }

        readBuffer.clear()

        if (totalDrained > 0) {
            Log.d(TAG, "Drained $totalDrained bytes from serial port")
        }
    }

    /**
     * Write data to the USB device.
     * Thread-safe - can be called from any thread.
     *
     * @param data Bytes to write (typically KISS-framed data)
     * @return Number of bytes written, or -1 on error
     */
    fun write(data: ByteArray): Int {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot write - not connected")
            return -1
        }

        val port =
            currentPort ?: run {
                Log.e(TAG, "Port is null")
                return -1
            }

        return synchronized(this) {
            try {
                port.write(data, WRITE_TIMEOUT_MS)
                Log.v(TAG, "Wrote ${data.size} bytes")
                data.size
            } catch (e: IOException) {
                Log.e(TAG, "Write failed", e)
                scope.launch { handleDisconnect() }
                -1
            }
        }
    }

    /**
     * Read available data from the buffer (non-blocking).
     * Data is accumulated from the async I/O manager.
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
    fun available(): Int = readBuffer.size

    /**
     * Callback from SerialInputOutputManager when new data arrives.
     * Parses KISS frames to detect CMD_BT_PIN responses during pairing mode.
     */
    override fun onNewData(data: ByteArray) {
        if (data.isNotEmpty()) {
            // In raw mode, the ioManager should be stopped but if it's still running,
            // don't process the data - it will be read via readBlocking() instead
            if (rawModeEnabled.get()) {
                // DFU/raw mode: buffer data for readFromBuffer() but skip
                // KISS parsing and Python callbacks (bootloader data, not Reticulum)
                for (byte in data) {
                    readBuffer.offer(byte)
                }
                return
            }

            Log.v(TAG, "USB received ${data.size} bytes")

            // Add to read buffer
            for (byte in data) {
                readBuffer.offer(byte)
            }

            // Parse KISS frames to detect CMD_BT_PIN
            parseKissFrames(data)

            // Notify Python callback
            onDataReceived?.callAttr("__call__", data)
        }
    }

    /**
     * Parse incoming data for KISS frames, specifically looking for CMD_BT_PIN.
     * This allows the Kotlin layer to detect Bluetooth PIN responses without
     * requiring Python to be running the USB read loop.
     */
    @Suppress("NestedBlockDepth") // KISS protocol state machine requires nested conditions
    private fun parseKissFrames(data: ByteArray) {
        for (byte in data) {
            if (byte == KISS_FEND) {
                // FEND ends current frame (if any) and starts a new one
                if (kissInFrame && kissHasCommand) {
                    // End of frame - process it
                    Log.d(TAG, "KISS frame end: cmd=0x${kissCommand.toInt().and(0xFF).toString(16)}, dataLen=${kissDataBuffer.size}")
                    if (kissCommand == KISS_CMD_BT_PIN && kissDataBuffer.size >= 4) {
                        // PIN is sent as 4-byte big-endian integer by RNode firmware
                        try {
                            val rawBytes = kissDataBuffer.take(4).map { it.toInt() and 0xFF }
                            Log.d(TAG, "Raw PIN bytes: ${rawBytes.joinToString(", ") { "0x${it.toString(16).padStart(2, '0')}" }}")

                            val pinValue =
                                ((kissDataBuffer[0].toInt() and 0xFF) shl 24) or
                                    ((kissDataBuffer[1].toInt() and 0xFF) shl 16) or
                                    ((kissDataBuffer[2].toInt() and 0xFF) shl 8) or
                                    (kissDataBuffer[3].toInt() and 0xFF)
                            Log.d(TAG, "PIN as integer: $pinValue (0x${pinValue.toString(16)})")

                            val pin = String.format(Locale.US, "%06d", pinValue)
                            Log.i(TAG, "Parsed Bluetooth PIN from KISS frame: $pin")
                            notifyBluetoothPin(pin)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decode PIN bytes", e)
                        }
                    }
                }
                // Start new frame (FEND always starts a frame for sync)
                kissInFrame = true
                kissEscape = false
                kissHasCommand = false
                kissCommand = 0
                kissDataBuffer.clear()
            } else if (kissInFrame) {
                if (kissEscape) {
                    // Handle escaped byte
                    kissEscape = false
                    val actualByte =
                        when (byte) {
                            KISS_TFEND -> KISS_FEND
                            KISS_TFESC -> KISS_FESC
                            else -> byte
                        }
                    kissDataBuffer.add(actualByte)
                } else if (byte == KISS_FESC) {
                    kissEscape = true
                } else if (!kissHasCommand) {
                    // First byte after FEND is the command
                    kissCommand = byte
                    kissHasCommand = true
                } else {
                    kissDataBuffer.add(byte)
                }
            }
        }
    }

    /**
     * Callback from SerialInputOutputManager on error.
     *
     * In DFU/raw mode, ioManager crashes are expected and non-fatal.
     * The nRF52840 bootloader's minimal USB stack may not respond to the
     * GET_STATUS control transfer that testConnection() sends on read
     * timeouts. The port itself is still valid — writes use bulkTransfer
     * to the OUT endpoint, which doesn't call testConnection().
     */
    override fun onRunError(e: Exception) {
        Log.e(TAG, "USB I/O error", e)
        if (rawModeEnabled.get()) {
            Log.w(TAG, "Suppressing disconnect in DFU/raw mode — port stays open for writes")
            return
        }
        handleDisconnect()
    }

    /**
     * Shutdown the bridge and release resources.
     */
    fun shutdown() {
        disconnect()

        try {
            context.unregisterReceiver(usbPermissionReceiver)
            context.unregisterReceiver(usbEventReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receivers already unregistered: ${e.message}")
        }

        scope.cancel()
        ioExecutor.shutdown()
        Log.d(TAG, "KotlinUSBBridge shutdown")
    }
}
