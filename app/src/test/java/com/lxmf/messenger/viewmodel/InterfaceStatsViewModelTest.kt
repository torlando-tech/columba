package com.lxmf.messenger.viewmodel

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for InterfaceStatsViewModel.
 * Tests interface loading, stats polling, and state management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceStatsViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var context: Context
    private lateinit var usbManager: UsbManager

    private val testRNodeEntity = InterfaceEntity(
        id = 1L,
        name = "Test RNode",
        type = "RNode",
        enabled = true,
        configJson = """{"connection_mode":"classic","target_device_name":"RNode A1B2","frequency":915000000,"bandwidth":125000,"spreading_factor":7,"tx_power":17,"coding_rate":5,"mode":"full"}""",
        displayOrder = 0,
    )

    private val testTcpClientEntity = InterfaceEntity(
        id = 2L,
        name = "Test TCP Client",
        type = "TCPClient",
        enabled = true,
        configJson = """{"target_host":"192.168.1.100","target_port":4242,"mode":"full"}""",
        displayOrder = 1,
    )

    private val testUsbRNodeEntity = InterfaceEntity(
        id = 3L,
        name = "Test USB RNode",
        type = "RNode",
        enabled = true,
        configJson = """{"connection_mode":"usb","usb_device_id":123,"frequency":915000000,"bandwidth":125000,"spreading_factor":7,"tx_power":17,"coding_rate":5,"mode":"full"}""",
        displayOrder = 2,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        interfaceRepository = mockk(relaxed = true)
        reticulumProtocol = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        usbManager = mockk(relaxed = true)

        every { context.getSystemService(Context.USB_SERVICE) } returns usbManager
        every { usbManager.deviceList } returns HashMap()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(interfaceId: Long): InterfaceStatsViewModel {
        savedStateHandle = SavedStateHandle(mapOf("interfaceId" to interfaceId))
        return InterfaceStatsViewModel(
            savedStateHandle = savedStateHandle,
            interfaceRepository = interfaceRepository,
            reticulumProtocol = reticulumProtocol,
            configManager = configManager,
            context = context,
        )
    }

    // ========== Initialization Tests ==========

    @Test
    fun `initial state has isLoading true`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity

        val viewModel = createViewModel(1L)

        // Initial state before any processing
        assertTrue(viewModel.state.value.isLoading)
    }

    @Test
    fun `invalid interface ID sets error message`() = runTest {
        val viewModel = createViewModel(-1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Invalid interface ID", state.errorMessage)
        }
    }

    // ========== Interface Loading Tests ==========

    @Test
    fun `loadInterface sets interface entity on success`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
            assertEquals(testRNodeEntity, state.interfaceEntity)
        }
    }

    @Test
    fun `loadInterface sets error when interface not found`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(99L) } returns null
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(99L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals("Interface not found", state.errorMessage)
        }
    }

    @Test
    fun `loadInterface handles exception`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } throws RuntimeException("Database error")

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertTrue(state.errorMessage?.contains("Database error") == true)
        }
    }

    // ========== Config Parsing Tests ==========

    @Test
    fun `RNode config is parsed correctly`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("classic", state.connectionMode)
            assertEquals("RNode A1B2", state.targetDeviceName)
            assertEquals(915000000L, state.frequency)
            assertEquals(125000, state.bandwidth)
            assertEquals(7, state.spreadingFactor)
            assertEquals(17, state.txPower)
            assertEquals(5, state.codingRate)
            assertEquals("full", state.interfaceMode)
        }
    }

    @Test
    fun `TCPClient config is parsed correctly`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(2L) } returns testTcpClientEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(2L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("192.168.1.100", state.tcpHost)
            assertEquals(4242, state.tcpPort)
            assertEquals("full", state.interfaceMode)
        }
    }

    @Test
    fun `USB RNode config parses usb_device_id`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(3L) } returns testUsbRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(3L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("usb", state.connectionMode)
            assertEquals(123, state.usbDeviceId)
        }
    }

    @Test
    fun `invalid JSON returns empty parsed config`() = runTest {
        val invalidEntity = testRNodeEntity.copy(configJson = "not valid json")
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns invalidEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            // Should have defaults/nulls but not crash
            assertNull(state.frequency)
            assertNull(state.bandwidth)
        }
    }

    // ========== Stats Refresh Tests ==========

    @Test
    fun `refreshStats updates online status`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats("Test RNode") } returns mapOf(
            "online" to true,
            "rxb" to 1000L,
            "txb" to 500L,
        )

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isOnline)
            assertEquals(1000L, state.rxBytes)
            assertEquals(500L, state.txBytes)
        }
    }

    @Test
    fun `refreshStats updates RSSI for online RNode`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats("Test RNode") } returns mapOf(
            "online" to true,
            "rxb" to 0L,
            "txb" to 0L,
        )
        every { configManager.getRNodeRssi() } returns -75

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(-75, state.rssi)
        }
    }

    @Test
    fun `refreshStats does not set RSSI if value is too low`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats("Test RNode") } returns mapOf(
            "online" to true,
            "rxb" to 0L,
            "txb" to 0L,
        )
        every { configManager.getRNodeRssi() } returns -150 // Below -100 threshold

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.rssi)
        }
    }

    @Test
    fun `refreshStats handles null interface stats`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isOnline)
            assertEquals(0L, state.rxBytes)
            assertEquals(0L, state.txBytes)
        }
    }

    // ========== Toggle Enabled Tests ==========

    @Test
    fun `toggleEnabled updates interface enabled state`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null
        coEvery { interfaceRepository.toggleInterfaceEnabled(any(), any()) } returns Unit

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        // Verify initial state
        assertTrue(viewModel.state.value.interfaceEntity?.enabled == true)

        // Toggle to disabled
        viewModel.toggleEnabled()
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        coVerify { interfaceRepository.toggleInterfaceEnabled(1L, false) }

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.interfaceEntity?.enabled == true)
        }
    }

    @Test
    fun `toggleEnabled does nothing if no interface entity`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(99L) } returns null
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(99L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.toggleEnabled()
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        // Should not call repository
        coVerify(exactly = 0) { interfaceRepository.toggleInterfaceEnabled(any(), any()) }
    }

    // ========== Signal Reconnecting Tests ==========

    @Test
    fun `signalReconnecting resets connecting state`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.signalReconnecting()

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isConnecting)
        }
    }

    // ========== USB Permission Tests ==========

    @Test
    fun `needsUsbPermission is false when not in USB mode`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns mapOf(
            "online" to false,
            "rxb" to 0L,
            "txb" to 0L,
        )

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.needsUsbPermission)
        }
    }

    @Test
    fun `needsUsbPermission is true when USB device lacks permission`() = runTest {
        val mockDevice = mockk<UsbDevice>()
        every { mockDevice.deviceId } returns 123
        every { usbManager.deviceList } returns hashMapOf("device" to mockDevice)
        every { usbManager.hasPermission(mockDevice) } returns false

        coEvery { interfaceRepository.getInterfaceByIdOnce(3L) } returns testUsbRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns mapOf(
            "online" to false,
            "rxb" to 0L,
            "txb" to 0L,
        )

        val viewModel = createViewModel(3L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.needsUsbPermission)
        }
    }

    @Test
    fun `needsUsbPermission is false when device not found`() = runTest {
        every { usbManager.deviceList } returns HashMap()

        coEvery { interfaceRepository.getInterfaceByIdOnce(3L) } returns testUsbRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns mapOf(
            "online" to false,
            "rxb" to 0L,
            "txb" to 0L,
        )

        val viewModel = createViewModel(3L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.needsUsbPermission)
        }
    }

    @Test
    fun `requestUsbPermission does nothing without usbDeviceId`() = runTest {
        val entityWithoutUsbId = testUsbRNodeEntity.copy(
            configJson = """{"connection_mode":"usb","frequency":915000000}"""
        )
        coEvery { interfaceRepository.getInterfaceByIdOnce(3L) } returns entityWithoutUsbId
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(3L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.requestUsbPermission()
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        // Should not interact with USB manager for permission
        io.mockk.verify(exactly = 0) { usbManager.requestPermission(any<UsbDevice>(), any()) }
    }

    // ========== TCPServer Config Parsing Test ==========

    @Test
    fun `TCPServer config is parsed correctly`() = runTest {
        val tcpServerEntity = InterfaceEntity(
            id = 4L,
            name = "Test TCP Server",
            type = "TCPServer",
            enabled = true,
            configJson = """{"listen_ip":"0.0.0.0","listen_port":5000,"mode":"gateway"}""",
            displayOrder = 3,
        )
        coEvery { interfaceRepository.getInterfaceByIdOnce(4L) } returns tcpServerEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(4L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("0.0.0.0", state.tcpHost)
            assertEquals(5000, state.tcpPort)
            assertEquals("gateway", state.interfaceMode)
        }
    }

    // ========== Unknown Interface Type Test ==========

    @Test
    fun `unknown interface type returns default mode`() = runTest {
        val unknownEntity = InterfaceEntity(
            id = 5L,
            name = "Unknown Interface",
            type = "SomeNewType",
            enabled = true,
            configJson = """{"mode":"roaming"}""",
            displayOrder = 4,
        )
        coEvery { interfaceRepository.getInterfaceByIdOnce(5L) } returns unknownEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns null

        val viewModel = createViewModel(5L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals("roaming", state.interfaceMode)
        }
    }

    // ========== Connecting State Tests ==========

    @Test
    fun `isConnecting is true for enabled offline interface that was never online`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns mapOf(
            "online" to false,
            "rxb" to 0L,
            "txb" to 0L,
        )

        val viewModel = createViewModel(1L)
        // First refresh happens during init, but timeout hasn't passed
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            // Initially shows connecting since interface was never online
            assertTrue(state.isConnecting)
        }
    }

    @Test
    fun `isConnecting is false for disabled interface`() = runTest {
        val disabledEntity = testRNodeEntity.copy(enabled = false)
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns disabledEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns mapOf(
            "online" to false,
            "rxb" to 0L,
            "txb" to 0L,
        )

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isConnecting)
        }
    }

    @Test
    fun `isConnecting is false for online interface`() = runTest {
        coEvery { interfaceRepository.getInterfaceByIdOnce(1L) } returns testRNodeEntity
        coEvery { reticulumProtocol.getInterfaceStats(any()) } returns mapOf(
            "online" to true,
            "rxb" to 0L,
            "txb" to 0L,
        )

        val viewModel = createViewModel(1L)
        advanceTimeBy(1100) // Allow one poll cycle (1000ms + buffer)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isConnecting)
        }
    }
}
