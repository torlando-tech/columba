package com.lxmf.messenger.migration

import android.util.Log
import com.lxmf.messenger.repository.SettingsRepository

/**
 * Handles importing settings from legacy export formats (pre-automatic DataStore export).
 * This maintains backward compatibility with exports from older app versions.
 *
 * Note: This class will be removed once we no longer need to support legacy exports.
 */
internal class LegacySettingsImporter(
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "LegacySettingsImporter"
    }

    /**
     * Import all settings from legacy format.
     * @param settings The legacy settings export
     * @param themeIdMap Maps old custom theme IDs to new IDs for theme preference restoration
     */
    @Suppress("DEPRECATION")
    suspend fun importAll(
        settings: SettingsExport,
        themeIdMap: Map<Long, Long>,
    ) {
        importNotificationSettings(settings)
        importAnnounceSettings(settings)
        importThemeSettings(settings, themeIdMap)
        importInstanceSettings(settings)
        importPropagationSettings(settings)
        importLocationSettings(settings)
    }

    @Suppress("DEPRECATION")
    private suspend fun importNotificationSettings(settings: SettingsExport) {
        settings.notificationsEnabled?.let { settingsRepository.saveNotificationsEnabled(it) }
        settings.notificationReceivedMessage?.let { settingsRepository.saveNotificationReceivedMessage(it) }
        settings.notificationReceivedMessageFavorite?.let {
            settingsRepository.saveNotificationReceivedMessageFavorite(it)
        }
        settings.notificationHeardAnnounce?.let { settingsRepository.saveNotificationHeardAnnounce(it) }
        settings.notificationBleConnected?.let { settingsRepository.saveNotificationBleConnected(it) }
        settings.notificationBleDisconnected?.let { settingsRepository.saveNotificationBleDisconnected(it) }
        settings.hasRequestedNotificationPermission?.let {
            settingsRepository.markNotificationPermissionRequested()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun importAnnounceSettings(settings: SettingsExport) {
        settings.autoAnnounceEnabled?.let { settingsRepository.saveAutoAnnounceEnabled(it) }
        // Use hours field if present, otherwise convert legacy minutes to hours
        val intervalHours =
            settings.autoAnnounceIntervalHours
                ?: settings.autoAnnounceIntervalMinutes?.let { minutes ->
                    // Old values (5, 10, 15, 30, 60 min) all < 60, convert with rounding and clamp to valid range
                    ((minutes + 30) / 60).coerceIn(1, 12)
                }
        intervalHours?.let { settingsRepository.saveAutoAnnounceIntervalHours(it) }
        settings.lastAutoAnnounceTime?.let { settingsRepository.saveLastAutoAnnounceTime(it) }
    }

    @Suppress("DEPRECATION")
    private suspend fun importThemeSettings(
        settings: SettingsExport,
        themeIdMap: Map<Long, Long>,
    ) {
        settings.themePreference?.let { themePreference ->
            try {
                val remappedThemePref =
                    if (themePreference.startsWith("custom:")) {
                        val oldId = themePreference.removePrefix("custom:").toLongOrNull()
                        if (oldId != null && themeIdMap.containsKey(oldId)) {
                            "custom:${themeIdMap[oldId]}"
                        } else {
                            Log.w(TAG, "Custom theme ID $oldId not found in mapping, using default")
                            null
                        }
                    } else {
                        themePreference
                    }

                if (remappedThemePref != null) {
                    settingsRepository.saveThemePreferenceByIdentifier(remappedThemePref)
                    Log.d(TAG, "Restored theme preference: $remappedThemePref")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore theme preference: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun importInstanceSettings(settings: SettingsExport) {
        settings.preferOwnInstance?.let { settingsRepository.savePreferOwnInstance(it) }
        settings.rpcKey?.let { settingsRepository.saveRpcKey(it) }
    }

    @Suppress("DEPRECATION")
    private suspend fun importPropagationSettings(settings: SettingsExport) {
        settings.defaultDeliveryMethod?.let { settingsRepository.saveDefaultDeliveryMethod(it) }
        settings.tryPropagationOnFail?.let { settingsRepository.saveTryPropagationOnFail(it) }
        settings.manualPropagationNode?.let { settingsRepository.saveManualPropagationNode(it) }
        settings.lastPropagationNode?.let { settingsRepository.saveLastPropagationNode(it) }
        settings.autoSelectPropagationNode?.let { settingsRepository.saveAutoSelectPropagationNode(it) }
        settings.autoRetrieveEnabled?.let { settingsRepository.saveAutoRetrieveEnabled(it) }
        settings.retrievalIntervalSeconds?.let { settingsRepository.saveRetrievalIntervalSeconds(it) }
        settings.lastSyncTimestamp?.let { settingsRepository.saveLastSyncTimestamp(it) }
        settings.transportNodeEnabled?.let { settingsRepository.saveTransportNodeEnabled(it) }
    }

    @Suppress("DEPRECATION")
    private suspend fun importLocationSettings(settings: SettingsExport) {
        settings.locationSharingEnabled?.let { settingsRepository.saveLocationSharingEnabled(it) }
        settings.defaultSharingDuration?.let { settingsRepository.saveDefaultSharingDuration(it) }
        settings.locationPrecisionRadius?.let { settingsRepository.saveLocationPrecisionRadius(it) }
    }
}
