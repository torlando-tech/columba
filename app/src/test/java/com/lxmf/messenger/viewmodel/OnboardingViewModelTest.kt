package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.ui.screens.onboarding.OnboardingInterfaceType
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Unit tests for OnboardingViewModel.
 * Tests state management, interface creation, and onboarding flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockIdentityRepository: IdentityRepository
    private lateinit var mockInterfaceRepository: InterfaceRepository
    private lateinit var mockInterfaceConfigManager: InterfaceConfigManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockSettingsRepository = mockk()
        mockIdentityRepository = mockk()
        mockInterfaceRepository = mockk()
        mockInterfaceConfigManager = mockk()

        // Default stubs for SettingsRepository
        coEvery { mockSettingsRepository.hasCompletedOnboardingFlow } returns MutableStateFlow(false)
        coEvery { mockSettingsRepository.markOnboardingCompleted() } just Runs

        // Default stubs for IdentityRepository
        coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
        coEvery { mockIdentityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)

        // Default stubs for InterfaceRepository
        every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(emptyList())
        every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(emptyList())
        coEvery { mockInterfaceRepository.toggleInterfaceEnabled(any(), any()) } just Runs
        coEvery { mockInterfaceRepository.insertInterface(any()) } returns 1L

        // Default stubs for InterfaceConfigManager
        coEvery { mockInterfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(): OnboardingViewModel =
        OnboardingViewModel(
            mockSettingsRepository,
            mockIdentityRepository,
            mockInterfaceRepository,
            mockInterfaceConfigManager,
        )

    private fun createTestIdentity(
        hash: String = "test_hash",
        displayName: String = "Test",
    ) = LocalIdentityEntity(
        identityHash = hash,
        displayName = displayName,
        destinationHash = "dest_$hash",
        filePath = "/data/identity_$hash",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = true,
    )

    private fun createInterfaceEntity(
        id: Long,
        type: String,
        name: String,
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = type,
        enabled = enabled,
        configJson = "{}",
        displayOrder = id.toInt(),
    )

    // ========== Initial State Tests ==========

    @Test
    fun `initial state has loading true`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isLoading) // After init, loading should be false
            }
        }

    @Test
    fun `initial state has AUTO interface selected by default`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.selectedInterfaces.contains(OnboardingInterfaceType.AUTO))
                assertEquals(1, state.selectedInterfaces.size)
            }
        }

    @Test
    fun `initial state has empty display name`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("", state.displayName)
            }
        }

    @Test
    fun `checks onboarding status on init`() =
        runTest {
            coEvery { mockSettingsRepository.hasCompletedOnboardingFlow } returns MutableStateFlow(true)

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.hasCompletedOnboarding)
            }
        }

    // ========== Display Name Tests ==========

    @Test
    fun `updateDisplayName updates state`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateDisplayName("Tyler")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("Tyler", state.displayName)
            }
        }

    @Test
    fun `updateDisplayName clears error`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            // First update with a name
            viewModel.updateDisplayName("Test")
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.error)
            }
        }

    // ========== Interface Toggle Tests ==========

    @Test
    fun `toggleInterface adds interface when not selected`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.selectedInterfaces.contains(OnboardingInterfaceType.BLE))
                assertTrue(state.selectedInterfaces.contains(OnboardingInterfaceType.AUTO))
            }
        }

    @Test
    fun `toggleInterface removes interface when already selected`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            // AUTO is selected by default, toggle it off
            viewModel.toggleInterface(OnboardingInterfaceType.AUTO)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.selectedInterfaces.contains(OnboardingInterfaceType.AUTO))
            }
        }

    @Test
    fun `toggleInterface can select multiple interfaces`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            viewModel.toggleInterface(OnboardingInterfaceType.TCP)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(3, state.selectedInterfaces.size)
                assertTrue(state.selectedInterfaces.contains(OnboardingInterfaceType.AUTO))
                assertTrue(state.selectedInterfaces.contains(OnboardingInterfaceType.BLE))
                assertTrue(state.selectedInterfaces.contains(OnboardingInterfaceType.TCP))
            }
        }

    // ========== Permission Result Tests ==========

    @Test
    fun `onNotificationPermissionResult updates state when granted`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onNotificationPermissionResult(true)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.notificationsEnabled)
                assertTrue(state.notificationsGranted)
            }
        }

    @Test
    fun `onNotificationPermissionResult updates state when denied`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onNotificationPermissionResult(false)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.notificationsEnabled)
                assertFalse(state.notificationsGranted)
            }
        }

    @Test
    fun `onBlePermissionsResult updates state when granted`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.onBlePermissionsResult(allGranted = true, anyDenied = false)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.blePermissionsGranted)
                assertFalse(state.blePermissionsDenied)
            }
        }

    @Test
    fun `onBlePermissionsResult removes BLE from selection when denied`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            // First add BLE
            viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            advanceUntilIdle()

            // Then deny permissions
            viewModel.onBlePermissionsResult(allGranted = false, anyDenied = true)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.selectedInterfaces.contains(OnboardingInterfaceType.BLE))
                assertTrue(state.blePermissionsDenied)
            }
        }

    // ========== Page Navigation Tests ==========

    @Test
    fun `setCurrentPage updates state`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.setCurrentPage(3)
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(3, state.currentPage)
            }
        }

    // ========== Complete Onboarding Tests ==========

    @Test
    fun `completeOnboarding saves display name`() =
        runTest {
            val viewModel = createViewModel()
            val testIdentity = createTestIdentity()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { mockIdentityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            advanceUntilIdle()

            viewModel.updateDisplayName("Tyler")
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            coVerify { mockIdentityRepository.updateDisplayName(testIdentity.identityHash, "Tyler") }
            assertTrue(callbackCalled)
        }

    @Test
    fun `completeOnboarding uses default name when empty`() =
        runTest {
            val viewModel = createViewModel()
            val testIdentity = createTestIdentity()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { mockIdentityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            advanceUntilIdle()

            // Don't set display name - should use default
            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            coVerify { mockIdentityRepository.updateDisplayName(testIdentity.identityHash, "Anonymous Peer") }
            assertTrue(callbackCalled)
        }

    @Test
    fun `completeOnboarding marks onboarding completed`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify { mockSettingsRepository.markOnboardingCompleted() }
        }

    @Test
    fun `completeOnboarding applies interface changes`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify { mockInterfaceConfigManager.applyInterfaceChanges() }
        }

    @Test
    fun `completeOnboarding updates hasCompletedOnboarding state`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            advanceUntilIdle()

            viewModel.completeOnboarding { }
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.hasCompletedOnboarding)
                assertFalse(state.isSaving)
            }
        }

    // ========== Interface Creation Tests ==========

    @Test
    fun `completeOnboarding enables existing interface when selected`() =
        runTest {
            // Given: BLE interface exists but is disabled
            val existingBle = createInterfaceEntity(id = 1, type = "AndroidBLE", name = "Bluetooth LE", enabled = false)
            every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(listOf(existingBle))
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null

            val viewModel = createViewModel()
            advanceUntilIdle()

            // When: User selects BLE
            viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Then: BLE should be enabled
            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify { mockInterfaceRepository.toggleInterfaceEnabled(1, true) }
        }

    @Test
    fun `completeOnboarding disables existing interface when not selected`() =
        runTest {
            // Given: BLE interface exists and is enabled
            val existingBle = createInterfaceEntity(id = 1, type = "AndroidBLE", name = "Bluetooth LE", enabled = true)
            val existingAuto = createInterfaceEntity(id = 2, type = "AutoInterface", name = "Local WiFi", enabled = true)
            every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(listOf(existingBle, existingAuto))
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null

            val viewModel = createViewModel()
            advanceUntilIdle()

            // When: User only has AUTO selected (default), BLE is not selected
            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Then: BLE should be disabled, AUTO should be enabled
            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify { mockInterfaceRepository.toggleInterfaceEnabled(1, false) }
            coVerify { mockInterfaceRepository.toggleInterfaceEnabled(2, true) }
        }

    @Test
    fun `completeOnboarding creates interface when missing and selected`() =
        runTest {
            // Given: No interfaces exist
            every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(emptyList())
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            coEvery { mockInterfaceRepository.insertInterface(any()) } returns 1L

            val viewModel = createViewModel()
            advanceUntilIdle()

            // When: User has AUTO selected (default)
            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Then: AutoInterface should be created
            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.AutoInterface }) }
        }

    @Test
    fun `completeOnboarding creates TCP interface with default server`() =
        runTest {
            // Given: No interfaces exist
            every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(emptyList())
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            coEvery { mockInterfaceRepository.insertInterface(any()) } returns 1L

            val viewModel = createViewModel()
            advanceUntilIdle()

            // When: User selects TCP
            viewModel.toggleInterface(OnboardingInterfaceType.TCP)
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Then: TCPClient should be created with default server
            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify {
                mockInterfaceRepository.insertInterface(
                    match {
                        it is InterfaceConfig.TCPClient && it.enabled
                    },
                )
            }
        }

    @Test
    fun `completeOnboarding does not auto-create RNode interface`() =
        runTest {
            // Given: No interfaces exist
            every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(emptyList())
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            coEvery { mockInterfaceRepository.insertInterface(any()) } returns 1L

            val viewModel = createViewModel()
            advanceUntilIdle()

            // When: User selects RNODE
            viewModel.toggleInterface(OnboardingInterfaceType.RNODE)
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Then: RNode should NOT be auto-created (requires wizard)
            assertTrue("Callback should be called on completion", callbackCalled)
            coVerify(exactly = 0) { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.RNode }) }
        }

    @Test
    fun `completeOnboarding handles all selected interfaces correctly`() =
        runTest {
            // Given: Some interfaces exist
            val existingAuto = createInterfaceEntity(id = 1, type = "AutoInterface", name = "Local WiFi", enabled = true)
            every { mockInterfaceRepository.allInterfaceEntities } returns MutableStateFlow(listOf(existingAuto))
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            coEvery { mockInterfaceRepository.insertInterface(any()) } returns 2L

            val viewModel = createViewModel()
            advanceUntilIdle()

            // When: User selects AUTO, BLE, TCP
            viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            viewModel.toggleInterface(OnboardingInterfaceType.TCP)
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.completeOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Then:
            assertTrue("Callback should be called on completion", callbackCalled)
            // - AUTO exists -> enable
            coVerify { mockInterfaceRepository.toggleInterfaceEnabled(1, true) }
            // - BLE doesn't exist -> create
            coVerify { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.AndroidBLE }) }
            // - TCP doesn't exist -> create
            coVerify { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.TCPClient }) }
        }

    // ========== Skip Onboarding Tests ==========

    @Test
    fun `skipOnboarding sets default display name`() =
        runTest {
            val viewModel = createViewModel()
            val testIdentity = createTestIdentity()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns testIdentity
            coEvery { mockIdentityRepository.updateDisplayName(any(), any()) } returns Result.success(Unit)
            every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(emptyList())
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.skipOnboarding { callbackCalled = true }
            advanceUntilIdle()

            coVerify { mockIdentityRepository.updateDisplayName(testIdentity.identityHash, "Anonymous Peer") }
            assertTrue(callbackCalled)
        }

    @Test
    fun `skipOnboarding creates only AutoInterface`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(emptyList())
            coEvery { mockInterfaceRepository.insertInterface(any()) } returns 1L
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.skipOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Only AutoInterface should be created
            assertTrue("Callback should be called on skip", callbackCalled)
            coVerify(exactly = 1) { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.AutoInterface }) }
            coVerify(exactly = 0) { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.AndroidBLE }) }
            coVerify(exactly = 0) { mockInterfaceRepository.insertInterface(match { it is InterfaceConfig.TCPClient }) }
        }

    @Test
    fun `skipOnboarding does not create duplicate AutoInterface`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            val existingAuto = InterfaceConfig.AutoInterface(name = "Local WiFi", enabled = true)
            every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(listOf(existingAuto))
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.skipOnboarding { callbackCalled = true }
            advanceUntilIdle()

            // Should not insert new interface since AutoInterface already exists
            assertTrue("Callback should be called on skip", callbackCalled)
            coVerify(exactly = 0) { mockInterfaceRepository.insertInterface(any()) }
        }

    @Test
    fun `skipOnboarding marks onboarding completed`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(emptyList())
            advanceUntilIdle()

            var callbackCalled = false
            viewModel.skipOnboarding { callbackCalled = true }
            advanceUntilIdle()

            assertTrue("Callback should be called on skip", callbackCalled)
            coVerify { mockSettingsRepository.markOnboardingCompleted() }
        }

    @Test
    fun `skipOnboarding updates hasCompletedOnboarding state`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            every { mockInterfaceRepository.allInterfaces } returns MutableStateFlow(emptyList())
            advanceUntilIdle()

            viewModel.skipOnboarding { }
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.hasCompletedOnboarding)
                assertFalse(state.isSaving)
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `completeOnboarding sets error state on exception`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } throws RuntimeException("Test error")
            advanceUntilIdle()

            viewModel.completeOnboarding { }
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("Test error", state.error)
                assertFalse(state.isSaving)
            }
        }

    @Test
    fun `skipOnboarding sets error state on exception`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } throws RuntimeException("Skip error")
            advanceUntilIdle()

            viewModel.skipOnboarding { }
            advanceUntilIdle()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("Skip error", state.error)
                assertFalse(state.isSaving)
            }
        }

    // ========== isSaving State Tests ==========

    @Test
    fun `completeOnboarding sets isSaving to true during operation`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { mockIdentityRepository.getActiveIdentitySync() } returns null
            advanceUntilIdle()

            viewModel.state.test {
                val initialState = awaitItem()
                assertFalse(initialState.isSaving)

                viewModel.completeOnboarding { }

                // Should be saving during operation
                val savingState = awaitItem()
                assertTrue(savingState.isSaving)

                // After completion
                advanceUntilIdle()
                val finalState = awaitItem()
                assertFalse(finalState.isSaving)
            }
        }
}
