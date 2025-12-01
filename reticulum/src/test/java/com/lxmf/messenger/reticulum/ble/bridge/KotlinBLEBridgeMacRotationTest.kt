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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for KotlinBLEBridge MAC rotation and identity handling.
 *
 * Tests the following scenarios:
 * - Stale MAC rotation cleanup (old connection removed when identity migrates)
 * - Pending connection completion (identity race condition fix)
 * - Identity-to-address mapping management
 *
 * Note: These tests use reflection to access private fields and verify state changes.
 */
class KotlinBLEBridgeMacRotationTest {
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

        // Mock disconnect methods to return success
        coEvery { mockGattClient.disconnect(any()) } returns Unit
        coEvery { mockGattServer.disconnectCentral(any()) } returns Unit
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Active MAC Rotation Tests (existing peer still connected) ==========

    @Test
    fun `active MAC rotation removes old connection from connectedPeers`() {
        // Given: Bridge with existing ACTIVE connection at old MAC address
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()

        // Set up initial state: active connection at old MAC with identity
        addMockPeer(bridge, oldMac, identityHash, isCentral = true)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity is received from NEW MAC (simulating MAC rotation while connected)
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Old connection should be removed from connectedPeers
        val connectedPeers = getConnectedPeers(bridge)
        assertFalse("Old MAC should be removed from connectedPeers", connectedPeers.containsKey(oldMac))
    }

