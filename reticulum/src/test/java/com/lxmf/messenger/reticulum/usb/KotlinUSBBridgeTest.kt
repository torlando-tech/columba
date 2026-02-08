// Context, UsbManager are Android framework classes with many methods
@file:Suppress("NoRelaxedMocks")

package com.lxmf.messenger.reticulum.usb

import android.content.Context
import android.hardware.usb.UsbManager
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for KotlinUSBBridge.
 *
 * Tests USB device enumeration, connection management, and callback functionality.
 * Note: These tests mock the Android USB system since we can't use real USB hardware.
 */
class KotlinUSBBridgeTest {
    private lateinit var mockContext: Context
    private lateinit var mockUsbManager: UsbManager

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockUsbManager = mockk<UsbManager>(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSystemService(Context.USB_SERVICE) } returns mockUsbManager
        every { mockUsbManager.deviceList } returns HashMap()
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Initialization Tests ==========

    @Test
    fun `bridge initializes without errors`() {
        val bridge = KotlinUSBBridge(mockContext)
        assertNotNull("Bridge should be created", bridge)
    }

    @Test
    fun `getConnectedUsbDevices returns empty list when no devices`() {
        val bridge = KotlinUSBBridge(mockContext)
        val devices = bridge.getConnectedUsbDevices()
        assertTrue("Should return empty list", devices.isEmpty())
    }

    @Test
    fun `isConnected returns false initially`() {
        val bridge = KotlinUSBBridge(mockContext)
        assertFalse("Should not be connected initially", bridge.isConnected())
    }

    @Test
    fun `getConnectedDeviceId returns null when not connected`() {
        val bridge = KotlinUSBBridge(mockContext)
        assertNull("Should return null when not connected", bridge.getConnectedDeviceId())
    }

    // ========== Connection Listener Tests ==========

    @Test
    fun `addConnectionListener registers listener correctly`() {
        val bridge = KotlinUSBBridge(mockContext)
        var connectedDeviceId: Int? = null
        var disconnectedDeviceId: Int? = null

        val listener =
            object : UsbConnectionListener {
                override fun onUsbConnected(deviceId: Int) {
                    connectedDeviceId = deviceId
                }

                override fun onUsbDisconnected(deviceId: Int) {
                    disconnectedDeviceId = deviceId
                }

                override fun onUsbPermissionGranted(deviceId: Int) = Unit

                override fun onUsbPermissionDenied(deviceId: Int) = Unit
            }

        bridge.addConnectionListener(listener)

        // Access notifyListeners via reflection to test listener registration
        val notifyMethod =
            KotlinUSBBridge::class.java.getDeclaredMethod(
                "notifyListeners",
                Function1::class.java,
            )
        notifyMethod.isAccessible = true
        notifyMethod.invoke(bridge, { l: UsbConnectionListener -> l.onUsbConnected(42) })

        assertEquals("Listener should receive connection event", 42, connectedDeviceId)
    }

    @Test
    fun `removeConnectionListener stops notifications`() {
        val bridge = KotlinUSBBridge(mockContext)
        val connectionCount = AtomicInteger(0)

        val listener =
            object : UsbConnectionListener {
                override fun onUsbConnected(deviceId: Int) {
                    connectionCount.incrementAndGet()
                }

                override fun onUsbDisconnected(deviceId: Int) = Unit

                override fun onUsbPermissionGranted(deviceId: Int) = Unit

                override fun onUsbPermissionDenied(deviceId: Int) = Unit
            }

        bridge.addConnectionListener(listener)

        // Notify first time
        val notifyMethod =
            KotlinUSBBridge::class.java.getDeclaredMethod(
                "notifyListeners",
                Function1::class.java,
            )
        notifyMethod.isAccessible = true
        notifyMethod.invoke(bridge, { l: UsbConnectionListener -> l.onUsbConnected(1) })

        assertEquals("Should receive first notification", 1, connectionCount.get())

        bridge.removeConnectionListener(listener)

        // Notify second time
        notifyMethod.invoke(bridge, { l: UsbConnectionListener -> l.onUsbConnected(2) })

        assertEquals("Should not receive second notification", 1, connectionCount.get())
    }

    @Test
    fun `duplicate listener registration is prevented`() {
        val bridge = KotlinUSBBridge(mockContext)
        val notificationCount = AtomicInteger(0)

        val listener =
            object : UsbConnectionListener {
                override fun onUsbConnected(deviceId: Int) {
                    notificationCount.incrementAndGet()
                }

                override fun onUsbDisconnected(deviceId: Int) = Unit

                override fun onUsbPermissionGranted(deviceId: Int) = Unit

                override fun onUsbPermissionDenied(deviceId: Int) = Unit
            }

        // Register same listener twice
        bridge.addConnectionListener(listener)
        bridge.addConnectionListener(listener)

        val notifyMethod =
            KotlinUSBBridge::class.java.getDeclaredMethod(
                "notifyListeners",
                Function1::class.java,
            )
        notifyMethod.isAccessible = true
        notifyMethod.invoke(bridge, { l: UsbConnectionListener -> l.onUsbConnected(1) })

        assertEquals("Should only receive one notification", 1, notificationCount.get())
    }

