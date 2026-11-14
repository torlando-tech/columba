package network.columba.app.rns.host.persistence

import android.content.Context
import network.columba.app.rns.api.notify.MessageNotificationBridge

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
        const val KEY_ALLOW_CALLS_FROM_CONTACTS_ONLY = "allow_calls_from_contacts_only"
        const val KEY_ALLOW_VOICE_CALLS = "allow_voice_calls"
        const val KEY_NETWORK_CHANGE_ANNOUNCE_TIME = "network_change_announce_time"
        const val KEY_LAST_AUTO_ANNOUNCE_TIME = "last_auto_announce_time"
        const val KEY_LAST_NETWORK_STATUS = "last_network_status"
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
        getCrossProcessPrefs()
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
        getCrossProcessPrefs()
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
     * Persist the app process's current network-status string so the service process can
     * restore the correct initial notification across Android-driven restarts of the
     * `:reticulum` process. Without this the service comes back with the default "SHUTDOWN"
     * and the foreground notification flashes (or sticks on) "Disconnected" even though
     * the native stack in the app process is still READY.
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
        getCrossProcessPrefs()
            .edit()
            .putString(KEY_LAST_NETWORK_STATUS, status)
            .commit()
    }

    /**
     * Read the last network status written by the app process. Returns null when nothing
     * has been persisted yet (first install, or before the app process has run) so the
     * caller can fall back to its own default.
     */
    fun getLastNetworkStatus(): String? = getCrossProcessPrefs().getString(KEY_LAST_NETWORK_STATUS, null)

    /**
     * Whether the app process is currently in the foreground, as judged by its
     * [MessageNotificationBridge.KEY_UI_FOREGROUND_AT] heartbeat.
     *
     * A frozen/cached app stops ticking, so a stale heartbeat is the only signal
     * that stays truthful after the OS freezes the process — `ProcessLifecycleOwner`
     * state is not readable from here and is not even accurate once frozen.
     *
     * @param now injected for tests; defaults to wall clock.
     */
    fun isUiForeground(
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val lastTick = getCrossProcessPrefs().getLong(MessageNotificationBridge.KEY_UI_FOREGROUND_AT, 0L)
        if (lastTick <= 0L) return false
        // Clock jumps backwards (or a stale value from before a reboot) fail closed.
        if (lastTick > now) return false
        return now - lastTick <= MessageNotificationBridge.UI_FOREGROUND_STALE_AFTER_MS
    }

    /**
     * Read the mirrored message-notification gates written by the app process.
     *
     * @return null when the app has never mirrored them ([MessageNotificationBridge.KEY_MIRROR_WRITTEN]
     *   absent). Callers must then stay silent rather than substitute defaults: the
     *   upgrade case (booted again before the app ran this build) would otherwise leak
     *   previews on the lock screen for a user whose level is `NONE`.
     */
    fun getNotificationMirror(): NotificationMirror? {
        val prefs = getCrossProcessPrefs()
        if (!prefs.contains(MessageNotificationBridge.KEY_MIRROR_WRITTEN)) return null
        return NotificationMirror(
            enabled = prefs.getBoolean(MessageNotificationBridge.KEY_NOTIFICATIONS_ENABLED, true),
            receivedMessage = prefs.getBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE, true),
            favoriteAllowed = prefs.getBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE_FAVORITE, true),
            revealPreview = prefs.getBoolean(MessageNotificationBridge.KEY_REVEAL_PREVIEW, true),
            revealSender = prefs.getBoolean(MessageNotificationBridge.KEY_REVEAL_SENDER, true),
        )
    }
}

/**
 * The notification gates `:reticulum` needs, mirrored from the app's DataStore.
 *
 * @param enabled master notifications toggle
 * @param receivedMessage "notify for received messages"
 * @param favoriteAllowed "notify for saved peers" (consulted only for saved peers)
 * @param revealPreview may the notification show message text
 * @param revealSender may the notification show the sender
 */
data class NotificationMirror(
    val enabled: Boolean,
    val receivedMessage: Boolean,
    val favoriteAllowed: Boolean,
    val revealPreview: Boolean,
    val revealSender: Boolean,
)
