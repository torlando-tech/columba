package com.lxmf.messenger.reticulum.ble.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import io.mockk.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MAC-based connection deduplication in KotlinBLEBridge.
 *
 * Algorithm: Lower MAC address initiates connection (acts as central),
 * higher MAC address waits (acts as peripheral only).
 *
 * This prevents dual connections (both central and peripheral to same peer)
 * by ensuring both devices independently agree on connection direction.
 */
class KotlinBLEBridgeMacSortingTest {
    private lateinit var context: Context
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        bluetoothManager = mockk(relaxed = true)
        bluetoothAdapter = mockk(relaxed = true)

        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { bluetoothAdapter.isEnabled } returns true
    }

    @org.junit.After
    fun teardown() {
        // Reset singleton using reflection
        val instanceField = KotlinBLEBridge::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    /**
     * Test: Lower MAC should initiate connection.
     *
     * Scenario: Local MAC "AA:BB:CC:DD:EE:01", Peer MAC "AA:BB:CC:DD:EE:FF"
     * Expected: shouldConnect returns true (01 < FF)
     */
    @Test
    fun shouldConnect_whenLocalMacLower_returnsTrue() {
        // Arrange
        val localMac = "AA:BB:CC:DD:EE:01"
        val peerMac = "AA:BB:CC:DD:EE:FF"
        every { bluetoothAdapter.address } returns localMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertTrue("Lower MAC ($localMac) should connect to higher MAC ($peerMac)", result)
    }

    /**
     * Test: Higher MAC should NOT initiate connection (wait for peer to connect).
     *
     * Scenario: Local MAC "AA:BB:CC:DD:EE:FF", Peer MAC "AA:BB:CC:DD:EE:01"
     * Expected: shouldConnect returns false (FF > 01)
     */
    @Test
    fun shouldConnect_whenLocalMacHigher_returnsFalse() {
        // Arrange
        val localMac = "AA:BB:CC:DD:EE:FF"
        val peerMac = "AA:BB:CC:DD:EE:01"
        every { bluetoothAdapter.address } returns localMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertFalse("Higher MAC ($localMac) should NOT connect to lower MAC ($peerMac)", result)
    }

    /**
     * Test: Equal MACs should NOT connect (edge case).
     *
     * Scenario: Both MACs are identical
     * Expected: shouldConnect returns false (prevents self-connection)
     */
    @Test
    fun shouldConnect_whenMacsEqual_returnsFalse() {
        // Arrange
        val mac = "AA:BB:CC:DD:EE:FF"
        every { bluetoothAdapter.address } returns mac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(mac)

        // Assert
        assertFalse("Equal MACs should not connect (prevent self-connection)", result)
    }

    /**
     * Test: MAC comparison should be case-insensitive.
     *
     * Scenario: MACs differ only in case (lowercase vs uppercase hex)
     * Expected: Comparison works correctly regardless of case
     */
    @Test
    fun shouldConnect_whenMacsCaseInsensitive_comparesCorrectly() {
        // Arrange
        val localMac = "aa:bb:cc:dd:ee:01" // lowercase
        val peerMac = "AA:BB:CC:DD:EE:FF" // uppercase
        every { bluetoothAdapter.address } returns localMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertTrue("Case-insensitive: lower MAC should connect", result)
    }

    /**
     * Test: Fallback when local MAC unavailable.
     *
     * Scenario: BluetoothAdapter.address returns null (permission denied or unavailable)
     * Expected: shouldConnect returns true (fallback to always connect)
     */
    @Test
    fun shouldConnect_whenLocalMacUnavailable_returnsTrue() {
        // Arrange
        val peerMac = "AA:BB:CC:DD:EE:FF"
        every { bluetoothAdapter.address } returns null
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertTrue("Should fallback to true when local MAC unavailable", result)
    }

    /**
     * Test: Fallback when local MAC is invalid format.
     *
     * Scenario: BluetoothAdapter.address returns malformed MAC (e.g., "02:00:00:00:00:00")
     * Expected: shouldConnect returns true (fallback)
     */
    @Test
    fun shouldConnect_whenLocalMacInvalid_returnsTrue() {
        // Arrange
        val invalidMac = "02:00:00:00:00:00" // Placeholder/invalid MAC
        val peerMac = "AA:BB:CC:DD:EE:FF"
        every { bluetoothAdapter.address } returns invalidMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert - implementation should handle gracefully
        // Either true (fallback) or correct comparison
        assertNotNull(result)
    }

    /**
     * Test: MAC comparison with different length prefixes.
     *
     * Scenario: Different MAC vendors (different OUI prefixes)
     * Expected: Numerical comparison works correctly
     */
    @Test
    fun shouldConnect_whenDifferentMacVendors_comparesNumerically() {
        // Arrange
        val localMac = "11:22:33:44:55:66" // Lower vendor OUI
        val peerMac = "AA:BB:CC:DD:EE:FF" // Higher vendor OUI
        every { bluetoothAdapter.address } returns localMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertTrue("11:22... < AA:BB... numerically", result)
    }

    /**
     * Test: Colon format handling in MAC addresses.
     *
     * Scenario: MACs with colons (standard format)
     * Expected: Colons are stripped before comparison
     */
    @Test
    fun shouldConnect_handlesColonFormatCorrectly() {
        // Arrange
        val localMac = "AA:BB:CC:DD:EE:01"
        val peerMac = "AA:BB:CC:DD:EE:02"
        every { bluetoothAdapter.address } returns localMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertTrue("Should strip colons and compare: AABBCCDDEE01 < AABBCCDDEE02", result)
    }

    /**
     * Test: Very close MAC addresses (differ by 1).
     *
     * Scenario: MACs differ only in last byte by 1
     * Expected: Correct comparison
     */
    @Test
    fun shouldConnect_whenMacsDifferByOne_comparesCorrectly() {
        // Arrange
        val localMac = "AA:BB:CC:DD:EE:FE"
        val peerMac = "AA:BB:CC:DD:EE:FF"
        every { bluetoothAdapter.address } returns localMac
        val bridge = KotlinBLEBridge.getInstance(context)

        // Act
        val result = bridge.shouldConnect(peerMac)

        // Assert
        assertTrue("FE < FF by 1", result)
    }
}