    @Test
    fun `listener exception does not affect other listeners`() {
        val bridge = KotlinUSBBridge(mockContext)
        val listener2Called = AtomicBoolean(false)

        val throwingListener =
            object : UsbConnectionListener {
                override fun onUsbConnected(deviceId: Int) {
                    error("Test exception")
                }

                override fun onUsbDisconnected(deviceId: Int) = Unit

                override fun onUsbPermissionGranted(deviceId: Int) = Unit

                override fun onUsbPermissionDenied(deviceId: Int) = Unit
            }

        val normalListener =
            object : UsbConnectionListener {
                override fun onUsbConnected(deviceId: Int) {
                    listener2Called.set(true)
                }

                override fun onUsbDisconnected(deviceId: Int) = Unit

                override fun onUsbPermissionGranted(deviceId: Int) = Unit

                override fun onUsbPermissionDenied(deviceId: Int) = Unit
            }

        bridge.addConnectionListener(throwingListener)
        bridge.addConnectionListener(normalListener)

        val notifyMethod =
            KotlinUSBBridge::class.java.getDeclaredMethod(
                "notifyListeners",
                Function1::class.java,
            )
        notifyMethod.isAccessible = true

        // Should not throw and should still notify second listener
        notifyMethod.invoke(bridge, { l: UsbConnectionListener -> l.onUsbConnected(1) })

        assertTrue("Second listener should still be called", listener2Called.get())
    }

    // ========== Bluetooth PIN Callback Tests ==========

    @Test
    fun `setOnBluetoothPinReceivedKotlin registers callback`() {
        val bridge = KotlinUSBBridge(mockContext)
        val receivedPin = AtomicReference<String>()

        bridge.setOnBluetoothPinReceivedKotlin { pin ->
            receivedPin.set(pin)
        }

        // Call notifyBluetoothPin
        bridge.notifyBluetoothPin("123456")

        assertEquals("Callback should receive PIN", "123456", receivedPin.get())
    }

    @Test
    fun `notifyBluetoothPin notifies Kotlin callback`() {
        val bridge = KotlinUSBBridge(mockContext)
        val pins = mutableListOf<String>()

        bridge.setOnBluetoothPinReceivedKotlin { pin ->
            pins.add(pin)
        }

        bridge.notifyBluetoothPin("111111")
        bridge.notifyBluetoothPin("222222")

        assertEquals("Should receive both PINs", 2, pins.size)
        assertEquals("First PIN should match", "111111", pins[0])
        assertEquals("Second PIN should match", "222222", pins[1])
    }

    @Test
    fun `notifyBluetoothPin handles no callback set`() {
        val bridge = KotlinUSBBridge(mockContext)

        // Should not throw when no callback is set
        bridge.notifyBluetoothPin("123456")
    }

    // ========== Write/Read Tests ==========

    @Test
    fun `write returns negative one when not connected`() {
        val bridge = KotlinUSBBridge(mockContext)
        val result = bridge.write(byteArrayOf(0xC0.toByte(), 0x00, 0xC0.toByte()))
        assertEquals("Write should return -1 when not connected", -1, result)
    }

    @Test
    fun `read returns empty array when not connected`() {
        val bridge = KotlinUSBBridge(mockContext)
        val result = bridge.read()
        assertTrue("Read should return empty array", result.isEmpty())
    }

    @Test
    fun `available returns zero when not connected`() {
        val bridge = KotlinUSBBridge(mockContext)
        val result = bridge.available()
        assertEquals("Available should return 0", 0, result)
    }

    // ========== Permission Tests ==========

    @Test
    fun `hasPermission returns false for unknown device`() {
        val bridge = KotlinUSBBridge(mockContext)
        assertFalse("Should return false for unknown device", bridge.hasPermission(999))
    }

    // ========== Thread Safety Tests ==========

