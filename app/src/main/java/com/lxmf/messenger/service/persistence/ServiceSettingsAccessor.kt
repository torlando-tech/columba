package com.lxmf.messenger.service.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * Service-side accessor for settings that need cross-process communication.
 *
 * This provides a minimal interface for the service process to write to the same
 * DataStore that the main app process reads via SettingsRepository. Uses the same
 * DataStore name ("settings") to ensure both processes access the same preferences file.
 *
 * Only includes settings that need to be written from the service and read by the app.
 *
 * For settings that need to be READ by the service (written by app), we use SharedPreferences
 * with MODE_MULTI_PROCESS since DataStore doesn't support multi-process reads reliably.
 */
@Suppress("DEPRECATION") // MODE_MULTI_PROCESS is deprecated but necessary for cross-process reads
class ServiceSettingsAccessor(
    private val context: Context,
) {
    companion object {
        private val NETWORK_CHANGE_ANNOUNCE_TIME = longPreferencesKey("network_change_announce_time")
        private val LAST_AUTO_ANNOUNCE_TIME = longPreferencesKey("last_auto_announce_time")

        // SharedPreferences keys for cross-process readable settings
        const val CROSS_PROCESS_PREFS_NAME = "cross_process_settings"
        const val KEY_BLOCK_UNKNOWN_SENDERS = "block_unknown_senders"
    }

    // Uses the same DataStore name as SettingsRepository for cross-process access
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

    // Get fresh SharedPreferences each time to avoid caching issues across processes
    private fun getCrossProcessPrefs() =
        context.getSharedPreferences(CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)

    /**
     * Save the network change announce timestamp.
     * Called when a network topology change triggers an announce, signaling the main app's
     * AutoAnnounceManager to reset its periodic timer.
     *
     * @param timestamp The timestamp in epoch milliseconds
     */
    suspend fun saveNetworkChangeAnnounceTime(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[NETWORK_CHANGE_ANNOUNCE_TIME] = timestamp
        }
    }

    /**
     * Save the last auto-announce timestamp.
     * Called after a successful announce to update the shared timestamp.
     *
     * @param timestamp The timestamp in epoch milliseconds
     */
    suspend fun saveLastAutoAnnounceTime(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_AUTO_ANNOUNCE_TIME] = timestamp
        }
    }

    /**
     * Get the block unknown senders setting.
     * When enabled, messages from senders not in the contacts list should be discarded.
     *
     * Uses SharedPreferences with MODE_MULTI_PROCESS to read settings written by the app process.
     * Gets a fresh SharedPreferences instance each time to ensure we see cross-process updates.
     *
     * @return true if unknown senders should be blocked, false otherwise (default)
     */
    fun getBlockUnknownSenders(): Boolean =
        getCrossProcessPrefs().getBoolean(KEY_BLOCK_UNKNOWN_SENDERS, false)
}
