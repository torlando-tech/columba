package network.columba.app.service.manager

import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import network.columba.app.reticulum.ble.bridge.KotlinBLEBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BleCoordinator.
 */
class BleCoordinatorTest {
    private lateinit var mockContext: Context
    private lateinit var mockBridge: KotlinBLEBridge
    private lateinit var coordinator: BleCoordinator

    @Before
    fun setup() {
        mockContext = mockk()
        mockBridge = mockk()

        mockkObject(KotlinBLEBridge.Companion)
        every { KotlinBLEBridge.getInstance(any()) } returns mockBridge

        every { mockBridge.getConnectionDetailsSync() } returns emptyList()

        coordinator = BleCoordinator(mockContext)
    }

    @After
    fun tearDown() {
        unmockkObject(KotlinBLEBridge.Companion)
        clearAllMocks()
    }

    @Test
    fun `cleanup completes without throwing`() {
        val result = runCatching { coordinator.cleanup() }
        assertTrue("cleanup should complete without throwing", result.isSuccess)
    }

    @Test
    fun `getConnectionDetailsJson returns empty array for no connections`() {
        every { mockBridge.getConnectionDetailsSync() } returns emptyList()

        val result = coordinator.getConnectionDetailsJson()

        assertEquals("[]", result)
    }

    @Test
    fun `getConnectionDetailsJson returns valid JSON with connections`() {
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

        val result = coordinator.getConnectionDetailsJson()

        assert(result.contains("abc123"))
        assert(result.contains("TestPeer"))
        assert(result.contains("AA:BB:CC:DD:EE:FF"))
        assert(result.contains("hasCentralConnection"))
        assert(result.contains("hasPeripheralConnection"))
    }

    @Test
    fun `getConnectionDetailsJson returns empty array on exception`() {
        every { mockBridge.getConnectionDetailsSync() } throws RuntimeException("Test error")

        val result = coordinator.getConnectionDetailsJson()

        assertEquals("[]", result)
    }

    @Test
    fun `getBridge returns bridge instance`() {
        val bridge = coordinator.getBridge()

        assertEquals(mockBridge, bridge)
    }
}
