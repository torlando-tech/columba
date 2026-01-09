package com.lxmf.messenger.migration

import com.lxmf.messenger.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LegacySettingsImporter.
 * Verifies that legacy settings are correctly imported from old export formats.
 */
class LegacySettingsImporterTest {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var importer: LegacySettingsImporter

    @Before
    fun setup() {
        settingsRepository = mockk(relaxed = true)
        importer = LegacySettingsImporter(settingsRepository)
    }

    // region Notification Settings

    @Test
    fun `importAll imports notification settings when present`() =
        runTest {
            val settings =
                createLegacySettings(
                    notificationsEnabled = true,
                    notificationReceivedMessage = false,
                    notificationReceivedMessageFavorite = true,
                    notificationHeardAnnounce = false,
                    notificationBleConnected = true,
                    notificationBleDisconnected = false,
                    hasRequestedNotificationPermission = true,
                )

            importer.importAll(settings, emptyMap())

            coVerify { settingsRepository.saveNotificationsEnabled(true) }
            coVerify { settingsRepository.saveNotificationReceivedMessage(false) }
            coVerify { settingsRepository.saveNotificationReceivedMessageFavorite(true) }
            coVerify { settingsRepository.saveNotificationHeardAnnounce(false) }
            coVerify { settingsRepository.saveNotificationBleConnected(true) }
            coVerify { settingsRepository.saveNotificationBleDisconnected(false) }
            coVerify { settingsRepository.markNotificationPermissionRequested() }
        }

    @Test
    fun `importAll skips null notification settings`() =
        runTest {
            val settings = createLegacySettings()

            importer.importAll(settings, emptyMap())

            coVerify(exactly = 0) { settingsRepository.saveNotificationsEnabled(any()) }
            coVerify(exactly = 0) { settingsRepository.saveNotificationReceivedMessage(any()) }
        }

    // endregion

    // region Auto-Announce Settings

    @Test
    fun `importAll imports announce settings when present`() =
        runTest {
            val settings =
                createLegacySettings(
                    autoAnnounceEnabled = true,
                    autoAnnounceIntervalMinutes = 15,
                    lastAutoAnnounceTime = 1700000000000L,
                )

            importer.importAll(settings, emptyMap())

            coVerify { settingsRepository.saveAutoAnnounceEnabled(true) }
            coVerify { settingsRepository.saveAutoAnnounceIntervalMinutes(15) }
            coVerify { settingsRepository.saveLastAutoAnnounceTime(1700000000000L) }
        }

    // endregion

    // region Theme Settings

    @Test
    fun `importAll imports standard theme preference`() =
        runTest {
            val settings = createLegacySettings(themePreference = "preset:VIBRANT")

            importer.importAll(settings, emptyMap())

            coVerify { settingsRepository.saveThemePreferenceByIdentifier("preset:VIBRANT") }
        }

    @Test
    fun `importAll remaps custom theme ID when present in map`() =
        runTest {
            val settings = createLegacySettings(themePreference = "custom:123")
            val themeIdMap = mapOf(123L to 456L)

            importer.importAll(settings, themeIdMap)

            coVerify { settingsRepository.saveThemePreferenceByIdentifier("custom:456") }
        }

    @Test
    fun `importAll skips custom theme when ID not in map`() =
        runTest {
            val settings = createLegacySettings(themePreference = "custom:999")
            val themeIdMap = mapOf(123L to 456L) // 999 not in map

            importer.importAll(settings, themeIdMap)

            coVerify(exactly = 0) { settingsRepository.saveThemePreferenceByIdentifier(any()) }
        }

    @Test
    fun `importAll handles invalid custom theme ID gracefully`() =
        runTest {
            val settings = createLegacySettings(themePreference = "custom:notanumber")

            importer.importAll(settings, emptyMap())

            // Should not crash, and should not save invalid theme
            coVerify(exactly = 0) { settingsRepository.saveThemePreferenceByIdentifier(any()) }
        }

    // endregion

    // region Instance Settings

    @Test
    fun `importAll imports instance settings when present`() =
        runTest {
            val settings =
                createLegacySettings(
                    preferOwnInstance = true,
                    rpcKey = "test-rpc-key",
                )

            importer.importAll(settings, emptyMap())

            coVerify { settingsRepository.savePreferOwnInstance(true) }
            coVerify { settingsRepository.saveRpcKey("test-rpc-key") }
        }

    // endregion

    // region Propagation Settings

