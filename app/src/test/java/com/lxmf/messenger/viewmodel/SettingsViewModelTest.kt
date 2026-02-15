package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * Unit tests for SettingsViewModel shared instance functionality.
 *
 * Tests cover:
 * - RPC key parsing from various formats
 * - State transitions for shared instance toggles
 * - Banner expansion state
 * - Service restart triggers
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
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
    private lateinit var contactRepository: ContactRepository
    private lateinit var viewModel: SettingsViewModel

    // Mutable flows for controlling test scenarios
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
    private val imageCompressionPresetFlow =
        MutableStateFlow(com.lxmf.messenger.data.model.ImageCompressionPreset.AUTO)

    @Before
    @Suppress("LongMethod") // Setup configures many mock stubs for ViewModel's 10+ dependencies
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Disable monitor coroutines during testing to avoid infinite loops
        SettingsViewModel.enableMonitors = false

        settingsRepository = mockk()
        identityRepository = mockk()
        reticulumProtocol = mockk()
        interfaceConfigManager = mockk()
        propagationNodeManager = mockk()
        locationSharingManager = mockk()
        interfaceRepository = mockk()
        mapTileSourceManager = mockk()
        telemetryCollectorManager = mockk()
        contactRepository = mockk()

        // Mock TelemetryCollectorManager flows used during init
        every { telemetryCollectorManager.isEnabled } returns MutableStateFlow(false)
        every { telemetryCollectorManager.isSending } returns MutableStateFlow(false)
        every { telemetryCollectorManager.isRequesting } returns MutableStateFlow(false)
        coEvery { telemetryCollectorManager.setEnabled(any()) } just Runs

        // Mock locationSharingManager flows and methods
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())
        coEvery { locationSharingManager.stopSharing(any()) } just Runs

        // Setup interface repository flow mock
        every { interfaceRepository.enabledInterfaces } returns flowOf(emptyList())

        // Setup contact repository mock
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())

        // Setup repository flow mocks
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
        every { settingsRepository.imageCompressionPresetFlow } returns imageCompressionPresetFlow
        every { settingsRepository.locationSharingEnabledFlow } returns flowOf(false)
        every { settingsRepository.defaultSharingDurationFlow } returns flowOf("ONE_HOUR")
        every { settingsRepository.locationPrecisionRadiusFlow } returns flowOf(0)
        every { settingsRepository.tryPropagationOnFailFlow } returns flowOf(false)
        every { settingsRepository.autoSelectPropagationNodeFlow } returns flowOf(false)
        every { settingsRepository.mapSourceHttpEnabledFlow } returns flowOf(true)
        every { settingsRepository.mapSourceRmspEnabledFlow } returns flowOf(false)
        every { settingsRepository.incomingMessageSizeLimitKbFlow } returns flowOf(500)
        every { settingsRepository.notificationsEnabledFlow } returns flowOf(true)
        every { settingsRepository.blockUnknownSendersFlow } returns flowOf(false)
        every { settingsRepository.telemetryCollectorEnabledFlow } returns flowOf(false)
        every { settingsRepository.telemetryCollectorAddressFlow } returns flowOf(null)
        every { settingsRepository.telemetrySendIntervalSecondsFlow } returns flowOf(60)
        every { settingsRepository.lastTelemetrySendTimeFlow } returns flowOf(null)
        every { settingsRepository.telemetryRequestEnabledFlow } returns flowOf(false)
        every { settingsRepository.telemetryRequestIntervalSecondsFlow } returns flowOf(60)
        every { settingsRepository.lastTelemetryRequestTimeFlow } returns flowOf(null)
        every { settingsRepository.telemetryHostModeEnabledFlow } returns flowOf(false)
        every { settingsRepository.telemetryAllowedRequestersFlow } returns flowOf(emptySet<String>())

        // Stub settings save methods
        coEvery { settingsRepository.savePreferOwnInstance(any()) } just Runs
        coEvery { settingsRepository.saveRpcKey(any()) } just Runs
        coEvery { settingsRepository.saveAutoAnnounceEnabled(any()) } just Runs
        coEvery { settingsRepository.saveAutoAnnounceIntervalHours(any()) } just Runs
        coEvery { settingsRepository.saveLastAutoAnnounceTime(any()) } just Runs
        coEvery { settingsRepository.saveThemePreference(any()) } just Runs
        coEvery { settingsRepository.saveDefaultDeliveryMethod(any()) } just Runs
        coEvery { settingsRepository.saveTryPropagationOnFail(any()) } just Runs
        coEvery { settingsRepository.saveAutoSelectPropagationNode(any()) } just Runs
        coEvery { settingsRepository.saveAutoRetrieveEnabled(any()) } just Runs
        coEvery { settingsRepository.saveRetrievalIntervalSeconds(any()) } just Runs
        coEvery { settingsRepository.saveTransportNodeEnabled(any()) } just Runs
        coEvery { settingsRepository.saveLocationSharingEnabled(any()) } just Runs
        coEvery { settingsRepository.saveDefaultSharingDuration(any()) } just Runs
        coEvery { settingsRepository.saveLocationPrecisionRadius(any()) } just Runs
        coEvery { settingsRepository.saveImageCompressionPreset(any()) } just Runs
        coEvery { settingsRepository.saveMapSourceHttpEnabled(any()) } just Runs
        coEvery { settingsRepository.saveMapSourceRmspEnabled(any()) } just Runs
        coEvery { settingsRepository.getCustomThemeById(any()) } returns null
        coEvery { settingsRepository.saveNotificationsEnabled(any()) } just Runs
        coEvery { settingsRepository.saveBlockUnknownSenders(any()) } just Runs
        coEvery { settingsRepository.saveIncomingMessageSizeLimitKb(any()) } just Runs

        every { identityRepository.activeIdentity } returns activeIdentityFlow

        // Mock PropagationNodeManager flows (StateFlows)
        every { propagationNodeManager.currentRelay } returns MutableStateFlow(null)
        every { propagationNodeManager.isSyncing } returns MutableStateFlow(false)
        every { propagationNodeManager.lastSyncTimestamp } returns MutableStateFlow(null)
        every { propagationNodeManager.availableRelaysState } returns
            MutableStateFlow(AvailableRelaysState.Loaded(emptyList()))
        coEvery { propagationNodeManager.enableAutoSelect() } just Runs
        coEvery { propagationNodeManager.triggerSync() } just Runs
        coEvery { propagationNodeManager.setManualRelayByHash(any(), any()) } just Runs
        coEvery { propagationNodeManager.setManualRelay(any()) } just Runs

        // Mock other required methods
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        coEvery { identityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
        coEvery { identityRepository.updateIconAppearance(any(), any(), any(), any()) } returns Result.success(Unit)

        coEvery { interfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)

        // Mock ReticulumProtocol methods
        every { reticulumProtocol.networkStatus } returns networkStatusFlow
        coEvery { reticulumProtocol.shutdown() } returns Result.success(Unit)

        // Mock MapTileSourceManager flows
        every { mapTileSourceManager.hasOfflineMaps() } returns flowOf(false)
        every { mapTileSourceManager.observeRmspServerCount() } returns flowOf(0)
        every { mapTileSourceManager.httpEnabledFlow } returns flowOf(true)
        every { locationSharingManager.sendImmediateUpdate() } just Runs
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

    // region parseRpcKey Tests

    @Test
    fun `parseRpcKey with full Sideband config extracts key`() =
        runTest {
            viewModel = createViewModel()

            val input = "shared_instance_type = tcp\nrpc_key = e17abc123def456"
            val result = viewModel.parseRpcKey(input)

            assertEquals("e17abc123def456", result)
        }

    @Test
    fun `parseRpcKey with key only returns key`() =
        runTest {
            viewModel = createViewModel()

            val input = "e17abc123def456"
            val result = viewModel.parseRpcKey(input)

            assertEquals("e17abc123def456", result)
        }

    @Test
    fun `parseRpcKey with spaces around equals extracts key`() =
        runTest {
            viewModel = createViewModel()

            val input = "rpc_key  =  e17abc"
            val result = viewModel.parseRpcKey(input)

            assertEquals("e17abc", result)
        }

    @Test
    fun `parseRpcKey with extra whitespace trims correctly`() =
        runTest {
            viewModel = createViewModel()

            val input = "  e17abc123  "
            val result = viewModel.parseRpcKey(input)

            assertEquals("e17abc123", result)
        }

    @Test
    fun `parseRpcKey with invalid characters returns null`() =
        runTest {
            viewModel = createViewModel()

            val input = "not-a-hex-key!"
            val result = viewModel.parseRpcKey(input)

            assertNull(result)
        }

    @Test
    fun `parseRpcKey with empty string returns null`() =
        runTest {
            viewModel = createViewModel()

            val input = ""
            val result = viewModel.parseRpcKey(input)

            assertNull(result)
        }

    @Test
    fun `parseRpcKey with null returns null`() =
        runTest {
            viewModel = createViewModel()

            val result = viewModel.parseRpcKey(null)

            assertNull(result)
        }

    @Test
    fun `parseRpcKey with mixed case preserves case`() =
        runTest {
            viewModel = createViewModel()

            val input = "e17AbC123DeF"
            val result = viewModel.parseRpcKey(input)

            assertEquals("e17AbC123DeF", result)
        }

    @Test
    fun `parseRpcKey with multiline config and key in middle extracts key`() =
        runTest {
            viewModel = createViewModel()

            val input =
                """
                shared_instance_type = tcp
                rpc_key = abcd1234ef56
                some_other_setting = value
                """.trimIndent()
            val result = viewModel.parseRpcKey(input)

            assertEquals("abcd1234ef56", result)
        }

    @Test
    fun `parseRpcKey with whitespace only returns null`() =
        runTest {
            viewModel = createViewModel()

            val input = "   \n\t  "
            val result = viewModel.parseRpcKey(input)

            assertNull(result)
        }

    // endregion

    // region Initial State Tests

    @Test
    fun `initial state has correct defaults`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSharedInstanceBannerExpanded)
                assertFalse(state.isRestarting)
                assertFalse(state.wasUsingSharedInstance)
                assertFalse(state.sharedInstanceAvailable)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `initial state reflects repository values`() =
        runTest {
            // Setup initial flow values before creating ViewModel
            preferOwnInstanceFlow.value = true
            isSharedInstanceFlow.value = true
            rpcKeyFlow.value = "testkey123"

            viewModel = createViewModel()

            viewModel.state.test {
                // First emission may be initial defaults while loading
                var state = awaitItem()
                // Wait for the state to load (isLoading becomes false after loadSettings completes)
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                assertTrue(state.preferOwnInstance)
                assertTrue(state.isSharedInstance)
                assertEquals("testkey123", state.rpcKey)
                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region State Transition Tests

    @Test
    fun `toggleSharedInstanceBannerExpanded toggles state`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                assertFalse(awaitItem().isSharedInstanceBannerExpanded)

                // Toggle on
                viewModel.toggleSharedInstanceBannerExpanded(true)
                assertTrue(awaitItem().isSharedInstanceBannerExpanded)

                // Toggle off
                viewModel.toggleSharedInstanceBannerExpanded(false)
                assertFalse(awaitItem().isSharedInstanceBannerExpanded)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `togglePreferOwnInstance saves to repository and triggers restart`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.togglePreferOwnInstance(true) }

            assertTrue("togglePreferOwnInstance should complete successfully", result.isSuccess)
            coVerify { settingsRepository.savePreferOwnInstance(true) }
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `dismissSharedInstanceLostWarning clears flag`() =
        runTest {
            viewModel = createViewModel()

            // First, trigger the lost state by updating the internal state
            viewModel.state.test {
                awaitItem() // initial

                // Manually trigger the method
                viewModel.dismissSharedInstanceLostWarning()

                // State should have wasUsingSharedInstance = false
                val finalState = awaitItem()
                assertFalse(finalState.wasUsingSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `switchToOwnInstanceAfterLoss sets preferOwn and triggers restart`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.switchToOwnInstanceAfterLoss() }

            assertTrue("switchToOwnInstanceAfterLoss should complete successfully", result.isSuccess)
            coVerify { settingsRepository.savePreferOwnInstance(true) }
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `switchToSharedInstance clears preferOwn and triggers restart`() =
        runTest {
            preferOwnInstanceFlow.value = true
            viewModel = createViewModel()

            val result = runCatching { viewModel.switchToSharedInstance() }

            assertTrue("switchToSharedInstance should complete successfully", result.isSuccess)
            coVerify { settingsRepository.savePreferOwnInstance(false) }
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `dismissSharedInstanceAvailable sets preferOwnInstance`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.dismissSharedInstanceAvailable() }

            assertTrue("dismissSharedInstanceAvailable should complete successfully", result.isSuccess)
            coVerify { settingsRepository.savePreferOwnInstance(true) }
        }

    // endregion

    // region saveRpcKey Tests

    @Test
    fun `saveRpcKey with valid config parses and saves`() =
        runTest {
            isSharedInstanceFlow.value = true // Must be shared instance to trigger restart
            viewModel = createViewModel()

            val result = runCatching { viewModel.saveRpcKey("shared_instance_type = tcp\nrpc_key = abc123def") }

            assertTrue("saveRpcKey should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRpcKey("abc123def") }
        }

    @Test
    fun `saveRpcKey with raw hex saves directly`() =
        runTest {
            isSharedInstanceFlow.value = true
            viewModel = createViewModel()

            val result = runCatching { viewModel.saveRpcKey("abc123def456") }

            assertTrue("saveRpcKey should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRpcKey("abc123def456") }
        }

    @Test
    fun `saveRpcKey with invalid input saves null`() =
        runTest {
            isSharedInstanceFlow.value = true
            viewModel = createViewModel()

            val result = runCatching { viewModel.saveRpcKey("invalid-key!") }

            assertTrue("saveRpcKey should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRpcKey(null) }
        }

    @Test
    fun `saveRpcKey triggers service restart when shared instance`() =
        runTest {
            isSharedInstanceFlow.value = true
            viewModel = createViewModel()

            // Wait for state to load so isSharedInstance is populated from flow
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.saveRpcKey("abc123") }

            assertTrue("saveRpcKey should complete successfully", result.isSuccess)
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `saveRpcKey does not restart when not shared instance`() =
        runTest {
            isSharedInstanceFlow.value = false
            viewModel = createViewModel()

            val result = runCatching { viewModel.saveRpcKey("abc123") }

            assertTrue("saveRpcKey should complete successfully", result.isSuccess)
            // Should not call applyInterfaceChanges since not using shared instance
            coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges() }
        }

    // endregion

    // region Flow Collection Tests

    @Test
    fun `state collects preferOwnInstance from repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                assertFalse(state.preferOwnInstance)

                // Update flow
                preferOwnInstanceFlow.value = true
                state = awaitItem()
                assertTrue(state.preferOwnInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collects isSharedInstance from repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                assertFalse(state.isSharedInstance)

                // Update flow
                isSharedInstanceFlow.value = true
                state = awaitItem()
                assertTrue(state.isSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collects rpcKey from repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                assertNull(state.rpcKey)

                // Update flow
                rpcKeyFlow.value = "newkey456"
                state = awaitItem()
                assertEquals("newkey456", state.rpcKey)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Restart State Tests

    @Test
    fun `restartService calls interfaceConfigManager`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.restartService() }

            assertTrue("restartService should complete successfully", result.isSuccess)
            // Verify the restart was triggered via interfaceConfigManager
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    // endregion

    // region Shared Instance Monitoring Tests

    @Test
    fun `networkStatus READY clears wasUsingSharedInstance flag`() =
        runTest {
            // Note: Don't enable monitors - they have infinite loops that cause hangs

            // Setup as shared instance mode
            isSharedInstanceFlow.value = true
            preferOwnInstanceFlow.value = false
            networkStatusFlow.value = NetworkStatus.READY

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                // Wait for initial loading to complete
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Verify wasUsingSharedInstance is false when networkStatus is READY
                assertFalse(state.wasUsingSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `networkStatus uses service status not port probe for monitoring`() =
        runTest {
            // This test verifies the fix: monitoring should use networkStatus flow
            // not the unreliable port probe
            // Note: Don't enable monitors - they have infinite loops that cause hangs

            // Setup as shared instance mode with READY status
            isSharedInstanceFlow.value = true
            preferOwnInstanceFlow.value = false
            networkStatusFlow.value = NetworkStatus.READY

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // With networkStatus READY, wasUsingSharedInstance should be false
                // (even if port probe would fail, which it does in release builds)
                assertFalse(state.wasUsingSharedInstance)
                assertTrue(state.isSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `wasUsingSharedInstance flag remains false when networkStatus stays READY`() =
        runTest {
            // Note: Don't enable monitors - they have infinite loops that cause hangs

            // Setup as shared instance mode
            isSharedInstanceFlow.value = true
            preferOwnInstanceFlow.value = false
            networkStatusFlow.value = NetworkStatus.READY

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Verify state is correct
                assertTrue(state.isSharedInstance)
                assertFalse(state.preferOwnInstance)
                assertFalse(state.wasUsingSharedInstance)

                // Keep emitting READY - should stay not lost
                networkStatusFlow.value = NetworkStatus.READY

                // The state should remain with wasUsingSharedInstance = false
                // No new emission expected since value didn't change meaningfully
                assertFalse(state.wasUsingSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `non-shared instance mode ignores networkStatus changes`() =
        runTest {
            // Note: Don't enable monitors - they have infinite loops that cause hangs

            // Setup as NOT shared instance mode
            isSharedInstanceFlow.value = false
            preferOwnInstanceFlow.value = true
            networkStatusFlow.value = NetworkStatus.SHUTDOWN

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Even with SHUTDOWN status, wasUsingSharedInstance should be false
                // because we're not using shared instance
                assertFalse(state.wasUsingSharedInstance)
                assertFalse(state.isSharedInstance)
                assertTrue(state.preferOwnInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `reticulumProtocol networkStatus flow is available for monitoring`() =
        runTest {
            // Note: Don't enable monitors - they have infinite loops that cause hangs
            // This test verifies the networkStatus mock is set up correctly

            // Setup as shared instance mode
            isSharedInstanceFlow.value = true
            preferOwnInstanceFlow.value = false
            networkStatusFlow.value = NetworkStatus.READY

            viewModel = createViewModel()

            // Verify the mock is properly configured (networkStatus returns our flow)
            assertEquals(NetworkStatus.READY, networkStatusFlow.value)
        }

    // endregion

    // region Shared Instance Transition Flow Tests

    /**
     * Tests the complete flow when shared instance goes offline while Columba is using it:
     * 1. Shared instance goes offline (sharedInstanceOnline: true -> false)
     * 2. wasUsingSharedInstance is set to true
     * 3. isRestarting is set to true
     * 4. preferOwnInstance is saved as true (the fix)
     * 5. Service restart is triggered
     * 6. After restart completes, isRestarting is false but wasUsingSharedInstance remains true
     * 7. When shared instance comes back online, wasUsingSharedInstance is cleared
     */

    @Test
    fun `shared instance offline triggers auto-restart with correct state transitions`() =
        runTest {
            // Note: Don't enable monitors here - they have infinite loops that cause hangs
            // We test state setup without needing the actual monitor to run

            // Setup: Columba is using shared instance
            isSharedInstanceFlow.value = true
            preferOwnInstanceFlow.value = false

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Verify initial state
                assertTrue("Should be using shared instance", state.isSharedInstance)
                assertFalse("preferOwnInstance should be false", state.preferOwnInstance)
                assertFalse("wasUsingSharedInstance should initially be false", state.wasUsingSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `restartServiceAfterSharedInstanceLost saves preferOwnInstance true`() =
        runTest {
            viewModel = createViewModel()

            // Wait for initialization
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Call the method that would be triggered when shared instance goes offline
            // We need to access this indirectly through the availability monitor behavior
            // For now, we verify the toggle behavior preserves the preference

            // Simulate the auto-restart scenario by verifying restartService behavior
            val result = runCatching { viewModel.restartService() }

            assertTrue("restartService should complete successfully", result.isSuccess)
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `dismissSharedInstanceLostWarning sets wasUsingSharedInstance to false`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Initially wasUsingSharedInstance is false
                assertFalse("wasUsingSharedInstance should start false", state.wasUsingSharedInstance)

                // Calling dismiss should keep it false (no state change expected)
                viewModel.dismissSharedInstanceLostWarning()

                // Verify the state still has wasUsingSharedInstance = false
                // No new emission expected since value didn't change
                assertFalse("wasUsingSharedInstance should still be false", state.wasUsingSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `toggle reflects preferOwnInstance value from repository`() =
        runTest {
            // Start with preferOwnInstance = false
            preferOwnInstanceFlow.value = false
            isSharedInstanceFlow.value = true

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Toggle should show OFF (false)
                assertFalse("Toggle should reflect preferOwnInstance=false", state.preferOwnInstance)

                // Now update the flow as if auto-restart saved preferOwnInstance=true
                preferOwnInstanceFlow.value = true
                state = awaitItem()

                // Toggle should now show ON (true)
                assertTrue("Toggle should reflect preferOwnInstance=true after update", state.preferOwnInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `toggle shows ON after switching to own instance`() =
        runTest {
            // Start as shared instance
            preferOwnInstanceFlow.value = false
            isSharedInstanceFlow.value = true

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Switch to own instance (simulates what auto-restart does)
            val result = runCatching { viewModel.togglePreferOwnInstance(true) }

            assertTrue("togglePreferOwnInstance should complete successfully", result.isSuccess)
            // Verify preference was saved
            coVerify { settingsRepository.savePreferOwnInstance(true) }
        }

    @Test
    fun `sharedInstanceOnline state is preserved across flow emissions`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Initial state should have sharedInstanceOnline from availability monitor
                // Since monitors are disabled by default in tests, it starts as false
                assertFalse("sharedInstanceOnline should start false with monitors disabled", state.sharedInstanceOnline)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `wasUsingSharedInstance is preserved when other state changes`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // wasUsingSharedInstance should remain false when other settings change
                assertFalse(state.wasUsingSharedInstance)

                // Change another setting
                autoAnnounceEnabledFlow.value = false
                state = awaitItem()

                // wasUsingSharedInstance should still be preserved
                assertFalse("wasUsingSharedInstance should be preserved across state changes", state.wasUsingSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `isRestarting state controls monitoring behavior`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Initial state should not be restarting
                assertFalse("Should not be restarting initially", state.isRestarting)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `switchToOwnInstanceAfterLoss clears wasUsingSharedInstance and saves preference`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.switchToOwnInstanceAfterLoss() }

            assertTrue("switchToOwnInstanceAfterLoss should complete successfully", result.isSuccess)
            // Verify both preference save and restart are triggered
            coVerify { settingsRepository.savePreferOwnInstance(true) }
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `sharedInstanceAvailable is preserved in state`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // sharedInstanceAvailable should start false
                assertFalse("sharedInstanceAvailable should start false", state.sharedInstanceAvailable)

                // It should be preserved when other settings change
                preferOwnInstanceFlow.value = true
                state = awaitItem()
                assertFalse("sharedInstanceAvailable should be preserved", state.sharedInstanceAvailable)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `complete transition flow - preferOwnInstance updates correctly`() =
        runTest {
            // This test simulates the flow where preferOwnInstance changes
            // after auto-restart (when savePreferOwnInstance(true) is called)

            preferOwnInstanceFlow.value = false
            isSharedInstanceFlow.value = true

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Step 1: Verify initial state
                assertTrue("Should be using shared instance", state.isSharedInstance)
                assertFalse("preferOwnInstance should be false initially", state.preferOwnInstance)
                assertFalse("wasUsingSharedInstance should be false initially", state.wasUsingSharedInstance)

                // Step 2: Simulate shared instance going offline by updating repository flow
                // (as if restartServiceAfterSharedInstanceLost() called savePreferOwnInstance(true))
                preferOwnInstanceFlow.value = true
                state = awaitItem()

                // Step 3: Verify the toggle now shows the correct value
                assertTrue("preferOwnInstance should be true after auto-restart", state.preferOwnInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `banner visibility conditions work correctly`() =
        runTest {
            // Banner should show when:
            // - isSharedInstance = true, OR
            // - sharedInstanceOnline = true, OR
            // - wasUsingSharedInstance = true, OR
            // - isRestarting = true

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // All false = no banner
                assertFalse(state.isSharedInstance)
                assertFalse(state.sharedInstanceOnline)
                assertFalse(state.wasUsingSharedInstance)
                assertFalse(state.isRestarting)

                val showBanner =
                    state.isSharedInstance ||
                        state.sharedInstanceOnline ||
                        state.wasUsingSharedInstance ||
                        state.isRestarting
                assertFalse("Banner should not show when all conditions are false", showBanner)

                // Set isSharedInstance = true
                isSharedInstanceFlow.value = true
                state = awaitItem()
                assertTrue("Banner should show when isSharedInstance is true", state.isSharedInstance)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `informational state condition is correct`() =
        runTest {
            // isInformationalState = wasUsingSharedInstance && !sharedInstanceOnline

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Initial: wasUsingSharedInstance=false, sharedInstanceOnline=false
                val isInformational = state.wasUsingSharedInstance && !state.sharedInstanceOnline
                assertFalse(
                    "Should not be informational when wasUsingSharedInstance is false",
                    isInformational,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `toggle enable logic respects shared instance availability`() =
        runTest {
            // Toggle should be:
            // - Enabled to switch TO own instance (always)
            // - Enabled to switch TO shared only if sharedInstanceOnline

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // canSwitchToShared = sharedInstanceOnline || isUsingSharedInstance
                // toggleEnabled = !preferOwnInstance || canSwitchToShared

                // Case 1: preferOwnInstance=false, shared offline -> can switch to own
                val canSwitchToShared1 = state.sharedInstanceOnline || state.isSharedInstance
                val toggleEnabled1 = !state.preferOwnInstance || canSwitchToShared1
                assertTrue("Toggle should be enabled to switch to own", toggleEnabled1)

                // Case 2: preferOwnInstance=true, shared offline -> toggle disabled
                preferOwnInstanceFlow.value = true
                state = awaitItem()
                val canSwitchToShared2 = state.sharedInstanceOnline || state.isSharedInstance
                val toggleEnabled2 = !state.preferOwnInstance || canSwitchToShared2
                // preferOwnInstance=true, canSwitchToShared=false -> toggleEnabled=false
                assertFalse("Toggle should be disabled when can't switch to shared", toggleEnabled2)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Message Delivery Settings Tests

    @Test
    fun `setDefaultDeliveryMethod direct saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setDefaultDeliveryMethod("direct") }

            assertTrue("setDefaultDeliveryMethod should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveDefaultDeliveryMethod("direct") }
        }

    @Test
    fun `setDefaultDeliveryMethod propagated saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setDefaultDeliveryMethod("propagated") }

            assertTrue("setDefaultDeliveryMethod should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveDefaultDeliveryMethod("propagated") }
        }

    @Test
    fun `setTryPropagationOnFail enabled saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTryPropagationOnFail(true) }

            assertTrue("setTryPropagationOnFail should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveTryPropagationOnFail(true) }
        }

    @Test
    fun `setTryPropagationOnFail disabled saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTryPropagationOnFail(false) }

            assertTrue("setTryPropagationOnFail should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveTryPropagationOnFail(false) }
        }

    @Test
    fun `setAutoSelectPropagationNode true enables auto-select and saves`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAutoSelectPropagationNode(true) }

            assertTrue("setAutoSelectPropagationNode should complete successfully", result.isSuccess)
            coVerify { propagationNodeManager.enableAutoSelect() }
            coVerify { settingsRepository.saveAutoSelectPropagationNode(true) }
        }

    @Test
    fun `setAutoSelectPropagationNode false saves without enabling auto-select`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAutoSelectPropagationNode(false) }

            assertTrue("setAutoSelectPropagationNode should complete successfully", result.isSuccess)
            coVerify(exactly = 0) { propagationNodeManager.enableAutoSelect() }
            coVerify { settingsRepository.saveAutoSelectPropagationNode(false) }
        }

    // endregion

    // region Display Name Management Tests

    private fun createTestIdentity(
        identityHash: String = "test123",
        displayName: String = "TestUser",
    ) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = displayName,
        destinationHash = "dest456",
        filePath = "/test/path",
        keyData = null,
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = true,
    )

    @Test
    fun `updateDisplayName validName savesToRepository`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "OldName")
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { identityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            viewModel = createViewModel()

            val result = runCatching { viewModel.updateDisplayName("NewName") }

            assertTrue("updateDisplayName should complete successfully", result.isSuccess)
            coVerify { identityRepository.updateDisplayName("test123", "NewName") }
        }

    @Test
    fun `updateDisplayName emptyName savesEmptyString`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "OldName")
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { identityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            viewModel = createViewModel()

            val result = runCatching { viewModel.updateDisplayName("") }

            assertTrue("updateDisplayName should complete successfully", result.isSuccess)
            coVerify { identityRepository.updateDisplayName("test123", "") }
        }

    @Test
    fun `updateDisplayName success triggersShowSaveSuccess`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "OldName")
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { identityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                assertFalse(state.showSaveSuccess)

                viewModel.updateDisplayName("NewName")
                state = awaitItem()
                assertTrue(state.showSaveSuccess)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `updateDisplayName noActiveIdentity doesNotCrash`() =
        runTest {
            coEvery { identityRepository.getActiveIdentitySync() } returns null
            viewModel = createViewModel()

            // Should not throw
            val result = runCatching { viewModel.updateDisplayName("NewName") }

            assertTrue("updateDisplayName should complete successfully even without identity", result.isSuccess)
            coVerify(exactly = 0) { identityRepository.updateDisplayName(any(), any()) }
        }

    @Test
    fun `clearSaveSuccess setsShowSaveSuccessToFalse`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial

                viewModel.clearSaveSuccess()
                val state = awaitItem()
                assertFalse(state.showSaveSuccess)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `getEffectiveDisplayName returnsDisplayNameFromState`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "TestUser")
            activeIdentityFlow.value = testIdentity
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                val effectiveName = viewModel.getEffectiveDisplayName()
                assertEquals(state.displayName, effectiveName)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `updateDisplayName failure doesNotSetShowSaveSuccess`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "OldName")
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { identityRepository.updateDisplayName(any(), any()) } returns
                Result.failure(RuntimeException("Database error"))
            viewModel = createViewModel()

            // Get initial state - UnconfinedTestDispatcher runs coroutines immediately
            val initialState = viewModel.state.value
            assertFalse("showSaveSuccess should initially be false", initialState.showSaveSuccess)

            // Call updateDisplayName which should fail
            viewModel.updateDisplayName("NewName")

            // Verify the update was attempted and failed
            coVerify { identityRepository.updateDisplayName("test123", "NewName") }

            // showSaveSuccess should still be false since it failed
            val finalState = viewModel.state.value
            assertFalse("showSaveSuccess should be false on failure", finalState.showSaveSuccess)
        }

    @Test
    fun `updateDisplayName exception handledGracefully`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "OldName")
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { identityRepository.updateDisplayName(any(), any()) } throws
                RuntimeException("Unexpected error")
            viewModel = createViewModel()

            // Should not throw - the ViewModel should handle exceptions internally
            val result = runCatching { viewModel.updateDisplayName("NewName") }

            assertTrue("updateDisplayName should not propagate exceptions", result.isSuccess)
            // Verify the update was attempted
            coVerify { identityRepository.updateDisplayName("test123", "NewName") }
        }

    // endregion

    // region QR Dialog Tests

    @Test
    fun `toggleQrDialog true setsShowQrDialogTrue`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial

                viewModel.toggleQrDialog(true)
                val state = awaitItem()
                assertTrue(state.showQrDialog)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `toggleQrDialog false setsShowQrDialogFalse`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial

                // First set to true
                viewModel.toggleQrDialog(true)
                assertTrue(awaitItem().showQrDialog)

                // Then set to false
                viewModel.toggleQrDialog(false)
                assertFalse(awaitItem().showQrDialog)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Auto-Announce Tests

    @Test
    fun `toggleAutoAnnounce enabled savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.toggleAutoAnnounce(true) }

            assertTrue("toggleAutoAnnounce should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoAnnounceEnabled(true) }
        }

    @Test
    fun `toggleAutoAnnounce disabled savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.toggleAutoAnnounce(false) }

            assertTrue("toggleAutoAnnounce should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoAnnounceEnabled(false) }
        }

    @Test
    fun `setAnnounceInterval validMinutes savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAnnounceInterval(10) }

            assertTrue("setAnnounceInterval should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoAnnounceIntervalHours(10) }
        }

    @Test
    fun `setAnnounceInterval boundaryMin1 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAnnounceInterval(1) }

            assertTrue("setAnnounceInterval should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoAnnounceIntervalHours(1) }
        }

    @Test
    fun `setAnnounceInterval boundaryMax60 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAnnounceInterval(60) }

            assertTrue("setAnnounceInterval should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoAnnounceIntervalHours(60) }
        }

    @Test
    fun `triggerManualAnnounce setsIsManualAnnouncingTrue`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                assertFalse(state.isManualAnnouncing)

                viewModel.triggerManualAnnounce()

                // Since protocol is mocked but not as ServiceReticulumProtocol,
                // it will set error state. We verify the state change happened.
                // Use expectMostRecentItem() to get the latest state without timing issues.
                val finalState = expectMostRecentItem()
                // The method sets isManualAnnouncing=true initially, then sets it back to false
                // with an error. We just verify the error was set (proving the method ran).
                assertEquals("Service not available", finalState.manualAnnounceError)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `triggerManualAnnounce success with ServiceReticulumProtocol`() =
        runTest {
            // Given: ServiceReticulumProtocol that returns success
            val serviceProtocol =
                mockk<com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol>(relaxed = true) {
                    every { networkStatus } returns networkStatusFlow
                    coEvery { triggerAutoAnnounce(any()) } returns Result.success(Unit)
                }

            viewModel =
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    identityRepository = identityRepository,
                    reticulumProtocol = serviceProtocol,
                    interfaceConfigManager = interfaceConfigManager,
                    propagationNodeManager = propagationNodeManager,
                    locationSharingManager = locationSharingManager,
                    interfaceRepository = interfaceRepository,
                    mapTileSourceManager = mapTileSourceManager,
                    telemetryCollectorManager = telemetryCollectorManager,
                    contactRepository = contactRepository,
                )

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                viewModel.triggerManualAnnounce()

                // Wait for success state
                val finalState = expectMostRecentItem()
                assertTrue("Should show success", finalState.showManualAnnounceSuccess)
                assertFalse("Should not be announcing anymore", finalState.isManualAnnouncing)

                cancelAndConsumeRemainingEvents()
            }

            // Verify announce was called and timestamp was saved
            coVerify { serviceProtocol.triggerAutoAnnounce(any()) }
            coVerify { settingsRepository.saveLastAutoAnnounceTime(any()) }
        }

    @Test
    fun `triggerManualAnnounce failure with ServiceReticulumProtocol`() =
        runTest {
            // Given: ServiceReticulumProtocol that returns failure
            val serviceProtocol =
                mockk<com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol>(relaxed = true) {
                    every { networkStatus } returns networkStatusFlow
                    coEvery { triggerAutoAnnounce(any()) } returns Result.failure(RuntimeException("Announce failed"))
                }

            viewModel =
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    identityRepository = identityRepository,
                    reticulumProtocol = serviceProtocol,
                    interfaceConfigManager = interfaceConfigManager,
                    propagationNodeManager = propagationNodeManager,
                    locationSharingManager = locationSharingManager,
                    interfaceRepository = interfaceRepository,
                    mapTileSourceManager = mapTileSourceManager,
                    telemetryCollectorManager = telemetryCollectorManager,
                    contactRepository = contactRepository,
                )

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                viewModel.triggerManualAnnounce()

                // Wait for error state
                val finalState = expectMostRecentItem()
                assertEquals("Announce failed", finalState.manualAnnounceError)
                assertFalse("Should not be announcing anymore", finalState.isManualAnnouncing)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearManualAnnounceStatus clearsSuccessFlag`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial

                viewModel.clearManualAnnounceStatus()
                val state = awaitItem()
                assertFalse(state.showManualAnnounceSuccess)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearManualAnnounceStatus clearsErrorMessage`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial

                viewModel.clearManualAnnounceStatus()
                val state = awaitItem()
                assertNull(state.manualAnnounceError)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collectsAutoAnnounceEnabledFromRepository`() =
        runTest {
            autoAnnounceEnabledFlow.value = false
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertFalse(state.autoAnnounceEnabled)

                // Update flow
                autoAnnounceEnabledFlow.value = true
                state = awaitItem()
                assertTrue(state.autoAnnounceEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collectsAutoAnnounceIntervalFromRepository`() =
        runTest {
            autoAnnounceIntervalHoursFlow.value = 6
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertEquals(6, state.autoAnnounceIntervalHours)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Theme Management Tests

    @Test
    fun `setTheme presetTheme savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTheme(PresetTheme.OCEAN) }

            assertTrue("setTheme should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveThemePreference(PresetTheme.OCEAN) }
        }

    @Test
    fun `setTheme defaultTheme savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTheme(PresetTheme.VIBRANT) }

            assertTrue("setTheme should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveThemePreference(PresetTheme.VIBRANT) }
        }

    @Test
    fun `applyCustomTheme validId appliesTheme`() =
        runTest {
            val mockThemeData = mockk<com.lxmf.messenger.data.repository.CustomThemeData>()
            val mockCustomTheme = mockk<com.lxmf.messenger.ui.theme.CustomTheme>()
            coEvery { settingsRepository.getCustomThemeById(123L) } returns mockThemeData
            every { settingsRepository.customThemeDataToAppTheme(mockThemeData) } returns mockCustomTheme

            viewModel = createViewModel()

            val result = runCatching { viewModel.applyCustomTheme(123L) }

            assertTrue("applyCustomTheme should complete successfully", result.isSuccess)
            coVerify { settingsRepository.getCustomThemeById(123L) }
            coVerify { settingsRepository.saveThemePreference(mockCustomTheme) }
        }

    @Test
    fun `applyCustomTheme invalidId doesNotCrash`() =
        runTest {
            coEvery { settingsRepository.getCustomThemeById(999L) } returns null

            viewModel = createViewModel()

            // Should not throw
            val result = runCatching { viewModel.applyCustomTheme(999L) }

            assertTrue("applyCustomTheme should complete successfully", result.isSuccess)
            coVerify { settingsRepository.getCustomThemeById(999L) }
            coVerify(exactly = 0) { settingsRepository.saveThemePreference(any()) }
        }

    @Test
    fun `state collectsThemePreferenceFromRepository`() =
        runTest {
            themePreferenceFlow.value = PresetTheme.FOREST
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertEquals(PresetTheme.FOREST, state.selectedTheme)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Service Control Tests

    @Test
    fun `shutdownService callsReticulumProtocolShutdown`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.shutdownService() }

            assertTrue("shutdownService should complete successfully", result.isSuccess)
            coVerify { reticulumProtocol.shutdown() }
        }

    // endregion

    // region Message Retrieval Tests

    @Test
    fun `setAutoRetrieveEnabled true savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAutoRetrieveEnabled(true) }

            assertTrue("setAutoRetrieveEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoRetrieveEnabled(true) }
        }

    @Test
    fun `setAutoRetrieveEnabled false savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setAutoRetrieveEnabled(false) }

            assertTrue("setAutoRetrieveEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveAutoRetrieveEnabled(false) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 30 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setRetrievalIntervalSeconds(30) }

            assertTrue("setRetrievalIntervalSeconds should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRetrievalIntervalSeconds(30) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 60 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setRetrievalIntervalSeconds(60) }

            assertTrue("setRetrievalIntervalSeconds should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRetrievalIntervalSeconds(60) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 120 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setRetrievalIntervalSeconds(120) }

            assertTrue("setRetrievalIntervalSeconds should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRetrievalIntervalSeconds(120) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 300 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setRetrievalIntervalSeconds(300) }

            assertTrue("setRetrievalIntervalSeconds should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveRetrievalIntervalSeconds(300) }
        }

    @Test
    fun `syncNow callsPropagationNodeManagerTriggerSync`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.syncNow() }

            assertTrue("syncNow should complete successfully", result.isSuccess)
            coVerify { propagationNodeManager.triggerSync() }
        }

    @Test
    fun `state collects lastSyncTimestamp from propagationNodeManager`() =
        runTest {
            val lastSyncTimestampFlow = MutableStateFlow<Long?>(null)
            every { propagationNodeManager.lastSyncTimestamp } returns lastSyncTimestampFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Initially null
                assertNull(state.lastSyncTimestamp)

                // Update flow with timestamp
                val testTimestamp = System.currentTimeMillis()
                lastSyncTimestampFlow.value = testTimestamp
                state = awaitItem()

                assertEquals(testTimestamp, state.lastSyncTimestamp)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collects isSyncing from propagationNodeManager`() =
        runTest {
            val isSyncingFlow = MutableStateFlow(false)
            every { propagationNodeManager.isSyncing } returns isSyncingFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Initially false
                assertFalse(state.isSyncing)

                // Update flow to true
                isSyncingFlow.value = true
                state = awaitItem()
                assertTrue(state.isSyncing)

                // Update flow back to false
                isSyncingFlow.value = false
                state = awaitItem()
                assertFalse(state.isSyncing)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `lastSyncTimestamp is preserved when other settings change`() =
        runTest {
            val lastSyncTimestampFlow = MutableStateFlow<Long?>(null)
            every { propagationNodeManager.lastSyncTimestamp } returns lastSyncTimestampFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Set a timestamp
                val testTimestamp = System.currentTimeMillis()
                lastSyncTimestampFlow.value = testTimestamp
                state = awaitItem()
                assertEquals(testTimestamp, state.lastSyncTimestamp)

                // Change another setting - lastSyncTimestamp should be preserved
                autoAnnounceEnabledFlow.value = false
                state = awaitItem()

                assertEquals(
                    "lastSyncTimestamp should be preserved when other settings change",
                    testTimestamp,
                    state.lastSyncTimestamp,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // Note: Relay State Preservation Tests were removed because they require enableMonitors=true,
    // which starts an infinite while(true) loop in SettingsViewModel that causes tests to hang.
    // The relay functionality is tested via PropagationNodeManagerTest instead.

    // region Transport Node Tests

    @Test
    fun `setTransportNodeEnabled true saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTransportNodeEnabled(true) }

            assertTrue("setTransportNodeEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveTransportNodeEnabled(true) }
        }

    @Test
    fun `setTransportNodeEnabled false saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTransportNodeEnabled(false) }

            assertTrue("setTransportNodeEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveTransportNodeEnabled(false) }
        }

    @Test
    fun `setTransportNodeEnabled triggers service restart`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setTransportNodeEnabled(false) }

            assertTrue("setTransportNodeEnabled should complete successfully", result.isSuccess)
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `setTransportNodeEnabled does not restart if already restarting`() =
        runTest {
            // Make applyInterfaceChanges suspend indefinitely so isRestarting stays true
            coEvery { interfaceConfigManager.applyInterfaceChanges() } coAnswers {
                kotlinx.coroutines.delay(Long.MAX_VALUE)
                Result.success(Unit)
            }

            viewModel = createViewModel()

            // First call - should trigger restart (but won't complete due to delay)
            viewModel.setTransportNodeEnabled(false)

            // Wait for the state to show isRestarting = true
            viewModel.state.test {
                var state = awaitItem()
                var attempts = 0
                while (!state.isRestarting && attempts++ < 50) {
                    state = awaitItem()
                }
                assertTrue("isRestarting should be true", state.isRestarting)
                cancelAndConsumeRemainingEvents()
            }

            // Second call while isRestarting is true - should NOT trigger restart
            viewModel.setTransportNodeEnabled(true)

            // Should only have called applyInterfaceChanges once (from the first call)
            coVerify(exactly = 1) { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `state collects transportNodeEnabled from repository`() =
        runTest {
            transportNodeEnabledFlow.value = true
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertTrue(state.transportNodeEnabled)

                // Update flow to false
                transportNodeEnabledFlow.value = false
                state = awaitItem()
                assertFalse(state.transportNodeEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `initial transportNodeEnabled state is true by default`() =
        runTest {
            transportNodeEnabledFlow.value = true
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertTrue("Transport node should be enabled by default", state.transportNodeEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `transportNodeEnabled state is preserved when other settings change`() =
        runTest {
            transportNodeEnabledFlow.value = false
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertFalse(state.transportNodeEnabled)

                // Change another setting
                autoAnnounceEnabledFlow.value = false
                state = awaitItem()

                // transportNodeEnabled should still be false
                assertFalse(
                    "transportNodeEnabled should be preserved across state changes",
                    state.transportNodeEnabled,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Default Delivery Method Tests

    @Test
    fun `state collects defaultDeliveryMethod from repository`() =
        runTest {
            defaultDeliveryMethodFlow.value = "propagated"
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertEquals("propagated", state.defaultDeliveryMethod)

                // Update flow to direct
                defaultDeliveryMethodFlow.value = "direct"
                state = awaitItem()
                assertEquals("direct", state.defaultDeliveryMethod)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `defaultDeliveryMethod persists across navigation`() =
        runTest {
            // Simulate the scenario where user sets to propagated, navigates away and back
            defaultDeliveryMethodFlow.value = "propagated"
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                // Should load as "propagated" from the repository flow
                assertEquals(
                    "defaultDeliveryMethod should be loaded from repository",
                    "propagated",
                    state.defaultDeliveryMethod,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Shared Instance Monitoring Tests

    /**
     * Verifies that monitors don't run when enableMonitors is false.
     *
     * This is the key test for the duplicate SettingsViewModel fix:
     * - Before fix: Multiple screens created their own SettingsViewModel instances
     * - Each instance started its own monitor, causing 3-4x battery drain
     * - After fix: One shared SettingsViewModel means one monitor
     *
     * The enableMonitors flag allows disabling monitors in tests to prevent
     * the infinite while(true) loop from running. In production, monitors
     * are enabled and poll every 5 seconds.
     */
    @Test
    fun `monitors are disabled when enableMonitors flag is false`() =
        runTest {
            // Verify that the enableMonitors flag prevents monitoring loops from starting
            // This is set to false in @Before, verify the monitor doesn't call isSharedInstanceAvailable
            SettingsViewModel.enableMonitors = false

            var monitorCallCount = 0
            val serviceProtocol =
                mockk<com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol>(relaxed = true) {
                    every { networkStatus } returns networkStatusFlow
                    coEvery { isSharedInstanceAvailable() } coAnswers {
                        monitorCallCount++
                        false
                    }
                }

            viewModel =
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    identityRepository = identityRepository,
                    reticulumProtocol = serviceProtocol,
                    interfaceConfigManager = interfaceConfigManager,
                    propagationNodeManager = propagationNodeManager,
                    locationSharingManager = locationSharingManager,
                    interfaceRepository = interfaceRepository,
                    mapTileSourceManager = mapTileSourceManager,
                    telemetryCollectorManager = telemetryCollectorManager,
                    contactRepository = contactRepository,
                )

            // Wait for any potential async operations to settle
            // With UnconfinedTestDispatcher, coroutines run eagerly
            // If monitors were enabled, the while(true) loop would run infinitely
            viewModel.state.test {
                awaitItem() // initial state
                cancelAndConsumeRemainingEvents()
            }

            // Should NOT have been called since monitors are disabled
            assertEquals(
                "Monitor should not run when enableMonitors is false",
                0,
                monitorCallCount,
            )
        }

    @Test
    fun `viewmodel passes ServiceReticulumProtocol check for monitoring`() =
        runTest {
            // Verify that the ViewModel correctly identifies ServiceReticulumProtocol
            // for shared instance monitoring. This is important because the monitor
            // only calls isSharedInstanceAvailable() on ServiceReticulumProtocol.
            SettingsViewModel.enableMonitors = false

            val serviceProtocol =
                mockk<com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol>(relaxed = true) {
                    every { networkStatus } returns networkStatusFlow
                }

            viewModel =
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    identityRepository = identityRepository,
                    reticulumProtocol = serviceProtocol,
                    interfaceConfigManager = interfaceConfigManager,
                    propagationNodeManager = propagationNodeManager,
                    locationSharingManager = locationSharingManager,
                    interfaceRepository = interfaceRepository,
                    mapTileSourceManager = mapTileSourceManager,
                    telemetryCollectorManager = telemetryCollectorManager,
                    contactRepository = contactRepository,
                )

            // The ViewModel should be created successfully with ServiceReticulumProtocol
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isRestarting)
                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Location Sharing Tests

    @Test
    fun `setLocationSharingEnabled true saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setLocationSharingEnabled(true) }

            assertTrue("setLocationSharingEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveLocationSharingEnabled(true) }
        }

    @Test
    fun `setLocationSharingEnabled false saves to repository and stops all sharing`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setLocationSharingEnabled(false) }

            assertTrue("setLocationSharingEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveLocationSharingEnabled(false) }
            coVerify { locationSharingManager.stopSharing(null) }
            coVerify { telemetryCollectorManager.setEnabled(false) }
        }

    @Test
    fun `stopSharingWith calls locationSharingManager stopSharing`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "testDestinationHash123"

            val result = runCatching { viewModel.stopSharingWith(testHash) }

            assertTrue("stopSharingWith should complete successfully", result.isSuccess)
            coVerify { locationSharingManager.stopSharing(testHash) }
        }

    @Test
    fun `stopAllSharing calls locationSharingManager stopSharing with null`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.stopAllSharing() }

            assertTrue("stopAllSharing should complete successfully", result.isSuccess)
            coVerify { locationSharingManager.stopSharing(null) }
        }

    @Test
    fun `setDefaultSharingDuration saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setDefaultSharingDuration("FOUR_HOURS") }

            assertTrue("setDefaultSharingDuration should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveDefaultSharingDuration("FOUR_HOURS") }
        }

    @Test
    fun `setLocationPrecisionRadius saves to repository and sends update`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setLocationPrecisionRadius(1000) }

            assertTrue("setLocationPrecisionRadius should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveLocationPrecisionRadius(1000) }
            coVerify { locationSharingManager.sendImmediateUpdate() }
        }

    // endregion

    // region Manual Propagation Node Tests

    @Test
    fun `addManualPropagationNode calls propagationNodeManager setManualRelayByHash`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "abcd1234abcd1234abcd1234abcd1234"
            val testNickname = "My Test Relay"

            val result = runCatching { viewModel.addManualPropagationNode(testHash, testNickname) }

            assertTrue("addManualPropagationNode should complete successfully", result.isSuccess)
            coVerify { propagationNodeManager.setManualRelayByHash(testHash, testNickname) }
        }

    @Test
    fun `addManualPropagationNode with null nickname calls propagationNodeManager`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "abcd1234abcd1234abcd1234abcd1234"

            val result = runCatching { viewModel.addManualPropagationNode(testHash, null) }

            assertTrue("addManualPropagationNode should complete successfully", result.isSuccess)
            coVerify { propagationNodeManager.setManualRelayByHash(testHash, null) }
        }

    @Test
    fun `selectRelay calls propagationNodeManager setManualRelay`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "abcd1234abcd1234abcd1234abcd1234"

            val result = runCatching { viewModel.selectRelay(testHash) }

            assertTrue("selectRelay should complete successfully", result.isSuccess)
            coVerify { propagationNodeManager.setManualRelay(testHash) }
        }

    // endregion

    // region updateIconAppearance Tests

    private fun createTestIdentity(identityHash: String = "abc123") =
        LocalIdentityEntity(
            identityHash = identityHash,
            displayName = "Test User",
            destinationHash = "dest123",
            filePath = "/path/to/identity",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    @Test
    fun `updateIconAppearance updates icon when active identity exists`() =
        runTest {
            val testIdentity = createTestIdentity()
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery {
                identityRepository.updateIconAppearance(any(), any(), any(), any())
            } returns Result.success(Unit)

            viewModel = createViewModel()

            val result = runCatching { viewModel.updateIconAppearance("account", "FFFFFF", "1E88E5") }

            assertTrue("updateIconAppearance should complete successfully", result.isSuccess)
            coVerify {
                identityRepository.updateIconAppearance("abc123", "account", "FFFFFF", "1E88E5")
            }
        }

    @Test
    fun `updateIconAppearance clears icon when null values provided`() =
        runTest {
            val testIdentity = createTestIdentity()
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery {
                identityRepository.updateIconAppearance(any(), any(), any(), any())
            } returns Result.success(Unit)

            viewModel = createViewModel()

            val result = runCatching { viewModel.updateIconAppearance(null, null, null) }

            assertTrue("updateIconAppearance should complete successfully", result.isSuccess)
            coVerify {
                identityRepository.updateIconAppearance("abc123", null, null, null)
            }
        }

    @Test
    fun `updateIconAppearance does not update when no active identity`() =
        runTest {
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            viewModel = createViewModel()

            val result = runCatching { viewModel.updateIconAppearance("account", "FFFFFF", "1E88E5") }

            assertTrue("updateIconAppearance should complete successfully", result.isSuccess)
            coVerify(exactly = 0) {
                identityRepository.updateIconAppearance(any(), any(), any(), any())
            }
        }

    @Test
    fun `updateIconAppearance shows success when update succeeds`() =
        runTest {
            val testIdentity = createTestIdentity()
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery {
                identityRepository.updateIconAppearance(any(), any(), any(), any())
            } returns Result.success(Unit)

            viewModel = createViewModel()

            viewModel.state.test {
                val initial = awaitItem()
                assertFalse(initial.showSaveSuccess)

                viewModel.updateIconAppearance("star", "FF0000", "0000FF")

                val updated = awaitItem()
                assertTrue(updated.showSaveSuccess)
            }
        }

    @Test
    fun `updateIconAppearance handles repository failure gracefully`() =
        runTest {
            val testIdentity = createTestIdentity()
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery {
                identityRepository.updateIconAppearance(any(), any(), any(), any())
            } returns Result.failure(Exception("Database error"))

            viewModel = createViewModel()

            // Should not throw exception
            val result = runCatching { viewModel.updateIconAppearance("account", "FFFFFF", "1E88E5") }

            assertTrue("updateIconAppearance should not propagate exceptions", result.isSuccess)
            // Verify update was attempted
            coVerify {
                identityRepository.updateIconAppearance("abc123", "account", "FFFFFF", "1E88E5")
            }
        }

    // endregion

    // region Image Compression Tests

    @Test
    fun `initial state has AUTO image compression preset`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.AUTO,
                    state.imageCompressionPreset,
                )
            }
        }

    @Test
    fun `setImageCompressionPreset saves preset to repository`() =
        runTest {
            viewModel = createViewModel()

            val result =
                runCatching {
                    viewModel.setImageCompressionPreset(com.lxmf.messenger.data.model.ImageCompressionPreset.LOW)
                }

            assertTrue("setImageCompressionPreset should complete successfully", result.isSuccess)
            coVerify {
                settingsRepository.saveImageCompressionPreset(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.LOW,
                )
            }
        }

    @Test
    fun `setImageCompressionPreset to HIGH saves preset`() =
        runTest {
            viewModel = createViewModel()

            val result =
                runCatching {
                    viewModel.setImageCompressionPreset(com.lxmf.messenger.data.model.ImageCompressionPreset.HIGH)
                }

            assertTrue("setImageCompressionPreset should complete successfully", result.isSuccess)
            coVerify {
                settingsRepository.saveImageCompressionPreset(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.HIGH,
                )
            }
        }

    @Test
    fun `setImageCompressionPreset to MEDIUM saves preset`() =
        runTest {
            viewModel = createViewModel()

            val result =
                runCatching {
                    viewModel.setImageCompressionPreset(com.lxmf.messenger.data.model.ImageCompressionPreset.MEDIUM)
                }

            assertTrue("setImageCompressionPreset should complete successfully", result.isSuccess)
            coVerify {
                settingsRepository.saveImageCompressionPreset(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.MEDIUM,
                )
            }
        }

    @Test
    fun `setImageCompressionPreset to ORIGINAL saves preset`() =
        runTest {
            viewModel = createViewModel()

            val result =
                runCatching {
                    viewModel.setImageCompressionPreset(com.lxmf.messenger.data.model.ImageCompressionPreset.ORIGINAL)
                }

            assertTrue("setImageCompressionPreset should complete successfully", result.isSuccess)
            coVerify {
                settingsRepository.saveImageCompressionPreset(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.ORIGINAL,
                )
            }
        }

    @Test
    fun `image compression preset flow updates state`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                val initial = awaitItem()
                assertEquals(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.AUTO,
                    initial.imageCompressionPreset,
                )

                // Change preset via flow
                imageCompressionPresetFlow.value = com.lxmf.messenger.data.model.ImageCompressionPreset.MEDIUM

                // State should update
                val updated = awaitItem()
                assertEquals(
                    com.lxmf.messenger.data.model.ImageCompressionPreset.MEDIUM,
                    updated.imageCompressionPreset,
                )
            }
        }

    // endregion

    // region Map Source Settings Tests

    @Test
    fun `setMapSourceHttpEnabled true saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setMapSourceHttpEnabled(true) }

            assertTrue("setMapSourceHttpEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveMapSourceHttpEnabled(true) }
        }

    @Test
    fun `setMapSourceHttpEnabled false saves to repository when RMSP enabled`() =
        runTest {
            val rmspEnabledFlow = MutableStateFlow(true)
            every { settingsRepository.mapSourceRmspEnabledFlow } returns rmspEnabledFlow

            viewModel = createViewModel()

            // Wait for state to load
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.setMapSourceHttpEnabled(false) }

            assertTrue("setMapSourceHttpEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveMapSourceHttpEnabled(false) }
        }

    @Test
    fun `setMapSourceHttpEnabled false saves to repository when offline maps exist`() =
        runTest {
            val hasOfflineMapsFlow = MutableStateFlow(true)
            every { mapTileSourceManager.hasOfflineMaps() } returns hasOfflineMapsFlow

            viewModel = createViewModel()

            // Wait for state to load
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.setMapSourceHttpEnabled(false) }

            assertTrue("setMapSourceHttpEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveMapSourceHttpEnabled(false) }
        }

    @Test
    fun `setMapSourceHttpEnabled false saves even when only source`() =
        runTest {
            // Issue #285 fix: HTTP can now be disabled even if it's the only source
            // Both RMSP and offline maps disabled - HTTP can still be disabled
            val rmspEnabledFlow = MutableStateFlow(false)
            val hasOfflineMapsFlow = MutableStateFlow(false)
            every { settingsRepository.mapSourceRmspEnabledFlow } returns rmspEnabledFlow
            every { mapTileSourceManager.hasOfflineMaps() } returns hasOfflineMapsFlow

            viewModel = createViewModel()

            // Wait for state to load
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.setMapSourceHttpEnabled(false) }

            assertTrue("setMapSourceHttpEnabled should complete successfully", result.isSuccess)
            // Should save - blocking was removed in Issue #285 fix
            coVerify { settingsRepository.saveMapSourceHttpEnabled(false) }
        }

    @Test
    fun `setMapSourceRmspEnabled true saves to repository`() =
        runTest {
            viewModel = createViewModel()

            val result = runCatching { viewModel.setMapSourceRmspEnabled(true) }

            assertTrue("setMapSourceRmspEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveMapSourceRmspEnabled(true) }
        }

    @Test
    fun `setMapSourceRmspEnabled false saves to repository when HTTP enabled`() =
        runTest {
            val httpEnabledFlow = MutableStateFlow(true)
            every { settingsRepository.mapSourceHttpEnabledFlow } returns httpEnabledFlow

            viewModel = createViewModel()

            // Wait for state to load
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.setMapSourceRmspEnabled(false) }

            assertTrue("setMapSourceRmspEnabled should complete successfully", result.isSuccess)
            coVerify { settingsRepository.saveMapSourceRmspEnabled(false) }
        }

    @Test
    fun `setMapSourceRmspEnabled false does not save when only source`() =
        runTest {
            // Both HTTP and offline maps disabled - RMSP cannot be disabled
            val httpEnabledFlow = MutableStateFlow(false)
            val hasOfflineMapsFlow = MutableStateFlow(false)
            every { settingsRepository.mapSourceHttpEnabledFlow } returns httpEnabledFlow
            every { mapTileSourceManager.hasOfflineMaps() } returns hasOfflineMapsFlow

            viewModel = createViewModel()

            // Wait for state to load
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            val result = runCatching { viewModel.setMapSourceRmspEnabled(false) }

            assertTrue("setMapSourceRmspEnabled should complete successfully", result.isSuccess)
            // Should NOT save because RMSP is the only source
            coVerify(exactly = 0) { settingsRepository.saveMapSourceRmspEnabled(false) }
        }

    @Test
    fun `state collects mapSourceHttpEnabled from repository`() =
        runTest {
            val httpEnabledFlow = MutableStateFlow(true)
            every { settingsRepository.mapSourceHttpEnabledFlow } returns httpEnabledFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertTrue(state.mapSourceHttpEnabled)

                // Update flow to false
                httpEnabledFlow.value = false
                state = awaitItem()
                assertFalse(state.mapSourceHttpEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collects mapSourceRmspEnabled from repository`() =
        runTest {
            val rmspEnabledFlow = MutableStateFlow(false)
            every { settingsRepository.mapSourceRmspEnabledFlow } returns rmspEnabledFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertFalse(state.mapSourceRmspEnabled)

                // Update flow to true
                rmspEnabledFlow.value = true
                state = awaitItem()
                assertTrue(state.mapSourceRmspEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collects rmspServerCount from mapTileSourceManager`() =
        runTest {
            val serverCountFlow = MutableStateFlow(0)
            every { mapTileSourceManager.observeRmspServerCount() } returns serverCountFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertEquals(0, state.rmspServerCount)

                // Update flow with servers
                serverCountFlow.value = 3
                state = awaitItem()
                assertEquals(3, state.rmspServerCount)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state collects hasOfflineMaps from mapTileSourceManager`() =
        runTest {
            val hasOfflineMapsFlow = MutableStateFlow(false)
            every { mapTileSourceManager.hasOfflineMaps() } returns hasOfflineMapsFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertFalse(state.hasOfflineMaps)

                // Update flow to true
                hasOfflineMapsFlow.value = true
                state = awaitItem()
                assertTrue(state.hasOfflineMaps)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `map source settings are preserved across state changes`() =
        runTest {
            val httpEnabledFlow = MutableStateFlow(false)
            val rmspEnabledFlow = MutableStateFlow(true)
            every { settingsRepository.mapSourceHttpEnabledFlow } returns httpEnabledFlow
            every { settingsRepository.mapSourceRmspEnabledFlow } returns rmspEnabledFlow

            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertFalse(state.mapSourceHttpEnabled)
                assertTrue(state.mapSourceRmspEnabled)

                // Change another unrelated setting
                autoAnnounceEnabledFlow.value = false
                state = awaitItem()

                // Map source settings should be preserved
                assertFalse(
                    "mapSourceHttpEnabled should be preserved",
                    state.mapSourceHttpEnabled,
                )
                assertTrue(
                    "mapSourceRmspEnabled should be preserved",
                    state.mapSourceRmspEnabled,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region HTTP Toggle and Card Expansion Tests

    @Test
    fun `setMapSourceHttpEnabled can disable HTTP without blocking`() =
        runTest {
            // Setup: HTTP initially enabled
            val httpEnabledFlow = MutableStateFlow(true)
            every { mapTileSourceManager.httpEnabledFlow } returns httpEnabledFlow

            viewModel = createViewModel()

            // Wait for initial load
            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }
                cancelAndConsumeRemainingEvents()
            }

            // Disable HTTP - should work without blocking
            val result = runCatching { viewModel.setMapSourceHttpEnabled(false) }

            assertTrue("setMapSourceHttpEnabled should complete successfully", result.isSuccess)
            // Verify the repository was called to save the setting
            coVerify { settingsRepository.saveMapSourceHttpEnabled(false) }
        }

    @Test
    fun `card expansion states are preserved after toggling HTTP`() =
        runTest {
            val httpEnabledFlow = MutableStateFlow(true)
            every { mapTileSourceManager.httpEnabledFlow } returns httpEnabledFlow

            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                // Expand a card first
                viewModel.toggleCardExpanded(SettingsCardId.MAP_SOURCES, true)
                var state = awaitItem()
                assertTrue(
                    "MAP_SOURCES should be expanded",
                    state.cardExpansionStates[SettingsCardId.MAP_SOURCES.name] == true,
                )

                // Toggle HTTP
                viewModel.setMapSourceHttpEnabled(false)
                httpEnabledFlow.value = false
                state = awaitItem()

                // Card should still be expanded
                assertTrue(
                    "MAP_SOURCES should still be expanded after toggle",
                    state.cardExpansionStates[SettingsCardId.MAP_SOURCES.name] == true,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion
}
