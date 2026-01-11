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
 */
class ServiceSettingsAccessor(
    private val context: Context,
) {
    companion object {
        private val NETWORK_CHANGE_ANNOUNCE_TIME = longPreferencesKey("network_change_announce_time")
        private val LAST_AUTO_ANNOUNCE_TIME = longPreferencesKey("last_auto_announce_time")
    }

    // Uses the same DataStore name as SettingsRepository for cross-process access
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
}