    @Test
    fun `active MAC rotation updates identityToAddress to new MAC`() {
        // Given: Bridge with existing active connection at old MAC address
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, oldMac, identityHash, isCentral = true)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity received from new MAC
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Identity should now point to new MAC
        val identityToAddress = getIdentityToAddress(bridge)
        assertEquals("Identity should map to new MAC", newMac, identityToAddress[identityHash])
    }

    @Test
    fun `active MAC rotation disconnects old central connection`() {
        // Given: Bridge with existing CENTRAL connection at old MAC
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, oldMac, identityHash, isCentral = true, isPeripheral = false)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity received from new MAC (same direction - central)
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Should have called disconnect on GATT client for old MAC
        coVerify { mockGattClient.disconnect(oldMac) }
    }

    @Test
    fun `active MAC rotation disconnects old peripheral connection`() {
        // Given: Bridge with existing PERIPHERAL connection at old MAC
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, oldMac, identityHash, isCentral = false, isPeripheral = true)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity received from new MAC (same direction - peripheral)
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = false)

        // Then: Should have called disconnectCentral on GATT server for old MAC
        coVerify { mockGattServer.disconnectCentral(oldMac) }
    }

    // ========== Stale MAC Rotation Tests (old connection gone, only mapping remains) ==========

    @Test
    fun `stale MAC rotation cleans up addressToIdentity mapping`() {
        // Given: Identity mapping exists but NO peer in connectedPeers (connection already gone)
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        // Only set up mappings, NO peer - simulating stale/gone connection
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity received from new MAC
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Old address mapping should be removed
        val addressToIdentity = getAddressToIdentity(bridge)
        assertFalse("Old MAC should be removed from addressToIdentity", addressToIdentity.containsKey(oldMac))
    }

    @Test
    fun `stale MAC rotation cleans up pending connections for old address`() {
        // Given: Identity mapping and pending connection exist but NO active peer
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        // Set up pending connection and mappings but NO active peer
        addPendingConnection(bridge, oldMac, 514, isCentral = true, isPeripheral = false)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity received from new MAC
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Pending connection for old MAC should be removed
        val pendingConnections = getPendingConnections(bridge)
        assertFalse("Pending connection for old MAC should be removed", pendingConnections.containsKey(oldMac))
    }

    @Test
    fun `stale MAC rotation updates identityToAddress to new MAC`() {
        // Given: Identity mapping exists but NO peer (stale connection)
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // When: Identity received from new MAC
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Identity should now point to new MAC
        val identityToAddress = getIdentityToAddress(bridge)
        assertEquals("Identity should map to new MAC", newMac, identityToAddress[identityHash])
    }

    // ========== Pending Connection Completion Tests ==========

    @Test
    fun `pending connection is completed when identity arrives`() {
        // Given: Bridge with pending connection (connection arrived before identity)
        val address = "AA:BB:CC:DD:EE:FF"
        val identityHash = "test12345678identity"
        val mtu = 514

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, address, null, isCentral = true) // No identity yet
        addPendingConnection(bridge, address, mtu, isCentral = true, isPeripheral = false)

        // When: Identity is received
        invokeHandleIdentityReceivedBlocking(bridge, address, identityHash, isCentralConnection = true)

        // Then: Pending connection should be removed (completed)
        val pendingConnections = getPendingConnections(bridge)
        assertFalse("Pending connection should be completed and removed", pendingConnections.containsKey(address))
    }

    @Test
    fun `identity mapping is created when identity received`() {
        // Given: New connection with no prior identity
        val address = "AA:BB:CC:DD:EE:FF"
        val identityHash = "test12345678identity"

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, address, null, isCentral = true)

        // When: Identity is received
        invokeHandleIdentityReceivedBlocking(bridge, address, identityHash, isCentralConnection = true)

        // Then: Both mappings should be created
        val addressToIdentity = getAddressToIdentity(bridge)
        val identityToAddress = getIdentityToAddress(bridge)

        assertEquals("addressToIdentity should map address to identity", identityHash, addressToIdentity[address])
        assertEquals("identityToAddress should map identity to address", address, identityToAddress[identityHash])
    }

    // ========== Edge Cases ==========

    @Test
    fun `no cleanup when identity is first seen at address`() {
        // Given: New connection with no prior identity mappings
        val address = "AA:BB:CC:DD:EE:FF"
        val identityHash = "test12345678identity"

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, address, null, isCentral = true)

        // When: Identity is received (first time for this identity)
        invokeHandleIdentityReceivedBlocking(bridge, address, identityHash, isCentralConnection = true)

        // Then: Connection should remain, mappings created
        val connectedPeers = getConnectedPeers(bridge)
        assertTrue("Connection should remain", connectedPeers.containsKey(address))

        // Disconnect should NOT be called since there's no stale connection
        coVerify(exactly = 0) { mockGattClient.disconnect(any()) }
        coVerify(exactly = 0) { mockGattServer.disconnectCentral(any()) }
    }

    @Test
    fun `no cleanup when same address reports same identity again`() {
        // Given: Connection with identity already set
        val address = "AA:BB:CC:DD:EE:FF"
        val identityHash = "test12345678identity"

        val bridge = createBridgeWithMocks()
        addMockPeer(bridge, address, identityHash, isCentral = true)
        setAddressToIdentity(bridge, address, identityHash)
        setIdentityToAddress(bridge, identityHash, address)

        // When: Same identity received from same address (redundant)
        invokeHandleIdentityReceivedBlocking(bridge, address, identityHash, isCentralConnection = true)

        // Then: Connection should remain, no disconnects
        val connectedPeers = getConnectedPeers(bridge)
        assertTrue("Connection should remain", connectedPeers.containsKey(address))
        coVerify(exactly = 0) { mockGattClient.disconnect(any()) }
    }

    // ========== Helper Methods ==========

    private fun createBridgeWithMocks(): KotlinBLEBridge {
        val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

        // Inject mock scanner
        val scannerField = KotlinBLEBridge::class.java.getDeclaredField("scanner")
        scannerField.isAccessible = true
        scannerField.set(bridge, mockScanner)

        // Inject mock GATT client
        val gattClientField = KotlinBLEBridge::class.java.getDeclaredField("gattClient")
        gattClientField.isAccessible = true
        gattClientField.set(bridge, mockGattClient)

        // Inject mock GATT server
        val gattServerField = KotlinBLEBridge::class.java.getDeclaredField("gattServer")
        gattServerField.isAccessible = true
        gattServerField.set(bridge, mockGattServer)

        return bridge
    }

    private fun addMockPeer(
        bridge: KotlinBLEBridge,
        address: String,
        identityHash: String?,
        isCentral: Boolean = true,
        isPeripheral: Boolean = false,
        mtu: Int = 514,
    ) {
        val peerConnectionClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$PeerConnection",
            )
        val constructor =
            peerConnectionClass.getDeclaredConstructor(
                String::class.java,
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java,
                String::class.java,
                Long::class.java,
            )
        constructor.isAccessible = true

        val peer =
            constructor.newInstance(
                address,
                mtu,
                isCentral,
                isPeripheral,
                identityHash,
                System.currentTimeMillis(),
            )

        val connectedPeersField = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
        connectedPeersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connectedPeers = connectedPeersField.get(bridge) as ConcurrentHashMap<String, Any>
        connectedPeers[address] = peer
    }

    private fun addPendingConnection(
        bridge: KotlinBLEBridge,
        address: String,
        mtu: Int,
        isCentral: Boolean,
        isPeripheral: Boolean,
    ) {
        val pendingConnectionClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$PendingConnection",
            )
        val constructor =
            pendingConnectionClass.getDeclaredConstructor(
                String::class.java,
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Long::class.java,
            )
        constructor.isAccessible = true

        val pending =
            constructor.newInstance(
                address,
                mtu,
                isCentral,
                isPeripheral,
                System.currentTimeMillis(),
            )

        val pendingConnectionsField = KotlinBLEBridge::class.java.getDeclaredField("pendingConnections")
        pendingConnectionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pendingConnections = pendingConnectionsField.get(bridge) as ConcurrentHashMap<String, Any>
        pendingConnections[address] = pending
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

    private fun getConnectedPeers(bridge: KotlinBLEBridge): ConcurrentHashMap<String, Any> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, Any>
    }

    private fun getPendingConnections(bridge: KotlinBLEBridge): ConcurrentHashMap<String, Any> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("pendingConnections")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, Any>
    }

    private fun getAddressToIdentity(bridge: KotlinBLEBridge): ConcurrentHashMap<String, String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("addressToIdentity")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, String>
    }

    private fun getIdentityToAddress(bridge: KotlinBLEBridge): ConcurrentHashMap<String, String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("identityToAddress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, String>
    }

    private fun invokeHandleIdentityReceivedBlocking(
        bridge: KotlinBLEBridge,
        address: String,
        identityHash: String,
        isCentralConnection: Boolean,
    ) {
        kotlinx.coroutines.runBlocking {
            val method =
                KotlinBLEBridge::class.java.getDeclaredMethod(
                    "handleIdentityReceived",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    kotlin.coroutines.Continuation::class.java,
                )
            method.isAccessible = true

            // Use suspendCoroutine to properly invoke the suspend function
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(bridge, address, identityHash, isCentralConnection, continuation)
            }
        }
    }
}
