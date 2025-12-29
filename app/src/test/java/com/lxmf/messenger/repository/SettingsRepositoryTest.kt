package com.lxmf.messenger.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.CustomThemeRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for SettingsRepository using Robolectric.
 * Tests distinctUntilChanged behavior for all preference flows.
 *
 * Note: DataStore singleton persists across tests in Robolectric.
 * Tests are designed to be state-agnostic where possible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var mockCustomThemeRepository: CustomThemeRepository
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        mockCustomThemeRepository = mockk(relaxed = true)

        // Mock theme repository flows to prevent null pointer exceptions
        every { mockCustomThemeRepository.getAllThemes() } returns flowOf(emptyList())
        every { mockCustomThemeRepository.getThemeByIdFlow(any()) } returns flowOf(null)

        repository = SettingsRepository(context, mockCustomThemeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Transport Node Tests ==========

    @Test
    fun transportNodeEnabledFlow_emitsOnlyOnChange() =
        runTest {
            repository.transportNodeEnabledFlow.test(timeout = 5.seconds) {
                // Get initial value
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveTransportNodeEnabled(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveTransportNodeEnabled(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun transportNodeEnabled_persistsValue() =
        runTest {
            // Save a specific value
            repository.saveTransportNodeEnabled(false)
            assertFalse(repository.getTransportNodeEnabled())

            // Save opposite value
            repository.saveTransportNodeEnabled(true)
            assertTrue(repository.getTransportNodeEnabled())
        }

    @Test
    fun flowAndGetMethod_returnSameValue() =
        runTest {
            // Set to a known value
            repository.saveTransportNodeEnabled(false)

            // When - Read from both sources
            val flowValue = repository.transportNodeEnabledFlow.first()
            val methodValue = repository.getTransportNodeEnabled()

            // Then - Both should return same value
            assertEquals("Flow and method should return same value", flowValue, methodValue)
        }

    // ========== Notification Preferences Flow Tests ==========

    @Test
    fun notificationsEnabledFlow_emitsOnlyOnChange() =
        runTest {
            repository.notificationsEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveNotificationsEnabled(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveNotificationsEnabled(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun notificationReceivedMessageFlow_emitsOnlyOnChange() =
        runTest {
            repository.notificationReceivedMessageFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveNotificationReceivedMessage(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveNotificationReceivedMessage(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun notificationReceivedMessageFavoriteFlow_emitsOnlyOnChange() =
        runTest {
            repository.notificationReceivedMessageFavoriteFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveNotificationReceivedMessageFavorite(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveNotificationReceivedMessageFavorite(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun notificationHeardAnnounceFlow_emitsOnlyOnChange() =
        runTest {
            repository.notificationHeardAnnounceFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveNotificationHeardAnnounce(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveNotificationHeardAnnounce(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun notificationBleConnectedFlow_emitsOnlyOnChange() =
        runTest {
            repository.notificationBleConnectedFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveNotificationBleConnected(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveNotificationBleConnected(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun notificationBleDisconnectedFlow_emitsOnlyOnChange() =
        runTest {
            repository.notificationBleDisconnectedFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveNotificationBleDisconnected(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveNotificationBleDisconnected(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun hasRequestedNotificationPermissionFlow_emitsOnlyOnChange() =
        runTest {
            repository.hasRequestedNotificationPermissionFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                if (!initial) {
                    // Mark as requested - should emit
                    repository.markNotificationPermissionRequested()
                    assertTrue(awaitItem())

                    // Mark again - should NOT emit (already true)
                    repository.markNotificationPermissionRequested()
                    expectNoEvents()
                } else {
                    // Already true, marking again should NOT emit
                    repository.markNotificationPermissionRequested()
                    expectNoEvents()
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Onboarding Flow Tests ==========

    @Test
    fun hasCompletedOnboardingFlow_emitsOnlyOnChange() =
        runTest {
            repository.hasCompletedOnboardingFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                if (!initial) {
                    // Mark as completed - should emit
                    repository.markOnboardingCompleted()
                    assertTrue(awaitItem())

                    // Mark again - should NOT emit (already true)
                    repository.markOnboardingCompleted()
                    expectNoEvents()
                } else {
                    // Already true, marking again should NOT emit
                    repository.markOnboardingCompleted()
                    expectNoEvents()
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Auto-Announce Flow Tests ==========

    @Test
    fun autoAnnounceEnabledFlow_emitsOnlyOnChange() =
        runTest {
            repository.autoAnnounceEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveAutoAnnounceEnabled(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveAutoAnnounceEnabled(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun autoAnnounceIntervalMinutesFlow_emitsOnlyOnChange() =
        runTest {
            repository.autoAnnounceIntervalMinutesFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveAutoAnnounceIntervalMinutes(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == 5) 10 else 5
                repository.saveAutoAnnounceIntervalMinutes(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastAutoAnnounceTimeFlow_emitsOnlyOnChange() =
        runTest {
            repository.lastAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                if (initial != null) {
                    repository.saveLastAutoAnnounceTime(initial)
                    expectNoEvents()
                }

                // Save new value - should emit
                val newValue = System.currentTimeMillis()
                repository.saveLastAutoAnnounceTime(newValue)
                assertEquals(newValue, awaitItem())

                // Save same new value - should NOT emit
                repository.saveLastAutoAnnounceTime(newValue)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Service Status Flow Tests ==========

    @Test
    fun lastServiceStatusFlow_emitsOnlyOnChange() =
        runTest {
            repository.lastServiceStatusFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveServiceStatus(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == "READY") "UNKNOWN" else "READY"
                repository.saveServiceStatus(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Shared Instance Flow Tests ==========

    @Test
    fun preferOwnInstanceFlow_emitsOnlyOnChange() =
        runTest {
            repository.preferOwnInstanceFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.savePreferOwnInstance(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.savePreferOwnInstance(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun isSharedInstanceFlow_emitsOnlyOnChange() =
        runTest {
            repository.isSharedInstanceFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveIsSharedInstance(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveIsSharedInstance(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun rpcKeyFlow_emitsOnlyOnChange() =
        runTest {
            repository.rpcKeyFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveRpcKey(initial)
                expectNoEvents()

                // Save new value - should emit
                val newValue = "test_key_${System.currentTimeMillis()}"
                repository.saveRpcKey(newValue)
                assertEquals(newValue, awaitItem())

                // Save same value again - should NOT emit
                repository.saveRpcKey(newValue)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Message Delivery Flow Tests ==========

    @Test
    fun defaultDeliveryMethodFlow_emitsOnlyOnChange() =
        runTest {
            repository.defaultDeliveryMethodFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveDefaultDeliveryMethod(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == "direct") "propagated" else "direct"
                repository.saveDefaultDeliveryMethod(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun tryPropagationOnFailFlow_emitsOnlyOnChange() =
        runTest {
            repository.tryPropagationOnFailFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveTryPropagationOnFail(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveTryPropagationOnFail(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun manualPropagationNodeFlow_emitsOnlyOnChange() =
        runTest {
            repository.manualPropagationNodeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveManualPropagationNode(initial)
                expectNoEvents()

                // Save new value - should emit
                val newValue = "node_${System.currentTimeMillis()}"
                repository.saveManualPropagationNode(newValue)
                assertEquals(newValue, awaitItem())

                // Save same value - should NOT emit
                repository.saveManualPropagationNode(newValue)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastPropagationNodeFlow_emitsOnlyOnChange() =
        runTest {
            repository.lastPropagationNodeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                if (initial != null) {
                    repository.saveLastPropagationNode(initial)
                    expectNoEvents()
                }

                // Save new value - should emit
                val newValue = "node_${System.currentTimeMillis()}"
                repository.saveLastPropagationNode(newValue)
                assertEquals(newValue, awaitItem())

                // Save same value - should NOT emit
                repository.saveLastPropagationNode(newValue)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun autoSelectPropagationNodeFlow_emitsOnlyOnChange() =
        runTest {
            repository.autoSelectPropagationNodeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveAutoSelectPropagationNode(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveAutoSelectPropagationNode(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Message Retrieval Flow Tests ==========

    @Test
    fun autoRetrieveEnabledFlow_emitsOnlyOnChange() =
        runTest {
            repository.autoRetrieveEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveAutoRetrieveEnabled(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveAutoRetrieveEnabled(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun retrievalIntervalSecondsFlow_emitsOnlyOnChange() =
        runTest {
            repository.retrievalIntervalSecondsFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveRetrievalIntervalSeconds(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == 30) 60 else 30
                repository.saveRetrievalIntervalSeconds(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastSyncTimestampFlow_emitsOnlyOnChange() =
        runTest {
            repository.lastSyncTimestampFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                if (initial != null) {
                    repository.saveLastSyncTimestamp(initial)
                    expectNoEvents()
                }

                // Save new value - should emit
                val newValue = System.currentTimeMillis()
                repository.saveLastSyncTimestamp(newValue)
                assertEquals(newValue, awaitItem())

                // Save same value - should NOT emit
                repository.saveLastSyncTimestamp(newValue)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Location Sharing Flow Tests ==========

    @Test
    fun locationSharingEnabledFlow_defaultsToFalse() =
        runTest {
            // Issue #151: Location sharing should default to disabled for privacy
            repository.locationSharingEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Default should be false (disabled) for privacy-conscious defaults
                assertFalse(
                    "Location sharing should default to disabled for privacy",
                    initial,
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun locationSharingEnabledFlow_emitsOnlyOnChange() =
        runTest {
            repository.locationSharingEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveLocationSharingEnabled(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveLocationSharingEnabled(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}
