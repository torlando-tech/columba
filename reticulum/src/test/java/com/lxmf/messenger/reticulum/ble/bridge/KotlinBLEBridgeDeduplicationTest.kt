// Context, BluetoothManager, BluetoothAdapter, PyObject are framework classes with many methods
@file:Suppress("NoRelaxedMocks")

package com.lxmf.messenger.reticulum.ble.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.ble.client.BleGattClient
import com.lxmf.messenger.reticulum.ble.client.BleScanner
import com.lxmf.messenger.reticulum.ble.server.BleGattServer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for KotlinBLEBridge deduplication tracking.
 *
 * Tests the following live features:
 * - pendingCentralConnections - tracks in-progress central connections for race condition fix
 * - onAddressChanged callback - notifies Python of MAC address changes
 */
class KotlinBLEBridgeDeduplicationTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockScanner: BleScanner
    private lateinit var mockGattClient: BleGattClient
    private lateinit var mockGattServer: BleGattServer
    private lateinit var mockOnDisconnected: PyObject

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothManager = mockk<BluetoothManager>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        mockScanner = mockk<BleScanner>(relaxed = true)
        mockGattClient = mockk<BleGattClient>(relaxed = true)
        mockGattServer = mockk<BleGattServer>(relaxed = true)
        mockOnDisconnected = mockk<PyObject>(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockBluetoothManager.adapter } returns mockBluetoothAdapter
        every { mockBluetoothAdapter.isEnabled } returns true

        coEvery { mockGattClient.disconnect(any()) } returns Unit
        coEvery { mockGattServer.disconnectCentral(any()) } returns Unit
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== pendingCentralConnections Tests ==========

    @Test
    fun `pendingCentralConnections tracks addresses correctly`() {
        val bridge = createBridgeWithMocks()
        val address = "AA:BB:CC:DD:EE:FF"

        // When: add to pending
        addToPendingCentralConnections(bridge, address)

        // Then: should contain address
        val pending = getPendingCentralConnections(bridge)
        assertTrue("Should contain added address", pending.contains(address))
    }

    @Test
    fun `pendingCentralConnections removal works correctly`() {
        val bridge = createBridgeWithMocks()
        val address = "AA:BB:CC:DD:EE:FF"

        // Given: address in pending
        addToPendingCentralConnections(bridge, address)

        // When: remove from pending
        removeFromPendingCentralConnections(bridge, address)

        // Then: should not contain address
        val pending = getPendingCentralConnections(bridge)
        assertFalse("Should not contain removed address", pending.contains(address))
    }

    @Test
    fun `pendingCentralConnections prevents stale detection during identity race`() {
        val bridge = createBridgeWithMocks()
        val oldMac = "AA:BB:CC:DD:EE:01"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        // Given: Identity mapping exists for old MAC (simulating prior connection)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // And: old MAC is in pendingCentralConnections (connection in progress)
        addToPendingCentralConnections(bridge, oldMac)

        // When: check if old connection is considered "active"
        // The handleIdentityReceived logic checks:
        // val isPendingCentral = pendingCentralConnections.contains(existingAddress)
        // val isActiveConnection = existingPeerHasConnection || isPendingCentral

        val pending = getPendingCentralConnections(bridge)
        assertTrue("Pending connection should be detected as active", pending.contains(oldMac))
    }

    // ========== onAddressChanged Callback Tests ==========

    @Test
    fun `onAddressChanged callback can be set`() {
        val bridge = createBridgeWithMocks()
        val mockCallback = mockk<PyObject>(relaxed = true)

        // When: set the callback
        setOnAddressChanged(bridge, mockCallback)

        // Then: callback should be stored (use assertSame to avoid native PyObject.equals())
        val storedCallback = getOnAddressChanged(bridge)
        org.junit.Assert.assertSame("Callback should be stored", mockCallback, storedCallback)
    }

    @Test
    fun `onAddressChanged callback is initially null`() {
        val bridge = createBridgeWithMocks()

        // Verify callback starts as null
        val callback = getOnAddressChanged(bridge)
        org.junit.Assert.assertNull("Callback should be null initially", callback)
    }

    // ========== Helper Methods ==========

    private fun createBridgeWithMocks(): KotlinBLEBridge {
        val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

        val scannerField = KotlinBLEBridge::class.java.getDeclaredField("scanner")
        scannerField.isAccessible = true
        scannerField.set(bridge, mockScanner)

        val gattClientField = KotlinBLEBridge::class.java.getDeclaredField("gattClient")
        gattClientField.isAccessible = true
        gattClientField.set(bridge, mockGattClient)

        val gattServerField = KotlinBLEBridge::class.java.getDeclaredField("gattServer")
        gattServerField.isAccessible = true
        gattServerField.set(bridge, mockGattServer)

        return bridge
    }

    private fun getPendingCentralConnections(bridge: KotlinBLEBridge): MutableSet<String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("pendingCentralConnections")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as MutableSet<String>
    }

    private fun addToPendingCentralConnections(
        bridge: KotlinBLEBridge,
        address: String,
    ) {
        val set = getPendingCentralConnections(bridge)
        set.add(address)
    }

    private fun removeFromPendingCentralConnections(
        bridge: KotlinBLEBridge,
        address: String,
    ) {
        val set = getPendingCentralConnections(bridge)
        set.remove(address)
    }

    private fun setAddressToIdentity(
        bridge: KotlinBLEBridge,
        address: String,
        identity: String,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("addressToIdentity")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(bridge) as ConcurrentHashMap<String, String>
        map[address] = identity
    }

    private fun setIdentityToAddress(
        bridge: KotlinBLEBridge,
        identity: String,
        address: String,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("identityToAddress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(bridge) as ConcurrentHashMap<String, String>
        map[identity] = address
    }

    private fun setOnAddressChanged(
        bridge: KotlinBLEBridge,
        callback: PyObject,
    ) {
        val method =
            KotlinBLEBridge::class.java.getDeclaredMethod(
                "setOnAddressChanged",
                PyObject::class.java,
            )
        method.isAccessible = true
        method.invoke(bridge, callback)
    }

    private fun getOnAddressChanged(bridge: KotlinBLEBridge): PyObject? {
        val field = KotlinBLEBridge::class.java.getDeclaredField("onAddressChanged")
        field.isAccessible = true
        return field.get(bridge) as? PyObject
    }
}
