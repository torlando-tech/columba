@file:Suppress("InjectDispatcher")

package network.columba.app.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.data.database.entity.InterfaceEntity
import network.columba.app.data.model.BleConnectionsState
import network.columba.app.data.repository.BleStatusRepository
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.service.PendingInterfaceChanges
import network.columba.app.service.manager.InterfaceTransportObserver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceManagementViewModelNetworkRestrictionTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var bleStatusRepository: BleStatusRepository
    private lateinit var transportAdmin: RnsTransportAdmin
    private lateinit var transportObserver: InterfaceTransportObserver
    private lateinit var rnsBackend: RnsBackend
    private lateinit var viewModel: InterfaceManagementViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        InterfaceManagementViewModel.ioDispatcher = testDispatcher
        InterfaceManagementViewModel.enableStatusPolling = false

        interfaceRepository = mockk()
        every { interfaceRepository.allInterfaceEntities } returns flowOf(emptyList())
        every { interfaceRepository.enabledInterfaceCount } returns flowOf(0)
        every { interfaceRepository.totalInterfaceCount } returns flowOf(0)
        every { interfaceRepository.enabledInterfaces } returns flowOf(emptyList())

        configManager = mockk()
        every { configManager.consumePendingChanges() } returns PendingInterfaceChanges()

        bleStatusRepository = mockk()
        every { bleStatusRepository.getConnectedPeersFlow() } returns
            flowOf(BleConnectionsState.Success(emptyList()))

        transportAdmin = mockk()
        every { transportAdmin.interfaceStatusFlow } returns MutableSharedFlow()
        every { transportAdmin.debugInfoFlow } returns MutableSharedFlow()
        coEvery { transportAdmin.isDiscoveryEnabled() } returns false
        coEvery { transportAdmin.getDiscoveredInterfaces() } returns emptyList()
        coEvery { transportAdmin.getDebugInfo() } returns emptyMap()
        coEvery { transportAdmin.reloadInterfaces(any()) } returns Unit

        transportObserver = mockk()
        every { transportObserver.snapshotTransport() } returns CurrentTransport.WIFI_LIKE
        every { transportObserver.currentTransport } returns MutableStateFlow(CurrentTransport.WIFI_LIKE)

        rnsBackend = mockk()
        every { rnsBackend.capabilities } returns
            MutableStateFlow(
                BackendCapabilities.UNKNOWN.copy(
                    interfaces = BackendCapabilities.InterfaceCaps(hotReloadInterfaces = true),
                ),
            )

        viewModel =
            InterfaceManagementViewModel(
                interfaceRepository,
                configManager,
                bleStatusRepository,
                transportAdmin,
                transportObserver,
                rnsBackend,
            )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        InterfaceManagementViewModel.ioDispatcher = Dispatchers.IO
        InterfaceManagementViewModel.enableStatusPolling = true
        clearAllMocks()
    }

    @Test
    fun `fresh add state uses an untouched restriction sentinel`() =
        runTest {
            advanceUntilIdle()

            viewModel.showAddDialog()

            assertEquals("AutoInterface", viewModel.configState.value.type)
            assertNull(viewModel.configState.value.networkRestriction)
        }

    @Test
    fun `fresh AutoInterface save defaults to wifi only`() =
        runTest {
            val savedConfig = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(savedConfig)) } returns 1L
            advanceUntilIdle()

            viewModel.showAddDialog()
            viewModel.updateConfigState { it.copy(name = "Auto Test") }
            viewModel.saveInterface()
            advanceUntilIdle()

            val saved = savedConfig.captured as InterfaceConfig.AutoInterface
            assertEquals(NetworkRestriction.WIFI_ONLY, saved.networkRestriction)
        }

    @Test
    fun `explicit Any selection on AutoInterface remains Any`() =
        runTest {
            val savedConfig = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(savedConfig)) } returns 1L
            advanceUntilIdle()

            viewModel.showAddDialog()
            viewModel.updateConfigState {
                it.copy(
                    name = "Auto Any",
                    networkRestriction = NetworkRestriction.ANY.value,
                )
            }
            viewModel.saveInterface()
            advanceUntilIdle()

            val saved = savedConfig.captured as InterfaceConfig.AutoInterface
            assertEquals(NetworkRestriction.ANY, saved.networkRestriction)
        }

    @Test
    fun `fresh TCPClient save defaults to Any`() =
        runTest {
            val savedConfig = slot<InterfaceConfig>()
            coEvery { interfaceRepository.insertInterface(capture(savedConfig)) } returns 1L
            advanceUntilIdle()

            viewModel.showAddDialog()
            viewModel.updateConfigState {
                it.copy(
                    name = "TCP Test",
                    type = "TCPClient",
                    targetHost = "example.com",
                )
            }
            viewModel.saveInterface()
            advanceUntilIdle()

            val saved = savedConfig.captured as InterfaceConfig.TCPClient
            assertEquals(NetworkRestriction.ANY, saved.networkRestriction)
        }

    @Test
    fun `editing configured Any AutoInterface round trips Any`() =
        runTest {
            val entity = autoEntity(id = 41L, name = "Configured Any")
            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.AutoInterface(
                    name = entity.name,
                    networkRestriction = NetworkRestriction.ANY,
                )
            val savedConfig = slot<InterfaceConfig>()
            coEvery { interfaceRepository.updateInterface(entity.id, capture(savedConfig)) } returns Unit
            advanceUntilIdle()

            viewModel.showEditDialog(entity)
            assertEquals(NetworkRestriction.ANY.value, viewModel.configState.value.networkRestriction)
            viewModel.saveInterface()
            advanceUntilIdle()

            val saved = savedConfig.captured as InterfaceConfig.AutoInterface
            assertEquals(NetworkRestriction.ANY, saved.networkRestriction)
        }

    @Test
    fun `editing legacy AutoInterface default round trips wifi only`() =
        runTest {
            val entity = autoEntity(id = 42L, name = "Legacy Auto")
            every { interfaceRepository.entityToConfig(entity) } returns
                InterfaceConfig.AutoInterface(name = entity.name)
            val savedConfig = slot<InterfaceConfig>()
            coEvery { interfaceRepository.updateInterface(entity.id, capture(savedConfig)) } returns Unit
            advanceUntilIdle()

            viewModel.showEditDialog(entity)
            assertEquals(NetworkRestriction.WIFI_ONLY.value, viewModel.configState.value.networkRestriction)
            viewModel.saveInterface()
            advanceUntilIdle()

            val saved = savedConfig.captured as InterfaceConfig.AutoInterface
            assertEquals(NetworkRestriction.WIFI_ONLY, saved.networkRestriction)
        }

    @Test
    fun `type switching preserves untouched sentinel and opening a new dialog resets explicit selection`() =
        runTest {
            advanceUntilIdle()

            viewModel.showAddDialog()
            assertEquals(NetworkRestriction.WIFI_ONLY.value, viewModel.configState.value.effectiveNetworkRestriction)

            viewModel.updateConfigState { it.copy(type = "TCPClient") }
            assertNull(viewModel.configState.value.networkRestriction)
            assertEquals(NetworkRestriction.ANY.value, viewModel.configState.value.effectiveNetworkRestriction)

            viewModel.updateConfigState { it.copy(type = "AutoInterface") }
            assertNull(viewModel.configState.value.networkRestriction)
            assertEquals(NetworkRestriction.WIFI_ONLY.value, viewModel.configState.value.effectiveNetworkRestriction)

            viewModel.updateConfigState {
                it.copy(networkRestriction = NetworkRestriction.ANY.value)
            }
            assertEquals(NetworkRestriction.ANY.value, viewModel.configState.value.networkRestriction)
            assertEquals(NetworkRestriction.ANY.value, viewModel.configState.value.effectiveNetworkRestriction)

            viewModel.updateConfigState { it.copy(type = "TCPClient") }
            viewModel.updateConfigState { it.copy(type = "AutoInterface") }
            assertEquals(NetworkRestriction.ANY.value, viewModel.configState.value.networkRestriction)

            viewModel.showAddDialog()
            assertNull(viewModel.configState.value.networkRestriction)
            assertEquals(NetworkRestriction.WIFI_ONLY.value, viewModel.configState.value.effectiveNetworkRestriction)
        }

    private fun autoEntity(
        id: Long,
        name: String,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "AutoInterface",
        enabled = true,
        configJson = "{}",
        displayOrder = 0,
    )
}
