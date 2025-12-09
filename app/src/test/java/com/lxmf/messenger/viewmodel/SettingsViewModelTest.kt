package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Disable monitor coroutines during testing to avoid infinite loops
        SettingsViewModel.enableMonitors = false

        settingsRepository = mockk(relaxed = true)
        identityRepository = mockk(relaxed = true)
        reticulumProtocol = mockk(relaxed = true)
        interfaceConfigManager = mockk(relaxed = true)

        // Setup repository flow mocks
        every { settingsRepository.preferOwnInstanceFlow } returns preferOwnInstanceFlow
        every { settingsRepository.isSharedInstanceFlow } returns isSharedInstanceFlow
        every { settingsRepository.rpcKeyFlow } returns rpcKeyFlow
        every { settingsRepository.autoAnnounceEnabledFlow } returns autoAnnounceEnabledFlow
        every { settingsRepository.autoAnnounceIntervalMinutesFlow } returns autoAnnounceIntervalMinutesFlow
        every { settingsRepository.lastAutoAnnounceTimeFlow } returns lastAutoAnnounceTimeFlow
        every { settingsRepository.themePreferenceFlow } returns themePreferenceFlow
        every { settingsRepository.getAllCustomThemes() } returns flowOf(emptyList())
        every { identityRepository.activeIdentity } returns activeIdentityFlow

        // Mock other required methods
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        coEvery { interfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)
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
                assertFalse(state.sharedInstanceLost)
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
                while (state.isLoading) {
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

                // State should have sharedInstanceLost = false
                val finalState = awaitItem()
                assertFalse(finalState.sharedInstanceLost)

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
                while (state.isLoading) {
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
}
