package com.lxmf.messenger.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for NotificationSettingsViewModel.
 *
 * Tests cover:
 * - Initial state loading from repository flows
 * - Toggle methods calling correct repository save methods
 * - State updates when repository flows change
 * - distinctUntilChanged preventing duplicate state emissions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: NotificationSettingsViewModel

    // Mutable flows for controlling test scenarios
    private val notificationsEnabledFlow = MutableStateFlow(true)
    private val notificationReceivedMessageFlow = MutableStateFlow(true)
    private val notificationReceivedMessageFavoriteFlow = MutableStateFlow(true)
    private val notificationHeardAnnounceFlow = MutableStateFlow(false)
    private val notificationBleConnectedFlow = MutableStateFlow(false)
    private val notificationBleDisconnectedFlow = MutableStateFlow(false)
    private val hasRequestedNotificationPermissionFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mockk()

        // Setup repository flow mocks
        every { settingsRepository.notificationsEnabledFlow } returns notificationsEnabledFlow
        every { settingsRepository.notificationReceivedMessageFlow } returns notificationReceivedMessageFlow
        every { settingsRepository.notificationReceivedMessageFavoriteFlow } returns notificationReceivedMessageFavoriteFlow
        every { settingsRepository.notificationHeardAnnounceFlow } returns notificationHeardAnnounceFlow
        every { settingsRepository.notificationBleConnectedFlow } returns notificationBleConnectedFlow
        every { settingsRepository.notificationBleDisconnectedFlow } returns notificationBleDisconnectedFlow
        every { settingsRepository.hasRequestedNotificationPermissionFlow } returns hasRequestedNotificationPermissionFlow

        // Setup suspend function stubs
        coEvery { settingsRepository.saveNotificationsEnabled(any()) } just Runs
        coEvery { settingsRepository.saveNotificationReceivedMessage(any()) } just Runs
        coEvery { settingsRepository.saveNotificationReceivedMessageFavorite(any()) } just Runs
        coEvery { settingsRepository.saveNotificationHeardAnnounce(any()) } just Runs
        coEvery { settingsRepository.saveNotificationBleConnected(any()) } just Runs
        coEvery { settingsRepository.saveNotificationBleDisconnected(any()) } just Runs
        coEvery { settingsRepository.markNotificationPermissionRequested() } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(): NotificationSettingsViewModel = NotificationSettingsViewModel(settingsRepository)

    // ========== Initial State Tests ==========

    @Test
    fun `initial state has isLoading true before collection completes`() =
        runTest {
            // The initial state should have isLoading = true
            val initialState = NotificationSettingsState()
            assertTrue("Initial state should have isLoading true", initialState.isLoading)
        }

    @Test
    fun `state collects values from repository flows`() =
        runTest {
            // Given - Set specific values in flows
            notificationsEnabledFlow.value = false
            notificationReceivedMessageFlow.value = false
            notificationReceivedMessageFavoriteFlow.value = false
            notificationHeardAnnounceFlow.value = true
            notificationBleConnectedFlow.value = true
            notificationBleDisconnectedFlow.value = true
            hasRequestedNotificationPermissionFlow.value = true

            // When
            viewModel = createViewModel()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertFalse("notificationsEnabled should be false", state.notificationsEnabled)
                assertFalse("receivedMessage should be false", state.receivedMessage)
                assertFalse("receivedMessageFavorite should be false", state.receivedMessageFavorite)
                assertTrue("heardAnnounce should be true", state.heardAnnounce)
                assertTrue("bleConnected should be true", state.bleConnected)
                assertTrue("bleDisconnected should be true", state.bleDisconnected)
                assertTrue("hasRequestedNotificationPermission should be true", state.hasRequestedNotificationPermission)
                assertFalse("isLoading should be false after collection", state.isLoading)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Toggle Method Tests ==========

    @Test
    fun `toggleNotificationsEnabled calls repository saveNotificationsEnabled`() =
        runTest {
            // Given - Capture the value passed to repository
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationsEnabled(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When
            viewModel.toggleNotificationsEnabled(false)

            // Then
            coVerify { settingsRepository.saveNotificationsEnabled(false) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertFalse("Should pass false to repository", capturedValue.captured)
        }

    @Test
    fun `toggleReceivedMessage calls repository saveNotificationReceivedMessage`() =
        runTest {
            // Given - Capture the value passed to repository
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationReceivedMessage(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When
            viewModel.toggleReceivedMessage(false)

            // Then
            coVerify { settingsRepository.saveNotificationReceivedMessage(false) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertFalse("Should pass false to repository", capturedValue.captured)
        }

    @Test
    fun `toggleReceivedMessageFavorite calls repository saveNotificationReceivedMessageFavorite`() =
        runTest {
            // Given - Capture the value passed to repository
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationReceivedMessageFavorite(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When
            viewModel.toggleReceivedMessageFavorite(false)

            // Then
            coVerify { settingsRepository.saveNotificationReceivedMessageFavorite(false) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertFalse("Should pass false to repository", capturedValue.captured)
        }

    @Test
    fun `toggleHeardAnnounce calls repository saveNotificationHeardAnnounce`() =
        runTest {
            // Given - Capture the value passed to repository
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationHeardAnnounce(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When
            viewModel.toggleHeardAnnounce(true)

            // Then
            coVerify { settingsRepository.saveNotificationHeardAnnounce(true) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertTrue("Should pass true to repository", capturedValue.captured)
        }

    @Test
    fun `toggleBleConnected calls repository saveNotificationBleConnected`() =
        runTest {
            // Given - Capture the value passed to repository
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationBleConnected(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When
            viewModel.toggleBleConnected(true)

            // Then
            coVerify { settingsRepository.saveNotificationBleConnected(true) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertTrue("Should pass true to repository", capturedValue.captured)
        }

    @Test
    fun `toggleBleDisconnected calls repository saveNotificationBleDisconnected`() =
        runTest {
            // Given - Capture the value passed to repository
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationBleDisconnected(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When
            viewModel.toggleBleDisconnected(true)

            // Then
            coVerify { settingsRepository.saveNotificationBleDisconnected(true) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertTrue("Should pass true to repository", capturedValue.captured)
        }

    @Test
    fun `markNotificationPermissionRequested calls repository markNotificationPermissionRequested`() =
        runTest {
            // Given - Track if repository method was called
            var wasCalled = false
            coEvery { settingsRepository.markNotificationPermissionRequested() } answers {
                wasCalled = true
            }
            viewModel = createViewModel()

            // When
            viewModel.markNotificationPermissionRequested()

            // Then
            coVerify { settingsRepository.markNotificationPermissionRequested() }
            assertTrue("Repository method should have been called", wasCalled)
        }

    // ========== Permission Grant Scenario Tests ==========

    @Test
    fun `permission granted scenario enables notifications`() =
        runTest {
            // Given - Simulates MainActivity's permission launcher callback when permission is granted
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationsEnabled(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When - Permission is granted (MainActivity.kt:239-242)
            viewModel.toggleNotificationsEnabled(true)

            // Then - Notifications should be enabled in repository
            coVerify { settingsRepository.saveNotificationsEnabled(true) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertTrue("Should enable notifications", capturedValue.captured)
        }

    @Test
    fun `permission denied scenario disables notifications`() =
        runTest {
            // Given - Simulates MainActivity's permission launcher callback when permission is denied
            val capturedValue = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationsEnabled(capture(capturedValue)) } just Runs
            viewModel = createViewModel()

            // When - Permission is denied (MainActivity.kt:244-246)
            viewModel.toggleNotificationsEnabled(false)

            // Then - Notifications should be disabled in repository
            coVerify { settingsRepository.saveNotificationsEnabled(false) }
            assertTrue("Repository should have been called", capturedValue.isCaptured)
            assertFalse("Should disable notifications", capturedValue.captured)
        }

    @Test
    fun `permission grant properly initializes notification system`() =
        runTest {
            // Given - Simulates first launch where permission hasn't been requested yet
            hasRequestedNotificationPermissionFlow.value = false
            notificationsEnabledFlow.value = true // Default value

            var permissionMarked = false
            val enabledValue = slot<Boolean>()
            coEvery { settingsRepository.markNotificationPermissionRequested() } answers {
                permissionMarked = true
            }
            coEvery { settingsRepository.saveNotificationsEnabled(capture(enabledValue)) } just Runs

            viewModel = createViewModel()

            // When - User grants permission on first launch
            viewModel.markNotificationPermissionRequested()
            viewModel.toggleNotificationsEnabled(true)

            // Then - Both repository methods should be called
            coVerify { settingsRepository.markNotificationPermissionRequested() }
            coVerify { settingsRepository.saveNotificationsEnabled(true) }
            assertTrue("Permission should be marked as requested", permissionMarked)
            assertTrue("Notifications should be enabled", enabledValue.captured)
        }

    // ========== State Update Tests ==========

    @Test
    fun `state updates when repository flows change`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                var state = awaitItem()
                assertTrue("Initial notificationsEnabled should be true", state.notificationsEnabled)

                // When - Update repository flow
                notificationsEnabledFlow.value = false

                // Then - State should update
                state = awaitItem()
                assertFalse("notificationsEnabled should update to false", state.notificationsEnabled)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `state updates for each notification type independently`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                awaitItem()

                // Update each flow and verify state updates
                notificationReceivedMessageFlow.value = false
                var state = awaitItem()
                assertFalse("receivedMessage should update", state.receivedMessage)

                notificationHeardAnnounceFlow.value = true
                state = awaitItem()
                assertTrue("heardAnnounce should update", state.heardAnnounce)

                notificationBleConnectedFlow.value = true
                state = awaitItem()
                assertTrue("bleConnected should update", state.bleConnected)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Default Values Tests ==========

    @Test
    fun `default state values match repository defaults`() =
        runTest {
            // Given - Use default flow values
            viewModel = createViewModel()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertTrue("notificationsEnabled defaults to true", state.notificationsEnabled)
                assertTrue("receivedMessage defaults to true", state.receivedMessage)
                assertTrue("receivedMessageFavorite defaults to true", state.receivedMessageFavorite)
                assertFalse("heardAnnounce defaults to false", state.heardAnnounce)
                assertFalse("bleConnected defaults to false", state.bleConnected)
                assertFalse("bleDisconnected defaults to false", state.bleDisconnected)
                assertFalse("hasRequestedNotificationPermission defaults to false", state.hasRequestedNotificationPermission)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== State Equality Tests ==========

    @Test
    fun `NotificationSettingsState equals works correctly for distinctUntilChanged`() {
        val state1 =
            NotificationSettingsState(
                notificationsEnabled = true,
                receivedMessage = true,
                receivedMessageFavorite = true,
                heardAnnounce = false,
                bleConnected = false,
                bleDisconnected = false,
                hasRequestedNotificationPermission = false,
                isLoading = false,
            )
        val state2 =
            NotificationSettingsState(
                notificationsEnabled = true,
                receivedMessage = true,
                receivedMessageFavorite = true,
                heardAnnounce = false,
                bleConnected = false,
                bleDisconnected = false,
                hasRequestedNotificationPermission = false,
                isLoading = false,
            )
        val state3 =
            NotificationSettingsState(
                notificationsEnabled = false,
                receivedMessage = true,
                receivedMessageFavorite = true,
                heardAnnounce = false,
                bleConnected = false,
                bleDisconnected = false,
                hasRequestedNotificationPermission = false,
                isLoading = false,
            )

        assertEquals("Identical states should be equal", state1, state2)
        assertFalse("Different states should not be equal", state1 == state3)
    }
}
