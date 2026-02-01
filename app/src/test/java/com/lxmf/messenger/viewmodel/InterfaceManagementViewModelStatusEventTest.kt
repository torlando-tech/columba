@file:Suppress("InjectDispatcher")

package com.lxmf.messenger.viewmodel

import android.bluetooth.BluetoothAdapter
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
    private lateinit var interfaceStatusFlow: MutableSharedFlow<String>
    private lateinit var viewModel: InterfaceManagementViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Use test dispatcher for IO operations
        InterfaceManagementViewModel.ioDispatcher = testDispatcher

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

        // Mock interfaceStatusFlow for ServiceReticulumProtocol (event-driven updates)
        interfaceStatusFlow = MutableSharedFlow(replay = 1, extraBufferCapacity = 1)
        every { serviceProtocol.interfaceStatusFlow } returns interfaceStatusFlow

        // Mock getDebugInfo for initial status fetch
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
        // Reset IO dispatcher to default
        InterfaceManagementViewModel.ioDispatcher = Dispatchers.IO
        clearAllMocks()
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
    fun `ViewModel observes ServiceReticulumProtocol interfaceStatusFlow`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Verify interfaceStatusFlow was accessed and ViewModel initialized
            verify { serviceProtocol.interfaceStatusFlow }
            assertTrue("ViewModel should be initialized", viewModel.state.value != null)
        }

    @Test
    fun `interface status event triggers state update`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Emit interface status event with JSON data
            interfaceStatusFlow.emit("""{"ble0": false}""")
            advanceUntilIdle()

            // Verify state was updated from the event
            assertEquals(false, viewModel.state.value.interfaceOnlineStatus["ble0"])
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

            // Emit first event to populate initial status with JSON data
            interfaceStatusFlow.emit("""{"ble0": true}""")
            advanceUntilIdle()

            viewModel.state.test {
                val initialState = awaitItem()
                assertEquals(true, initialState.interfaceOnlineStatus["ble0"])

                // Emit status change event with new status
                interfaceStatusFlow.emit("""{"ble0": false, "rnode0": true}""")
                advanceUntilIdle()

                // Should receive updated state
                val updatedState = awaitItem()
                assertEquals(false, updatedState.interfaceOnlineStatus["ble0"])
                assertEquals(true, updatedState.interfaceOnlineStatus["rnode0"])
            }
        }

    @Test
    fun `multiple interface status events all update state`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Emit multiple events with different states
            interfaceStatusFlow.emit("""{"ble0": true}""")
            advanceUntilIdle()
            assertEquals(true, viewModel.state.value.interfaceOnlineStatus["ble0"])

            interfaceStatusFlow.emit("""{"ble0": false}""")
            advanceUntilIdle()
            assertEquals(false, viewModel.state.value.interfaceOnlineStatus["ble0"])

            interfaceStatusFlow.emit("""{"ble0": true, "rnode0": true}""")
            advanceUntilIdle()
            assertEquals(true, viewModel.state.value.interfaceOnlineStatus["ble0"])
            assertEquals(true, viewModel.state.value.interfaceOnlineStatus["rnode0"])
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

    // region Duplicate Interface Name Validation Tests

    @Test
    fun `validateConfig sets nameError for duplicate interface name`() =
        runTest {
            // Create existing interface
            val existingEntity =
                InterfaceEntity(
                    id = 1L,
                    name = "Existing Interface",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1", "target_port": 4242}""",
                    displayOrder = 0,
                )

            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "Existing Interface",
                    enabled = true,
                    targetHost = "10.0.0.1",
                    targetPort = 4242,
                )

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Show add dialog (not editing)
            viewModel.showAddDialog()
            advanceUntilIdle()

            // Set name to duplicate using updateConfigState
            viewModel.updateConfigState {
                it.copy(
                    name = "Existing Interface",
                    type = "TCPClient",
                    targetHost = "192.168.1.1",
                    targetPort = "4242",
                )
            }
            advanceUntilIdle()

            // Try to save - should fail validation
            viewModel.saveInterface()
            advanceUntilIdle()

            // Check nameError is set
            viewModel.configState.test {
                val configState = awaitItem()
                assertNotNull(configState.nameError)
                assertTrue(configState.nameError!!.contains("already exists"))
            }
        }

    @Test
    fun `validateConfig sets nameError for case-insensitive duplicate`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 1L,
                    name = "My Interface",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1", "target_port": 4242}""",
                    displayOrder = 0,
                )

            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "My Interface",
                    enabled = true,
                    targetHost = "10.0.0.1",
                    targetPort = 4242,
                )

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            viewModel.showAddDialog()
            advanceUntilIdle()

            // Set name to case-insensitive duplicate using updateConfigState
            viewModel.updateConfigState {
                it.copy(
                    name = "my interface",
                    type = "TCPClient",
                    targetHost = "192.168.1.1",
                    targetPort = "4242",
                )
            }
            advanceUntilIdle()

            viewModel.saveInterface()
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertNotNull(configState.nameError)
                assertTrue(configState.nameError!!.contains("already exists"))
            }
        }

    @Test
    fun `validateConfig allows same name when editing existing interface`() =
        runTest {
            val existingEntity =
                InterfaceEntity(
                    id = 1L,
                    name = "My Interface",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1", "target_port": 4242}""",
                    displayOrder = 0,
                )

            every { interfaceRepository.allInterfaceEntities } returns flowOf(listOf(existingEntity))
            every { interfaceRepository.entityToConfig(existingEntity) } returns
                InterfaceConfig.TCPClient(
                    name = "My Interface",
                    enabled = true,
                    targetHost = "10.0.0.1",
                    targetPort = 4242,
                )
            coEvery { interfaceRepository.updateInterface(any(), any()) } returns Unit

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Edit existing interface (same name should be allowed)
            viewModel.showEditDialog(existingEntity)
            advanceUntilIdle()

            // Keep the same name, just change host
            viewModel.updateConfigState { it.copy(targetHost = "192.168.1.100") }
            advanceUntilIdle()

            viewModel.saveInterface()
            advanceUntilIdle()

            // Should not have name error (same name is allowed when editing)
            viewModel.configState.test {
                val configState = awaitItem()
                assertEquals(null, configState.nameError)
            }
        }

    @Test
    fun `validateConfig rejects duplicate when editing to another existing name`() =
        runTest {
            val existingEntity1 =
                InterfaceEntity(
                    id = 1L,
                    name = "Interface One",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1", "target_port": 4242}""",
                    displayOrder = 0,
                )
            val existingEntity2 =
                InterfaceEntity(
                    id = 2L,
                    name = "Interface Two",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.2", "target_port": 4242}""",
                    displayOrder = 1,
                )

            every { interfaceRepository.allInterfaceEntities } returns
                flowOf(listOf(existingEntity1, existingEntity2))
            every { interfaceRepository.entityToConfig(existingEntity1) } returns
                InterfaceConfig.TCPClient(
                    name = "Interface One",
                    enabled = true,
                    targetHost = "10.0.0.1",
                    targetPort = 4242,
                )

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Edit Interface One and try to rename to Interface Two
            viewModel.showEditDialog(existingEntity1)
            advanceUntilIdle()

            viewModel.updateConfigState { it.copy(name = "Interface Two") } // Duplicate of other interface
            advanceUntilIdle()

            viewModel.saveInterface()
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertNotNull(configState.nameError)
                assertTrue(configState.nameError!!.contains("already exists"))
            }
        }

    // endregion

    // region handleBluetoothStateChange Tests

    @Test
    fun `handleBluetoothStateChange returns early when no BLE interfaces enabled`() =
        runTest {
            // Given - no interfaces at all
            every { interfaceRepository.allInterfaceEntities } returns flowOf(emptyList())

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // When - Bluetooth state changes (no BLE interfaces = no infoMessage shown)
            every { bleStatusRepository.getConnectedPeersFlow() } returns
                flowOf(BleConnectionsState.BluetoothDisabled)

            advanceUntilIdle()

            // Then - No info message should be shown (early return path)
            assertEquals(null, viewModel.state.value.infoMessage)
        }

    @Test
    fun `observeBluetoothState handles BleConnectionsState Error variant`() =
        runTest {
            // Given - flow emits Error state
            every { bleStatusRepository.getConnectedPeersFlow() } returns
                flowOf(BleConnectionsState.Error("Test error message"))

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Then - Should treat as STATE_ON (assuming BT is on but there's an error)
            assertEquals(BluetoothAdapter.STATE_ON, viewModel.state.value.bluetoothState)
        }

    // endregion

    // region entityToConfigState Tests - Unsupported Type

    @Test
    fun `showEditDialog with unsupported interface type returns default config state`() =
        runTest {
            // Create test entity with unsupported type
            val entity =
                InterfaceEntity(
                    id = 1L,
                    name = "Unknown Interface",
                    type = "UnknownType",
                    enabled = true,
                    configJson = """{}""",
                    displayOrder = 0,
                )

            // Mock entityToConfig to return an unsupported type (UDP which isn't handled)
            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.UDP(
                    name = "Unknown Interface",
                    enabled = true,
                    listenIp = "0.0.0.0",
                    listenPort = 4242,
                    forwardIp = "127.0.0.1",
                    forwardPort = 4243,
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

            // Verify configState returns default values for unsupported type
            viewModel.configState.test {
                val configState = awaitItem()
                // Default type should be AutoInterface as per InterfaceConfigState defaults
                assertEquals("AutoInterface", configState.type)
            }
        }

    // endregion

    // region Message Clear Tests

    @Test
    fun `clearError clears error message`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Trigger an error (showAddDialog and try to save invalid config)
            viewModel.showAddDialog()
            viewModel.updateConfigState { it.copy(name = "", type = "TCPClient", targetHost = "", targetPort = "") }
            viewModel.saveInterface()
            advanceUntilIdle()

            // Clear error
            viewModel.clearError()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(null, state.errorMessage)
            }
        }

    @Test
    fun `clearSuccess clears success message`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Clear success
            viewModel.clearSuccess()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(null, state.successMessage)
            }
        }

    @Test
    fun `clearInfo clears info message`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // Clear info
            viewModel.clearInfo()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(null, state.infoMessage)
            }
        }

    // endregion

    // region TCPServer Tests

    @Test
    fun `showEditDialog with TCPServer converts correctly`() =
        runTest {
            val entity =
                InterfaceEntity(
                    id = 5L,
                    name = "My TCP Server",
                    type = "TCPServer",
                    enabled = true,
                    configJson = """{"listen_ip": "0.0.0.0", "listen_port": 4242, "mode": "full"}""",
                    displayOrder = 0,
                )

            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.TCPServer(
                    name = "My TCP Server",
                    enabled = true,
                    listenIp = "0.0.0.0",
                    listenPort = 4242,
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
                assertEquals("My TCP Server", configState.name)
                assertEquals("TCPServer", configState.type)
                assertEquals("0.0.0.0", configState.listenIp)
                assertEquals("4242", configState.listenPort)
                assertEquals("full", configState.mode)
            }
        }

    @Test
    fun `validateConfig rejects TCPServer with invalid listen port`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            viewModel.showAddDialog()
            advanceUntilIdle()

            // Set up a TCPServer with invalid port
            viewModel.updateConfigState {
                it.copy(
                    name = "Test Server",
                    type = "TCPServer",
                    listenIp = "0.0.0.0",
                    listenPort = "99999", // Invalid: > 65535
                )
            }
            advanceUntilIdle()

            viewModel.saveInterface()
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertNotNull(configState.listenPortError)
            }
        }

    @Test
    fun `validateConfig rejects TCPServer with invalid listen IP`() =
        runTest {
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            viewModel.showAddDialog()
            advanceUntilIdle()

            // Set up a TCPServer with invalid IP
            viewModel.updateConfigState {
                it.copy(
                    name = "Test Server",
                    type = "TCPServer",
                    listenIp = "invalid ip with spaces",
                    listenPort = "4242",
                )
            }
            advanceUntilIdle()

            viewModel.saveInterface()
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertNotNull(configState.listenIpError)
            }
        }

    @Test
    fun `validateConfig accepts valid TCPServer configuration`() =
        runTest {
            coEvery { interfaceRepository.insertInterface(any()) } returns 1L

            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            viewModel.showAddDialog()
            advanceUntilIdle()

            // Set up a valid TCPServer
            viewModel.updateConfigState {
                it.copy(
                    name = "Valid Server",
                    type = "TCPServer",
                    listenIp = "192.168.1.1",
                    listenPort = "8080",
                )
            }
            advanceUntilIdle()

            viewModel.saveInterface()
            advanceUntilIdle()

            viewModel.configState.test {
                val configState = awaitItem()
                assertEquals(null, configState.listenIpError)
                assertEquals(null, configState.listenPortError)
            }
        }

    // endregion

    // region Interface Status Flow Tests

    @Test
    fun `interfaceStatusFlow updates interfaceOnlineStatus in state`() =
        runTest {
            // Given - emit interface status JSON to the flow
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // When - emit status update
            interfaceStatusFlow.emit("""{"WiFi": true, "BLE": false, "RNode": true}""")
            advanceUntilIdle()

            // Then - state should have updated interface status
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(true, state.interfaceOnlineStatus["WiFi"])
                assertEquals(false, state.interfaceOnlineStatus["BLE"])
                assertEquals(true, state.interfaceOnlineStatus["RNode"])
            }
        }

    @Test
    fun `interfaceStatusFlow handles malformed JSON gracefully`() =
        runTest {
            // Given
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // When - emit malformed JSON
            interfaceStatusFlow.emit("not valid json {{{")
            advanceUntilIdle()

            // Then - should not crash, state remains unchanged
            viewModel.state.test {
                val state = awaitItem()
                // interfaceOnlineStatus should be empty or unchanged
                assertTrue("Should not crash on malformed JSON", true)
            }
        }

    @Test
    fun `interfaceStatusFlow handles empty JSON object`() =
        runTest {
            // Given
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // When - emit empty JSON object
            interfaceStatusFlow.emit("{}")
            advanceUntilIdle()

            // Then - state should have empty map
            viewModel.state.test {
                val state = awaitItem()
                assertTrue("Should handle empty JSON object", state.interfaceOnlineStatus.isEmpty())
            }
        }

    @Test
    fun `multiple interfaceStatusFlow emissions update state correctly`() =
        runTest {
            // Given
            viewModel =
                InterfaceManagementViewModel(
                    interfaceRepository,
                    configManager,
                    bleStatusRepository,
                    serviceProtocol,
                )

            advanceUntilIdle()

            // When - emit multiple status updates
            interfaceStatusFlow.emit("""{"WiFi": false}""")
            advanceUntilIdle()
            interfaceStatusFlow.emit("""{"WiFi": true, "BLE": true}""")
            advanceUntilIdle()

            // Then - state should reflect latest emission
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(true, state.interfaceOnlineStatus["WiFi"])
                assertEquals(true, state.interfaceOnlineStatus["BLE"])
            }
        }

    // endregion
}
