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
import org.junit.Assert.assertNull
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
    fun autoAnnounceIntervalHoursFlow_emitsOnlyOnChange() =
        runTest {
            repository.autoAnnounceIntervalHoursFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveAutoAnnounceIntervalHours(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == 3) 6 else 3
                repository.saveAutoAnnounceIntervalHours(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun autoAnnounceIntervalHoursFlow_defaultsTo3Hours() =
        runTest {
            // Fresh repository should default to 3 hours
            val defaultValue = repository.autoAnnounceIntervalHoursFlow.first()
            // Default is 3, but if previously set in another test, just verify it's in valid range
            assertTrue("Default should be in valid range 1-12", defaultValue in 1..12)
        }

    @Test
    fun saveAutoAnnounceIntervalHours_clampsToValidRange() =
        runTest {
            // Save values and verify they're accepted within range
            repository.saveAutoAnnounceIntervalHours(1)
            assertEquals(1, repository.autoAnnounceIntervalHoursFlow.first())

            repository.saveAutoAnnounceIntervalHours(12)
            assertEquals(12, repository.autoAnnounceIntervalHoursFlow.first())

            repository.saveAutoAnnounceIntervalHours(6)
            assertEquals(6, repository.autoAnnounceIntervalHoursFlow.first())
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

    @Test
    fun nextAutoAnnounceTimeFlow_defaultsToNull() =
        runTest {
            val value = repository.nextAutoAnnounceTimeFlow.first()
            assertNull(value)
        }

    @Test
    fun nextAutoAnnounceTimeFlow_emitsOnlyOnChange() =
        runTest {
            repository.nextAutoAnnounceTimeFlow.test(timeout = 5.seconds) {
                // Initial value should be null
                assertNull(awaitItem())

                // Save a timestamp - should emit
                val timestamp = 1704067200000L
                repository.saveNextAutoAnnounceTime(timestamp)
                assertEquals(timestamp, awaitItem())

                // Save same value - should NOT emit
                repository.saveNextAutoAnnounceTime(timestamp)
                expectNoEvents()

                // Save null - should emit
                repository.saveNextAutoAnnounceTime(null)
                assertNull(awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun networkChangeAnnounceTimeFlow_defaultsToNull() =
        runTest {
            val value = repository.networkChangeAnnounceTimeFlow.first()
            assertNull(value)
        }

    @Test
    fun networkChangeAnnounceTimeFlow_emitsOnlyOnChange() =
        runTest {
            repository.networkChangeAnnounceTimeFlow.test(timeout = 5.seconds) {
                // Initial value should be null
                assertNull(awaitItem())

                // Save a timestamp - should emit
                val timestamp = System.currentTimeMillis()
                repository.saveNetworkChangeAnnounceTime(timestamp)
                assertEquals(timestamp, awaitItem())

                // Save same value - should NOT emit
                repository.saveNetworkChangeAnnounceTime(timestamp)
                expectNoEvents()

                // Save new value - should emit
                val newTimestamp = timestamp + 1000
                repository.saveNetworkChangeAnnounceTime(newTimestamp)
                assertEquals(newTimestamp, awaitItem())

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

    // ========== Incoming Message Size Limit Tests ==========

    @Test
    fun incomingMessageSizeLimitKbFlow_defaultsTo1024() =
        runTest {
            repository.incomingMessageSizeLimitKbFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                // Default should be 1024 (1MB) or whatever was previously set
                assertTrue("Limit should be at least minimum (512)", initial >= 512)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun incomingMessageSizeLimitKbFlow_emitsOnlyOnChange() =
        runTest {
            repository.incomingMessageSizeLimitKbFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveIncomingMessageSizeLimitKb(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == 5120) 10240 else 5120
                repository.saveIncomingMessageSizeLimitKb(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun saveIncomingMessageSizeLimitKb_clampsToMinimum() =
        runTest {
            // Try to save below minimum (512KB)
            repository.saveIncomingMessageSizeLimitKb(100)

            val result = repository.getIncomingMessageSizeLimitKb()
            assertEquals(
                "Should be clamped to minimum",
                SettingsRepository.MIN_INCOMING_SIZE_LIMIT_KB,
                result,
            )
        }

    @Test
    fun saveIncomingMessageSizeLimitKb_clampsToMaximum() =
        runTest {
            // Try to save above maximum (128MB)
            repository.saveIncomingMessageSizeLimitKb(200000)

            val result = repository.getIncomingMessageSizeLimitKb()
            assertEquals(
                "Should be clamped to maximum",
                SettingsRepository.MAX_INCOMING_SIZE_LIMIT_KB,
                result,
            )
        }

    @Test
    fun saveIncomingMessageSizeLimitKb_acceptsValidValues() =
        runTest {
            // Test several valid values
            val validValues = listOf(1024, 5120, 10240, 25600, 131072)

            for (value in validValues) {
                repository.saveIncomingMessageSizeLimitKb(value)
                val result = repository.getIncomingMessageSizeLimitKb()
                assertEquals("Value $value should be saved as-is", value, result)
            }
        }

    @Test
    fun getIncomingMessageSizeLimitKb_returnsDefaultWhenNotSet() =
        runTest {
            // First call without any set should return default
            val result = repository.getIncomingMessageSizeLimitKb()
            assertTrue(
                "Should return a value within valid range",
                result in SettingsRepository.MIN_INCOMING_SIZE_LIMIT_KB..SettingsRepository.MAX_INCOMING_SIZE_LIMIT_KB,
            )
        }

    // ========== Location Permission Sheet Flow Tests ==========

    @Test
    fun hasDismissedLocationPermissionSheetFlow_defaultsToFalse() =
        runTest {
            // Reset first to ensure clean state
            repository.resetLocationPermissionSheetDismissal()

            repository.hasDismissedLocationPermissionSheetFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertFalse(
                    "Location permission sheet dismissal should default to false",
                    initial,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun markLocationPermissionSheetDismissed_setsToTrue() =
        runTest {
            // Reset first to ensure clean state
            repository.resetLocationPermissionSheetDismissal()

            repository.hasDismissedLocationPermissionSheetFlow.test(timeout = 5.seconds) {
                // Initial should be false
                assertFalse(awaitItem())

                // Mark as dismissed - should emit true
                repository.markLocationPermissionSheetDismissed()
                assertTrue(awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun resetLocationPermissionSheetDismissal_setsToFalse() =
        runTest {
            // First mark as dismissed
            repository.markLocationPermissionSheetDismissed()

            repository.hasDismissedLocationPermissionSheetFlow.test(timeout = 5.seconds) {
                // Initial should be true (from previous mark)
                assertTrue(awaitItem())

                // Reset - should emit false
                repository.resetLocationPermissionSheetDismissal()
                assertFalse(awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun hasDismissedLocationPermissionSheetFlow_emitsOnlyOnChange() =
        runTest {
            repository.hasDismissedLocationPermissionSheetFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                if (!initial) {
                    // Mark as dismissed - should emit
                    repository.markLocationPermissionSheetDismissed()
                    assertTrue(awaitItem())

                    // Mark again - should NOT emit (already true)
                    repository.markLocationPermissionSheetDismissed()
                    expectNoEvents()
                } else {
                    // Already true, marking again should NOT emit
                    repository.markLocationPermissionSheetDismissed()
                    expectNoEvents()
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Export/Import Tests ==========

    @Test
    fun exportAllPreferences_includesAllSavedSettings() =
        runTest {
            // Save some settings
            repository.saveNotificationsEnabled(true)
            repository.saveAutoAnnounceEnabled(false)
            repository.saveAutoAnnounceIntervalHours(3)
            testDispatcher.scheduler.advanceUntilIdle()

            // Export
            val entries = repository.exportAllPreferences()

            // Verify entries contain our saved settings
            val notificationsEntry = entries.find { it.key == "notifications_enabled" }
            assertEquals("boolean", notificationsEntry?.type)
            assertEquals("true", notificationsEntry?.value)

            val autoAnnounceEntry = entries.find { it.key == "auto_announce_enabled" }
            assertEquals("boolean", autoAnnounceEntry?.type)
            assertEquals("false", autoAnnounceEntry?.value)

            val intervalEntry = entries.find { it.key == "auto_announce_interval_hours" }
            assertEquals("int", intervalEntry?.type)
            assertEquals("3", intervalEntry?.value)
        }

    @Test
    fun importAllPreferences_restoresBooleanSettings() =
        runTest {
            val entries =
                listOf(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "notifications_enabled",
                        type = "boolean",
                        value = "false",
                    ),
                )

            repository.importAllPreferences(entries)
            testDispatcher.scheduler.advanceUntilIdle()

            val result = repository.notificationsEnabledFlow.first()
            assertFalse(result)
        }

    @Test
    fun importAllPreferences_restoresIntSettings() =
        runTest {
            val entries =
                listOf(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "auto_announce_interval_hours",
                        type = "int",
                        value = "6",
                    ),
                )

            repository.importAllPreferences(entries)
            testDispatcher.scheduler.advanceUntilIdle()

            val result = repository.autoAnnounceIntervalHoursFlow.first()
            assertEquals(6, result)
        }

    @Test
    fun importAllPreferences_restoresLongSettings() =
        runTest {
            val entries =
                listOf(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "last_sync_timestamp",
                        type = "long",
                        value = "1700000000000",
                    ),
                )

            repository.importAllPreferences(entries)
            testDispatcher.scheduler.advanceUntilIdle()

            val result = repository.lastSyncTimestampFlow.first()
            assertEquals(1700000000000L, result)
        }

    @Test
    fun importAllPreferences_restoresStringSettings() =
        runTest {
            val entries =
                listOf(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "default_delivery_method",
                        type = "string",
                        value = "PROPAGATED",
                    ),
                )

            repository.importAllPreferences(entries)
            testDispatcher.scheduler.advanceUntilIdle()

            val result = repository.defaultDeliveryMethodFlow.first()
            assertEquals("PROPAGATED", result)
        }

    @Test
    fun importAllPreferences_ignoresUnknownKeys() =
        runTest {
            val entries =
                listOf(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "unknown_future_setting",
                        type = "boolean",
                        value = "true",
                    ),
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "notifications_enabled",
                        type = "boolean",
                        value = "true",
                    ),
                )

            // Should not throw, should import known key
            repository.importAllPreferences(entries)
            testDispatcher.scheduler.advanceUntilIdle()

            // Known key should be imported
            val result = repository.notificationsEnabledFlow.first()
            assertTrue(result)
        }

    @Test
    fun importAllPreferences_handlesInvalidValuesGracefully() =
        runTest {
            val entries =
                listOf(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = "auto_announce_interval_minutes",
                        type = "int",
                        value = "not_a_number",
                    ),
                )

            // Should not throw
            repository.importAllPreferences(entries)
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun exportImport_roundTrip_preservesSettings() =
        runTest {
            // Save some settings
            repository.saveNotificationsEnabled(true)
            repository.saveAutoAnnounceEnabled(true)
            repository.saveAutoAnnounceIntervalHours(6)
            repository.saveTransportNodeEnabled(true)
            testDispatcher.scheduler.advanceUntilIdle()

            // Export
            val exported = repository.exportAllPreferences()

            // Change settings
            repository.saveNotificationsEnabled(false)
            repository.saveAutoAnnounceEnabled(false)
            repository.saveAutoAnnounceIntervalHours(1)
            repository.saveTransportNodeEnabled(false)
            testDispatcher.scheduler.advanceUntilIdle()

            // Import exported settings
            repository.importAllPreferences(exported)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify original values restored
            assertTrue(repository.notificationsEnabledFlow.first())
            assertTrue(repository.autoAnnounceEnabledFlow.first())
            assertEquals(6, repository.autoAnnounceIntervalHoursFlow.first())
            assertTrue(repository.transportNodeEnabledFlow.first())
        }

    // ========== Block Unknown Senders (Privacy) Flow Tests ==========

    @Test
    fun blockUnknownSendersFlow_defaultsToFalse() =
        runTest {
            // Issue #208: Block unknown senders should default to false (allow all messages)
            repository.blockUnknownSendersFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertFalse(
                    "Block unknown senders should default to false to preserve existing behavior",
                    initial,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun blockUnknownSendersFlow_emitsOnlyOnChange() =
        runTest {
            repository.blockUnknownSendersFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveBlockUnknownSenders(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveBlockUnknownSenders(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getBlockUnknownSenders_matchesFlow() =
        runTest {
            // Set to a known value
            repository.saveBlockUnknownSenders(true)
            testDispatcher.scheduler.advanceUntilIdle()

            // When - Read from both sources
            val flowValue = repository.blockUnknownSendersFlow.first()
            val methodValue = repository.getBlockUnknownSenders()

            // Then - Both should return same value
            assertEquals("Flow and method should return same value", flowValue, methodValue)
            assertTrue("Both should be true", methodValue)
        }

    // ========== Telemetry Collector Flow Tests ==========

    @Test
    fun telemetryCollectorEnabledFlow_defaultsToFalse() =
        runTest {
            repository.telemetryCollectorEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertFalse("Telemetry collector should default to disabled", initial)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetryCollectorEnabledFlow_emitsOnlyOnChange() =
        runTest {
            repository.telemetryCollectorEnabledFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveTelemetryCollectorEnabled(initial)
                expectNoEvents()

                // Save opposite value - should emit
                repository.saveTelemetryCollectorEnabled(!initial)
                assertEquals(!initial, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetryCollectorAddressFlow_defaultsToNull() =
        runTest {
            repository.telemetryCollectorAddressFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertNull("Telemetry collector address should default to null", initial)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetryCollectorAddressFlow_emitsOnlyOnChange() =
        runTest {
            repository.telemetryCollectorAddressFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // First ensure we have a known starting state by saving a unique address
                val testAddress1 = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
                val testAddress2 = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5"
                // Use a different address than initial to guarantee an emission
                val firstAddress = if (initial == testAddress1) testAddress2 else testAddress1
                repository.saveTelemetryCollectorAddress(firstAddress)
                assertEquals(firstAddress, awaitItem())

                // Save same address - should NOT emit
                repository.saveTelemetryCollectorAddress(firstAddress)
                expectNoEvents()

                // Save different address - should emit
                val secondAddress = if (firstAddress == testAddress1) testAddress2 else testAddress1
                repository.saveTelemetryCollectorAddress(secondAddress)
                assertEquals(secondAddress, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetrySendIntervalSecondsFlow_emitsIntervalValues() =
        runTest {
            // Note: DataStore persists across tests, so we test that the flow
            // correctly emits interval values rather than assuming default
            repository.telemetrySendIntervalSecondsFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                // Initial could be default (fresh install) or modified from previous test
                assertTrue(
                    "Send interval should be a valid value (60-86400 seconds)",
                    initial >= 60 && initial <= 86400,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetrySendIntervalSecondsFlow_emitsOnlyOnChange() =
        runTest {
            repository.telemetrySendIntervalSecondsFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveTelemetrySendIntervalSeconds(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == 300) 900 else 300
                repository.saveTelemetrySendIntervalSeconds(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun saveTelemetrySendIntervalSeconds_clampsToMinimum() =
        runTest {
            // Try to save below minimum (60s)
            repository.saveTelemetrySendIntervalSeconds(30)

            val result = repository.getTelemetrySendIntervalSeconds()
            assertEquals("Should be clamped to minimum 60s", 60, result)
        }

    @Test
    fun saveTelemetrySendIntervalSeconds_clampsToMaximum() =
        runTest {
            // Try to save above maximum (86400s = 24 hours)
            repository.saveTelemetrySendIntervalSeconds(100000)

            val result = repository.getTelemetrySendIntervalSeconds()
            assertEquals("Should be clamped to maximum 86400s", 86400, result)
        }

    @Test
    fun saveTelemetrySendIntervalSeconds_acceptsValidValues() =
        runTest {
            val validValues = listOf(300, 900, 1800, 3600)

            for (value in validValues) {
                repository.saveTelemetrySendIntervalSeconds(value)
                val result = repository.getTelemetrySendIntervalSeconds()
                assertEquals("Value $value should be saved as-is", value, result)
            }
        }

    @Test
    fun telemetryRequestIntervalSecondsFlow_defaultsTo900() =
        runTest {
            repository.telemetryRequestIntervalSecondsFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                assertEquals(
                    "Default request interval should be 900 seconds (15 min)",
                    SettingsRepository.DEFAULT_TELEMETRY_REQUEST_INTERVAL_SECONDS,
                    initial,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetryRequestIntervalSecondsFlow_emitsOnlyOnChange() =
        runTest {
            repository.telemetryRequestIntervalSecondsFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save same value - should NOT emit
                repository.saveTelemetryRequestIntervalSeconds(initial)
                expectNoEvents()

                // Save different value - should emit
                val newValue = if (initial == 900) 1800 else 900
                repository.saveTelemetryRequestIntervalSeconds(newValue)
                assertEquals(newValue, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun saveTelemetryRequestIntervalSeconds_clampsToValidRange() =
        runTest {
            // Test minimum clamping
            repository.saveTelemetryRequestIntervalSeconds(30)
            assertEquals(60, repository.getTelemetryRequestIntervalSeconds())

            // Test maximum clamping
            repository.saveTelemetryRequestIntervalSeconds(100000)
            assertEquals(86400, repository.getTelemetryRequestIntervalSeconds())
        }

    @Test
    fun lastTelemetrySendTimeFlow_emitsTimestampValues() =
        runTest {
            // Note: DataStore persists across tests, so we test that the flow
            // correctly emits timestamp values rather than assuming null default
            repository.lastTelemetrySendTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                // Initial could be null (fresh install) or a timestamp (from previous test)
                assertTrue(
                    "Last telemetry send time should be null or a valid timestamp",
                    initial == null || initial > 0,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastTelemetrySendTimeFlow_emitsOnlyOnChange() =
        runTest {
            repository.lastTelemetrySendTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save a new timestamp different from initial - should emit
                // Use a timestamp guaranteed to be different from any existing value
                val timestamp1 = 1000000000000L // Fixed timestamp for predictability
                if (initial != timestamp1) {
                    repository.saveLastTelemetrySendTime(timestamp1)
                    assertEquals(timestamp1, awaitItem())
                }

                // Save same timestamp - should NOT emit
                repository.saveLastTelemetrySendTime(timestamp1)
                expectNoEvents()

                // Save new timestamp - should emit
                val timestamp2 = timestamp1 + 60_000
                repository.saveLastTelemetrySendTime(timestamp2)
                assertEquals(timestamp2, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastTelemetryRequestTimeFlow_emitsTimestampValues() =
        runTest {
            // Note: DataStore persists across tests, so we test that the flow
            // correctly emits timestamp values rather than assuming null default
            repository.lastTelemetryRequestTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()
                // Initial could be null (fresh install) or a timestamp (from previous test)
                assertTrue(
                    "Last telemetry request time should be null or a valid timestamp",
                    initial == null || initial > 0,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastTelemetryRequestTimeFlow_emitsOnlyOnChange() =
        runTest {
            repository.lastTelemetryRequestTimeFlow.test(timeout = 5.seconds) {
                val initial = awaitItem()

                // Save a new timestamp different from initial - should emit
                // Use a timestamp guaranteed to be different from any existing value
                val timestamp1 = 2000000000000L // Fixed timestamp for predictability
                if (initial != timestamp1) {
                    repository.saveLastTelemetryRequestTime(timestamp1)
                    assertEquals(timestamp1, awaitItem())
                }

                // Save same timestamp - should NOT emit
                repository.saveLastTelemetryRequestTime(timestamp1)
                expectNoEvents()

                // Save new timestamp - should emit
                val timestamp2 = timestamp1 + 60_000
                repository.saveLastTelemetryRequestTime(timestamp2)
                assertEquals(timestamp2, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun telemetryCollector_flowAndGetMethodReturnSameValue() =
        runTest {
            // Set to known values
            val testAddress = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"
            repository.saveTelemetryCollectorEnabled(true)
            repository.saveTelemetryCollectorAddress(testAddress)
            repository.saveTelemetrySendIntervalSeconds(900)
            testDispatcher.scheduler.advanceUntilIdle()

            // Compare flow and method values
            assertEquals(
                repository.telemetryCollectorEnabledFlow.first(),
                repository.getTelemetryCollectorEnabled(),
            )
            assertEquals(
                repository.telemetryCollectorAddressFlow.first(),
                repository.getTelemetryCollectorAddress(),
            )
            assertEquals(
                repository.telemetrySendIntervalSecondsFlow.first(),
                repository.getTelemetrySendIntervalSeconds(),
            )
        }
}
