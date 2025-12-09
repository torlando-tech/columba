package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.repository.BleStatusRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for InterfaceManagementViewModel interface status event handling.
 * Tests the observeInterfaceStatusChanges() functionality and polling behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceManagementViewModelStatusEventTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var bleStatusRepository: BleStatusRepository
    private lateinit var serviceProtocol: ServiceReticulumProtocol
    private lateinit var interfaceStatusFlow: MutableSharedFlow<Unit>
    private lateinit var viewModel: InterfaceManagementViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // CRITICAL: Disable polling to prevent OOM in tests
        InterfaceManagementViewModel.STATUS_POLL_INTERVAL_MS = 0

        interfaceRepository = mockk()
        configManager = mockk()
        bleStatusRepository = mockk()
        serviceProtocol = mockk()

        // Mock repository flows
        every { interfaceRepository.allInterfaceEntities } returns flowOf(emptyList())
        every { interfaceRepository.enabledInterfaceCount } returns flowOf(0)
        every { interfaceRepository.totalInterfaceCount } returns flowOf(0)

        // Mock BleStatusRepository
        every { bleStatusRepository.getConnectedPeersFlow() } returns
            flowOf(BleConnectionsState.Success(emptyList()))

        // Mock InterfaceConfigManager
        every { configManager.checkAndClearPendingChanges() } returns false

        // Mock interfaceStatusChanged flow for ServiceReticulumProtocol
        interfaceStatusFlow = MutableSharedFlow(replay = 0, extraBufferCapacity = 1)
        every { serviceProtocol.interfaceStatusChanged } returns interfaceStatusFlow

        // Mock getDebugInfo for status polling
        coEvery { serviceProtocol.getDebugInfo() } returns mapOf(
            "interfaces" to listOf(
                mapOf("name" to "ble0", "online" to true),
            ),
        )
    }

    @After
    fun tearDown() {
        // CRITICAL: Cancel all ViewModel coroutines to prevent UncompletedCoroutinesError
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
        clearAllMocks()
        // Reset polling interval to default
        InterfaceManagementViewModel.STATUS_POLL_INTERVAL_MS = 3000L
    }

    @Test
    fun `ViewModel initializes without error with ServiceReticulumProtocol`() = runTest {
        viewModel = InterfaceManagementViewModel(
            interfaceRepository,
            configManager,
            bleStatusRepository,
            serviceProtocol,
        )

        advanceUntilIdle()

        assertNotNull(viewModel)
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `ViewModel observes ServiceReticulumProtocol interfaceStatusChanged flow`() = runTest {
        viewModel = InterfaceManagementViewModel(
            interfaceRepository,
            configManager,
            bleStatusRepository,
            serviceProtocol,
        )

        advanceUntilIdle()

        // Verify interfaceStatusChanged was accessed
        verify { serviceProtocol.interfaceStatusChanged }
    }

    @Test
    fun `interface status event triggers immediate debug info fetch`() = runTest {
        viewModel = InterfaceManagementViewModel(
            interfaceRepository,
            configManager,
            bleStatusRepository,
            serviceProtocol,
        )

        advanceUntilIdle()

        // Reset mock to track new calls
        coEvery { serviceProtocol.getDebugInfo() } returns mapOf(
            "interfaces" to listOf(
                mapOf("name" to "ble0", "online" to false),
            ),
        )

        // Emit interface status event
        interfaceStatusFlow.emit(Unit)
        advanceUntilIdle()

        // Verify getDebugInfo was called after event
        io.mockk.coVerify(atLeast = 1) { serviceProtocol.getDebugInfo() }
    }

    @Test
    fun `interface online status is updated in state after event`() = runTest {
        viewModel = InterfaceManagementViewModel(
            interfaceRepository,
            configManager,
            bleStatusRepository,
            serviceProtocol,
        )

        advanceUntilIdle()

        // Since polling is disabled in tests, initial state has empty status map
        // Emit first event to populate initial status
        interfaceStatusFlow.emit(Unit)
        advanceUntilIdle()

        viewModel.state.test {
            val initialState = awaitItem()
            assertEquals(true, initialState.interfaceOnlineStatus["ble0"])

            // Update mock to return different status
            coEvery { serviceProtocol.getDebugInfo() } returns mapOf(
                "interfaces" to listOf(
                    mapOf("name" to "ble0", "online" to false),
                    mapOf("name" to "rnode0", "online" to true),
                ),
            )

            // Emit status change event
            interfaceStatusFlow.emit(Unit)
            advanceUntilIdle()

            // Should receive updated state
            val updatedState = awaitItem()
            assertEquals(false, updatedState.interfaceOnlineStatus["ble0"])
            assertEquals(true, updatedState.interfaceOnlineStatus["rnode0"])
        }
    }

    @Test
    fun `multiple interface status events all trigger refreshes`() = runTest {
        var callCount = 0
        coEvery { serviceProtocol.getDebugInfo() } answers {
            callCount++
            val isOnline: Boolean = callCount % 2 == 0
            mapOf(
                "interfaces" to listOf(
                    mapOf("name" to "ble0", "online" to isOnline),
                ),
            )
        }

        viewModel = InterfaceManagementViewModel(
            interfaceRepository,
            configManager,
            bleStatusRepository,
            serviceProtocol,
        )

        advanceUntilIdle()
        val initialCallCount = callCount

        // Emit multiple events
        interfaceStatusFlow.emit(Unit)
        advanceUntilIdle()
        interfaceStatusFlow.emit(Unit)
        advanceUntilIdle()
        interfaceStatusFlow.emit(Unit)
        advanceUntilIdle()

        // Should have called getDebugInfo multiple times
        assertTrue("Expected at least 3 additional calls", callCount >= initialCallCount + 3)
    }

    @Test
    fun `non-ServiceReticulumProtocol does not crash`() = runTest {
        // Use a generic ReticulumProtocol mock instead of ServiceReticulumProtocol
        val genericProtocol: ReticulumProtocol = mockk()
        coEvery { genericProtocol.getDebugInfo() } returns emptyMap()

        viewModel = InterfaceManagementViewModel(
            interfaceRepository,
            configManager,
            bleStatusRepository,
            genericProtocol,
        )

        advanceUntilIdle()

        // Should not crash, just skip event observation
        assertNotNull(viewModel)
    }
}
