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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for KotlinBLEBridge address change detection.
 *
 * Tests verify that identity-to-address mapping is correctly updated when an identity's
 * address changes due to MAC rotation. The onAddressChanged callback invocation is
 * tested implicitly through the mapping updates (PyObject mocking for callback verification
 * has native library dependencies that can't be satisfied in unit tests).
 *
 * For integration-level callback verification, see instrumented tests or manual testing.
 */
class KotlinBLEBridgeAddressChangeTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockScanner: BleScanner
    private lateinit var mockGattClient: BleGattClient
    private lateinit var mockGattServer: BleGattServer
    private lateinit var mockOnAddressChanged: PyObject

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothManager = mockk<BluetoothManager>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        mockScanner = mockk<BleScanner>(relaxed = true)
        mockGattClient = mockk<BleGattClient>(relaxed = true)
        mockGattServer = mockk<BleGattServer>(relaxed = true)
        mockOnAddressChanged = mockk<PyObject>(relaxed = true)

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

    @Test
    fun `identity mapping updates when address changes for MAC rotation`() {
        // Given: Bridge with existing connection at old MAC address
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        bridge.setOnAddressChanged(mockOnAddressChanged)

        // Set up initial state: connection at old MAC with identity
        addMockPeer(bridge, oldMac, identityHash, isCentral = true)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)

        // New connection at new MAC (MAC rotation)
        addMockPeer(bridge, newMac, null, isCentral = true)

        // When: Identity is received from NEW MAC
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Identity should now map to new MAC address
        val identityToAddress = getIdentityToAddress(bridge)
        assertEquals("Identity should map to new MAC after rotation", newMac, identityToAddress[identityHash])

        // And: Address-to-identity mapping should include new address
        val addressToIdentity = getAddressToIdentity(bridge)
        assertEquals("New address should map to identity", identityHash, addressToIdentity[newMac])
    }

    @Test
    fun `identity mapping created when identity first seen`() {
        // Given: Bridge with no prior connection for this identity
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        bridge.setOnAddressChanged(mockOnAddressChanged)

        // New connection at new MAC (first connection for this identity)
        addMockPeer(bridge, newMac, null, isCentral = true)

        // Verify no prior mapping exists
        val identityToAddressBefore = getIdentityToAddress(bridge)
        assertNull("No prior mapping should exist", identityToAddressBefore[identityHash])

        // When: Identity is received for the first time
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // Then: Identity should now map to new MAC address
        val identityToAddress = getIdentityToAddress(bridge)
        assertEquals("Identity should map to address", newMac, identityToAddress[identityHash])
    }

    @Test
    fun `identity mapping unchanged when same address seen again`() {
        // Given: Bridge with existing connection
        val address = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        bridge.setOnAddressChanged(mockOnAddressChanged)

        // Set up initial state: connection at address with identity
        addMockPeer(bridge, address, identityHash, isCentral = true)
        setAddressToIdentity(bridge, address, identityHash)
        setIdentityToAddress(bridge, identityHash, address)

        // Clear the dedup set to allow re-invocation (simulating a reconnect scenario)
        clearProcessedIdentityCallbacks(bridge)

        // When: Same identity seen from same address again
        invokeHandleIdentityReceivedBlocking(bridge, address, identityHash, isCentralConnection = true)

        // Then: Identity mapping should remain unchanged
        val identityToAddress = getIdentityToAddress(bridge)
        assertEquals("Identity should still map to same address", address, identityToAddress[identityHash])
    }

    @Test
    fun `address change handled safely when callback not set`() {
        // Given: Bridge without onAddressChanged callback set
        val oldMac = "72:B3:41:9C:50:56"
        val newMac = "53:6D:66:34:A3:07"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        val bridge = createBridgeWithMocks()
        // NOTE: Not setting onAddressChanged callback

        // Set up initial state
        addMockPeer(bridge, oldMac, identityHash, isCentral = true)
        setAddressToIdentity(bridge, oldMac, identityHash)
        setIdentityToAddress(bridge, identityHash, oldMac)
        addMockPeer(bridge, newMac, null, isCentral = true)

        // When: Identity is received from NEW MAC
        // Then: Should not throw (null-safe invocation)
        invokeHandleIdentityReceivedBlocking(bridge, newMac, identityHash, isCentralConnection = true)

        // And mapping should still be updated
        val identityToAddress = getIdentityToAddress(bridge)
        assertEquals("Identity should map to new address even without callback", newMac, identityToAddress[identityHash])
    }

    // ========== Helper Functions ==========

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
        val deduplicationStateClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$DeduplicationState",
            )
        val constructor =
            peerConnectionClass.getDeclaredConstructor(
                String::class.java,
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java,
                String::class.java,
                Long::class.java,
                Int::class.java,
                Long::class.java,
                deduplicationStateClass,
            )
        constructor.isAccessible = true

        val deduplicationStateNone = deduplicationStateClass.enumConstants!![0]
        val peer =
            constructor.newInstance(
                address,
                mtu,
                isCentral,
                isPeripheral,
                identityHash,
                System.currentTimeMillis(),
                -100, // rssi
                System.currentTimeMillis(), // lastActivity
                deduplicationStateNone,
            )

        val connectedPeersField = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
        connectedPeersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connectedPeers = connectedPeersField.get(bridge) as ConcurrentHashMap<String, Any>
        connectedPeers[address] = peer
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

    private fun getIdentityToAddress(bridge: KotlinBLEBridge): ConcurrentHashMap<String, String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("identityToAddress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, String>
    }

    private fun getAddressToIdentity(bridge: KotlinBLEBridge): ConcurrentHashMap<String, String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("addressToIdentity")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, String>
    }

    private fun clearProcessedIdentityCallbacks(bridge: KotlinBLEBridge) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("processedIdentityCallbacks")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val set = field.get(bridge) as MutableSet<String>
        set.clear()
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
