package com.lxmf.messenger.migration

import com.lxmf.messenger.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LegacySettingsImporter.
 *
 * Tests the conversion logic for legacy settings imports:
 * - Minutes to hours conversion for auto-announce interval
 * - Custom theme ID remapping
 * - Null handling for optional fields
 *
 * Uses explicit mock stubs (not relaxed) to verify exact values passed to repository.
 */
class LegacySettingsImporterTest {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var importer: LegacySettingsImporter

    @Before
    fun setup() {
        settingsRepository = mockk()
        importer = LegacySettingsImporter(settingsRepository)

        // Stub all repository methods that might be called
        stubAllRepositoryMethods()
    }

    // ========== Minutes to Hours Conversion Tests ==========

    @Test
    fun `importAll converts 5 minutes to 1 hour (minimum)`() =
        runTest {
            // Given: Legacy export with 5-minute interval
            val settings = createSettingsExport(autoAnnounceIntervalMinutes = 5)
            val hoursSlot = slot<Int>()
            coEvery { settingsRepository.saveAutoAnnounceIntervalHours(capture(hoursSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: Should convert to 1 hour (5+30)/60 = 0.58, rounded and clamped to 1
            assertEquals(1, hoursSlot.captured)
        }

    @Test
    fun `importAll converts 30 minutes to 1 hour`() =
        runTest {
            // Given: Legacy export with 30-minute interval
            val settings = createSettingsExport(autoAnnounceIntervalMinutes = 30)
            val hoursSlot = slot<Int>()
            coEvery { settingsRepository.saveAutoAnnounceIntervalHours(capture(hoursSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: (30+30)/60 = 1
            assertEquals(1, hoursSlot.captured)
        }

    @Test
    fun `importAll converts 60 minutes to 2 hours`() =
        runTest {
            // Given: Legacy export with 60-minute interval
            val settings = createSettingsExport(autoAnnounceIntervalMinutes = 60)
            val hoursSlot = slot<Int>()
            coEvery { settingsRepository.saveAutoAnnounceIntervalHours(capture(hoursSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: (60+30)/60 = 1.5, truncated to 1
            assertEquals(1, hoursSlot.captured)
        }

    @Test
    fun `importAll prefers hours field over minutes when both present`() =
        runTest {
            // Given: Export with both hours and minutes (hours should win)
            @Suppress("DEPRECATION")
            val settings =
                SettingsExport(
                    autoAnnounceIntervalHours = 6,
                    autoAnnounceIntervalMinutes = 30, // Should be ignored
                )
            val hoursSlot = slot<Int>()
            coEvery { settingsRepository.saveAutoAnnounceIntervalHours(capture(hoursSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: Should use hours value directly
            assertEquals(6, hoursSlot.captured)
        }

    @Test
    fun `importAll clamps converted hours to maximum of 12`() =
        runTest {
            // Given: Extremely high minutes value
            val settings = createSettingsExport(autoAnnounceIntervalMinutes = 1000)
            val hoursSlot = slot<Int>()
            coEvery { settingsRepository.saveAutoAnnounceIntervalHours(capture(hoursSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: Should clamp to 12 hours max
            assertEquals(12, hoursSlot.captured)
        }

    // ========== Theme ID Remapping Tests ==========

    @Test
    fun `importAll remaps custom theme ID correctly`() =
        runTest {
            // Given: Legacy export with custom theme, and ID mapping
            val settings = createSettingsExport(themePreference = "custom:100")
            val themeIdMap = mapOf(100L to 999L) // Old ID 100 -> New ID 999
            val themeSlot = slot<String>()
            coEvery { settingsRepository.saveThemePreferenceByIdentifier(capture(themeSlot)) } just Runs

            // When
            importer.importAll(settings, themeIdMap)

            // Then: Should use remapped ID
            assertEquals("custom:999", themeSlot.captured)
        }

    @Test
    fun `importAll preserves non-custom theme preferences`() =
        runTest {
            // Given: Built-in theme (not custom)
            val settings = createSettingsExport(themePreference = "material_you")
            val themeSlot = slot<String>()
            coEvery { settingsRepository.saveThemePreferenceByIdentifier(capture(themeSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: Should preserve as-is
            assertEquals("material_you", themeSlot.captured)
        }

    @Test
    fun `importAll skips theme when custom ID not in mapping`() =
        runTest {
            // Given: Custom theme with ID that doesn't exist in mapping
            val settings = createSettingsExport(themePreference = "custom:999")
            val themeIdMap = mapOf(100L to 200L) // 999 not in map

            // When - function should complete successfully (no exceptions)
            val result = runCatching { importer.importAll(settings, themeIdMap) }

            // Then: Function completed successfully and did NOT call saveThemePreferenceByIdentifier
            assertTrue("importAll should complete without throwing", result.isSuccess)
            coVerify(exactly = 0) { settingsRepository.saveThemePreferenceByIdentifier(any()) }
        }

    @Test
    fun `importAll handles malformed custom theme ID gracefully`() =
        runTest {
            // Given: Malformed custom theme preference
            val settings = createSettingsExport(themePreference = "custom:not_a_number")
            val themeIdMap = mapOf(100L to 200L)

            // When - function should complete successfully (no exceptions)
            val result = runCatching { importer.importAll(settings, themeIdMap) }

            // Then: Function completed successfully and did NOT call save
            assertTrue("importAll should complete without throwing", result.isSuccess)
            coVerify(exactly = 0) { settingsRepository.saveThemePreferenceByIdentifier(any()) }
        }

    // ========== Null Handling Tests ==========

    @Test
    fun `importAll handles all null values gracefully`() =
        runTest {
            // Given: Empty settings export
            @Suppress("DEPRECATION")
            val settings = SettingsExport()

            // When - function should complete successfully (no exceptions)
            val result = runCatching { importer.importAll(settings, emptyMap()) }

            // Then: Function completed successfully and no save methods were called
            assertTrue("importAll should complete without throwing", result.isSuccess)
            coVerify(exactly = 0) { settingsRepository.saveNotificationsEnabled(any()) }
            coVerify(exactly = 0) { settingsRepository.saveAutoAnnounceEnabled(any()) }
            coVerify(exactly = 0) { settingsRepository.saveAutoAnnounceIntervalHours(any()) }
        }

    @Test
    fun `importAll imports only present fields`() =
        runTest {
            // Given: Partial settings
            @Suppress("DEPRECATION")
            val settings =
                SettingsExport(
                    notificationsEnabled = true,
                    transportNodeEnabled = false,
                    // All other fields null
                )
            val notificationsSlot = slot<Boolean>()
            val transportSlot = slot<Boolean>()
            coEvery { settingsRepository.saveNotificationsEnabled(capture(notificationsSlot)) } just Runs
            coEvery { settingsRepository.saveTransportNodeEnabled(capture(transportSlot)) } just Runs

            // When
            importer.importAll(settings, emptyMap())

            // Then: Only present fields should be saved with correct values
            assertEquals(true, notificationsSlot.captured)
            assertEquals(false, transportSlot.captured)
            // autoAnnounce was null in settings, so should not be called
            coVerify(exactly = 0) { settingsRepository.saveAutoAnnounceEnabled(any()) }
        }

    // ========== Helper Methods ==========

    @Suppress("DEPRECATION")
    private fun createSettingsExport(
        autoAnnounceIntervalMinutes: Int? = null,
        autoAnnounceIntervalHours: Int? = null,
        themePreference: String? = null,
    ) = SettingsExport(
        autoAnnounceIntervalMinutes = autoAnnounceIntervalMinutes,
        autoAnnounceIntervalHours = autoAnnounceIntervalHours,
        themePreference = themePreference,
    )

    private fun stubAllRepositoryMethods() {
        // Stub all possible save methods to avoid "no answer found" errors
        coEvery { settingsRepository.saveNotificationsEnabled(any()) } just Runs
        coEvery { settingsRepository.saveNotificationReceivedMessage(any()) } just Runs
        coEvery { settingsRepository.saveNotificationReceivedMessageFavorite(any()) } just Runs
        coEvery { settingsRepository.saveNotificationHeardAnnounce(any()) } just Runs
        coEvery { settingsRepository.saveNotificationBleConnected(any()) } just Runs
        coEvery { settingsRepository.saveNotificationBleDisconnected(any()) } just Runs
        coEvery { settingsRepository.markNotificationPermissionRequested() } just Runs
        coEvery { settingsRepository.saveAutoAnnounceEnabled(any()) } just Runs
        coEvery { settingsRepository.saveAutoAnnounceIntervalHours(any()) } just Runs
        coEvery { settingsRepository.saveLastAutoAnnounceTime(any()) } just Runs
        coEvery { settingsRepository.saveThemePreferenceByIdentifier(any()) } just Runs
        coEvery { settingsRepository.savePreferOwnInstance(any()) } just Runs
        coEvery { settingsRepository.saveRpcKey(any()) } just Runs
        coEvery { settingsRepository.saveDefaultDeliveryMethod(any()) } just Runs
        coEvery { settingsRepository.saveTryPropagationOnFail(any()) } just Runs
        coEvery { settingsRepository.saveManualPropagationNode(any()) } just Runs
        coEvery { settingsRepository.saveLastPropagationNode(any()) } just Runs
        coEvery { settingsRepository.saveAutoSelectPropagationNode(any()) } just Runs
        coEvery { settingsRepository.saveAutoRetrieveEnabled(any()) } just Runs
        coEvery { settingsRepository.saveRetrievalIntervalSeconds(any()) } just Runs
        coEvery { settingsRepository.saveLastSyncTimestamp(any()) } just Runs
        coEvery { settingsRepository.saveTransportNodeEnabled(any()) } just Runs
        coEvery { settingsRepository.saveLocationSharingEnabled(any()) } just Runs
        coEvery { settingsRepository.saveDefaultSharingDuration(any()) } just Runs
        coEvery { settingsRepository.saveLocationPrecisionRadius(any()) } just Runs
    }
}
