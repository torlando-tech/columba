package com.lxmf.messenger.reticulum.ble.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for KotlinBLEBridge handling of null BluetoothAdapter.
 *
 * Issue #269: The app crashes immediately when opening the Interfaces screen
 * on devices where BluetoothManager.adapter returns null (emulator without
 * Bluetooth, devices with Bluetooth hardware disabled, etc.).
 *
 * Root cause: KotlinBLEBridge passes bluetoothAdapter to BleScanner, which
 * assumes non-null adapter and crashes with NullPointerException when
 * accessing bluetoothAdapter.bluetoothLeScanner.
 *
 * These tests verify the fix: BLE components should be conditionally
 * initialized and the bridge should degrade gracefully when Bluetooth
 * hardware is unavailable.
 */
class KotlinBLEBridgeNullAdapterTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothManager = mockk<BluetoothManager>(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSystemService(Context.BLUETOOTH_SERVICE) } returns mockBluetoothManager
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Null Adapter Initialization Tests ==========

    @Test
    fun `bridge instantiation does not crash when BluetoothManager adapter is null`() =
        runTest {
            // Given - BluetoothManager returns null adapter (emulator, no BT hardware)
            every { mockBluetoothManager.adapter } returns null

            // When - instantiate bridge (this previously crashed with NPE)
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // Then - bridge should be created successfully
            assertNotNull(bridge)
        }

    @Test
    fun `isBluetoothAvailable returns false when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null

            // When
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // Then
            assertFalse(bridge.isBluetoothAvailable)
        }

    @Test
    fun `isBluetoothAvailable returns true when adapter is present`() =
        runTest {
            // Given
            val mockAdapter = mockk<BluetoothAdapter>(relaxed = true)
            every { mockBluetoothManager.adapter } returns mockAdapter

            // When
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // Then
            assert(bridge.isBluetoothAvailable)
        }

    @Test
    fun `adapterState is STATE_OFF when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null

            // When
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // Then - adapter state should indicate Bluetooth is off
            assertEquals(BluetoothAdapter.STATE_OFF, bridge.adapterState.value)
        }

    // ========== Graceful Degradation Tests ==========

    @Test
    fun `start returns failure when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When
            val result =
                bridge.start(
                    serviceUuid = "test-service-uuid",
                    rxCharUuid = "test-rx-uuid",
                    txCharUuid = "test-tx-uuid",
                    identityCharUuid = "test-identity-uuid",
                )

            // Then
            assert(result.isFailure)
            assert(result.exceptionOrNull()?.message?.contains("Bluetooth") == true)
        }

    @Test
    fun `startScanning returns failure when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When
            val result = bridge.startScanning()

            // Then
            assert(result.isFailure)
        }

    @Test
    fun `startAdvertising returns failure when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When
            val result = bridge.startAdvertising()

            // Then
            assert(result.isFailure)
        }

    @Test
    fun `getConnectedPeers returns empty list when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When
            val peers = bridge.getConnectedPeers()

            // Then
            assert(peers.isEmpty())
        }

    @Test
    fun `getConnectionDetails returns empty list when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assert(details.isEmpty())
        }

    @Test
    fun `getConnectionDetailsSync returns empty list when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When
            val details = bridge.getConnectionDetailsSync()

            // Then
            assert(details.isEmpty())
        }

    @Test
    fun `connect does not crash when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When - should not throw
            bridge.connect("AA:BB:CC:DD:EE:FF")

            // Then - no crash, method returns gracefully
            assert(true)
        }

    @Test
    fun `disconnect does not crash when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When - should not throw
            bridge.disconnect("AA:BB:CC:DD:EE:FF")

            // Then - no crash, method returns gracefully
            assert(true)
        }

    @Test
    fun `send does not crash when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When - should not throw
            bridge.send("AA:BB:CC:DD:EE:FF", byteArrayOf(0x01, 0x02, 0x03))

            // Then - no crash, method returns gracefully
            assert(true)
        }

    @Test
    fun `stop does not crash when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When - should not throw
            bridge.stop()

            // Then - no crash, method returns gracefully
            assert(true)
        }

    @Test
    fun `shutdown does not crash when adapter is null`() =
        runTest {
            // Given
            every { mockBluetoothManager.adapter } returns null
            val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

            // When - should not throw
            bridge.shutdown()

            // Then - no crash, method returns gracefully
            assert(true)
        }

    // ========== Singleton getInstance Tests ==========

    @Test
    fun `getInstance does not crash when BluetoothManager adapter is null`() =
        runTest {
            // Given - Clear the singleton to ensure fresh instance
            clearSingleton()

            // Mock context to return null adapter BluetoothManager
            val testContext = mockk<Context>(relaxed = true)
            val testBluetoothManager = mockk<BluetoothManager>(relaxed = true)
            every { testContext.applicationContext } returns testContext
            every { testContext.getSystemService(Context.BLUETOOTH_SERVICE) } returns testBluetoothManager
            every { testBluetoothManager.adapter } returns null

            // When - getInstance should not crash
            val bridge = KotlinBLEBridge.getInstance(testContext)

            // Then
            assertNotNull(bridge)
            assertFalse(bridge.isBluetoothAvailable)

            // Cleanup
            clearSingleton()
        }

    /**
     * Helper to clear the singleton instance between tests.
     */
    @Suppress("SwallowedException") // Intentionally ignoring - field may not exist in some configurations
    private fun clearSingleton() {
        try {
            val instanceField = KotlinBLEBridge::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        } catch (e: Exception) {
            // Field may not exist in some configurations - safe to ignore
        }
    }
}
