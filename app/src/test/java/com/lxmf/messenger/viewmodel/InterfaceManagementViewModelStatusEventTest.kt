package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.repository.BleStatusRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
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
        coEvery { serviceProtocol.getDebugInfo() } returns
            mapOf(
                "interfaces" to
                    listOf(
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
    fun `ViewModel initializes without error with ServiceReticulumProtocol`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
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
    fun `ViewModel observes ServiceReticulumProtocol interfaceStatusChanged flow`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
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
    fun `interface status event triggers immediate debug info fetch`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Reset mock to track new calls
            coEvery { serviceProtocol.getDebugInfo() } returns
                mapOf(
                    "interfaces" to
                        listOf(
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
    fun `interface online status is updated in state after event`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
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
                coEvery { serviceProtocol.getDebugInfo() } returns
                    mapOf(
                        "interfaces" to
                            listOf(
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
    fun `multiple interface status events all trigger refreshes`() =
        runTest {
            var callCount = 0
            coEvery { serviceProtocol.getDebugInfo() } answers {
                callCount++
                val isOnline: Boolean = callCount % 2 == 0
                mapOf(
                    "interfaces" to
                        listOf(
                            mapOf("name" to "ble0", "online" to isOnline),
                        ),
                )
            }

            viewModel =
                InterfaceManagementViewModel(
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
    fun `non-ServiceReticulumProtocol does not crash`() =
        runTest {
            // Use a generic ReticulumProtocol mock instead of ServiceReticulumProtocol
            val genericProtocol: ReticulumProtocol = mockk()
            coEvery { genericProtocol.getDebugInfo() } returns emptyMap()

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    genericProtocol,
                )

            advanceUntilIdle()

            // Should not crash, just skip event observation
            assertNotNull(viewModel)
        }

    // region entityToConfigState Tests

    @Test
    fun `showEditDialog with AutoInterface null ports converts to empty strings`() =
        runTest {
            // Create test entity
            val entity =
                InterfaceEntity(
                    id = 1L,
                    name = "Test Auto",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id": "", "discovery_scope": "link", "mode": "full"}""",
                    displayOrder = 0,
                )

            // Mock entityToConfig to return AutoInterface with null ports
            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.AutoInterface(
                    name = "Test Auto",
                    enabled = true,
                    groupId = "",
                    discoveryScope = "link",
                    discoveryPort = null,
                    dataPort = null,
                    mode = "full",
                )

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Call showEditDialog which triggers entityToConfigState
            viewModel.showEditDialog(entity)
            advanceUntilIdle()

            // Verify configState has empty strings for ports (not "null" strings)
            viewModel.configState.test {
                val configState = awaitItem()
                assertEquals("", configState.discoveryPort)
                assertEquals("", configState.dataPort)
                assertEquals("Test Auto", configState.name)
                assertEquals("AutoInterface", configState.type)
            }
        }

    @Test
    fun `showEditDialog with AutoInterface valid ports converts correctly`() =
        runTest {
            val entity =
                InterfaceEntity(
                    id = 2L,
                    name = "Custom Auto",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"discovery_port": 12345, "data_port": 54321}""",
                    displayOrder = 0,
                )

            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.AutoInterface(
                    name = "Custom Auto",
                    enabled = true,
                    discoveryPort = 12345,
                    dataPort = 54321,
                    mode = "full",
                )

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            viewModel.showEditDialog(entity)
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertEquals("12345", configState.discoveryPort)
                assertEquals("54321", configState.dataPort)
            }
        }

    @Test
    fun `showEditDialog with TCPClient handles optional fields correctly`() =
        runTest {
            val entity =
                InterfaceEntity(
                    id = 3L,
                    name = "Remote TCP",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1", "target_port": 4242}""",
                    displayOrder = 0,
                )

            // Mock with null networkName and passphrase
            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.TCPClient(
                    name = "Remote TCP",
                    enabled = true,
                    targetHost = "10.0.0.1",
                    targetPort = 4242,
                    networkName = null,
                    passphrase = null,
                    mode = "full",
                )

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            viewModel.showEditDialog(entity)
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertEquals("Remote TCP", configState.name)
                assertEquals("TCPClient", configState.type)
                assertEquals("10.0.0.1", configState.targetHost)
                assertEquals("4242", configState.targetPort)
                // networkName and passphrase should be empty strings via .orEmpty()
                assertEquals("", configState.networkName)
                assertEquals("", configState.passphrase)
            }
        }

    // endregion
}
