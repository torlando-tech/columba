package com.lxmf.messenger.service.persistence

import android.content.Context

/**
 * Service-side accessor for settings that need cross-process communication.
 *
 * Uses SharedPreferences with MODE_MULTI_PROCESS for all cross-process communication.
 * DataStore does NOT support multi-process access and causes "multiple DataStores active"
 * errors when both the service and main app try to access the same file.
 *
 * Settings written here are read by the main app's SettingsRepository via SharedPreferences.
 */
@Suppress("DEPRECATION") // MODE_MULTI_PROCESS is deprecated but necessary for cross-process access
class ServiceSettingsAccessor(
    private val context: Context,
) {
    companion object {
        // SharedPreferences file for cross-process communication
        const val CROSS_PROCESS_PREFS_NAME = "cross_process_settings"

        // Keys for cross-process settings
        const val KEY_BLOCK_UNKNOWN_SENDERS = "block_unknown_senders"
        const val KEY_NETWORK_CHANGE_ANNOUNCE_TIME = "network_change_announce_time"
        const val KEY_LAST_AUTO_ANNOUNCE_TIME = "last_auto_announce_time"
    }

    // Get fresh SharedPreferences each time to avoid caching issues across processes
    private fun getCrossProcessPrefs() = context.getSharedPreferences(CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)

    /**
     * Save the network change announce timestamp.
     * Called when a network topology change triggers an announce, signaling the main app's
     * AutoAnnounceManager to reset its periodic timer.
     *
     * @param timestamp The timestamp in epoch milliseconds
     */
    fun saveNetworkChangeAnnounceTime(timestamp: Long) {
        getCrossProcessPrefs().edit()
            .putLong(KEY_NETWORK_CHANGE_ANNOUNCE_TIME, timestamp)
            .apply()
    }

    /**
     * Save the last auto-announce timestamp.
     * Called after a successful announce to update the shared timestamp.
     *
     * @param timestamp The timestamp in epoch milliseconds
     */
    fun saveLastAutoAnnounceTime(timestamp: Long) {
        getCrossProcessPrefs().edit()
            .putLong(KEY_LAST_AUTO_ANNOUNCE_TIME, timestamp)
            .apply()
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
    fun getBlockUnknownSenders(): Boolean = getCrossProcessPrefs().getBoolean(KEY_BLOCK_UNKNOWN_SENDERS, false)
}
