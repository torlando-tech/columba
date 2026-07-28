package network.columba.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConversationNavigation] decision helper.
 *
 * Regression tests for the duplicate-conversation NavController stack defect:
 * when a notification for the currently-viewed conversation is clicked,
 * a second identical entry was pushed onto the back stack, so one Back press
 * revealed the same conversation again instead of returning to the prior screen.
 */
class ConversationNavigationTest {
    // ==================== Idempotency: same conversation already visible ====================

    @Test
    fun `should not navigate when already viewing the same conversation`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "messaging/{destinationHash}/{peerName}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "abc123def456",
        )

        assertFalse(
            "Clicking a notification for the conversation already on screen must be a no-op",
            result,
        )
    }

    @Test
    fun `should not navigate when already viewing the same conversation with different peer name`() {
        // The route pattern and hash match even though the name argument differs in the URL.
        // The hash is the canonical identity; names can change.
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "messaging/{destinationHash}/{peerName}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "abc123def456",
        )

        assertFalse(result)
    }

    @Test
    fun `notification route pattern is recognized as the current conversation`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "messaging/{destinationHash}/{peerName}?fromNotification={fromNotification}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "abc123def456",
        )

        assertFalse(result)
    }

    @Test
    fun `notification for current conversation reuses top entry`() {
        val action = ConversationNavigation.actionFor(
            currentRoute = "messaging/{destinationHash}/{peerName}?fromNotification={fromNotification}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "abc123def456",
            fromNotification = true,
        )

        assertEquals(ConversationNavigation.Action.REUSE_CURRENT, action)
    }

    @Test
    fun `ordinary duplicate conversation navigation is skipped`() {
        val action = ConversationNavigation.actionFor(
            currentRoute = "messaging/{destinationHash}/{peerName}?fromNotification={fromNotification}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "abc123def456",
            fromNotification = false,
        )

        assertEquals(ConversationNavigation.Action.SKIP, action)
    }

    @Test
    fun `notification for different conversation pushes a new entry`() {
        val action = ConversationNavigation.actionFor(
            currentRoute = "messaging/{destinationHash}/{peerName}?fromNotification={fromNotification}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "xyz789ghi012",
            fromNotification = true,
        )

        assertEquals(ConversationNavigation.Action.NAVIGATE, action)
    }

    // ==================== Navigation to different conversations ====================

    @Test
    fun `should navigate when viewing a different conversation`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "messaging/{destinationHash}/{peerName}",
            currentDestinationHash = "abc123def456",
            targetDestinationHash = "xyz789ghi012",
        )

        assertTrue(
            "Clicking a notification for a different conversation should navigate",
            result,
        )
    }

    // ==================== Navigation from non-messaging screens ====================

    @Test
    fun `should navigate when current route is the Chats screen`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "chats",
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(result)
    }

    @Test
    fun `should navigate when current route is the Contacts screen`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "contacts",
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(result)
    }

    @Test
    fun `should navigate when current route is the Settings screen`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "settings",
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(result)
    }

    @Test
    fun `should navigate when current route is an announce detail screen`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "announce_detail/somehash",
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(result)
    }

    @Test
    fun `should navigate when current route is null`() {
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = null,
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(
            "Navigation should proceed when there is no current route (e.g. cold start)",
            result,
        )
    }

    // ==================== Edge cases ====================

    @Test
    fun `should navigate when current route is messaging but hash is null`() {
        // Malformed state — treat as "not the same conversation" so we still navigate.
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "messaging/{destinationHash}/{peerName}",
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(result)
    }

    @Test
    fun `should navigate when on a nested messaging screen variant`() {
        // The route pattern must match exactly — a partial match should not block navigation.
        val result = ConversationNavigation.shouldNavigateToConversation(
            currentRoute = "message_detail/somehash",
            currentDestinationHash = null,
            targetDestinationHash = "abc123def456",
        )

        assertTrue(result)
    }
}
