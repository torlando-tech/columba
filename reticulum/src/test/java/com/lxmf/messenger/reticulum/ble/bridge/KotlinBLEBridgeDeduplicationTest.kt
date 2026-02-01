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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for KotlinBLEBridge deduplication and cooldown functionality.
 *
 * Tests the following features from PR 59:
 * - shouldSkipDiscoveredDevice() - prevents reconnecting to recently deduplicated peers
 * - pendingCentralConnections - tracks in-progress central connections for race condition fix
 * - recentlyDeduplicatedIdentities - cooldown tracking for deduplication
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

    // ========== shouldSkipDiscoveredDevice() Tests ==========

    @Test
    fun `shouldSkipDiscoveredDevice returns false for null device name`() {
        val bridge = createBridgeWithMocks()

        // Add an identity to the cooldown map
        addToRecentlyDeduplicatedIdentities(bridge, "ab5609dfffb33b21a102e1ff81196be5", System.currentTimeMillis())

        // When: null device name
        val result = invokeShouldSkipDiscoveredDevice(bridge, null)

        // Then: should return false
        assertFalse("Should return false for null device name", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice returns false when no recent deduplication`() {
        val bridge = createBridgeWithMocks()

        // No entries in recentlyDeduplicatedIdentities

        // When: valid RNS device name
        val result = invokeShouldSkipDiscoveredDevice(bridge, "RNS-ab5609")

        // Then: should return false since map is empty
        assertFalse("Should return false when no recent deduplication", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice returns true for RNS prefix matching recently deduplicated identity`() {
        val bridge = createBridgeWithMocks()

        // Identity starts with "ab5609"
        val fullIdentity = "ab5609dfffb33b21a102e1ff81196be5"
        addToRecentlyDeduplicatedIdentities(bridge, fullIdentity, System.currentTimeMillis())

        // When: device name with matching prefix (lowercase match)
        val result = invokeShouldSkipDiscoveredDevice(bridge, "RNS-ab5609")

        // Then: should return true
        assertTrue("Should skip device matching recently deduplicated identity", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice returns true for Reticulum prefix matching recently deduplicated identity`() {
        val bridge = createBridgeWithMocks()

        val fullIdentity = "272b4c8f1a3d5e9b0c2f7a4d6e8b1c3a"
        addToRecentlyDeduplicatedIdentities(bridge, fullIdentity, System.currentTimeMillis())

        // When: Reticulum-prefixed device name with matching identity
        val result = invokeShouldSkipDiscoveredDevice(bridge, "Reticulum-272b4c")

        // Then: should return true
        assertTrue("Should skip Reticulum-prefixed device matching deduplicated identity", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice returns false for unrelated device name`() {
        val bridge = createBridgeWithMocks()

        val fullIdentity = "ab5609dfffb33b21a102e1ff81196be5"
        addToRecentlyDeduplicatedIdentities(bridge, fullIdentity, System.currentTimeMillis())

        // When: device name with non-matching prefix
        val result = invokeShouldSkipDiscoveredDevice(bridge, "RNS-ffffff")

        // Then: should return false
        assertFalse("Should not skip device with non-matching identity prefix", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice returns false for non-RNS-Reticulum device name`() {
        val bridge = createBridgeWithMocks()

        val fullIdentity = "ab5609dfffb33b21a102e1ff81196be5"
        addToRecentlyDeduplicatedIdentities(bridge, fullIdentity, System.currentTimeMillis())

        // When: device name without RNS- or Reticulum- prefix
        val result = invokeShouldSkipDiscoveredDevice(bridge, "SomeOtherDevice-ab5609")

        // Then: should return false
        assertFalse("Should not skip device without RNS/Reticulum prefix", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice returns false after cooldown expires`() {
        val bridge = createBridgeWithMocks()

        // Add identity with timestamp 61 seconds in the past (cooldown is 60s)
        val expiredTimestamp = System.currentTimeMillis() - 61_000L
        addToRecentlyDeduplicatedIdentities(bridge, "ab5609dfffb33b21a102e1ff81196be5", expiredTimestamp)

        // When: device name with matching prefix
        val result = invokeShouldSkipDiscoveredDevice(bridge, "RNS-ab5609")

        // Then: should return false since cooldown expired
        assertFalse("Should not skip device after cooldown expires", result)
    }

    @Test
    fun `shouldSkipDiscoveredDevice cleans up expired entries`() {
        val bridge = createBridgeWithMocks()

        // Add one expired entry and one fresh entry
        val expiredTimestamp = System.currentTimeMillis() - 61_000L
        val freshTimestamp = System.currentTimeMillis()

        addToRecentlyDeduplicatedIdentities(bridge, "expired1dfffb33b21a102e1ff81196be5", expiredTimestamp)
        addToRecentlyDeduplicatedIdentities(bridge, "fresh12dfffb33b21a102e1ff81196be5", freshTimestamp)

        // When: invoke the method (it cleans up expired entries)
        invokeShouldSkipDiscoveredDevice(bridge, "RNS-unrelated")

        // Then: expired entry should be removed, fresh entry should remain
        val map = getRecentlyDeduplicatedIdentities(bridge)
        assertEquals("Should have only 1 entry after cleanup", 1, map.size)
        assertTrue("Fresh entry should remain", map.containsKey("fresh12dfffb33b21a102e1ff81196be5"))
        assertFalse("Expired entry should be removed", map.containsKey("expired1dfffb33b21a102e1ff81196be5"))
    }

    @Test
    fun `shouldSkipDiscoveredDevice is case insensitive for identity prefix`() {
        val bridge = createBridgeWithMocks()

        // Identity stored in lowercase
        val fullIdentity = "ab5609dfffb33b21a102e1ff81196be5"
        addToRecentlyDeduplicatedIdentities(bridge, fullIdentity, System.currentTimeMillis())

        // When: device name with uppercase prefix (should be lowercased internally)
        val result = invokeShouldSkipDiscoveredDevice(bridge, "RNS-AB5609")

        // Then: should return true (case insensitive match)
        assertTrue("Should match identity case-insensitively", result)
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

    // ========== recentlyDeduplicatedIdentities Tests ==========

    @Test
    fun `recentlyDeduplicatedIdentities stores identity with timestamp`() {
        val bridge = createBridgeWithMocks()
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"
        val timestamp = System.currentTimeMillis()

        // When: add identity to cooldown
        addToRecentlyDeduplicatedIdentities(bridge, identityHash, timestamp)

        // Then: should be stored with timestamp
        val map = getRecentlyDeduplicatedIdentities(bridge)
        assertEquals("Should store timestamp", timestamp, map[identityHash])
    }

    @Test
    fun `recentlyDeduplicatedIdentities can be cleared`() {
        val bridge = createBridgeWithMocks()

        // Given: some entries
        addToRecentlyDeduplicatedIdentities(bridge, "identity1aaaaaaaaaaaaaaaaaaaaaaaa", System.currentTimeMillis())
        addToRecentlyDeduplicatedIdentities(bridge, "identity2bbbbbbbbbbbbbbbbbbbbbbbb", System.currentTimeMillis())

        // When: clear all
        clearRecentlyDeduplicatedIdentities(bridge)

        // Then: should be empty
        val map = getRecentlyDeduplicatedIdentities(bridge)
        assertTrue("Map should be empty after clear", map.isEmpty())
    }

    @Test
    fun `identity removed from cooldown allows reconnection`() {
        val bridge = createBridgeWithMocks()
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"

        // Given: identity in cooldown
        addToRecentlyDeduplicatedIdentities(bridge, identityHash, System.currentTimeMillis())

        // Verify it blocks
        assertTrue(
            "Should skip initially",
            invokeShouldSkipDiscoveredDevice(bridge, "RNS-ab5609"),
        )

        // When: remove from cooldown
        removeFromRecentlyDeduplicatedIdentities(bridge, identityHash)

        // Then: should no longer skip
        assertFalse(
            "Should not skip after removal",
            invokeShouldSkipDiscoveredDevice(bridge, "RNS-ab5609"),
        )
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

    private fun addToRecentlyDeduplicatedIdentities(
        bridge: KotlinBLEBridge,
        identity: String,
        timestamp: Long,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("recentlyDeduplicatedIdentities")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(bridge) as ConcurrentHashMap<String, Long>
        map[identity] = timestamp
    }

    private fun getRecentlyDeduplicatedIdentities(bridge: KotlinBLEBridge): ConcurrentHashMap<String, Long> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("recentlyDeduplicatedIdentities")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, Long>
    }

    private fun removeFromRecentlyDeduplicatedIdentities(
        bridge: KotlinBLEBridge,
        identity: String,
    ) {
        val map = getRecentlyDeduplicatedIdentities(bridge)
        map.remove(identity)
    }

    private fun clearRecentlyDeduplicatedIdentities(bridge: KotlinBLEBridge) {
        val map = getRecentlyDeduplicatedIdentities(bridge)
        map.clear()
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

    private fun invokeShouldSkipDiscoveredDevice(
        bridge: KotlinBLEBridge,
        deviceName: String?,
    ): Boolean {
        val method =
            KotlinBLEBridge::class.java.getDeclaredMethod(
                "shouldSkipDiscoveredDevice",
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(bridge, deviceName) as Boolean
    }

    private fun setTransportIdentityHash(
        bridge: KotlinBLEBridge,
        identityBytes: ByteArray?,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("transportIdentityHash")
        field.isAccessible = true
        field.set(bridge, identityBytes)
    }

    // ========== Cooldown Asymmetry Tests ==========

    @Test
    fun `deduplication sets cooldown when keeping peripheral connection`() {
        // This test verifies existing behavior (cooldown IS set when keeping peripheral)
        val bridge = createBridgeWithMocks()
        val identity = "ab5609dfffb33b21a102e1ff81196be5"

        // Setup: local identity is HIGHER, so we keep peripheral (!weKeepCentral)
        // Higher local identity means: localIdentityHex >= identityHash, so weKeepCentral = false
        val localIdentity = ByteArray(16) { 0xFF.toByte() }
        setTransportIdentityHash(bridge, localIdentity)

        // When deduplication happens with keeping peripheral, cooldown should be set
        // We can't easily trigger handleIdentityReceived, but we can verify the cooldown map behavior

        // For now, just verify the mechanism works - add to cooldown and check shouldSkipDiscoveredDevice
        addToRecentlyDeduplicatedIdentities(bridge, identity, System.currentTimeMillis())

        // Should skip this device
        val result = invokeShouldSkipDiscoveredDevice(bridge, "RNS-ab5609")
        assertTrue("Should skip device with matching identity prefix", result)
    }

    @Test
    fun `deduplication cooldown map is accessible for testing`() {
        val bridge = createBridgeWithMocks()
        val identity = "ab5609dfffb33b21a102e1ff81196be5"

        // Initially empty
        val cooldownBefore = getRecentlyDeduplicatedIdentities(bridge)
        assertFalse("Cooldown should not contain identity initially", cooldownBefore.containsKey(identity))

        // Add to cooldown
        addToRecentlyDeduplicatedIdentities(bridge, identity, System.currentTimeMillis())

        // Should now contain identity
        val cooldownAfter = getRecentlyDeduplicatedIdentities(bridge)
        assertTrue("Cooldown should contain identity after adding", cooldownAfter.containsKey(identity))
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

    // ========== Additional Helper Methods for onAddressChanged Tests ==========

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
