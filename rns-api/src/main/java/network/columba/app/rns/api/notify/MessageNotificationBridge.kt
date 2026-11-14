package network.columba.app.rns.api.notify

/**
 * Cross-process contract for received-message notifications.
 *
 * Both processes can emit these notifications:
 *  - `:app` while it is alive (its `MessageCollector` reconciler, which also
 *    owns foreground/thread suppression), and
 *  - `:reticulum` while the app is backgrounded, frozen, killed, or not yet
 *    started after a reboot (`ServiceMessageNotifier`).
 *
 * Exactly one of them posts, decided by a single `messages.notificationHandled`
 * compare-and-set claim. For the two to produce the *same* notification (same
 * channel, tag, id, deep link) and to agree on what may be shown, the constants
 * below are the single source of truth: the app re-exports them from
 * `NotificationHelper`, and both processes read the mirrored settings keys.
 *
 * Lives in `:rns-api` because `:rns-host` cannot depend on `:app` (module
 * direction) and duplicating these literals is how notifications silently stop
 * replacing each other.
 */
object MessageNotificationBridge {
    /** Plain SharedPreferences file readable from both processes (DataStore is not). */
    const val CROSS_PROCESS_PREFS_NAME = "cross_process_settings"

    /** Channel id for message notifications; must exist in `:reticulum` too, or `notify()` is dropped. */
    const val CHANNEL_ID_MESSAGES = "messages"

    /** Fixed notification id; per-sender separation comes from [tag]. */
    const val NOTIFICATION_ID_MESSAGE = 1000

    /** Per-sender notification tag prefix, so different peers stack instead of replacing. */
    const val NOTIFICATION_TAG_MESSAGE_PREFIX = "message:"

    /** Deep-link action handled by `MainActivity` to open a conversation. */
    const val ACTION_OPEN_CONVERSATION = "network.columba.app.ACTION_OPEN_CONVERSATION"

    /** Extra carrying the sender's destination hash. */
    const val EXTRA_DESTINATION_HASH = "destination_hash"

    /** Extra carrying the sender's display name. */
    const val EXTRA_PEER_NAME = "peer_name"

    /** Extra carrying the thread id (hex) the message belongs to, when any. */
    const val EXTRA_THREAD_ID = "thread_id"

    /**
     * `MainActivity`'s fully-qualified name. `:rns-host` cannot reference the
     * app's class directly, so the content intent is built with
     * `Intent().setClassName(context.packageName, MAIN_ACTIVITY_CLASS)`, which
     * resolves against the merged manifest exactly as `MainActivity::class`
     * would.
     */
    const val MAIN_ACTIVITY_CLASS = "network.columba.app.MainActivity"

    /**
     * Set by the app process the first time it mirrors [KEY_NOTIFICATIONS_ENABLED]
     * and friends. Absent means this build's app process has never run, so the
     * other keys are not authoritative: `:reticulum` refuses to notify rather
     * than fall back to permissive defaults and leak a preview the user hid.
     */
    const val KEY_MIRROR_WRITTEN = "notification_mirror_written"

    /** Master notifications toggle, mirrored from DataStore by SettingsRepository. */
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

    /** "Notify for received messages" toggle. */
    const val KEY_RECEIVED_MESSAGE = "notification_received_message"

    /** "Notify for saved peers only" toggle. */
    const val KEY_RECEIVED_MESSAGE_FAVORITE = "notification_received_message_favorite"

    /** Whether a notification may show the message text (`NotificationPrivacyLevel.FULL`). */
    const val KEY_REVEAL_PREVIEW = "message_notify_reveal_preview"

    /** Whether a notification may show the sender (`NotificationPrivacyLevel` != `NONE`). */
    const val KEY_REVEAL_SENDER = "message_notify_reveal_sender"

    /**
     * Epoch millis of the app's last `ProcessLifecycleOwner` foreground tick
     * (0 when the app is not foregrounded). `:reticulum` treats a value older
     * than [UI_FOREGROUND_STALE_AFTER_MS] as "app owns notifications is
     * unknowable, I own them" — this is the only liveness signal that survives
     * the app being cached/frozen, because a frozen process stops ticking.
     */
    const val KEY_UI_FOREGROUND_AT = "ui_foreground_at"

    /** A heartbeat older than this means the UI is gone or frozen. */
    const val UI_FOREGROUND_STALE_AFTER_MS = 15_000L

    /** Cadence of the app's foreground heartbeat. */
    const val UI_FOREGROUND_TICK_MS = 5_000L

    /** Notification tag for one sender. */
    fun tag(destinationHash: String): String = NOTIFICATION_TAG_MESSAGE_PREFIX + destinationHash
}
