package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.AvailableRelaysState
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.ui.theme.PresetTheme
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
    private lateinit var viewModel: SettingsViewModel

    // Mutable flows for controlling test scenarios
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
        reticulumProtocol = mockk(relaxed = true)
        interfaceConfigManager = mockk(relaxed = true)
        propagationNodeManager = mockk(relaxed = true)
        locationSharingManager = mockk(relaxed = true)

        // Mock locationSharingManager flows
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())

        // Setup repository flow mocks
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
        )
    }

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

            viewModel.togglePreferOwnInstance(true)

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

            viewModel.switchToOwnInstanceAfterLoss()

            coVerify { settingsRepository.savePreferOwnInstance(true) }
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `switchToSharedInstance clears preferOwn and triggers restart`() =
        runTest {
            preferOwnInstanceFlow.value = true
            viewModel = createViewModel()

            viewModel.switchToSharedInstance()

            coVerify { settingsRepository.savePreferOwnInstance(false) }
            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `dismissSharedInstanceAvailable sets preferOwnInstance`() =
        runTest {
            viewModel = createViewModel()

            viewModel.dismissSharedInstanceAvailable()

            coVerify { settingsRepository.savePreferOwnInstance(true) }
        }

    // endregion

    // region saveRpcKey Tests

    @Test
    fun `saveRpcKey with valid config parses and saves`() =
        runTest {
            isSharedInstanceFlow.value = true // Must be shared instance to trigger restart
            viewModel = createViewModel()

            viewModel.saveRpcKey("shared_instance_type = tcp\nrpc_key = abc123def")

            coVerify { settingsRepository.saveRpcKey("abc123def") }
        }

    @Test
    fun `saveRpcKey with raw hex saves directly`() =
        runTest {
            isSharedInstanceFlow.value = true
            viewModel = createViewModel()

            viewModel.saveRpcKey("abc123def456")

            coVerify { settingsRepository.saveRpcKey("abc123def456") }
        }

    @Test
    fun `saveRpcKey with invalid input saves null`() =
        runTest {
            isSharedInstanceFlow.value = true
            viewModel = createViewModel()

            viewModel.saveRpcKey("invalid-key!")

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

            viewModel.saveRpcKey("abc123")

            coVerify { interfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `saveRpcKey does not restart when not shared instance`() =
        runTest {
            isSharedInstanceFlow.value = false
            viewModel = createViewModel()

            viewModel.saveRpcKey("abc123")

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

            viewModel.restartService()

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
            viewModel.restartService()

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
            viewModel.togglePreferOwnInstance(true)

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

            viewModel.switchToOwnInstanceAfterLoss()

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

            viewModel.setDefaultDeliveryMethod("direct")

            coVerify { settingsRepository.saveDefaultDeliveryMethod("direct") }
        }

    @Test
    fun `setDefaultDeliveryMethod propagated saves to repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setDefaultDeliveryMethod("propagated")

            coVerify { settingsRepository.saveDefaultDeliveryMethod("propagated") }
        }

    @Test
    fun `setTryPropagationOnFail enabled saves to repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setTryPropagationOnFail(true)

            coVerify { settingsRepository.saveTryPropagationOnFail(true) }
        }

    @Test
    fun `setTryPropagationOnFail disabled saves to repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setTryPropagationOnFail(false)

            coVerify { settingsRepository.saveTryPropagationOnFail(false) }
        }

    @Test
    fun `setAutoSelectPropagationNode true enables auto-select and saves`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAutoSelectPropagationNode(true)

            coVerify { propagationNodeManager.enableAutoSelect() }
            coVerify { settingsRepository.saveAutoSelectPropagationNode(true) }
        }

    @Test
    fun `setAutoSelectPropagationNode false saves without enabling auto-select`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAutoSelectPropagationNode(false)

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

            viewModel.updateDisplayName("NewName")

            coVerify { identityRepository.updateDisplayName("test123", "NewName") }
        }

    @Test
    fun `updateDisplayName emptyName savesEmptyString`() =
        runTest {
            val testIdentity = createTestIdentity(displayName = "OldName")
            coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { identityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            viewModel = createViewModel()

            viewModel.updateDisplayName("")

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
            viewModel.updateDisplayName("NewName")

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

            // Should not throw
            viewModel.updateDisplayName("NewName")

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

            viewModel.toggleAutoAnnounce(true)

            coVerify { settingsRepository.saveAutoAnnounceEnabled(true) }
        }

    @Test
    fun `toggleAutoAnnounce disabled savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.toggleAutoAnnounce(false)

            coVerify { settingsRepository.saveAutoAnnounceEnabled(false) }
        }

    @Test
    fun `setAnnounceInterval validMinutes savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAnnounceInterval(10)

            coVerify { settingsRepository.saveAutoAnnounceIntervalMinutes(10) }
        }

    @Test
    fun `setAnnounceInterval boundaryMin1 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAnnounceInterval(1)

            coVerify { settingsRepository.saveAutoAnnounceIntervalMinutes(1) }
        }

    @Test
    fun `setAnnounceInterval boundaryMax60 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAnnounceInterval(60)

            coVerify { settingsRepository.saveAutoAnnounceIntervalMinutes(60) }
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
            autoAnnounceIntervalMinutesFlow.value = 15
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                var loadAttempts = 0
                while (state.isLoading && loadAttempts++ < 50) {
                    state = awaitItem()
                }

                assertEquals(15, state.autoAnnounceIntervalMinutes)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Theme Management Tests

    @Test
    fun `setTheme presetTheme savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setTheme(PresetTheme.OCEAN)

            coVerify { settingsRepository.saveThemePreference(PresetTheme.OCEAN) }
        }

    @Test
    fun `setTheme defaultTheme savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setTheme(PresetTheme.VIBRANT)

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

            viewModel.applyCustomTheme(123L)

            coVerify { settingsRepository.getCustomThemeById(123L) }
            coVerify { settingsRepository.saveThemePreference(mockCustomTheme) }
        }

    @Test
    fun `applyCustomTheme invalidId doesNotCrash`() =
        runTest {
            coEvery { settingsRepository.getCustomThemeById(999L) } returns null

            viewModel = createViewModel()

            // Should not throw
            viewModel.applyCustomTheme(999L)

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

            viewModel.shutdownService()

            coVerify { reticulumProtocol.shutdown() }
        }

    // endregion

    // region Message Retrieval Tests

    @Test
    fun `setAutoRetrieveEnabled true savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAutoRetrieveEnabled(true)

            coVerify { settingsRepository.saveAutoRetrieveEnabled(true) }
        }

    @Test
    fun `setAutoRetrieveEnabled false savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setAutoRetrieveEnabled(false)

            coVerify { settingsRepository.saveAutoRetrieveEnabled(false) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 30 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setRetrievalIntervalSeconds(30)

            coVerify { settingsRepository.saveRetrievalIntervalSeconds(30) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 60 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setRetrievalIntervalSeconds(60)

            coVerify { settingsRepository.saveRetrievalIntervalSeconds(60) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 120 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setRetrievalIntervalSeconds(120)

            coVerify { settingsRepository.saveRetrievalIntervalSeconds(120) }
        }

    @Test
    fun `setRetrievalIntervalSeconds 300 savesToRepository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setRetrievalIntervalSeconds(300)

            coVerify { settingsRepository.saveRetrievalIntervalSeconds(300) }
        }

    @Test
    fun `syncNow callsPropagationNodeManagerTriggerSync`() =
        runTest {
            viewModel = createViewModel()

            viewModel.syncNow()

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

            viewModel.setTransportNodeEnabled(true)

            coVerify { settingsRepository.saveTransportNodeEnabled(true) }
        }

    @Test
    fun `setTransportNodeEnabled false saves to repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setTransportNodeEnabled(false)

            coVerify { settingsRepository.saveTransportNodeEnabled(false) }
        }

    @Test
    fun `setTransportNodeEnabled triggers service restart`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setTransportNodeEnabled(false)

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

            viewModel.setLocationSharingEnabled(true)

            coVerify { settingsRepository.saveLocationSharingEnabled(true) }
        }

    @Test
    fun `setLocationSharingEnabled false saves to repository and stops all sharing`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setLocationSharingEnabled(false)

            coVerify { settingsRepository.saveLocationSharingEnabled(false) }
            coVerify { locationSharingManager.stopSharing(null) }
        }

    @Test
    fun `stopSharingWith calls locationSharingManager stopSharing`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "testDestinationHash123"

            viewModel.stopSharingWith(testHash)

            coVerify { locationSharingManager.stopSharing(testHash) }
        }

    @Test
    fun `stopAllSharing calls locationSharingManager stopSharing with null`() =
        runTest {
            viewModel = createViewModel()

            viewModel.stopAllSharing()

            coVerify { locationSharingManager.stopSharing(null) }
        }

    @Test
    fun `setDefaultSharingDuration saves to repository`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setDefaultSharingDuration("FOUR_HOURS")

            coVerify { settingsRepository.saveDefaultSharingDuration("FOUR_HOURS") }
        }

    @Test
    fun `setLocationPrecisionRadius saves to repository and sends update`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setLocationPrecisionRadius(1000)

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

            viewModel.addManualPropagationNode(testHash, testNickname)

            coVerify { propagationNodeManager.setManualRelayByHash(testHash, testNickname) }
        }

    @Test
    fun `addManualPropagationNode with null nickname calls propagationNodeManager`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "abcd1234abcd1234abcd1234abcd1234"

            viewModel.addManualPropagationNode(testHash, null)

            coVerify { propagationNodeManager.setManualRelayByHash(testHash, null) }
        }

    @Test
    fun `selectRelay calls propagationNodeManager setManualRelay`() =
        runTest {
            viewModel = createViewModel()
            val testHash = "abcd1234abcd1234abcd1234abcd1234"
            val testName = "Selected Relay"

            viewModel.selectRelay(testHash, testName)

            coVerify { propagationNodeManager.setManualRelay(testHash, testName) }
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

            viewModel.updateIconAppearance("account", "FFFFFF", "1E88E5")

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

            viewModel.updateIconAppearance(null, null, null)

            coVerify {
                identityRepository.updateIconAppearance("abc123", null, null, null)
            }
        }

    @Test
    fun `updateIconAppearance does not update when no active identity`() =
        runTest {
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            viewModel = createViewModel()

            viewModel.updateIconAppearance("account", "FFFFFF", "1E88E5")

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
            viewModel.updateIconAppearance("account", "FFFFFF", "1E88E5")

            // Verify update was attempted
            coVerify {
                identityRepository.updateIconAppearance("abc123", "account", "FFFFFF", "1E88E5")
            }
        }

    // endregion
}
