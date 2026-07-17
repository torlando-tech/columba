package network.columba.app.navigation

/**
 * Decision helper for idempotent conversation navigation.
 *
 * Prevents duplicate entries on the NavController back stack when a notification
 * or deep link targets the conversation that is already visible on screen.
 * Without this check, clicking a notification for the current conversation pushes
 * a second identical destination, so one Back press reveals the same conversation
 * again instead of returning to the prior screen.
 *
 * See: [ConversationNavigationTest] for regression evidence.
 */
object ConversationNavigation {
    private const val MESSAGING_ROUTE_PATTERN = "messaging/{destinationHash}/{peerName}"

    /**
     * Returns `true` if navigation to [targetDestinationHash] should proceed.
     *
     * Returns `false` when the user is already viewing the same conversation, so the
     * caller can skip the `navController.navigate(...)` call and avoid a duplicate
     * back-stack entry.
     *
     * @param currentRoute The route string of the current back-stack entry (from
     *   `navController.currentBackStackEntry?.destination?.route`), or `null`.
     * @param currentDestinationHash The `destinationHash` argument of the current
     *   back-stack entry, or `null` if not on a messaging screen.
     * @param targetDestinationHash The destination hash the user is trying to navigate to.
     */
    fun shouldNavigateToConversation(
        currentRoute: String?,
        currentDestinationHash: String?,
        targetDestinationHash: String,
    ): Boolean {
        val isOnMessagingScreen = currentRoute == MESSAGING_ROUTE_PATTERN
        val isSameConversation = isOnMessagingScreen &&
            currentDestinationHash == targetDestinationHash
        return !isSameConversation
    }
}
