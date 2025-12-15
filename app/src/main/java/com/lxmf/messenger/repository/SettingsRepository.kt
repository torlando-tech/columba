package com.lxmf.messenger.repository

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxmf.messenger.data.repository.CustomThemeRepository
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.CustomTheme
import com.lxmf.messenger.ui.theme.PresetTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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

            // Onboarding preferences
            val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")

            // Auto-announce preferences
            val AUTO_ANNOUNCE_ENABLED = booleanPreferencesKey("auto_announce_enabled")
            val AUTO_ANNOUNCE_INTERVAL_MINUTES = intPreferencesKey("auto_announce_interval_minutes")
            val LAST_AUTO_ANNOUNCE_TIME = longPreferencesKey("last_auto_announce_time")

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
        }

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
         * Flow of the auto-announce interval in minutes.
         * Defaults to 5 minutes if not set.
         */
        val autoAnnounceIntervalMinutesFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.AUTO_ANNOUNCE_INTERVAL_MINUTES] ?: 5
                }
                .distinctUntilChanged()

        /**
         * Save the auto-announce interval in minutes.
         *
         * @param minutes The interval in minutes (1-60)
         */
        suspend fun saveAutoAnnounceIntervalMinutes(minutes: Int) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.AUTO_ANNOUNCE_INTERVAL_MINUTES] = minutes.coerceIn(1, 60)
            }
        }

        /**
         * Flow of the last auto-announce timestamp (epoch milliseconds).
         * Returns null if no announce has been sent yet.
         */
        val lastAutoAnnounceTimeFlow: Flow<Long?> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.LAST_AUTO_ANNOUNCE_TIME]
                }
                .distinctUntilChanged()

        /**
         * Save the last auto-announce timestamp.
         *
         * @param timestamp The timestamp in epoch milliseconds
         */
        suspend fun saveLastAutoAnnounceTime(timestamp: Long) {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.LAST_AUTO_ANNOUNCE_TIME] = timestamp
            }
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
         * Defaults to 30 seconds if not set.
         * Uses distinctUntilChanged to only emit when the interval actually changes,
         * not when other DataStore values change (which would restart sync unnecessarily).
         */
        val retrievalIntervalSecondsFlow: Flow<Int> =
            context.dataStore.data
                .map { preferences ->
                    preferences[PreferencesKeys.RETRIEVAL_INTERVAL_SECONDS] ?: 30
                }
                .distinctUntilChanged()

        /**
         * Get the retrieval interval in seconds (non-flow).
         */
        suspend fun getRetrievalIntervalSeconds(): Int {
            return context.dataStore.data.map { preferences ->
                preferences[PreferencesKeys.RETRIEVAL_INTERVAL_SECONDS] ?: 30
            }.first()
        }

        /**
         * Save the retrieval interval in seconds.
         *
         * @param seconds The interval in seconds (30, 60, 120, or 300)
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
