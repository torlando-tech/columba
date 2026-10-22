package network.columba.app.rns.host.persistence

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
        // SharedPreferences file for cross-process communication.
        //
        // WRITER RULE: the UI process is the only writer of this file (settings
        // dual-writes from SettingsRepository); the service process only reads
        // it. SharedPreferences flushes its whole in-memory map on every write,
        // and MODE_MULTI_PROCESS does not synchronize writers — a service-side
        // write here clobbers UI settings keys written after the service loaded
        // its snapshot (observed in the field: frequent last_network_status
        // writes erased persistent_wake_lock, silently forcing the persistent
        // lock on every service restart). Service-owned runtime state lives in
        // SERVICE_STATE_PREFS_NAME instead.
        const val CROSS_PROCESS_PREFS_NAME = "cross_process_settings"

        // Runtime state written by the `:reticulum` service and read by the UI
        // (announce timestamps, last network status). The UI's rare writes to
        // the two announce timestamps are last-write-wins timestamps — a lost
        // update only ages the "last announce" display by one cycle, never a
        // setting.
        const val SERVICE_STATE_PREFS_NAME = "columba_service_state"

        // Keys for cross-process settings
        const val KEY_BLOCK_UNKNOWN_SENDERS = "block_unknown_senders"
        const val KEY_ALLOW_CALLS_FROM_CONTACTS_ONLY = "allow_calls_from_contacts_only"
        const val KEY_ALLOW_VOICE_CALLS = "allow_voice_calls"
        const val KEY_PERSISTENT_WAKE_LOCK = "persistent_wake_lock"

        // Keys for service-owned runtime state (SERVICE_STATE_PREFS_NAME)
        const val KEY_NETWORK_CHANGE_ANNOUNCE_TIME = "network_change_announce_time"
        const val KEY_LAST_AUTO_ANNOUNCE_TIME = "last_auto_announce_time"
        const val KEY_LAST_NETWORK_STATUS = "last_network_status"

    }

    // Get fresh SharedPreferences each time to avoid caching issues across processes
    private fun getCrossProcessPrefs() = context.getSharedPreferences(CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)

    private fun getServiceStatePrefs() = context.getSharedPreferences(SERVICE_STATE_PREFS_NAME, Context.MODE_MULTI_PROCESS)

    /**
     * Save the network change announce timestamp.
     * Called when a network topology change triggers an announce, signaling the main app's
     * AutoAnnounceManager to reset its periodic timer.
     *
     * @param timestamp The timestamp in epoch milliseconds
     */
    fun saveNetworkChangeAnnounceTime(timestamp: Long) {
        getServiceStatePrefs()
            .edit()
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
        getServiceStatePrefs()
            .edit()
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

    /**
     * Get the calls-from-contacts-only setting.
     * When enabled, inbound LXST link requests from non-contacts are silently dropped
     * after caller identification (no STATUS_RINGING to the originator, no UI surface).
     *
     * @return true if non-contact callers should be silently dropped, false (default) otherwise
     */
    fun getAllowCallsFromContactsOnly(): Boolean =
        getCrossProcessPrefs().getBoolean(KEY_ALLOW_CALLS_FROM_CONTACTS_ONLY, false)

    /**
     * Get the master allow-voice-calls setting.
     * When false, the `lxst.telephony` Destination should be deregistered so peers
     * cannot reach it. Outbound calls remain functional regardless (they construct
     * a fresh OUT destination per call). Defaults to true so a fresh install can
     * receive calls without onboarding past this toggle.
     *
     * @return true (default) if incoming voice calls should be accepted, false otherwise
     */
    fun getAllowVoiceCalls(): Boolean =
        getCrossProcessPrefs().getBoolean(KEY_ALLOW_VOICE_CALLS, true)

    /**
     * Get the persistent-wake-lock setting. When true (default), the service holds
     * a PARTIAL_WAKE_LOCK for its whole lifetime so the CPU never suspends. When
     * false (duty-cycle mode) the CPU may sleep between mesh traffic and short
     * timed locks cover active RX/TX bursts instead.
     *
     * Written by the app process (SettingsRepository dual-write); read here at
     * service start and on every network-change lock re-acquisition.
     *
     * @return true (default) to hold the persistent wake lock, false for duty-cycle mode
     */
    fun getPersistentWakeLockEnabled(): Boolean =
        getCrossProcessPrefs().getBoolean(KEY_PERSISTENT_WAKE_LOCK, true)

    /**
     * Persist the service's current network-status string (service-state file)
     * so the service process can restore the correct initial notification
     * across Android-driven restarts of the `:reticulum` process. Without this
     * the service comes back with the default "SHUTDOWN" and the foreground
     * notification flashes (or sticks on) "Disconnected" even though the mesh
     * stack was READY before the restart.
     *
     * Values follow the same vocabulary the notification already expects: "SHUTDOWN",
     * "INITIALIZING", "CONNECTING", "READY", or "ERROR:message".
     */
    fun saveLastNetworkStatus(status: String) {
        // commit() rather than apply(): the service process reads this in the very first
        // thing onCreate() does when Android restarts :reticulum, so an async-queued write
        // that hasn't flushed can lose the transition and leave the notification stale.
        // The disk sync is ~1ms and happens on the networkStatus collector coroutine, not
        // the main thread.
        getServiceStatePrefs()
            .edit()
            .putString(KEY_LAST_NETWORK_STATUS, status)
            .commit()
    }

    /**
     * Read the last network status written by the app process. Returns null when nothing
     * has been persisted yet (first install, or before the app process has run) so the
     * caller can fall back to its own default.
     */
    fun getLastNetworkStatus(): String? = getServiceStatePrefs().getString(KEY_LAST_NETWORK_STATUS, null)
}
