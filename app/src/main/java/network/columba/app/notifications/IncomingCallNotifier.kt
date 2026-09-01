package network.columba.app.notifications

/**
 * Narrow port for presenting and cancelling the incoming-call notification.
 *
 * The presentation policy (when the full-screen-intent notification is posted,
 * which caller name it carries) lives on the app side of the backend seam
 * ([network.columba.app.service.IncomingCallPresenter]); this port keeps that
 * policy testable without Android notification machinery.
 */
interface IncomingCallNotifier {
    /**
     * Post the high-importance category-call incoming notification with its
     * full-screen intent.
     *
     * @param identityHash The caller's identity hash (32-char hex)
     * @param callerName Resolved display name, or null to fall back to a
     *   formatted hash
     */
    fun showIncomingCallNotification(identityHash: String, callerName: String?)

    /** Cancel the incoming-call notification (no-op if not showing). */
    fun cancelIncomingCallNotification()

    /**
     * Monotonic cancel counter, bumped by every
     * [cancelIncomingCallNotification] from any caller (the presenter's
     * collector, MainActivity, the notification actions, the call screen).
     * A caller that queues a post can capture this before enqueuing and drop
     * the post if the tick moved by execution time: a cancel the caller never
     * observed still suppresses a post that would resurrect it.
     */
    val cancelTick: Long
}
