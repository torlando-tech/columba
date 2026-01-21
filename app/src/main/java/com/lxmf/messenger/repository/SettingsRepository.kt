package com.lxmf.messenger.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.data.repository.CustomThemeRepository
import com.lxmf.messenger.service.persistence.ServiceSettingsAccessor
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.CustomTheme
import com.lxmf.messenger.ui.theme.PresetTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Repository for managing user settings using DataStore.
 * Currently handles display name persistence.
 */
@Suppress("LargeClass") // Repository managing many user preferences
@Singleton
class SettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val customThemeRepository: CustomThemeRepository,
    ) {
        private object PreferencesKeys {
            // Notification preferences
            val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
            val NOTIFICATION_RECEIVED_MESSAGE = booleanPreferencesKey("notification_received_message")
            val NOTIFICATION_RECEIVED_MESSAGE_FAVORITE = booleanPreferencesKey("notification_received_message_favorite")
            val NOTIFICATION_HEARD_ANNOUNCE = booleanPreferencesKey("notification_heard_announce")
            val NOTIFICATION_BLE_CONNECTED = booleanPreferencesKey("notification_ble_connected")
            val NOTIFICATION_BLE_DISCONNECTED = booleanPreferencesKey("notification_ble_disconnected")
            val HAS_REQUESTED_NOTIFICATION_PERMISSION = booleanPreferencesKey("has_requested_notification_permission")

            // Location permission preferences
            val HAS_DISMISSED_LOCATION_PERMISSION_SHEET = booleanPreferencesKey("has_dismissed_location_permission_sheet")

            // Onboarding preferences
            val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")

            // Auto-announce preferences
            val AUTO_ANNOUNCE_ENABLED = booleanPreferencesKey("auto_announce_enabled")
            val AUTO_ANNOUNCE_INTERVAL_MINUTES = intPreferencesKey("auto_announce_interval_minutes") // Legacy, for migration
            val AUTO_ANNOUNCE_INTERVAL_HOURS = intPreferencesKey("auto_announce_interval_hours")
            val LAST_AUTO_ANNOUNCE_TIME = longPreferencesKey("last_auto_announce_time")
            val NEXT_AUTO_ANNOUNCE_TIME = longPreferencesKey("next_auto_announce_time") // Scheduled time for next announce
            val NETWORK_CHANGE_ANNOUNCE_TIME = longPreferencesKey("network_change_announce_time") // Cross-process signal for timer reset

            // Service status persistence
            val LAST_SERVICE_STATUS = stringPreferencesKey("last_service_status")

            // Theme preference
            val THEME_PREFERENCE = stringPreferencesKey("app_theme")

            // Shared instance preferences
            val PREFER_OWN_INSTANCE = booleanPreferencesKey("prefer_own_instance")
            val IS_SHARED_INSTANCE = booleanPreferencesKey("is_shared_instance")
            val RPC_KEY = stringPreferencesKey("rpc_key")

            // Message delivery preferences
            val DEFAULT_DELIVERY_METHOD = stringPreferencesKey("default_delivery_method")
            val TRY_PROPAGATION_ON_FAIL = booleanPreferencesKey("try_propagation_on_fail")
            val MANUAL_PROPAGATION_NODE = stringPreferencesKey("manual_propagation_node")
            val LAST_PROPAGATION_NODE = stringPreferencesKey("last_propagation_node")
            val AUTO_SELECT_PROPAGATION_NODE = booleanPreferencesKey("auto_select_propagation_node")

            // Message retrieval preferences
            val AUTO_RETRIEVE_ENABLED = booleanPreferencesKey("auto_retrieve_enabled")
            val RETRIEVAL_INTERVAL_SECONDS = intPreferencesKey("retrieval_interval_seconds")
            val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")

            // Transport node preferences
            val TRANSPORT_NODE_ENABLED = booleanPreferencesKey("transport_node_enabled")

            // Location sharing preferences
            val LOCATION_SHARING_ENABLED = booleanPreferencesKey("location_sharing_enabled")
            val DEFAULT_SHARING_DURATION = stringPreferencesKey("default_sharing_duration")
            val LOCATION_PRECISION_RADIUS = intPreferencesKey("location_precision_radius")

            // Incoming message size limit
            val INCOMING_MESSAGE_SIZE_LIMIT_KB = intPreferencesKey("incoming_message_size_limit_kb")

            // Image compression preferences
            val IMAGE_COMPRESSION_PRESET = stringPreferencesKey("image_compression_preset")

            // Map source preferences
            val MAP_SOURCE_HTTP_ENABLED = booleanPreferencesKey("map_source_http_enabled")
            val MAP_SOURCE_RMSP_ENABLED = booleanPreferencesKey("map_source_rmsp_enabled")

            // Privacy preferences
            val BLOCK_UNKNOWN_SENDERS = booleanPreferencesKey("block_unknown_senders")

            // Telemetry collector preferences
            val TELEMETRY_COLLECTOR_ADDRESS = stringPreferencesKey("telemetry_collector_address")
            val TELEMETRY_COLLECTOR_ENABLED = booleanPreferencesKey("telemetry_collector_enabled")
            val TELEMETRY_SEND_INTERVAL_SECONDS = intPreferencesKey("telemetry_send_interval_seconds")
            val TELEMETRY_REQUEST_ENABLED = booleanPreferencesKey("telemetry_request_enabled")
            val TELEMETRY_REQUEST_INTERVAL_SECONDS = intPreferencesKey("telemetry_request_interval_seconds")
            val LAST_TELEMETRY_SEND_TIME = longPreferencesKey("last_telemetry_send_time")
            val LAST_TELEMETRY_REQUEST_TIME = longPreferencesKey("last_telemetry_request_time")
        }

        // Cross-process SharedPreferences for service communication
        // DataStore does NOT support multi-process access, so we use SharedPreferences
        // with MODE_MULTI_PROCESS for values written by the service process.
        @Suppress("DEPRECATION") // MODE_MULTI_PROCESS is deprecated but necessary for cross-process
        private fun getCrossProcessPrefs(): SharedPreferences =
            context.getSharedPreferences(
                ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            )

        /**
         * Creates a Flow that observes a Long value from cross-process SharedPreferences.
         * Uses OnSharedPreferenceChangeListener to emit updates reactively.
         */
        private fun crossProcessLongFlow(key: String): Flow<Long?> =
            callbackFlow {
                val prefs = getCrossProcessPrefs()

                // Emit initial value
                val initialValue = prefs.getLong(key, -1L).takeIf { it != -1L }
                trySend(initialValue)

                // Listen for changes
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, changedKey ->
                        if (changedKey == key) {
                            val newValue = sharedPrefs.getLong(key, -1L).takeIf { it != -1L }
                            trySend(newValue)
                        }
                    }
                prefs.registerOnSharedPreferenceChangeListener(listener)

                awaitClose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }.distinctUntilChanged()

        // Notification preferences

        /**
         * Flow of the master notifications enabled state.
         * Defaults to true if not set.
         */
        val notificationsEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] ?: true
                }
                .distinctUntilChanged()

        /**
         * Save the master notifications enabled state.
         *
         * @param enabled Whether notifications are enabled
         */
        suspend fun saveNotificationsEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] = enabled
            }
        }

        /**
         * Flow of received message notification preference.
         * Defaults to true if not set.
         */
        val notificationReceivedMessageFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NOTIFICATION_RECEIVED_MESSAGE] ?: true
                }
                .distinctUntilChanged()

        /**
         * Save the received message notification preference.
         *
         * @param enabled Whether to show received message notifications
         */
        suspend fun saveNotificationReceivedMessage(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.NOTIFICATION_RECEIVED_MESSAGE] = enabled
            }
        }

        /**
         * Flow of received message from favorite notification preference.
         * Defaults to true if not set.
         */
        val notificationReceivedMessageFavoriteFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NOTIFICATION_RECEIVED_MESSAGE_FAVORITE] ?: true
                }
                .distinctUntilChanged()

        /**
         * Save the received message from favorite notification preference.
         *
         * @param enabled Whether to show received message from favorite notifications
         */
        suspend fun saveNotificationReceivedMessageFavorite(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.NOTIFICATION_RECEIVED_MESSAGE_FAVORITE] = enabled
            }
        }

        /**
         * Flow of heard announce notification preference.
         * Defaults to false if not set.
         */
        val notificationHeardAnnounceFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NOTIFICATION_HEARD_ANNOUNCE] ?: false
                }
                .distinctUntilChanged()

        /**
         * Save the heard announce notification preference.
         *
         * @param enabled Whether to show heard announce notifications
         */
        suspend fun saveNotificationHeardAnnounce(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.NOTIFICATION_HEARD_ANNOUNCE] = enabled
            }
        }

        /**
         * Flow of BLE connected notification preference.
         * Defaults to false if not set.
         */
        val notificationBleConnectedFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NOTIFICATION_BLE_CONNECTED] ?: false
                }
                .distinctUntilChanged()

        /**
         * Save the BLE connected notification preference.
         *
         * @param enabled Whether to show BLE connected notifications
         */
        suspend fun saveNotificationBleConnected(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.NOTIFICATION_BLE_CONNECTED] = enabled
            }
        }

        /**
         * Flow of BLE disconnected notification preference.
         * Defaults to false if not set.
         */
        val notificationBleDisconnectedFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NOTIFICATION_BLE_DISCONNECTED] ?: false
                }
                .distinctUntilChanged()

        /**
         * Save the BLE disconnected notification preference.
         *
         * @param enabled Whether to show BLE disconnected notifications
         */
        suspend fun saveNotificationBleDisconnected(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.NOTIFICATION_BLE_DISCONNECTED] = enabled
            }
        }

        /**
         * Flow tracking whether we've already requested notification permission.
         * Defaults to false if not set.
         */
        val hasRequestedNotificationPermissionFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.HAS_REQUESTED_NOTIFICATION_PERMISSION] ?: false
                }
                .distinctUntilChanged()

        /**
         * Mark that we've requested notification permission from the user.
         * This prevents us from asking again on subsequent app launches.
         */
        suspend fun markNotificationPermissionRequested() {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.HAS_REQUESTED_NOTIFICATION_PERMISSION] = true
            }
        }

        /**
         * Flow tracking whether the user has dismissed the location permission bottom sheet.
         * Defaults to false if not set.
         * Resets on app cold start to show the sheet again in a new session.
         */
        val hasDismissedLocationPermissionSheetFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.HAS_DISMISSED_LOCATION_PERMISSION_SHEET] ?: false
                }
                .distinctUntilChanged()

        /**
         * Mark that the user has dismissed the location permission bottom sheet.
         * This prevents showing it again during the current app session.
         */
        suspend fun markLocationPermissionSheetDismissed() {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.HAS_DISMISSED_LOCATION_PERMISSION_SHEET] = true
            }
        }

        /**
         * Reset the location permission sheet dismissal state.
         * Called on app cold start to show the sheet again in a new session.
         */
        suspend fun resetLocationPermissionSheetDismissal() {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.HAS_DISMISSED_LOCATION_PERMISSION_SHEET] = false
            }
        }

        // Onboarding preferences

        /**
         * Flow tracking whether the user has completed initial onboarding.
         * Defaults to false for fresh installs.
         */
        val hasCompletedOnboardingFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] ?: false
                }
                .distinctUntilChanged()

        /**
         * Mark that the user has completed onboarding.
         * This prevents showing the welcome screen on subsequent launches.
         */
        suspend fun markOnboardingCompleted() {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] = true
            }
        }

        // Auto-announce preferences

        /**
         * Flow of the auto-announce enabled state.
         * Defaults to true if not set.
         */
        val autoAnnounceEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.AUTO_ANNOUNCE_ENABLED] ?: true
                }
                .distinctUntilChanged()

        /**
         * Save the auto-announce enabled state.
         *
         * @param enabled Whether auto-announce is enabled
         */
        suspend fun saveAutoAnnounceEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.AUTO_ANNOUNCE_ENABLED] = enabled
            }
        }

        /**
         * Flow of the auto-announce interval in hours.
         * Defaults to 3 hours if not set.
         */
        val autoAnnounceIntervalHoursFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences -> preferences[PreferencesKeys.AUTO_ANNOUNCE_INTERVAL_HOURS] ?: 3 }
                .distinctUntilChanged()

        /**
         * Save the auto-announce interval in hours.
         *
         * @param hours The interval in hours (1-12)
         */
        suspend fun saveAutoAnnounceIntervalHours(hours: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.AUTO_ANNOUNCE_INTERVAL_HOURS] = hours.coerceIn(1, 12)
            }
        }

        /**
         * Flow of the last auto-announce timestamp (epoch milliseconds).
         * Returns null if no announce has been sent yet.
         *
         * Uses SharedPreferences for cross-process communication since both
         * the service and main app can trigger announces.
         */
        val lastAutoAnnounceTimeFlow: Flow<Long?> =
            crossProcessLongFlow(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME)

        /**
         * Save the last auto-announce timestamp.
         * Writes to SharedPreferences for cross-process visibility.
         *
         * @param timestamp The timestamp in epoch milliseconds
         */
        @Suppress("RedundantSuspendModifier") // Keep suspend for API compatibility
        suspend fun saveLastAutoAnnounceTime(timestamp: Long) {
            getCrossProcessPrefs().edit()
                .putLong(ServiceSettingsAccessor.KEY_LAST_AUTO_ANNOUNCE_TIME, timestamp)
                .apply()
        }

        /**
         * Flow of the next scheduled auto-announce timestamp (epoch milliseconds).
         * Returns null if no announce is scheduled (e.g., auto-announce is disabled).
         */
        val nextAutoAnnounceTimeFlow: Flow<Long?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.NEXT_AUTO_ANNOUNCE_TIME]
                }
                .distinctUntilChanged()

        /**
         * Save the next scheduled auto-announce timestamp.
         *
         * @param timestamp The timestamp in epoch milliseconds when the next announce is scheduled
         */
        suspend fun saveNextAutoAnnounceTime(timestamp: Long?) {
            context.dataStore.edit { preferences ->
                if (timestamp != null) {
                    preferences[PreferencesKeys.NEXT_AUTO_ANNOUNCE_TIME] = timestamp
                } else {
                    preferences.remove(PreferencesKeys.NEXT_AUTO_ANNOUNCE_TIME)
                }
            }
        }

        /**
         * Flow of the network change announce timestamp (epoch milliseconds).
         * Used for cross-process signaling: when the service triggers an announce
         * due to network topology change, it saves this timestamp, and the main app's
         * AutoAnnounceManager observes this to reset its timer.
         *
         * Uses SharedPreferences for cross-process communication.
         */
        val networkChangeAnnounceTimeFlow: Flow<Long?> =
            crossProcessLongFlow(ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME)

        /**
         * Save the network change announce timestamp.
         * Writes to SharedPreferences for cross-process visibility.
         *
         * @param timestamp The timestamp in epoch milliseconds
         */
        @Suppress("RedundantSuspendModifier") // Keep suspend for API compatibility
        suspend fun saveNetworkChangeAnnounceTime(timestamp: Long) {
            getCrossProcessPrefs().edit()
                .putLong(ServiceSettingsAccessor.KEY_NETWORK_CHANGE_ANNOUNCE_TIME, timestamp)
                .apply()
        }

        // Service status persistence

        /**
         * Flow of the last known service status.
         * Returns "UNKNOWN" if no status has been saved yet.
         */
        val lastServiceStatusFlow: Flow<String> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LAST_SERVICE_STATUS] ?: "UNKNOWN"
                }
                .distinctUntilChanged()

        /**
         * Save the last known service status for persistence across app restarts.
         *
         * @param status The service status string ("READY", "INITIALIZING", "SHUTDOWN", "ERROR:...")
         */
        suspend fun saveServiceStatus(status: String) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LAST_SERVICE_STATUS] = status
            }
        }

        // Theme preference

        /**
         * Flow of the selected app theme.
         * Defaults to VIBRANT if not set or if the stored value is invalid.
         * Loads custom themes from database when needed.
         */
        val themePreferenceFlow: Flow<AppTheme> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.THEME_PREFERENCE]
                }
                .flatMapLatest { themeIdentifier ->
                    if (themeIdentifier == null) {
                        flowOf(PresetTheme.VIBRANT)
                    } else {
                        parseThemeIdentifierFlow(themeIdentifier)
                    }
                }

        /**
         * Parse a theme identifier string back to an AppTheme Flow.
         * Format: "preset:VIBRANT" or "custom:123"
         * For custom themes, uses Flow to reactively load from database.
         */
        private fun parseThemeIdentifierFlow(identifier: String): Flow<AppTheme> {
            return when {
                identifier.startsWith("preset:") -> {
                    val presetName = identifier.substringAfter("preset:")
                    val theme =
                        try {
                            PresetTheme.valueOf(presetName)
                        } catch (e: IllegalArgumentException) {
                            PresetTheme.VIBRANT
                        }
                    flowOf(theme)
                }
                identifier.startsWith("custom:") -> {
                    // Load custom theme from database reactively
                    val themeId = identifier.substringAfter("custom:").toLongOrNull()
                    if (themeId != null) {
                        // Use Flow instead of suspend function for reactivity
                        customThemeRepository.getThemeByIdFlow(themeId)
                            .map { themeData ->
                                themeData?.let { customThemeDataToAppTheme(it) }
                                    ?: PresetTheme.VIBRANT
                            }
                    } else {
                        flowOf(PresetTheme.VIBRANT)
                    }
                }
                else -> {
                    // Legacy format (just theme name) - try to parse as preset
                    val theme =
                        try {
                            PresetTheme.valueOf(identifier)
                        } catch (e: IllegalArgumentException) {
                            PresetTheme.VIBRANT
                        }
                    flowOf(theme)
                }
            }
        }

        /**
         * Save the selected app theme.
         *
         * @param theme The theme to apply
         */
        suspend fun saveThemePreference(theme: AppTheme) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.THEME_PREFERENCE] = theme.getIdentifier()
            }
        }

        /**
         * Save theme preference by identifier string directly.
         * Used during migration import when the full AppTheme object isn't available.
         *
         * @param identifier The theme identifier (e.g., "preset:VIBRANT" or "custom:123")
         */
        suspend fun saveThemePreferenceByIdentifier(identifier: String) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.THEME_PREFERENCE] = identifier
            }
        }

        // Shared instance preference

        /**
         * Flow of the prefer own instance setting.
         * When true, Columba will create its own RNS instance even if a shared one is available.
         * Defaults to false (prefer shared instance if available).
         */
        val preferOwnInstanceFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.PREFER_OWN_INSTANCE] ?: false
                }
                .distinctUntilChanged()

        /**
         * Save the prefer own instance setting.
         *
         * @param preferOwn Whether to prefer Columba's own instance over a shared one
         */
        suspend fun savePreferOwnInstance(preferOwn: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.PREFER_OWN_INSTANCE] = preferOwn
            }
        }

        /**
         * Flow of whether Columba is currently connected to a shared RNS instance.
         * Set by the service when it initializes/connects.
         * Defaults to false.
         */
        val isSharedInstanceFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.IS_SHARED_INSTANCE] ?: false
                }
                .distinctUntilChanged()

        /**
         * Save the current shared instance status.
         * Called by the service when the RNS instance type is determined.
         *
         * @param isShared Whether currently connected to a shared instance
         */
        suspend fun saveIsSharedInstance(isShared: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.IS_SHARED_INSTANCE] = isShared
            }
        }

        /**
         * Flow of RPC authentication key for shared instance communication.
         * Required on Android when connecting to another app's shared instance (e.g., Sideband)
         * because apps have separate config directories with different RPC keys.
         * Export from Sideband: Connectivity → Share Instance Access
         */
        val rpcKeyFlow: Flow<String?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.RPC_KEY]
                }
                .distinctUntilChanged()

        /**
         * Save the RPC key for shared instance authentication.
         *
         * @param rpcKey Hexadecimal RPC key string, or null to clear
         */
        suspend fun saveRpcKey(rpcKey: String?) {
            context.dataStore.edit { preferences ->
                if (rpcKey != null) {
                    preferences[PreferencesKeys.RPC_KEY] = rpcKey
                } else {
                    preferences.remove(PreferencesKeys.RPC_KEY)
                }
            }
        }

        // Message delivery preferences

        /**
         * Flow of the default delivery method.
         * Defaults to "direct" if not set.
         * Values: "direct", "propagated"
         */
        val defaultDeliveryMethodFlow: Flow<String> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.DEFAULT_DELIVERY_METHOD] ?: "direct"
                }
                .distinctUntilChanged()

        /**
         * Get the default delivery method (non-flow for use in sending).
         */
        suspend fun getDefaultDeliveryMethod(): String {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.DEFAULT_DELIVERY_METHOD] ?: "direct"
            }.first()
        }

        /**
         * Save the default delivery method.
         *
         * @param method "direct" or "propagated"
         */
        suspend fun saveDefaultDeliveryMethod(method: String) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.DEFAULT_DELIVERY_METHOD] = method
            }
        }

        /**
         * Flow of the try propagation on fail setting.
         * Defaults to true if not set.
         */
        val tryPropagationOnFailFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TRY_PROPAGATION_ON_FAIL] ?: true
                }
                .distinctUntilChanged()

        /**
         * Get try propagation on fail setting (non-flow for use in sending).
         */
        suspend fun getTryPropagationOnFail(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TRY_PROPAGATION_ON_FAIL] ?: true
            }.first()
        }

        /**
         * Save the try propagation on fail setting.
         *
         * @param enabled Whether to retry via propagation when direct delivery fails
         */
        suspend fun saveTryPropagationOnFail(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.TRY_PROPAGATION_ON_FAIL] = enabled
            }
        }

        /**
         * Flow of the manually selected propagation node.
         * Returns hex destination hash string or null if not set.
         */
        val manualPropagationNodeFlow: Flow<String?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.MANUAL_PROPAGATION_NODE]
                }
                .distinctUntilChanged()

        /**
         * Get the manually selected propagation node (non-flow).
         */
        suspend fun getManualPropagationNode(): String? {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.MANUAL_PROPAGATION_NODE]
            }.first()
        }

        /**
         * Save the manually selected propagation node.
         *
         * @param nodeHash Hex destination hash of the propagation node, or null to clear
         */
        suspend fun saveManualPropagationNode(nodeHash: String?) {
            context.dataStore.edit { preferences ->
                if (nodeHash != null) {
                    preferences[PreferencesKeys.MANUAL_PROPAGATION_NODE] = nodeHash
                } else {
                    preferences.remove(PreferencesKeys.MANUAL_PROPAGATION_NODE)
                }
            }
        }

        /**
         * Flow of the last used propagation node (fallback for auto-selection).
         * Returns hex destination hash string or null if not set.
         */
        val lastPropagationNodeFlow: Flow<String?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LAST_PROPAGATION_NODE]
                }
                .distinctUntilChanged()

        /**
         * Get the last used propagation node (non-flow).
         */
        suspend fun getLastPropagationNode(): String? {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_PROPAGATION_NODE]
            }.first()
        }

        /**
         * Save the last used propagation node.
         *
         * @param nodeHash Hex destination hash of the propagation node
         */
        suspend fun saveLastPropagationNode(nodeHash: String) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LAST_PROPAGATION_NODE] = nodeHash
            }
        }

        /**
         * Flow of the auto-select propagation node setting.
         * Defaults to true if not set.
         */
        val autoSelectPropagationNodeFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.AUTO_SELECT_PROPAGATION_NODE] ?: true
                }
                .distinctUntilChanged()

        /**
         * Get the auto-select propagation node setting (non-flow).
         */
        suspend fun getAutoSelectPropagationNode(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.AUTO_SELECT_PROPAGATION_NODE] ?: true
            }.first()
        }

        /**
         * Save the auto-select propagation node setting.
         *
         * @param enabled Whether to automatically select the nearest propagation node
         */
        suspend fun saveAutoSelectPropagationNode(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.AUTO_SELECT_PROPAGATION_NODE] = enabled
            }
        }

        // Message retrieval preferences

        /**
         * Flow of the auto-retrieve enabled setting.
         * When enabled, periodically syncs with the propagation node.
         * Defaults to true if not set.
         */
        val autoRetrieveEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.AUTO_RETRIEVE_ENABLED] ?: true
                }
                .distinctUntilChanged()

        /**
         * Get the auto-retrieve enabled setting (non-flow).
         */
        suspend fun getAutoRetrieveEnabled(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.AUTO_RETRIEVE_ENABLED] ?: true
            }.first()
        }

        /**
         * Save the auto-retrieve enabled setting.
         *
         * @param enabled Whether to automatically retrieve messages from the propagation node
         */
        suspend fun saveAutoRetrieveEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.AUTO_RETRIEVE_ENABLED] = enabled
            }
        }

        /**
         * Flow of the retrieval interval in seconds.
         * Defaults to 1 hour (3600 seconds) if not set.
         * Uses distinctUntilChanged to only emit when the interval actually changes,
         * not when other DataStore values change (which would restart sync unnecessarily).
         */
        val retrievalIntervalSecondsFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.RETRIEVAL_INTERVAL_SECONDS] ?: 3600
                }
                .distinctUntilChanged()

        /**
         * Get the retrieval interval in seconds (non-flow).
         */
        suspend fun getRetrievalIntervalSeconds(): Int {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.RETRIEVAL_INTERVAL_SECONDS] ?: 3600
            }.first()
        }

        /**
         * Save the retrieval interval in seconds.
         *
         * @param seconds The interval in seconds (300, 600, 1800, or 3600)
         */
        suspend fun saveRetrievalIntervalSeconds(seconds: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.RETRIEVAL_INTERVAL_SECONDS] = seconds
            }
        }

        /**
         * Flow of the last sync timestamp (epoch milliseconds).
         * Returns null if no sync has occurred yet.
         */
        val lastSyncTimestampFlow: Flow<Long?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP]
                }
                .distinctUntilChanged()

        /**
         * Get the last sync timestamp (non-flow).
         */
        suspend fun getLastSyncTimestamp(): Long? {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP]
            }.first()
        }

        /**
         * Save the last sync timestamp.
         *
         * @param timestamp The timestamp in epoch milliseconds
         */
        suspend fun saveLastSyncTimestamp(timestamp: Long) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = timestamp
            }
        }

        // Transport node preferences

        /**
         * Flow of the transport node enabled setting.
         * When enabled (default), this device forwards traffic for the mesh network.
         * When disabled, only handles its own traffic.
         * Defaults to true if not set.
         */
        val transportNodeEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TRANSPORT_NODE_ENABLED] ?: true
                }
                .distinctUntilChanged()

        /**
         * Get the transport node enabled setting (non-flow).
         */
        suspend fun getTransportNodeEnabled(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TRANSPORT_NODE_ENABLED] ?: true
            }.first()
        }

        /**
         * Save the transport node enabled setting.
         *
         * @param enabled Whether transport node is enabled
         */
        suspend fun saveTransportNodeEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.TRANSPORT_NODE_ENABLED] = enabled
            }
        }

        // Location sharing preferences

        /**
         * Flow of the location sharing enabled setting.
         * When disabled, no location sharing is allowed and all active sessions should be stopped.
         * Defaults to false if not set.
         */
        val locationSharingEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LOCATION_SHARING_ENABLED] ?: false
                }
                .distinctUntilChanged()

        /**
         * Save the location sharing enabled setting.
         *
         * @param enabled Whether location sharing is enabled
         */
        suspend fun saveLocationSharingEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LOCATION_SHARING_ENABLED] = enabled
            }
        }

        /**
         * Flow of the default sharing duration.
         * Stores the SharingDuration enum name (e.g., "ONE_HOUR", "FOUR_HOURS").
         * Defaults to "ONE_HOUR" if not set.
         */
        val defaultSharingDurationFlow: Flow<String> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.DEFAULT_SHARING_DURATION] ?: "ONE_HOUR"
                }
                .distinctUntilChanged()

        /**
         * Save the default sharing duration.
         *
         * @param duration The SharingDuration enum name (e.g., "ONE_HOUR", "FOUR_HOURS")
         */
        suspend fun saveDefaultSharingDuration(duration: String) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.DEFAULT_SHARING_DURATION] = duration
            }
        }

        /**
         * Flow of the location precision radius in meters.
         * 0 = Precise (no coarsening), >0 = coarsening radius in meters.
         * Defaults to 0 (precise) if not set.
         */
        val locationPrecisionRadiusFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LOCATION_PRECISION_RADIUS] ?: 0
                }
                .distinctUntilChanged()

        /**
         * Save the location precision radius.
         *
         * @param radiusMeters 0 for precise, or coarsening radius in meters (100, 1000, 10000, etc.)
         */
        suspend fun saveLocationPrecisionRadius(radiusMeters: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LOCATION_PRECISION_RADIUS] = radiusMeters
            }
        }

        // Incoming message size limit

        /**
         * Flow of the incoming message size limit in KB.
         * Controls the maximum size of messages that can be received.
         * Defaults to 1024 KB (1MB) if not set.
         */
        val incomingMessageSizeLimitKbFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.INCOMING_MESSAGE_SIZE_LIMIT_KB] ?: DEFAULT_INCOMING_SIZE_LIMIT_KB
                }
                .distinctUntilChanged()

        // Image compression preferences

        /**
         * Flow of the image compression preset.
         * Defaults to AUTO if not set.
         */
        val imageCompressionPresetFlow: Flow<ImageCompressionPreset> =
            context.dataStore.data
                .map { preferences ->
                    val presetName = preferences[PreferencesKeys.IMAGE_COMPRESSION_PRESET]
                    if (presetName != null) {
                        ImageCompressionPreset.fromName(presetName)
                    } else {
                        ImageCompressionPreset.DEFAULT
                    }
                }
                .distinctUntilChanged()

        // Map source preferences

        /**
         * Flow of the HTTP map source enabled setting.
         * When enabled (default), tiles are fetched from OpenFreeMap via HTTP.
         * Defaults to true if not set.
         */
        val mapSourceHttpEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.MAP_SOURCE_HTTP_ENABLED] ?: true
                }
                .distinctUntilChanged()

        /**
         * Get the incoming message size limit in KB (non-flow).
         */
        suspend fun getIncomingMessageSizeLimitKb(): Int =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.INCOMING_MESSAGE_SIZE_LIMIT_KB] ?: DEFAULT_INCOMING_SIZE_LIMIT_KB
                }
                .first()

        /**
         * Save the incoming message size limit in KB.
         *
         * @param limitKb Size limit in KB (512 to 131072, representing 0.5MB to 128MB)
         */
        suspend fun saveIncomingMessageSizeLimitKb(limitKb: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.INCOMING_MESSAGE_SIZE_LIMIT_KB] =
                    limitKb.coerceIn(MIN_INCOMING_SIZE_LIMIT_KB, MAX_INCOMING_SIZE_LIMIT_KB)
            }
        }

        /**
         * Get the image compression preset (non-flow).
         */
        suspend fun getImageCompressionPreset(): ImageCompressionPreset {
            return context.dataStore.data.map { preferences ->
                val presetName = preferences[PreferencesKeys.IMAGE_COMPRESSION_PRESET]
                if (presetName != null) {
                    ImageCompressionPreset.fromName(presetName)
                } else {
                    ImageCompressionPreset.DEFAULT
                }
            }.first()
        }

        /**
         * Save the image compression preset.
         *
         * @param preset The compression preset to save
         */
        suspend fun saveImageCompressionPreset(preset: ImageCompressionPreset) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.IMAGE_COMPRESSION_PRESET] = preset.name
            }
        }

        /**
         * Get the HTTP map source enabled setting (non-flow).
         */
        suspend fun getMapSourceHttpEnabled(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.MAP_SOURCE_HTTP_ENABLED] ?: true
            }.first()
        }

        // Telemetry collector preferences

        /**
         * Flow of the telemetry collector address (32-char hex destination hash).
         * Returns null if not set.
         */
        val telemetryCollectorAddressFlow: Flow<String?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TELEMETRY_COLLECTOR_ADDRESS]
                }
                .distinctUntilChanged()

        /**
         * Get the telemetry collector address (non-flow).
         */
        suspend fun getTelemetryCollectorAddress(): String? {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TELEMETRY_COLLECTOR_ADDRESS]
            }.first()
        }

        /**
         * Save the telemetry collector address.
         *
         * @param address The 32-character hex destination hash, or null to clear
         */
        suspend fun saveTelemetryCollectorAddress(address: String?) {
            context.dataStore.edit { preferences ->
                if (address != null) {
                    preferences[PreferencesKeys.TELEMETRY_COLLECTOR_ADDRESS] = address
                } else {
                    preferences.remove(PreferencesKeys.TELEMETRY_COLLECTOR_ADDRESS)
                }
            }
        }

        /**
         * Flow of the telemetry collector enabled state.
         * Defaults to false if not set.
         */
        val telemetryCollectorEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TELEMETRY_COLLECTOR_ENABLED] ?: false
                }
                .distinctUntilChanged()

        /**
         * Get the telemetry collector enabled state (non-flow).
         */
        suspend fun getTelemetryCollectorEnabled(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TELEMETRY_COLLECTOR_ENABLED] ?: false
            }.first()
        }

        /**
         * Save the telemetry collector enabled state.
         *
         * @param enabled Whether automatic telemetry sending to collector is enabled
         */
        suspend fun saveTelemetryCollectorEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.TELEMETRY_COLLECTOR_ENABLED] = enabled
            }
        }

        /**
         * Flow of the telemetry send interval in seconds.
         * Defaults to 300 (5 minutes) if not set.
         */
        val telemetrySendIntervalSecondsFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TELEMETRY_SEND_INTERVAL_SECONDS] ?: DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS
                }
                .distinctUntilChanged()

        /**
         * Get the telemetry send interval in seconds (non-flow).
         */
        suspend fun getTelemetrySendIntervalSeconds(): Int {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TELEMETRY_SEND_INTERVAL_SECONDS] ?: DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS
            }.first()
        }

        /**
         * Save the telemetry send interval in seconds.
         *
         * @param seconds The interval in seconds (minimum 60, maximum 86400)
         */
        suspend fun saveTelemetrySendIntervalSeconds(seconds: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.TELEMETRY_SEND_INTERVAL_SECONDS] = seconds.coerceIn(60, 86400)
            }
        }

        /**
         * Flow of the telemetry request enabled state.
         * Defaults to false if not set.
         */
        val telemetryRequestEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TELEMETRY_REQUEST_ENABLED] ?: false
                }
                .distinctUntilChanged()

        /**
         * Get the telemetry request enabled state (non-flow).
         */
        suspend fun getTelemetryRequestEnabled(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TELEMETRY_REQUEST_ENABLED] ?: false
            }.first()
        }

        /**
         * Save the telemetry request enabled state.
         *
         * @param enabled Whether automatic telemetry requesting from collector is enabled
         */
        suspend fun saveTelemetryRequestEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.TELEMETRY_REQUEST_ENABLED] = enabled
            }
        }

        /**
         * Flow of the telemetry request interval in seconds.
         * Defaults to 900 (15 minutes) if not set.
         */
        val telemetryRequestIntervalSecondsFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.TELEMETRY_REQUEST_INTERVAL_SECONDS] ?: DEFAULT_TELEMETRY_REQUEST_INTERVAL_SECONDS
                }
                .distinctUntilChanged()

        /**
         * Get the telemetry request interval in seconds (non-flow).
         */
        suspend fun getTelemetryRequestIntervalSeconds(): Int {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.TELEMETRY_REQUEST_INTERVAL_SECONDS] ?: DEFAULT_TELEMETRY_REQUEST_INTERVAL_SECONDS
            }.first()
        }

        /**
         * Save the telemetry request interval in seconds.
         *
         * @param seconds The interval in seconds (minimum 60, maximum 86400)
         */
        suspend fun saveTelemetryRequestIntervalSeconds(seconds: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.TELEMETRY_REQUEST_INTERVAL_SECONDS] = seconds.coerceIn(60, 86400)
            }
        }

        /**
         * Flow of the last telemetry send timestamp (epoch milliseconds).
         * Returns null if no telemetry has been sent yet.
         */
        val lastTelemetrySendTimeFlow: Flow<Long?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LAST_TELEMETRY_SEND_TIME]
                }
                .distinctUntilChanged()

        /**
         * Get the last telemetry send timestamp (non-flow).
         */
        suspend fun getLastTelemetrySendTime(): Long? {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_TELEMETRY_SEND_TIME]
            }.first()
        }

        /**
         * Save the last telemetry send timestamp.
         *
         * @param timestamp The timestamp in epoch milliseconds
         */
        suspend fun saveLastTelemetrySendTime(timestamp: Long) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LAST_TELEMETRY_SEND_TIME] = timestamp
            }
        }

        /**
         * Flow of the last telemetry request timestamp (epoch milliseconds).
         * Returns null if no request has been made yet.
         */
        val lastTelemetryRequestTimeFlow: Flow<Long?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LAST_TELEMETRY_REQUEST_TIME]
                }
                .distinctUntilChanged()

        /**
         * Get the last telemetry request timestamp (non-flow).
         */
        suspend fun getLastTelemetryRequestTime(): Long? {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.LAST_TELEMETRY_REQUEST_TIME]
            }.first()
        }

        /**
         * Save the last telemetry request timestamp.
         *
         * @param timestamp The timestamp in epoch milliseconds
         */
        suspend fun saveLastTelemetryRequestTime(timestamp: Long) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LAST_TELEMETRY_REQUEST_TIME] = timestamp
            }
        }

        companion object {
            /** Default incoming message size limit: 1MB */
            const val DEFAULT_INCOMING_SIZE_LIMIT_KB = 1024

            /** Minimum incoming message size limit: 512KB */
            const val MIN_INCOMING_SIZE_LIMIT_KB = 512

            /** Maximum incoming message size limit: 128MB (effectively unlimited) */
            const val MAX_INCOMING_SIZE_LIMIT_KB = 131072

            // Cross-process SharedPreferences keys (for settings read by the service)
            const val CROSS_PROCESS_PREFS_NAME = "cross_process_settings"
            const val KEY_BLOCK_UNKNOWN_SENDERS = "block_unknown_senders"

            /** Default telemetry send interval: 5 minutes */
            const val DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS = 300

            /** Default telemetry request interval: 15 minutes */
            const val DEFAULT_TELEMETRY_REQUEST_INTERVAL_SECONDS = 900
        }

        // Export/Import methods for migration

        /**
         * Export all preferences from DataStore for backup/migration.
         * Returns a list of preference entries that can be serialized.
         */
        suspend fun exportAllPreferences(): List<com.lxmf.messenger.migration.PreferenceEntry> {
            val preferences = context.dataStore.data.first()
            val entries = mutableListOf<com.lxmf.messenger.migration.PreferenceEntry>()

            preferences.asMap().forEach { (key, value) ->
                val (type, stringValue) =
                    when (value) {
                        is Boolean -> "boolean" to value.toString()
                        is Int -> "int" to value.toString()
                        is Long -> "long" to value.toString()
                        is Float -> "float" to value.toString()
                        is String -> "string" to value
                        is Set<*> -> "string_set" to (value as Set<String>).joinToString("\u001F") // Unit separator
                        else -> return@forEach // Skip unknown types
                    }

                entries.add(
                    com.lxmf.messenger.migration.PreferenceEntry(
                        key = key.name,
                        type = type,
                        value = stringValue,
                    ),
                )
            }

            return entries
        }

        /**
         * Import preferences from a list of preference entries.
         * Unknown keys are safely ignored for forward/backward compatibility.
         */
        suspend fun importAllPreferences(entries: List<com.lxmf.messenger.migration.PreferenceEntry>) {
            context.dataStore.edit { prefs ->
                entries.forEach { entry ->
                    try {
                        when (entry.type) {
                            "boolean" -> {
                                val key = booleanPreferencesKey(entry.key)
                                prefs[key] = entry.value.toBoolean()
                            }
                            "int" -> {
                                val key = intPreferencesKey(entry.key)
                                prefs[key] = entry.value.toInt()
                            }
                            "long" -> {
                                val key = longPreferencesKey(entry.key)
                                prefs[key] = entry.value.toLong()
                            }
                            "float" -> {
                                val key = floatPreferencesKey(entry.key)
                                prefs[key] = entry.value.toFloat()
                            }
                            "string" -> {
                                val key = stringPreferencesKey(entry.key)
                                prefs[key] = entry.value
                            }
                            "string_set" -> {
                                val key = stringSetPreferencesKey(entry.key)
                                prefs[key] = entry.value.split("\u001F").toSet()
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore invalid entries - they may be from newer/older app versions
                        Log.w("SettingsRepository", "Skipping invalid preference: ${entry.key}", e)
                    }
                }
            }
        }

        /**
         * Save the HTTP map source enabled setting.
         *
         * @param enabled Whether HTTP map source is enabled
         */
        suspend fun saveMapSourceHttpEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.MAP_SOURCE_HTTP_ENABLED] = enabled
            }
        }

        /**
         * Flow of the RMSP map source enabled setting.
         * When enabled, tiles can be fetched from RMSP servers over Reticulum mesh.
         * Defaults to false if not set.
         */
        val mapSourceRmspEnabledFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.MAP_SOURCE_RMSP_ENABLED] ?: false
                }
                .distinctUntilChanged()

        /**
         * Get the RMSP map source enabled setting (non-flow).
         */
        suspend fun getMapSourceRmspEnabled(): Boolean {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.MAP_SOURCE_RMSP_ENABLED] ?: false
            }.first()
        }

        /**
         * Save the RMSP map source enabled setting.
         *
         * @param enabled Whether RMSP map source is enabled
         */
        suspend fun saveMapSourceRmspEnabled(enabled: Boolean) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.MAP_SOURCE_RMSP_ENABLED] = enabled
            }
        }

        // Privacy preferences

        /**
         * Flow of the block unknown senders setting.
         * When enabled, messages from senders not in the contacts list are silently discarded.
         * Defaults to false if not set (allow all messages - preserves existing behavior).
         */
        val blockUnknownSendersFlow: Flow<Boolean> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.BLOCK_UNKNOWN_SENDERS] ?: false
                }
                .distinctUntilChanged()

        /**
         * Get the block unknown senders setting (non-flow).
         */
        suspend fun getBlockUnknownSenders(): Boolean =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.BLOCK_UNKNOWN_SENDERS] ?: false
                }
                .first()

        /**
         * Save the block unknown senders setting.
         *
         * Also writes to SharedPreferences with MODE_MULTI_PROCESS so the service process
         * can read it (DataStore doesn't support reliable cross-process reads).
         *
         * @param enabled Whether to block messages from unknown senders
         */
        @Suppress("DEPRECATION") // MODE_MULTI_PROCESS needed for cross-process reads
        suspend fun saveBlockUnknownSenders(enabled: Boolean) {
            // Write to DataStore for local flow/UI
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.BLOCK_UNKNOWN_SENDERS] = enabled
            }
            // Write to SharedPreferences for cross-process access by the service
            context.getSharedPreferences(CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)
                .edit()
                .putBoolean(KEY_BLOCK_UNKNOWN_SENDERS, enabled)
                .apply()
        }

        // Custom theme methods (delegated to CustomThemeRepository)

        /**
         * Get all custom themes
         */
        fun getAllCustomThemes() = customThemeRepository.getAllThemes()

        /**
         * Get a custom theme by ID
         */
        suspend fun getCustomThemeById(id: Long) = customThemeRepository.getThemeById(id)

        /**
         * Convert a CustomThemeData to a CustomTheme (for use in UI)
         */
        fun customThemeDataToAppTheme(themeData: com.lxmf.messenger.data.repository.CustomThemeData): CustomTheme {
            // Convert ThemeColorSet to ColorScheme
            val lightScheme =
                with(themeData.lightColors) {
                    lightColorScheme(
                        primary = Color(primary),
                        onPrimary = Color(onPrimary),
                        primaryContainer = Color(primaryContainer),
                        onPrimaryContainer = Color(onPrimaryContainer),
                        secondary = Color(secondary),
                        onSecondary = Color(onSecondary),
                        secondaryContainer = Color(secondaryContainer),
                        onSecondaryContainer = Color(onSecondaryContainer),
                        tertiary = Color(tertiary),
                        onTertiary = Color(onTertiary),
                        tertiaryContainer = Color(tertiaryContainer),
                        onTertiaryContainer = Color(onTertiaryContainer),
                        error = Color(error),
                        onError = Color(onError),
                        errorContainer = Color(errorContainer),
                        onErrorContainer = Color(onErrorContainer),
                        background = Color(background),
                        onBackground = Color(onBackground),
                        surface = Color(surface),
                        onSurface = Color(onSurface),
                        surfaceVariant = Color(surfaceVariant),
                        onSurfaceVariant = Color(onSurfaceVariant),
                        outline = Color(outline),
                        outlineVariant = Color(outlineVariant),
                    )
                }

            val darkScheme =
                with(themeData.darkColors) {
                    darkColorScheme(
                        primary = Color(primary),
                        onPrimary = Color(onPrimary),
                        primaryContainer = Color(primaryContainer),
                        onPrimaryContainer = Color(onPrimaryContainer),
                        secondary = Color(secondary),
                        onSecondary = Color(onSecondary),
                        secondaryContainer = Color(secondaryContainer),
                        onSecondaryContainer = Color(onSecondaryContainer),
                        tertiary = Color(tertiary),
                        onTertiary = Color(onTertiary),
                        tertiaryContainer = Color(tertiaryContainer),
                        onTertiaryContainer = Color(onTertiaryContainer),
                        error = Color(error),
                        onError = Color(onError),
                        errorContainer = Color(errorContainer),
                        onErrorContainer = Color(onErrorContainer),
                        background = Color(background),
                        onBackground = Color(onBackground),
                        surface = Color(surface),
                        onSurface = Color(onSurface),
                        surfaceVariant = Color(surfaceVariant),
                        onSurfaceVariant = Color(onSurfaceVariant),
                        outline = Color(outline),
                        outlineVariant = Color(outlineVariant),
                    )
                }

            return CustomTheme(
                id = themeData.id,
                displayName = themeData.name,
                description = themeData.description,
                lightColorScheme = lightScheme,
                darkColorScheme = darkScheme,
                baseTheme = themeData.baseTheme?.let { PresetTheme.valueOf(it) },
            )
        }
    }