    @Test
    fun `concurrent listener registration is thread safe`() {
        val bridge = KotlinUSBBridge(mockContext)
        val listenerCount = 10
        val latch = CountDownLatch(listenerCount)
        val notificationCount = AtomicInteger(0)

        // Register listeners from multiple threads
        repeat(listenerCount) {
            Thread {
                bridge.addConnectionListener(
                    object : UsbConnectionListener {
                        override fun onUsbConnected(deviceId: Int) {
                            notificationCount.incrementAndGet()
                        }

                        override fun onUsbDisconnected(deviceId: Int) = Unit

                        override fun onUsbPermissionGranted(deviceId: Int) = Unit

                        override fun onUsbPermissionDenied(deviceId: Int) = Unit
                    },
                )
                latch.countDown()
            }.start()
        }

        assertTrue("All registrations should complete", latch.await(5, TimeUnit.SECONDS))

        val notifyMethod =
            KotlinUSBBridge::class.java.getDeclaredMethod(
                "notifyListeners",
                Function1::class.java,
            )
        notifyMethod.isAccessible = true
        notifyMethod.invoke(bridge, { l: UsbConnectionListener -> l.onUsbConnected(1) })

        assertEquals("All listeners should be notified", listenerCount, notificationCount.get())
    }

    // ========== Resource Cleanup Tests ==========

    @Test
    fun `shutdown cancels coroutine scope`() {
        val bridge = KotlinUSBBridge(mockContext)

        // Access private scope via reflection
        val scopeField = KotlinUSBBridge::class.java.getDeclaredField("scope")
        scopeField.isAccessible = true
        val scope = scopeField.get(bridge) as CoroutineScope

        // Verify scope is active before shutdown
        assertTrue("Scope should be active before shutdown", scope.isActive)

        // Call shutdown
        bridge.shutdown()

        // Verify scope is cancelled after shutdown
        assertFalse("Scope should be cancelled after shutdown", scope.isActive)
    }

    @Test
    fun `disconnect clears connection state`() {
        val bridge = KotlinUSBBridge(mockContext)

        // Even when not connected, disconnect should not throw
        bridge.disconnect()

        assertFalse("Should not be connected after disconnect", bridge.isConnected())
        assertNull("Device ID should be null after disconnect", bridge.getConnectedDeviceId())
    }

    // ========== UsbDeviceInfo Data Class Tests ==========

    @Test
    fun `UsbDeviceInfo holds correct values`() {
        val deviceInfo =
            UsbDeviceInfo(
                deviceId = 1,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "/dev/bus/usb/001/002",
                manufacturerName = "FTDI",
                productName = "FT232R",
                serialNumber = "A12345",
                driverType = "FTDI",
            )

        assertEquals("Device ID should match", 1, deviceInfo.deviceId)
        assertEquals("Vendor ID should match", 0x0403, deviceInfo.vendorId)
        assertEquals("Product ID should match", 0x6001, deviceInfo.productId)
        assertEquals("Device name should match", "/dev/bus/usb/001/002", deviceInfo.deviceName)
        assertEquals("Manufacturer should match", "FTDI", deviceInfo.manufacturerName)
        assertEquals("Product name should match", "FT232R", deviceInfo.productName)
        assertEquals("Serial number should match", "A12345", deviceInfo.serialNumber)
        assertEquals("Driver type should match", "FTDI", deviceInfo.driverType)
    }

    @Test
    fun `UsbDeviceInfo handles null optional fields`() {
        val deviceInfo =
            UsbDeviceInfo(
                deviceId = 1,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "/dev/bus/usb/001/003",
                manufacturerName = null,
                productName = null,
                serialNumber = null,
                driverType = "CH340",
            )

        assertEquals("Device ID should match", 1, deviceInfo.deviceId)
        assertNull("Manufacturer should be null", deviceInfo.manufacturerName)
        assertNull("Product name should be null", deviceInfo.productName)
        assertNull("Serial number should be null", deviceInfo.serialNumber)
        assertEquals("Driver type should match", "CH340", deviceInfo.driverType)
    }

    // ========== Supported VID Tests ==========

    @Test
    fun `SUPPORTED_VIDS contains expected vendors`() {
        // Access the private SUPPORTED_VIDS set via reflection
        val field = KotlinUSBBridge::class.java.getDeclaredField("SUPPORTED_VIDS")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val supportedVids = field.get(null) as Set<Int>

        // FTDI
        assertTrue("Should support FTDI (0x0403)", supportedVids.contains(0x0403))
        // SiLabs CP210x
        assertTrue("Should support CP210x (0x10C4)", supportedVids.contains(0x10C4))
        // Prolific PL2303
        assertTrue("Should support PL2303 (0x067B)", supportedVids.contains(0x067B))
        // CH340/CH341
        assertTrue("Should support CH340 (0x1A86)", supportedVids.contains(0x1A86))
        // ESP32/NRF52 CDC
        assertTrue("Should support CDC (0x0525)", supportedVids.contains(0x0525))
        // Raspberry Pi Pico
        assertTrue("Should support Pico (0x2E8A)", supportedVids.contains(0x2E8A))
        // Espressif
        assertTrue("Should support Espressif (0x303A)", supportedVids.contains(0x303A))
    }
}
