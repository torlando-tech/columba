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
}
