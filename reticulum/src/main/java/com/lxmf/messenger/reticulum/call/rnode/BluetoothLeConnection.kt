package com.lxmf.messenger.reticulum.call.rnode

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE GATT connection to an RNode device via Nordic UART Service (NUS).
 *
 * Provides [InputStream]/[OutputStream] adapters so that [RNodeInterface][network.reticulum.interfaces.rnode.RNodeInterface]
 * can read/write as if it were a serial port. Internally, GATT notification callbacks
 * feed a [PipedInputStream], and writes go through a latch-synchronized BLE write path
 * that chunks data to the negotiated MTU.
 *
 * Adapted from Columba's KotlinRNodeBridge — stripped of Chaquopy/Python, Bluetooth Classic,
 * and converted to a focused BLE-only connection helper.
 */
@SuppressLint("MissingPermission")
class BluetoothLeConnection(
    private val context: Context,
    private val deviceAddress: String,
) {
    companion object {
        private const val TAG = "BleConnection"

        // Nordic UART Service UUIDs
        private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write TO device
        private val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify FROM device
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val BLE_SCAN_TIMEOUT_MS = 10_000L
        private const val BLE_CONNECT_TIMEOUT_MS = 15_000L
        private const val BLE_CONNECT_MAX_RETRIES = 3
        private const val BLE_RETRY_DELAY_MS = 1_000L
        private const val BLE_WRITE_TIMEOUT_SECONDS = 5L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // GATT state
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleRxCharacteristic: BluetoothGattCharacteristic? = null
    private var bleTxCharacteristic: BluetoothGattCharacteristic? = null
    private var bleMtu: Int = 20

    @Volatile private var bleConnected = false

    @Volatile private var bleServicesDiscovered = false

    @Volatile private var bleMtuCallbackReceived = false

    private val connected = AtomicBoolean(false)

    // BLE write synchronization: writeMutex serializes entire write() calls across threads.
    // Each GATT chunk uses a CountDownLatch to wait for onCharacteristicWrite callback.
    // ReentrantLock stays held through latch.await() (unlike synchronized + Object.wait()).
    private val writeMutex =
        java.util.concurrent.locks
            .ReentrantLock()

    @Volatile private var bleWriteLatch: CountDownLatch? = null
    private val bleWriteStatus = AtomicInteger(BluetoothGatt.GATT_SUCCESS)

    // Stream bridge: GATT notifications → PipedOutputStream → PipedInputStream (read by RNodeInterface)
    private val pipedOut = PipedOutputStream()
    private val pipedIn = PipedInputStream(pipedOut, 8192)

    /** InputStream for RNodeInterface to read from (data arriving from BLE device). */
    val inputStream: InputStream get() = pipedIn

    /** OutputStream for RNodeInterface to write to (data sent to BLE device). */
    val outputStream: OutputStream = BleOutputStream()

    /**
     * Connect to the RNode BLE device.
     *
     * Scans for or finds the device by address, connects GATT, negotiates MTU,
     * discovers NUS service, and enables TX notifications. Retries up to 3 times
     * for transient BLE failures (e.g., GATT error 133).
     *
     * @return Pair of (inputStream, outputStream) for RNodeInterface
     * @throws IOException if connection fails after all retries
     */
    fun connect(): Pair<InputStream, OutputStream> {
        val device = findDevice()
        return connectWithRetries(device)
    }

    private fun findDevice(): BluetoothDevice {
        val adapter = bluetoothAdapter ?: throw IOException("Bluetooth not available")

        var device: BluetoothDevice? =
            try {
                adapter.bondedDevices.find { it.address == deviceAddress }
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
                null
            }

        if (device == null) {
            Log.d(TAG, "Device not bonded, scanning for: $deviceAddress")
            device = scanForDevice(deviceAddress)
        }

        return device ?: throw IOException("BLE device not found: $deviceAddress")
    }

    private fun connectWithRetries(device: BluetoothDevice): Pair<InputStream, OutputStream> {
        for (attempt in 1..BLE_CONNECT_MAX_RETRIES) {
            if (attempt > 1) {
                Log.i(TAG, "BLE connection attempt $attempt/$BLE_CONNECT_MAX_RETRIES...")
                Thread.sleep(BLE_RETRY_DELAY_MS)
            }

            if (attemptConnection(device)) {
                connected.set(true)
                return Pair(inputStream, outputStream)
            }

            if (attempt < BLE_CONNECT_MAX_RETRIES) {
                Log.w(TAG, "BLE connection failed, will retry...")
            }
        }

        throw IOException("BLE connection failed after $BLE_CONNECT_MAX_RETRIES attempts")
    }

    /**
     * Single GATT connection attempt.
     */
    private fun attemptConnection(device: BluetoothDevice): Boolean {
        return try {
            bleConnected = false
            bleServicesDiscovered = false
            bleMtuCallbackReceived = false
            bleRxCharacteristic = null
            bleTxCharacteristic = null

            Log.d(TAG, "Connecting GATT to ${device.address}...")
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

            // Wait for service discovery
            val startTime = System.currentTimeMillis()
            while (!bleServicesDiscovered && (System.currentTimeMillis() - startTime) < BLE_CONNECT_TIMEOUT_MS) {
                Thread.sleep(100)
            }

            if (!bleServicesDiscovered) {
                Log.e(TAG, "BLE timeout - services not discovered")
                cleanup()
                return false
            }

            if (bleRxCharacteristic == null || bleTxCharacteristic == null) {
                Log.e(TAG, "NUS characteristics not found")
                cleanup()
                return false
            }

            Log.i(TAG, "BLE connected to ${device.address}, MTU=$bleMtu")
            true
        } catch (e: Exception) {
            Log.e(TAG, "BLE connection attempt failed", e)
            cleanup()
            false
        }
    }

    /**
     * Scan for a BLE device by address or name.
     */
    private fun scanForDevice(address: String): BluetoothDevice? {
        val scanner =
            bluetoothAdapter?.bluetoothLeScanner ?: run {
                Log.e(TAG, "BLE scanner not available")
                return null
            }

        var result: BluetoothDevice? = null

        val scanCallback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    scanResult: ScanResult,
                ) {
                    val dev = scanResult.device
                    // Match by address or by name
                    if (dev.address == address || dev.name == address) {
                        Log.d(TAG, "Found BLE device: ${dev.name} (${dev.address})")
                        result = dev
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed: $errorCode")
                }
            }

        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
                .build()

        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)

            val startTime = System.currentTimeMillis()
            while (result == null && (System.currentTimeMillis() - startTime) < BLE_SCAN_TIMEOUT_MS) {
                Thread.sleep(100)
            }

            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission", e)
        }

        return result
    }

    /**
     * GATT callback handling the full lifecycle:
     * connect → MTU negotiation → service discovery → notification enable
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
                        gatt.requestMtu(512)
                        // Fallback: if MTU callback doesn't fire in 2s, discover services anyway
                        Thread {
                            Thread.sleep(2000)
                            if (!bleMtuCallbackReceived && bleConnected) {
                                Log.w(TAG, "MTU callback timeout, proceeding with service discovery")
                                gatt.discoverServices()
                            }
                        }.start()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "BLE disconnected (status=$status)")
                        bleConnected = false
                        if (connected.get()) {
                            connected.set(false)
                            try {
                                pipedOut.close()
                            } catch (_: Exception) {
                            }
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
                    Log.d(TAG, "MTU negotiated: $mtu")
                } else {
                    Log.w(TAG, "MTU change failed (status=$status), using default")
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    bleServicesDiscovered = true // unblock connect loop
                    return
                }

                val nusService = gatt.getService(NUS_SERVICE_UUID)
                if (nusService == null) {
                    Log.e(TAG, "Nordic UART Service not found")
                    bleServicesDiscovered = true // unblock connect loop
                    return
                }

                val rxChar = nusService.getCharacteristic(NUS_RX_CHAR_UUID)
                val txChar = nusService.getCharacteristic(NUS_TX_CHAR_UUID)
                bleRxCharacteristic = rxChar
                bleTxCharacteristic = txChar

                if (rxChar == null || txChar == null) {
                    Log.e(TAG, "NUS characteristics not found")
                    bleServicesDiscovered = true // unblock connect loop
                    return
                }

                // Enable notifications on TX characteristic (data FROM device)
                gatt.setCharacteristicNotification(txChar, true)
                val descriptor = txChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    // writeDescriptor is an async GATT operation — we must wait for
                    // onDescriptorWrite before signalling ready, otherwise the first
                    // characteristic write races with the pending descriptor write
                    // and gatt.writeCharacteristic() returns false.
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    // bleServicesDiscovered will be set in onDescriptorWrite callback
                } else {
                    // No CCCD descriptor — mark ready immediately
                    bleServicesDiscovered = true
                    Log.i(TAG, "BLE NUS service ready (no CCCD)")
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "BLE NUS service ready (CCCD written)")
                    } else {
                        Log.w(TAG, "CCCD descriptor write failed (status=$status), proceeding anyway")
                    }
                    bleServicesDiscovered = true
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == NUS_TX_CHAR_UUID) {
                    val data = characteristic.value
                    if (data != null && data.isNotEmpty()) {
                        try {
                            pipedOut.write(data)
                            pipedOut.flush()
                        } catch (e: IOException) {
                            Log.e(TAG, "Error writing to pipe", e)
                        }
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val currentLatch = bleWriteLatch
                if (currentLatch != null) {
                    bleWriteStatus.set(status)
                    currentLatch.countDown()
                } else {
                    Log.w(TAG, "Ignoring stale BLE write callback")
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "BLE write failed: $status")
                }
            }
        }

    /** Whether the BLE connection is currently active. */
    fun isConnected(): Boolean = connected.get() && bleConnected

    /**
     * Disconnect and release all BLE resources.
     */
    fun disconnect() {
        if (!connected.getAndSet(false)) return
        Log.i(TAG, "Disconnecting BLE...")
        cleanup()
        try {
            pipedOut.close()
        } catch (_: Exception) {
        }
        try {
            pipedIn.close()
        } catch (_: Exception) {
        }
    }

    private fun cleanup() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT", e)
        }
        bluetoothGatt = null
        bleRxCharacteristic = null
        bleTxCharacteristic = null
        bleConnected = false
        bleServicesDiscovered = false
        bleMtuCallbackReceived = false
    }

    /**
     * OutputStream that writes to the BLE RX characteristic (data TO device).
     *
     * Thread-safe: writeMutex serializes entire write() calls so concurrent
     * callers (Transport packets + framebuffer init) can't interleave GATT
     * operations. Each chunk waits for onCharacteristicWrite via CountDownLatch.
     */
    private inner class BleOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            writeMutex.lock()
            try {
                val gatt = bluetoothGatt ?: throw IOException("GATT not connected")
                val rxChar = bleRxCharacteristic ?: throw IOException("RX characteristic not available")
                val maxPayload = bleMtu - 3 // MTU minus ATT header overhead

                var offset = off
                val end = off + len
                while (offset < end) {
                    val chunkSize = minOf(maxPayload, end - offset)
                    val chunk = b.copyOfRange(offset, offset + chunkSize)

                    val latch = CountDownLatch(1)
                    bleWriteLatch = latch
                    bleWriteStatus.set(BluetoothGatt.GATT_SUCCESS)

                    rxChar.value = chunk
                    if (!gatt.writeCharacteristic(rxChar)) {
                        bleWriteLatch = null
                        throw IOException("BLE write failed to queue")
                    }

                    // latch.await() does NOT release writeMutex — other writers block
                    val completed = latch.await(BLE_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    bleWriteLatch = null

                    if (!completed) {
                        throw IOException("BLE write timed out")
                    }

                    if (bleWriteStatus.get() != BluetoothGatt.GATT_SUCCESS) {
                        throw IOException("BLE write callback reported failure: ${bleWriteStatus.get()}")
                    }

                    offset += chunkSize
                }
            } finally {
                writeMutex.unlock()
            }
        }

        override fun flush() {
            // BLE writes are synchronous via latch — nothing to flush
        }

        override fun close() {
            disconnect()
        }
    }
}
