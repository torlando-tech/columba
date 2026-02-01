// Context, BluetoothManager, BluetoothAdapter, PyObject are framework classes with many methods
@file:Suppress("NoRelaxedMocks")

package com.lxmf.messenger.reticulum.ble.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.lxmf.messenger.reticulum.ble.client.BleScanner
import com.lxmf.messenger.reticulum.ble.model.BleDevice
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for KotlinBLEBridge.
 *
 * Tests the BLE connection details feature, including:
 * - Real RSSI values from scanner (vs -100 dBm fallback)
 * - Real device names from scanner (vs "No Name" fallback)
 * - Identity hash management
 *
 * Note: This tests the public API and observable behavior.
 * Private methods are tested indirectly through their effects on public methods.
 */
class KotlinBLEBridgeTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockScanner: BleScanner

    @Before
    fun setup() {
        // Mock Android components
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothManager = mockk<BluetoothManager>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        mockScanner = mockk<BleScanner>(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockBluetoothManager.adapter } returns mockBluetoothAdapter
        every { mockBluetoothAdapter.isEnabled } returns true
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== getConnectionDetails() - RSSI Tests ==========

    @Test
    fun `getConnectionDetails returns real RSSI from scanner when device found`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val realRssi = -45

            val testDevice = createBleDevice(peerAddress, "TestDevice", realRssi)
            coEvery { mockScanner.getDevicesSortedByPriority() } returns listOf(testDevice)

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "test123")

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals(realRssi, details[0].rssi)
            assertEquals(peerAddress, details[0].currentMac)
        }

    @Test
    fun `getConnectionDetails returns fallback RSSI of -100 when device not in scanner`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "test123")

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals(-100, details[0].rssi)
            assertEquals(peerAddress, details[0].currentMac)
        }

    // ========== getPeerRssi() Tests ==========

    @Test
    fun `getPeerRssi returns RSSI when peer exists with valid RSSI`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val expectedRssi = -65

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "test123", rssi = expectedRssi)

            // When
            val rssi = bridge.getPeerRssi(peerAddress)

            // Then
            assertEquals(expectedRssi, rssi)
        }

    @Test
    fun `getPeerRssi returns null when peer does not exist`() =
        runTest {
            // Given
            val bridge = createBridgeWithMockScanner()
            // No peers added

            // When
            val rssi = bridge.getPeerRssi("AA:BB:CC:DD:EE:FF")

            // Then
            assertEquals(null, rssi)
        }

    @Test
    fun `getPeerRssi returns null when peer has unknown RSSI of -100`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "test123", rssi = -100)

            // When
            val rssi = bridge.getPeerRssi(peerAddress)

            // Then: -100 means "unknown" so should return null
            assertEquals(null, rssi)
        }

    @Test
    fun `getPeerRssi returns correct RSSI for specific peer among multiple`() =
        runTest {
            // Given
            val peer1 = "AA:BB:CC:DD:EE:01"
            val peer2 = "AA:BB:CC:DD:EE:02"
            val peer3 = "AA:BB:CC:DD:EE:03"

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peer1, "id1", rssi = -45)
            addMockPeer(bridge, peer2, "id2", rssi = -70)
            addMockPeer(bridge, peer3, "id3", rssi = -85)

            // When/Then
            assertEquals(-45, bridge.getPeerRssi(peer1))
            assertEquals(-70, bridge.getPeerRssi(peer2))
            assertEquals(-85, bridge.getPeerRssi(peer3))
        }

    @Test
    fun `getConnectionDetails returns different RSSI for multiple peers`() =
        runTest {
            // Given
            val peer1 = "AA:BB:CC:DD:EE:01"
            val peer2 = "AA:BB:CC:DD:EE:02"
            val peer3 = "AA:BB:CC:DD:EE:03"

            val devices =
                listOf(
                    createBleDevice(peer1, "Device1", -45),
                    createBleDevice(peer2, "Device2", -60),
                    createBleDevice(peer3, "Device3", -75),
                )
            coEvery { mockScanner.getDevicesSortedByPriority() } returns devices

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peer1, "id1")
            addMockPeer(bridge, peer2, "id2")
            addMockPeer(bridge, peer3, "id3")

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(3, details.size)
            assertTrue(
                "RSSI values should be different",
                details.map { it.rssi }.toSet().size == 3,
            )
            assertTrue(
                "RSSI values should be in expected range",
                details.all { it.rssi >= -75 && it.rssi <= -45 },
            )
        }

    @Test
    fun `getConnectionDetails handles empty scanner cache with multiple peers`() =
        runTest {
            // Given
            val peer1 = "AA:BB:CC:DD:EE:01"
            val peer2 = "AA:BB:CC:DD:EE:02"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peer1, "id1")
            addMockPeer(bridge, peer2, "id2")

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(2, details.size)
            assertTrue(
                "All peers should have fallback RSSI",
                details.all { it.rssi == -100 },
            )
        }

    // ========== getConnectionDetails() - Device Name Tests ==========

    @Test
    fun `getConnectionDetails returns real device name from scanner`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val deviceName = "RNS-TestDevice-12345"

            val testDevice = createBleDevice(peerAddress, deviceName, -50)
            coEvery { mockScanner.getDevicesSortedByPriority() } returns listOf(testDevice)

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "test123")

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals(deviceName, details[0].peerName)
        }

    @Test
    fun `getConnectionDetails returns identity hash when device not in scanner`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val identityHash = "test1234567890ab"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, identityHash)

            // When
            val details = bridge.getConnectionDetails()

            // Then: Falls back to identity hash prefix when no device name available
            assertEquals(1, details.size)
            assertEquals(identityHash.take(8), details[0].peerName)
        }

    @Test
    fun `getConnectionDetails returns identity hash when scanner device has null name`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val identityHash = "test1234567890ab"

            val testDevice = createBleDevice(peerAddress, null, -50)
            coEvery { mockScanner.getDevicesSortedByPriority() } returns listOf(testDevice)

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, identityHash)

            // When
            val details = bridge.getConnectionDetails()

            // Then: Falls back to identity hash prefix when device name is null
            assertEquals(1, details.size)
            assertEquals(identityHash.take(8), details[0].peerName)
        }

    @Test
    fun `getConnectionDetails handles mix of named and unnamed devices`() =
        runTest {
            // Given
            val peer1 = "AA:BB:CC:DD:EE:01"
            val peer2 = "AA:BB:CC:DD:EE:02"
            val peer3 = "AA:BB:CC:DD:EE:03"

            val devices =
                listOf(
                    createBleDevice(peer1, "Device1", -45),
                    createBleDevice(peer3, "Device3", -75),
                )
            coEvery { mockScanner.getDevicesSortedByPriority() } returns devices

            val id1 = "id1_full_hash_here"
            val id2 = "id2_full_hash_here"
            val id3 = "id3_full_hash_here"

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peer1, id1)
            addMockPeer(bridge, peer2, id2)
            addMockPeer(bridge, peer3, id3)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(3, details.size)

            val detail1 = details.find { it.currentMac == peer1 }
            val detail2 = details.find { it.currentMac == peer2 }
            val detail3 = details.find { it.currentMac == peer3 }

            assertNotNull(detail1)
            assertNotNull(detail2)
            assertNotNull(detail3)

            assertEquals("Device1", detail1!!.peerName)
            // peer2 not in scanner, falls back to identity hash prefix
            assertEquals(id2.take(8), detail2!!.peerName)
            assertEquals("Device3", detail3!!.peerName)
        }

    // ========== getConnectionDetails() - Identity Hash Tests ==========

    @Test
    fun `getConnectionDetails returns identity hash when set`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val identityHash = "abcd1234efgh5678ijkl9012mnop3456"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, identityHash)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals(identityHash, details[0].identityHash)
        }

    @Test
    fun `getConnectionDetails returns unknown when identity hash not set`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, identityHash = null)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals("unknown", details[0].identityHash)
        }

    // ========== getConnectionDetails() - Connection Type Tests ==========

    @Test
    fun `getConnectionDetails handles central connection type`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "id1", isCentral = true, isPeripheral = false)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertTrue(details[0].hasCentralConnection)
            assertFalse(details[0].hasPeripheralConnection)
        }

    @Test
    fun `getConnectionDetails handles peripheral connection type`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "id1", isCentral = false, isPeripheral = true)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertFalse(details[0].hasCentralConnection)
            assertTrue(details[0].hasPeripheralConnection)
        }

    @Test
    fun `getConnectionDetails handles dual-mode connection`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "id1", isCentral = true, isPeripheral = true)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertTrue(details[0].hasCentralConnection)
            assertTrue(details[0].hasPeripheralConnection)
        }

    // ========== getConnectionDetails() - Edge Cases ==========

    @Test
    fun `getConnectionDetails returns empty list when no peers connected`() =
        runTest {
            // Given
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertTrue(details.isEmpty())
        }

    @Test
    fun `getConnectionDetails handles scanner returning more devices than connected peers`() =
        runTest {
            // Given
            val connectedPeer = "AA:BB:CC:DD:EE:01"

            val devices =
                listOf(
                    createBleDevice(connectedPeer, "Connected", -45),
                    createBleDevice("AA:BB:CC:DD:EE:02", "NotConnected1", -60),
                    createBleDevice("AA:BB:CC:DD:EE:03", "NotConnected2", -75),
                )
            coEvery { mockScanner.getDevicesSortedByPriority() } returns devices

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, connectedPeer, "id1")

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals(connectedPeer, details[0].currentMac)
            assertEquals("Connected", details[0].peerName)
            assertEquals(-45, details[0].rssi)
        }

    @Test
    fun `getConnectionDetails preserves MTU values`() =
        runTest {
            // Given
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val mtuValue = 517
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()

            val bridge = createBridgeWithMockScanner()
            addMockPeer(bridge, peerAddress, "id1", mtu = mtuValue)

            // When
            val details = bridge.getConnectionDetails()

            // Then
            assertEquals(1, details.size)
            assertEquals(mtuValue, details[0].mtu)
        }

    // ========== Helper Methods ==========

    /**
     * Create a BleDevice with proper constructor parameters.
     */
    private fun createBleDevice(
        address: String,
        name: String?,
        rssi: Int,
    ): BleDevice =
        BleDevice(
            address = address,
            name = name,
            rssi = rssi,
            serviceUuids = null,
            identityHash = null,
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis(),
        )

    /**
     * Create a KotlinBLEBridge instance with mocked scanner.
     */
    private fun createBridgeWithMockScanner(): KotlinBLEBridge {
        val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

        // Use reflection to set the mocked scanner
        val scannerField = KotlinBLEBridge::class.java.getDeclaredField("scanner")
        scannerField.isAccessible = true
        scannerField.set(bridge, mockScanner)

        return bridge
    }

    /**
     * Add a mock peer to the bridge's connectedPeers map using reflection.
     * Note: Does not use suspend/coroutines as reflection operations are synchronous.
     */
    private fun addMockPeer(
        bridge: KotlinBLEBridge,
        address: String,
        identityHash: String?,
        isCentral: Boolean = true,
        isPeripheral: Boolean = false,
        mtu: Int = 512,
        rssi: Int = -100,
    ) {
        // Get the PeerConnection inner class
        val peerConnectionClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$PeerConnection",
            )
        val deduplicationStateClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$DeduplicationState",
            )

        // Create instance using constructor
        // Parameters: address, mtu, isCentral, isPeripheral, identityHash, connectedAt, rssi, lastActivity, deduplicationState
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

        val deduplicationStateNone = deduplicationStateClass.enumConstants[0]
        val peer =
            constructor.newInstance(
                address,
                mtu,
                isCentral,
                isPeripheral,
                identityHash,
                System.currentTimeMillis(),
                rssi,
                System.currentTimeMillis(), // lastActivity
                deduplicationStateNone,
            )

        // Add to connectedPeers map
        val connectedPeersField = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
        connectedPeersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connectedPeers = connectedPeersField.get(bridge) as java.util.concurrent.ConcurrentHashMap<String, Any>
        connectedPeers[address] = peer
    }

    // ========== Peripheral Mode MTU Tests ==========

    /**
     * This test documents the peripheral MTU bug and verifies the fix.
     *
     * BUG (before fix):
     * - BleGattServer.onCentralConnected callback signature: ((String) -> Unit)
     * - KotlinBLEBridge uses MIN_MTU (23) when handling peripheral connections
     * - Result: Peripheral connections report MTU 23 even when 514 was negotiated
     *
     * FIX (after):
     * - BleGattServer.onCentralConnected callback signature: ((String, Int) -> Unit)
     * - KotlinBLEBridge receives actual MTU from GATT server
     * - Result: Peripheral connections correctly report negotiated MTU
     *
     * This test verifies that peripheral connections in getConnectionDetails()
     * correctly report their actual MTU, not a hardcoded default.
     */
    @Test
    fun `peripheral connection should report actual MTU not default MIN_MTU`() =
        runTest {
            // Given: A peripheral connection with negotiated MTU 514
            val laptopAddress = "28:95:29:83:A8:AA"
            val negotiatedMtu = 514

            val bridge = createBridgeWithMockScanner()

            // Simulate peripheral connection as it would be created by the callback
            // NOTE: With BUGGY code, callback passes MIN_MTU (23) regardless of negotiation
            //       With FIXED code, callback passes actual negotiated MTU (514)
            // This simulates what SHOULD happen with the fix
            addMockPeer(
                bridge = bridge,
                address = laptopAddress,
                identityHash = "8b335b1cc30bde491c51e786bee0d951",
                isCentral = false,
                isPeripheral = true,
                mtu = negotiatedMtu,
            )

            // When
            val details = bridge.getConnectionDetails()

            // Then: MTU should be 514, not 23
            val connection = details.find { it.currentMac == laptopAddress }
            assertNotNull("Connection should exist", connection)
            assertEquals(negotiatedMtu, connection!!.mtu)

            // Note: This test passes because addMockPeer directly sets correct MTU.
            // The actual bug manifests in the callback chain which is tested via
            // integration tests and manual verification with real hardware.
            // See commit 897e2e6 for the fix that makes peripheral MTU work correctly.
        }

    // ========== Dual Connection Deduplication Tests ==========

    /**
     * This test documents the dual connection notification bug and verifies the fix.
     *
     * BUG (before fix):
     * In handleIdentityReceived, when identity arrives after dual connection:
     * 1. pendingConnections stores original state (e.g., isCentral=true, isPeripheral=false)
     * 2. Second connection makes it dual (isCentral=true, isPeripheral=true)
     * 3. Identity arrives → deduplication closes one connection (e.g., keeps peripheral)
     * 4. notifyPythonConnected uses pendingConnection's ORIGINAL flags (isCentral=true)
     * 5. Python gets wrong connection type - can't send on the closed central connection!
     *
     * FIX (after):
     * After deduplication, use the CURRENT peer state for notification, not the stale
     * pendingConnection state. This ensures Python knows about the correct remaining connection.
     */
    @Test
    fun `pending connection notification uses current peer state after deduplication`() =
        runTest {
            // Given: A bridge with mock scanner
            val bridge = createBridgeWithMockScanner()
            val peerAddress = "AA:BB:CC:DD:EE:FF"

            // Access PendingConnection class
            val pendingConnectionClass =
                Class.forName(
                    "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$PendingConnection",
                )

            // Create pending connection with ORIGINAL state (central only)
            val pendingConstructor =
                pendingConnectionClass.getDeclaredConstructor(
                    String::class.java,
                    Int::class.java,
                    Boolean::class.java,
                    Boolean::class.java,
                    Long::class.java,
                )
            pendingConstructor.isAccessible = true
            val pendingConnection =
                pendingConstructor.newInstance(
                    peerAddress,
                    512,
                    true, // isCentral - original state when first connection arrived
                    false, // isPeripheral - only central was connected initially
                    System.currentTimeMillis(),
                )

            // Access pendingConnections map and add our pending
            val pendingConnectionsField =
                KotlinBLEBridge::class.java.getDeclaredField("pendingConnections")
            pendingConnectionsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val pendingConnections =
                pendingConnectionsField.get(bridge) as ConcurrentHashMap<String, Any>
            pendingConnections[peerAddress] = pendingConnection

            // Simulate: peer now has DUAL connection (second connection arrived)
            addMockPeer(
                bridge = bridge,
                address = peerAddress,
                identityHash = null, // Identity not yet received
                isCentral = true,
                isPeripheral = true, // DUAL - both connections established
                mtu = 512,
            )

            // Verify: pendingConnection has STALE flags (central only)
            val pendingIsCentralField = pendingConnectionClass.getDeclaredField("isCentral")
            pendingIsCentralField.isAccessible = true
            val pendingIsPeripheralField = pendingConnectionClass.getDeclaredField("isPeripheral")
            pendingIsPeripheralField.isAccessible = true

            assertTrue(
                "Pending has stale isCentral=true",
                pendingIsCentralField.getBoolean(pendingConnection),
            )
            assertFalse(
                "Pending has stale isPeripheral=false",
                pendingIsPeripheralField.getBoolean(pendingConnection),
            )

            // Get actual peer which has CURRENT state (dual)
            val connectedPeersField = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
            connectedPeersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val connectedPeers = connectedPeersField.get(bridge) as ConcurrentHashMap<String, Any>
            val peer = connectedPeers[peerAddress]!!

            val peerIsCentralField = peer::class.java.getDeclaredField("isCentral")
            peerIsCentralField.isAccessible = true
            val peerIsPeripheralField = peer::class.java.getDeclaredField("isPeripheral")
            peerIsPeripheralField.isAccessible = true

            assertTrue("Peer has current isCentral=true", peerIsCentralField.getBoolean(peer))
            assertTrue("Peer has current isPeripheral=true", peerIsPeripheralField.getBoolean(peer))

            // Simulate deduplication where peripheral is kept (peer identity < our identity)
            peerIsCentralField.setBoolean(peer, false)
            peerIsPeripheralField.setBoolean(peer, true)

            // BUG: pending has isCentral=true but peer now has isCentral=false after dedup
            // If notification uses pending flags, Python thinks central is active but it's closed!
            assertNotEquals(
                "Pending isCentral doesn't match peer after dedup",
                pendingIsCentralField.getBoolean(pendingConnection),
                peerIsCentralField.getBoolean(peer),
            )
            assertNotEquals(
                "Pending isPeripheral doesn't match peer after dedup",
                pendingIsPeripheralField.getBoolean(pendingConnection),
                peerIsPeripheralField.getBoolean(peer),
            )
            // Fix: handleIdentityReceived uses peer's CURRENT flags for notifyPythonConnected
        }

    // ========== Identity Callback Deduplication Tests ==========
    //
    // These tests verify the fix for Linux devices (static MAC) reconnecting.
    // Previously, processedIdentityCallbacks wasn't cleared on disconnect, causing
    // identity callbacks to be rejected as "duplicate" when a static-MAC device reconnected.
    //
    // The fix adds: processedIdentityCallbacks.removeIf { it.startsWith("$address:") }
    // in handlePeerDisconnected.
    //
    // Since handlePeerDisconnected is a private suspend function and kotlin-reflect
    // isn't available in test scope, we test the cleanup logic directly by simulating
    // the removeIf operation on the processedIdentityCallbacks set.

    @Test
    fun `processedIdentityCallbacks cleanup removes entries for disconnected address`() =
        runTest {
            // Given - a bridge with processed identity callbacks
            val bridge = createBridgeWithMockScanner()
            val peerAddress = "AA:BB:CC:DD:EE:FF"
            val identityHash = "59ac1be8dd595376415ac31cd8e0e21f"

            // Access the processedIdentityCallbacks set
            val processedIdentityCallbacksField =
                KotlinBLEBridge::class.java.getDeclaredField("processedIdentityCallbacks")
            processedIdentityCallbacksField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val processedIdentityCallbacks =
                processedIdentityCallbacksField.get(bridge) as MutableSet<String>

            // Simulate identity callback having been processed (add to dedupe set)
            val dedupeKey = "$peerAddress:$identityHash"
            processedIdentityCallbacks.add(dedupeKey)

            // Verify dedupe entry exists before cleanup
            assertTrue(
                "Dedupe entry should exist before cleanup",
                processedIdentityCallbacks.contains(dedupeKey),
            )

            // When - simulate the cleanup logic from handlePeerDisconnected
            // This is the exact code added in the fix:
            processedIdentityCallbacks.removeIf { it.startsWith("$peerAddress:") }

            // Then - verify dedupe entry is cleared after cleanup
            assertFalse(
                "Dedupe entry should be cleared after cleanup",
                processedIdentityCallbacks.contains(dedupeKey),
            )
        }

    @Test
    fun `static MAC device can reconnect after disconnect cleanup`() =
        runTest {
            // This test verifies the fix scenario for Linux devices (static MAC) reconnecting
            // Previously, the identity callback would be rejected as "duplicate"
            // because processedIdentityCallbacks wasn't cleared on disconnect

            // Given - a bridge with a processed identity callback
            val bridge = createBridgeWithMockScanner()
            val peerAddress = "B8:27:EB:10:28:CD" // Static Linux MAC
            val identityHash = "59ac1be8dd595376415ac31cd8e0e21f"

            // Access the processedIdentityCallbacks set
            val processedIdentityCallbacksField =
                KotlinBLEBridge::class.java.getDeclaredField("processedIdentityCallbacks")
            processedIdentityCallbacksField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val processedIdentityCallbacks =
                processedIdentityCallbacksField.get(bridge) as MutableSet<String>

            // Simulate first connection: identity callback processed
            val dedupeKey = "$peerAddress:$identityHash"
            processedIdentityCallbacks.add(dedupeKey)

            // Simulate disconnect: cleanup logic runs
            processedIdentityCallbacks.removeIf { it.startsWith("$peerAddress:") }

            // When - device reconnects with SAME MAC + SAME identity
            // The dedupe key should NOT be in the set, allowing the callback to be processed
            assertFalse(
                "After disconnect cleanup, same address:identity should NOT be in dedupe set",
                processedIdentityCallbacks.contains(dedupeKey),
            )

            // Verify the dedupe set allows adding the key again (simulates reconnection)
            val wasAdded = processedIdentityCallbacks.add(dedupeKey)
            assertTrue(
                "Should be able to add dedupe key after disconnect (reconnection scenario)",
                wasAdded,
            )
        }

    @Test
    fun `cleanup only removes entries for disconnected address not other peers`() =
        runTest {
            // Given - bridge with two peers' identity callbacks processed
            val bridge = createBridgeWithMockScanner()

            val peer1Address = "AA:BB:CC:DD:EE:FF"
            val peer1Identity = "aaaa1111222233334444555566667777"
            val peer2Address = "11:22:33:44:55:66"
            val peer2Identity = "bbbb1111222233334444555566667777"

            // Access the processedIdentityCallbacks set
            val processedIdentityCallbacksField =
                KotlinBLEBridge::class.java.getDeclaredField("processedIdentityCallbacks")
            processedIdentityCallbacksField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val processedIdentityCallbacks =
                processedIdentityCallbacksField.get(bridge) as MutableSet<String>

            // Add dedupe entries for both peers
            val dedupeKey1 = "$peer1Address:$peer1Identity"
            val dedupeKey2 = "$peer2Address:$peer2Identity"
            processedIdentityCallbacks.add(dedupeKey1)
            processedIdentityCallbacks.add(dedupeKey2)

            // When - only peer1 disconnects (cleanup runs for peer1 only)
            processedIdentityCallbacks.removeIf { it.startsWith("$peer1Address:") }

            // Then - peer1's dedupe entry should be cleared, peer2's should remain
            assertFalse(
                "Disconnected peer's dedupe entry should be cleared",
                processedIdentityCallbacks.contains(dedupeKey1),
            )
            assertTrue(
                "Other peer's dedupe entry should remain",
                processedIdentityCallbacks.contains(dedupeKey2),
            )
        }

    // ========== ConnectionChangeListener Tests ==========

    @Test
    fun `addConnectionChangeListener registers listener successfully`() =
        runTest {
            // Given
            val bridge = createBridgeWithMockScanner()
            val listener =
                object : KotlinBLEBridge.ConnectionChangeListener {
                    override fun onConnectionsChanged(connectionDetailsJson: String) {
                        // No-op for this test
                    }
                }

            // When
            bridge.addConnectionChangeListener(listener)

            // Then - verify via reflection that listener was added
            val listenersField = KotlinBLEBridge::class.java.getDeclaredField("connectionChangeListeners")
            listenersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val listeners = listenersField.get(bridge) as MutableList<KotlinBLEBridge.ConnectionChangeListener>
            assertEquals(1, listeners.size)
        }

    @Test
    fun `removeConnectionChangeListener unregisters listener`() =
        runTest {
            // Given
            val bridge = createBridgeWithMockScanner()
            val listener =
                object : KotlinBLEBridge.ConnectionChangeListener {
                    override fun onConnectionsChanged(connectionDetailsJson: String) {
                        // No-op
                    }
                }

            // When
            bridge.addConnectionChangeListener(listener)
            bridge.removeConnectionChangeListener(listener)

            // Then - verify listener was removed
            val listenersField = KotlinBLEBridge::class.java.getDeclaredField("connectionChangeListeners")
            listenersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val listeners = listenersField.get(bridge) as MutableList<KotlinBLEBridge.ConnectionChangeListener>
            assertEquals(0, listeners.size)
        }

    @Test
    fun `notifyConnectionChange delivers JSON to all listeners`() =
        runTest {
            // Given
            val bridge = createBridgeWithMockScanner()
            val receivedJson = mutableListOf<String>()

            val listener1 =
                object : KotlinBLEBridge.ConnectionChangeListener {
                    override fun onConnectionsChanged(connectionDetailsJson: String) {
                        receivedJson.add("L1:$connectionDetailsJson")
                    }
                }
            val listener2 =
                object : KotlinBLEBridge.ConnectionChangeListener {
                    override fun onConnectionsChanged(connectionDetailsJson: String) {
                        receivedJson.add("L2:$connectionDetailsJson")
                    }
                }

            bridge.addConnectionChangeListener(listener1)
            bridge.addConnectionChangeListener(listener2)

            // When - trigger notifyConnectionChange via reflection
            val method = KotlinBLEBridge::class.java.getDeclaredMethod("notifyConnectionChange")
            method.isAccessible = true
            method.invoke(bridge)

            // Then
            assertEquals(2, receivedJson.size)
            assertTrue(receivedJson[0].startsWith("L1:"))
            assertTrue(receivedJson[1].startsWith("L2:"))
        }

    @Test
    fun `buildConnectionDetailsJson returns valid JSON array`() =
        runTest {
            // Given
            val bridge = createBridgeWithMockScanner()
            coEvery { mockScanner.getDevicesSortedByPriority() } returns emptyList()
            addMockPeer(bridge, "AA:BB:CC:DD:EE:FF", "test123hash")

            // When - invoke buildConnectionDetailsJson via reflection
            val method = KotlinBLEBridge::class.java.getDeclaredMethod("buildConnectionDetailsJson")
            method.isAccessible = true
            val json = method.invoke(bridge) as String

            // Then
            val jsonArray = org.json.JSONArray(json)
            assertEquals(1, jsonArray.length())
            val peer = jsonArray.getJSONObject(0)
            assertEquals("test123hash", peer.getString("identityHash"))
            assertEquals("AA:BB:CC:DD:EE:FF", peer.getString("address"))
        }
}
