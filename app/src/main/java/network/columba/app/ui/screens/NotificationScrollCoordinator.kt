package network.columba.app.ui.screens

/**
 * Pure-state-machine coordinator for the notification-entry scroll-to-bottom
 * behaviour. Lives outside Compose so it can be unit-tested without Robolectric.
 *
 * Accepted production semantics:
 * - When the user taps a notification, the conversation scrolls to the newest
 *   message (index 0) **once** after Paging data loads.
 * - Later inbound items cannot re-trigger the notification scroll.
 * - Non-notification entry (ordinary navigation) never triggers a forced scroll
 *   through this coordinator — that path is handled by the "initial scroll"
 *   effect in [MessagingScreen].
 *
 * The [MessagingScreen] LaunchedEffect keys each notification event by destination,
 * provenance, and notification event ID; it calls [onContentReady] when
 * [newestMessageId] becomes non-null.
 */
class NotificationScrollCoordinator(
    /** True when the user arrived via a message notification. */
    private val fromNotification: Boolean,
) {
    /** Whether content has loaded (newestMessageId != null). */
    private var contentReady = false

    /** Whether the one-shot scroll has already been issued for this entry. */
    private var scrollIssued = false

    /**
     * Called when Paging data signals that the newest message is available
     * (i.e. `newestMessageId` transitions from `null` to a real ID).
     */
    fun onContentReady() {
        contentReady = true
    }

    /**
     * Returns `true` exactly once — the first time content becomes ready
     * **and** the user arrived from a notification.
     *
     * After returning `true` once, subsequent calls return `false` even if
     * content changes again (new inbound messages).
     *
     * If [fromNotification] is `false`, this always returns `false`.
     */
    fun shouldScroll(): Boolean {
        if (!fromNotification || scrollIssued || !contentReady) return false

        scrollIssued = true
        return true
    }

    /**
     * Returns `true` if this coordinator was configured for notification entry.
     * Useful in tests to verify the setup contract.
     */
    fun isFromNotification(): Boolean = fromNotification
}
