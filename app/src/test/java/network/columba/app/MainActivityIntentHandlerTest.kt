package network.columba.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the navigation contract between MainActivityIntentHandler
 * and MessagingScreen — verifying that PendingNavigation.Conversation carries
 * fromNotification=true when the user taps a message notification, so the
 * MessagingScreen can perform the one-shot scroll on notification entry.
 *
 * The handler itself (MainActivityIntentHandler.handleOpenConversation) cannot be
 * unit-tested in isolation because it requires a real MainActivity instance with
 * injected Hilt dependencies. The handler logic is verified indirectly via the
 * PendingNavigation data class and the navigation routing in MainActivity.
 *
 * See MessagingScreen.kt LaunchedEffect(destinationHash, fromNotification):
 * fromNotification must be true only on first entry from a notification, and the
 * effect must be one-shot (not re-run on every new inbound message).
 */
class MainActivityIntentHandlerTest {
    @Test
    fun `Conversation with fromNotification true preserves flag`() {
        // MainActivityIntentHandler.handleOpenConversation creates:
        // PendingNavigation.Conversation(destinationHash, peerName, fromNotification = true)
        val nav = PendingNavigation.Conversation("hash123", "Peer", fromNotification = true)

        assertTrue("fromNotification should be true", nav.fromNotification)
        assertEquals("hash123", nav.destinationHash)
        assertEquals("Peer", nav.peerName)
    }

    @Test
    fun `Conversation without fromNotification defaults to false`() {
        // When navigating from within the app (not from a notification),
        // fromNotification defaults to false.
        val nav = PendingNavigation.Conversation("hash123", "Peer")

        assertFalse("fromNotification should default to false", nav.fromNotification)
    }

    @Test
    fun `Conversation with different fromNotification values are not equal`() {
        val withNotif = PendingNavigation.Conversation("hash", "Peer", fromNotification = true)
        val withoutNotif = PendingNavigation.Conversation("hash", "Peer", fromNotification = false)

        assertNotEquals(
            "Same conversation with different fromNotification should not be equal",
            withNotif,
            withoutNotif,
        )
    }

    @Test
    fun `two Conversation instances with same fromNotification are equal`() {
        val nav1 = PendingNavigation.Conversation("hash", "Peer", fromNotification = true)
        val nav2 = PendingNavigation.Conversation("hash", "Peer", fromNotification = true)

        assertEquals("Same conversation with same fromNotification should be equal", nav1, nav2)
    }
}