    @Test
    fun `importAll imports propagation settings when present`() =
        runTest {
            val settings =
                createLegacySettings(
                    defaultDeliveryMethod = "DIRECT",
                    tryPropagationOnFail = true,
                    manualPropagationNode = "node123",
                    lastPropagationNode = "node456",
                    autoSelectPropagationNode = false,
                    autoRetrieveEnabled = true,
                    retrievalIntervalSeconds = 300,
                    lastSyncTimestamp = 1700000000000L,
                    transportNodeEnabled = true,
                )

            importer.importAll(settings, emptyMap())

            coVerify { settingsRepository.saveDefaultDeliveryMethod("DIRECT") }
            coVerify { settingsRepository.saveTryPropagationOnFail(true) }
            coVerify { settingsRepository.saveManualPropagationNode("node123") }
            coVerify { settingsRepository.saveLastPropagationNode("node456") }
            coVerify { settingsRepository.saveAutoSelectPropagationNode(false) }
            coVerify { settingsRepository.saveAutoRetrieveEnabled(true) }
            coVerify { settingsRepository.saveRetrievalIntervalSeconds(300) }
            coVerify { settingsRepository.saveLastSyncTimestamp(1700000000000L) }
            coVerify { settingsRepository.saveTransportNodeEnabled(true) }
        }

    // endregion

    // region Location Settings

    @Test
    fun `importAll imports location settings when present`() =
        runTest {
            val settings =
                createLegacySettings(
                    locationSharingEnabled = true,
                    defaultSharingDuration = "1_HOUR",
                    locationPrecisionRadius = 100,
                )

            importer.importAll(settings, emptyMap())

            coVerify { settingsRepository.saveLocationSharingEnabled(true) }
            coVerify { settingsRepository.saveDefaultSharingDuration("1_HOUR") }
            coVerify { settingsRepository.saveLocationPrecisionRadius(100) }
        }

    // endregion

    // region Helper Methods

    @Suppress("DEPRECATION", "LongParameterList")
    private fun createLegacySettings(
        notificationsEnabled: Boolean? = null,
        notificationReceivedMessage: Boolean? = null,
        notificationReceivedMessageFavorite: Boolean? = null,
        notificationHeardAnnounce: Boolean? = null,
        notificationBleConnected: Boolean? = null,
        notificationBleDisconnected: Boolean? = null,
        hasRequestedNotificationPermission: Boolean? = null,
        autoAnnounceEnabled: Boolean? = null,
        autoAnnounceIntervalMinutes: Int? = null,
        lastAutoAnnounceTime: Long? = null,
        themePreference: String? = null,
        preferOwnInstance: Boolean? = null,
        rpcKey: String? = null,
        defaultDeliveryMethod: String? = null,
        tryPropagationOnFail: Boolean? = null,
        manualPropagationNode: String? = null,
        lastPropagationNode: String? = null,
        autoSelectPropagationNode: Boolean? = null,
        autoRetrieveEnabled: Boolean? = null,
        retrievalIntervalSeconds: Int? = null,
        lastSyncTimestamp: Long? = null,
        transportNodeEnabled: Boolean? = null,
        locationSharingEnabled: Boolean? = null,
        defaultSharingDuration: String? = null,
        locationPrecisionRadius: Int? = null,
    ) = SettingsExport(
        // Empty = legacy format
        preferences = emptyList(),
        notificationsEnabled = notificationsEnabled,
        notificationReceivedMessage = notificationReceivedMessage,
        notificationReceivedMessageFavorite = notificationReceivedMessageFavorite,
        notificationHeardAnnounce = notificationHeardAnnounce,
        notificationBleConnected = notificationBleConnected,
        notificationBleDisconnected = notificationBleDisconnected,
        hasRequestedNotificationPermission = hasRequestedNotificationPermission,
        autoAnnounceEnabled = autoAnnounceEnabled,
        autoAnnounceIntervalMinutes = autoAnnounceIntervalMinutes,
        lastAutoAnnounceTime = lastAutoAnnounceTime,
        themePreference = themePreference,
        preferOwnInstance = preferOwnInstance,
        rpcKey = rpcKey,
        defaultDeliveryMethod = defaultDeliveryMethod,
        tryPropagationOnFail = tryPropagationOnFail,
        manualPropagationNode = manualPropagationNode,
        lastPropagationNode = lastPropagationNode,
        autoSelectPropagationNode = autoSelectPropagationNode,
        autoRetrieveEnabled = autoRetrieveEnabled,
        retrievalIntervalSeconds = retrievalIntervalSeconds,
        lastSyncTimestamp = lastSyncTimestamp,
        transportNodeEnabled = transportNodeEnabled,
        locationSharingEnabled = locationSharingEnabled,
        defaultSharingDuration = defaultSharingDuration,
        locationPrecisionRadius = locationPrecisionRadius,
    )

    // endregion
}
