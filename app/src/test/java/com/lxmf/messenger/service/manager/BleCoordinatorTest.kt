package com.lxmf.messenger.service.manager

import android.content.Context
import com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BleCoordinator.
 *
 * Tests the event-driven callback integration between KotlinBLEBridge
 * and CallbackBroadcaster.
 */
class BleCoordinatorTest {
    private lateinit var mockContext: Context
    private lateinit var mockBridge: KotlinBLEBridge
    private lateinit var mockBroadcaster: CallbackBroadcaster
    private lateinit var coordinator: BleCoordinator

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockBridge = mockk(relaxed = true)
        mockBroadcaster = mockk(relaxed = true)

        // Mock the singleton's getInstance method
        mockkObject(KotlinBLEBridge.Companion)
        every { KotlinBLEBridge.getInstance(any()) } returns mockBridge

        // Default mock behaviors
        every { mockBridge.addConnectionChangeListener(any()) } just Runs
        every { mockBridge.removeConnectionChangeListener(any()) } just Runs
        every { mockBridge.getConnectionDetailsSync() } returns emptyList()

        coordinator = BleCoordinator(mockContext)
    }

    @After
    fun tearDown() {
        unmockkObject(KotlinBLEBridge.Companion)
        clearAllMocks()
    }

    @Test
    fun `setCallbackBroadcaster registers listener with bridge`() {
        // When
        coordinator.setCallbackBroadcaster(mockBroadcaster)

        // Then
        verify { mockBridge.addConnectionChangeListener(any()) }
    }

    @Test
    fun `cleanup removes listener and nulls broadcaster`() {
        // Given
        coordinator.setCallbackBroadcaster(mockBroadcaster)

        // When
        coordinator.cleanup()

        // Then
        verify { mockBridge.removeConnectionChangeListener(any()) }
    }

    @Test
    fun `connectionChangeListener broadcasts to callback`() {
        // Given - capture the listener when setCallbackBroadcaster is called
        val listenerSlot = slot<KotlinBLEBridge.ConnectionChangeListener>()
        every { mockBridge.addConnectionChangeListener(capture(listenerSlot)) } just Runs

        coordinator.setCallbackBroadcaster(mockBroadcaster)

        val testJson = """[{"identityHash":"abc123"}]"""

        // When - trigger the listener callback
        listenerSlot.captured.onConnectionsChanged(testJson)

        // Then
        verify { mockBroadcaster.broadcastBleConnectionChange(testJson) }
    }

    @Test
    fun `connectionChangeListener handles null broadcaster safely`() {
        // Given - capture the listener when setCallbackBroadcaster is called
        val listenerSlot = slot<KotlinBLEBridge.ConnectionChangeListener>()
        every { mockBridge.addConnectionChangeListener(capture(listenerSlot)) } just Runs

        coordinator.setCallbackBroadcaster(mockBroadcaster)
        coordinator.cleanup() // Nulls the broadcaster

        // When - trigger the listener callback after cleanup
        // Should not crash
        listenerSlot.captured.onConnectionsChanged("""[{"test":"data"}]""")

        // Then - no exception thrown (implicit assertion)
    }

    @Test
    fun `getConnectionDetailsJson returns empty array for no connections`() {
        // Given
        every { mockBridge.getConnectionDetailsSync() } returns emptyList()

        // When
        val result = coordinator.getConnectionDetailsJson()

        // Then
        assertEquals("[]", result)
    }

    @Test
    fun `getConnectionDetailsJson returns valid JSON with connections`() {
        // Given
        val details =
            listOf(
                KotlinBLEBridge.BleConnectionDetails(
                    identityHash = "abc123",
                    peerName = "TestPeer",
                    currentMac = "AA:BB:CC:DD:EE:FF",
                    hasCentralConnection = true,
                    hasPeripheralConnection = false,
                    mtu = 512,
                    connectedAt = 1000L,
                    firstSeen = 900L,
                    lastSeen = 1100L,
                    rssi = -50,
                ),
            )
        every { mockBridge.getConnectionDetailsSync() } returns details

        // When
        val result = coordinator.getConnectionDetailsJson()

        // Then - verify it's valid JSON with expected fields
        assert(result.contains("abc123"))
        assert(result.contains("TestPeer"))
        assert(result.contains("AA:BB:CC:DD:EE:FF"))
        assert(result.contains("hasCentralConnection"))
        assert(result.contains("hasPeripheralConnection"))
    }

    @Test
    fun `getConnectionDetailsJson returns empty array on exception`() {
        // Given
        every { mockBridge.getConnectionDetailsSync() } throws RuntimeException("Test error")

        // When
        val result = coordinator.getConnectionDetailsJson()

        // Then
        assertEquals("[]", result)
    }

    @Test
    fun `getBridge returns bridge instance`() {
        // When
        val bridge = coordinator.getBridge()

        // Then
        assertEquals(mockBridge, bridge)
    }
}
