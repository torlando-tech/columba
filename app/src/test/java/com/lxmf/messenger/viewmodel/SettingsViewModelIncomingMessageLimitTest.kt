package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.AvailableRelaysState
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.TelemetryCollectorManager
import com.lxmf.messenger.ui.theme.PresetTheme
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
import org.junit.Assert.assertTrue
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
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var mapTileSourceManager: MapTileSourceManager
    private lateinit var telemetryCollectorManager: TelemetryCollectorManager
    private lateinit var contactRepository: com.lxmf.messenger.data.repository.ContactRepository
    private lateinit var viewModel: SettingsViewModel

    // Mutable flows for controlling test scenarios
    private val incomingMessageSizeLimitKbFlow = MutableStateFlow(1024)
    private val preferOwnInstanceFlow = MutableStateFlow(false)
    private val isSharedInstanceFlow = MutableStateFlow(false)
    private val rpcKeyFlow = MutableStateFlow<String?>(null)
    private val autoAnnounceEnabledFlow = MutableStateFlow(true)
    private val autoAnnounceIntervalHoursFlow = MutableStateFlow(3)
    private val lastAutoAnnounceTimeFlow = MutableStateFlow<Long?>(null)
    private val nextAutoAnnounceTimeFlow = MutableStateFlow<Long?>(null)
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

        settingsRepository = mockk()
        identityRepository = mockk()
        reticulumProtocol = mockk<ServiceReticulumProtocol>()
        interfaceConfigManager = mockk()
        propagationNodeManager = mockk()
        locationSharingManager = mockk()
        interfaceRepository = mockk()
        mapTileSourceManager = mockk()
        telemetryCollectorManager = mockk()
        contactRepository = mockk()

        // Mock ContactRepository flow
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())

        // Mock TelemetryCollectorManager flows used during init
        every { telemetryCollectorManager.isEnabled } returns MutableStateFlow(false)
        every { telemetryCollectorManager.isSending } returns MutableStateFlow(false)
        every { telemetryCollectorManager.isRequesting } returns MutableStateFlow(false)

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
        every { settingsRepository.autoAnnounceIntervalHoursFlow } returns autoAnnounceIntervalHoursFlow
        every { settingsRepository.lastAutoAnnounceTimeFlow } returns lastAutoAnnounceTimeFlow
        every { settingsRepository.nextAutoAnnounceTimeFlow } returns nextAutoAnnounceTimeFlow
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
        every { settingsRepository.telemetryCollectorEnabledFlow } returns MutableStateFlow(false)
        every { settingsRepository.telemetryCollectorAddressFlow } returns MutableStateFlow<String?>(null)
        every { settingsRepository.telemetrySendIntervalSecondsFlow } returns MutableStateFlow(SettingsRepository.DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS)
        every { settingsRepository.lastTelemetrySendTimeFlow } returns MutableStateFlow<Long?>(null)
        every { settingsRepository.mapSourceHttpEnabledFlow } returns MutableStateFlow(false)
        every { settingsRepository.mapSourceRmspEnabledFlow } returns MutableStateFlow(false)
        every { mapTileSourceManager.observeRmspServerCount() } returns flowOf(0)
        every { mapTileSourceManager.hasOfflineMaps() } returns flowOf(false)
        every { identityRepository.activeIdentity } returns activeIdentityFlow

        // Notification settings flows
        every { settingsRepository.notificationsEnabledFlow } returns MutableStateFlow(true)
        every { settingsRepository.notificationReceivedMessageFlow } returns MutableStateFlow(true)
        every { settingsRepository.notificationReceivedMessageFavoriteFlow } returns MutableStateFlow(true)
        every { settingsRepository.notificationHeardAnnounceFlow } returns MutableStateFlow(false)
        every { settingsRepository.notificationBleConnectedFlow } returns MutableStateFlow(false)
        every { settingsRepository.notificationBleDisconnectedFlow } returns MutableStateFlow(false)

        // Privacy settings flows
        every { settingsRepository.blockUnknownSendersFlow } returns MutableStateFlow(false)

        // Telemetry request settings flows
        every { settingsRepository.telemetryRequestEnabledFlow } returns MutableStateFlow(false)
        every { settingsRepository.telemetryRequestIntervalSecondsFlow } returns MutableStateFlow(SettingsRepository.DEFAULT_TELEMETRY_REQUEST_INTERVAL_SECONDS)
        every { settingsRepository.lastTelemetryRequestTimeFlow } returns MutableStateFlow<Long?>(null)
        every { settingsRepository.telemetryHostModeEnabledFlow } returns MutableStateFlow(false)
        every { settingsRepository.telemetryAllowedRequestersFlow } returns MutableStateFlow(emptySet())

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

        // Mock methods called during setIncomingMessageSizeLimit
        coEvery { settingsRepository.saveIncomingMessageSizeLimitKb(any()) } just Runs
        every { (reticulumProtocol as ServiceReticulumProtocol).setIncomingMessageSizeLimit(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        // Restore default behavior for other tests
        SettingsViewModel.enableMonitors = true
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            settingsRepository = settingsRepository,
            identityRepository = identityRepository,
            reticulumProtocol = reticulumProtocol,
            interfaceConfigManager = interfaceConfigManager,
            propagationNodeManager = propagationNodeManager,
            locationSharingManager = locationSharingManager,
            interfaceRepository = interfaceRepository,
            mapTileSourceManager = mapTileSourceManager,
            telemetryCollectorManager = telemetryCollectorManager,
            contactRepository = contactRepository,
        )

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
            val result = runCatching { viewModel.setIncomingMessageSizeLimit(10240) } // 10MB

            advanceUntilIdle()

            // Then
            assertTrue("setIncomingMessageSizeLimit should complete without throwing", result.isSuccess)
            coVerify { settingsRepository.saveIncomingMessageSizeLimitKb(10240) }
        }

    @Test
    fun `setIncomingMessageSizeLimit calls protocol to apply at runtime`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            val result = runCatching { viewModel.setIncomingMessageSizeLimit(25600) } // 25MB

            advanceUntilIdle()

            // Then
            assertTrue("setIncomingMessageSizeLimit should complete without throwing", result.isSuccess)
            verify { (reticulumProtocol as ServiceReticulumProtocol).setIncomingMessageSizeLimit(25600) }
        }

    @Test
    fun `setIncomingMessageSizeLimit with 1MB limit`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            val result = runCatching { viewModel.setIncomingMessageSizeLimit(1024) }

            advanceUntilIdle()

            // Then
            assertTrue("setIncomingMessageSizeLimit should complete without throwing", result.isSuccess)
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
            val result = runCatching { viewModel.setIncomingMessageSizeLimit(5120) }

            advanceUntilIdle()

            // Then
            assertTrue("setIncomingMessageSizeLimit should complete without throwing", result.isSuccess)
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
            val result = runCatching { viewModel.setIncomingMessageSizeLimit(131072) }

            advanceUntilIdle()

            // Then
            assertTrue("setIncomingMessageSizeLimit should complete without throwing", result.isSuccess)
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
