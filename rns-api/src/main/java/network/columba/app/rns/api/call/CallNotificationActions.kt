package network.columba.app.rns.api.call

/**
 * Intent contract for the call notification's action buttons.
 *
 * Both sides of the backend seam need these literals: the app process builds the
 * incoming-call notification actions (`CallNotificationHelper`,
 * `CallActionReceiver`), while the `:reticulum` service process builds the
 * ongoing-call notification and its hang-up action, because a CallStyle call
 * notification must be posted through `startForeground`. Keeping the strings here
 * makes that cross-process contract single-sourced instead of duplicated.
 */
object CallNotificationActions {
    /** Answer the ringing call. */
    const val ACTION_ANSWER_CALL = "network.columba.app.ACTION_ANSWER_CALL"

    /** Decline the ringing call. */
    const val ACTION_DECLINE_CALL = "network.columba.app.ACTION_DECLINE_CALL"

    /** Hang up the active call. */
    const val ACTION_END_CALL = "network.columba.app.ACTION_END_CALL"

    /** Open the call screen for the given call. */
    const val ACTION_OPEN_CALL = "network.columba.app.ACTION_OPEN_CALL"

    /** Extra carrying the peer's identity hash (32-char hex). */
    const val EXTRA_IDENTITY_HASH = "identity_hash"

    /**
     * Fully-qualified name of the app-process broadcast receiver that performs the
     * actions above. The `:reticulum` service cannot reference the `:app` class
     * directly, so it targets it explicitly by component name; the app's
     * `CallActionReceiverTest` pins the name to the real class so a rename fails
     * loudly instead of silently losing the hang-up action.
     */
    const val RECEIVER_CLASS = "network.columba.app.notifications.CallActionReceiver"
}
