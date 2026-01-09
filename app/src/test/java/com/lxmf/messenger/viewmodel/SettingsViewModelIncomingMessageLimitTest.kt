package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.AvailableRelaysState
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.service.InterfaceDetector
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.ui.theme.PresetTheme
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for SettingsViewModel incoming message size limit functionality.
 *
 * Tests cover:
 * - Initial state loading from repository
 * - Setting different size limits
 * - State updates when limit changes
 * - Runtime update to protocol
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelIncomingMessageLimitTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var interfaceConfigManager: InterfaceConfigManager
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var locationSharingManager: LocationSharingManager
    private lateinit var interfaceDetector: InterfaceDetector
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var viewModel: SettingsViewModel

    // Mutable flows for controlling test scenarios
    private val incomingMessageSizeLimitKbFlow = MutableStateFlow(1024)
    private val preferOwnInstanceFlow = MutableStateFlow(false)
    private val isSharedInstanceFlow = MutableStateFlow(false)
    private val rpcKeyFlow = MutableStateFlow<String?>(null)
    private val autoAnnounceEnabledFlow = MutableStateFlow(true)
    private val autoAnnounceIntervalMinutesFlow = MutableStateFlow(5)
    private val lastAutoAnnounceTimeFlow = MutableStateFlow<Long?>(null)
    private val themePreferenceFlow = MutableStateFlow(PresetTheme.VIBRANT)
    private val activeIdentityFlow = MutableStateFlow<LocalIdentityEntity?>(null)
    private val networkStatusFlow = MutableStateFlow<NetworkStatus>(NetworkStatus.READY)
    private val autoRetrieveEnabledFlow = MutableStateFlow(true)
    private val retrievalIntervalSecondsFlow = MutableStateFlow(30)
    private val transportNodeEnabledFlow = MutableStateFlow(true)
    private val defaultDeliveryMethodFlow = MutableStateFlow("direct")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Disable monitor coroutines during testing to avoid infinite loops
        SettingsViewModel.enableMonitors = false

        settingsRepository = mockk(relaxed = true)
        identityRepository = mockk(relaxed = true)
        reticulumProtocol = mockk<ServiceReticulumProtocol>(relaxed = true)
        interfaceConfigManager = mockk(relaxed = true)
        propagationNodeManager = mockk(relaxed = true)
        locationSharingManager = mockk(relaxed = true)
        interfaceDetector = mockk(relaxed = true)
        interfaceRepository = mockk(relaxed = true)

        // Mock interfaceRepository flows
        every { interfaceRepository.enabledInterfaces } returns MutableStateFlow(emptyList<InterfaceConfig>())

        // Mock locationSharingManager flows
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())

        // Setup repository flow mocks
        every { settingsRepository.incomingMessageSizeLimitKbFlow } returns incomingMessageSizeLimitKbFlow
        every { settingsRepository.preferOwnInstanceFlow } returns preferOwnInstanceFlow
        every { settingsRepository.isSharedInstanceFlow } returns isSharedInstanceFlow
        every { settingsRepository.rpcKeyFlow } returns rpcKeyFlow
        every { settingsRepository.autoAnnounceEnabledFlow } returns autoAnnounceEnabledFlow
        every { settingsRepository.autoAnnounceIntervalMinutesFlow } returns autoAnnounceIntervalMinutesFlow
        every { settingsRepository.lastAutoAnnounceTimeFlow } returns lastAutoAnnounceTimeFlow
        every { settingsRepository.themePreferenceFlow } returns themePreferenceFlow
        every { settingsRepository.getAllCustomThemes() } returns flowOf(emptyList())
        every { settingsRepository.autoRetrieveEnabledFlow } returns autoRetrieveEnabledFlow
        every { settingsRepository.retrievalIntervalSecondsFlow } returns retrievalIntervalSecondsFlow
        every { settingsRepository.transportNodeEnabledFlow } returns transportNodeEnabledFlow
        every { settingsRepository.defaultDeliveryMethodFlow } returns defaultDeliveryMethodFlow
        every { settingsRepository.locationSharingEnabledFlow } returns MutableStateFlow(false)
        every { settingsRepository.defaultSharingDurationFlow } returns MutableStateFlow("ONE_HOUR")
        every { settingsRepository.locationPrecisionRadiusFlow } returns MutableStateFlow(0)
        every { settingsRepository.imageCompressionPresetFlow } returns MutableStateFlow(com.lxmf.messenger.data.model.ImageCompressionPreset.AUTO)
        every { identityRepository.activeIdentity } returns activeIdentityFlow

        // Mock PropagationNodeManager flows (StateFlows)
        every { propagationNodeManager.currentRelay } returns MutableStateFlow(null)
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.lastSyncTimestamp } returns MutableStateFlow(null)
        every { propagationNodeManager.availableRelaysState } returns
            MutableStateFlow(AvailableRelaysState.Loaded(emptyList()))

        // Mock other required methods
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        coEvery { interfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)

        // Mock ReticulumProtocol networkStatus flow
        every { reticulumProtocol.networkStatus } returns networkStatusFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        // Restore default behavior for other tests
        SettingsViewModel.enableMonitors = true
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            identityRepository = identityRepository,
            reticulumProtocol = reticulumProtocol,
            interfaceConfigManager = interfaceConfigManager,
            propagationNodeManager = propagationNodeManager,
            locationSharingManager = locationSharingManager,
            interfaceDetector = interfaceDetector,
            interfaceRepository = interfaceRepository,
        )
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state has default incoming message size limit`() =
        runTest {
            // Given - Default flow value of 1024 KB
            incomingMessageSizeLimitKbFlow.value = 1024

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then - State should have default value (1024 KB)
            assertEquals(1024, viewModel.state.value.incomingMessageSizeLimitKb)
        }

    // ========== Set Limit Tests ==========

    @Test
    fun `setIncomingMessageSizeLimit saves to repository`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.setIncomingMessageSizeLimit(10240) // 10MB

            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveIncomingMessageSizeLimitKb(10240) }
        }

    @Test
    fun `setIncomingMessageSizeLimit calls protocol to apply at runtime`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.setIncomingMessageSizeLimit(25600) // 25MB

            advanceUntilIdle()

            // Then
            verify { (reticulumProtocol as ServiceReticulumProtocol).setIncomingMessageSizeLimit(25600) }
        }

    @Test
    fun `setIncomingMessageSizeLimit with 1MB limit`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.setIncomingMessageSizeLimit(1024)

            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveIncomingMessageSizeLimitKb(1024) }
            verify { (reticulumProtocol as ServiceReticulumProtocol).setIncomingMessageSizeLimit(1024) }
        }

    @Test
    fun `setIncomingMessageSizeLimit with 5MB limit`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.setIncomingMessageSizeLimit(5120)

            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveIncomingMessageSizeLimitKb(5120) }
            verify { (reticulumProtocol as ServiceReticulumProtocol).setIncomingMessageSizeLimit(5120) }
        }

    @Test
    fun `setIncomingMessageSizeLimit with unlimited 128MB`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.setIncomingMessageSizeLimit(131072)

            advanceUntilIdle()

            // Then
            coVerify { settingsRepository.saveIncomingMessageSizeLimitKb(131072) }
            verify { (reticulumProtocol as ServiceReticulumProtocol).setIncomingMessageSizeLimit(131072) }
        }

    // ========== State Update Tests ==========

    @Test
    fun `state updates when incoming message size limit flow emits new value`() =
        runTest {
            // Given
            incomingMessageSizeLimitKbFlow.value = 1024
            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(1024, viewModel.state.value.incomingMessageSizeLimitKb)

            // When - Repository emits new value
            incomingMessageSizeLimitKbFlow.value = 10240

            advanceUntilIdle()

            // Then
            assertEquals(10240, viewModel.state.value.incomingMessageSizeLimitKb)
        }

    @Test
    fun `state updates to different limit values`() =
        runTest {
            // Given - Start with default
            incomingMessageSizeLimitKbFlow.value = 1024
            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(1024, viewModel.state.value.incomingMessageSizeLimitKb)

            // When - Change to 5MB
            incomingMessageSizeLimitKbFlow.value = 5120

            advanceUntilIdle()

            // Then
            assertEquals(5120, viewModel.state.value.incomingMessageSizeLimitKb)

            // When - Change to unlimited (128MB)
            incomingMessageSizeLimitKbFlow.value = 131072

            advanceUntilIdle()

            // Then
            assertEquals(131072, viewModel.state.value.incomingMessageSizeLimitKb)
        }
}
