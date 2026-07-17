package network.columba.app

import android.app.Application
import android.content.Intent
import network.columba.app.notifications.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationIntentParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        // Ensure a clean application context for each test
    }

    @Test
    fun `parseOpenConversation with valid notification intent returns Conversation with fromNotification true`() {
        // Given - an intent mimicking a notification tap
        val intent = Intent().apply {
            action = NotificationHelper.ACTION_OPEN_CONVERSATION
            putExtra(NotificationHelper.EXTRA_DESTINATION_HASH, "testHash123")
            putExtra(NotificationHelper.EXTRA_PEER_NAME, "Alice")
        }

        // When
        val result = NotificationIntentParser.parseOpenConversation(intent)

        // Then - navigation object with fromNotification = true
        assertNotNull(result)
        assertEquals("testHash123", result!!.destinationHash)
        assertEquals("Alice", result.peerName)
        assertTrue("fromNotification must be true for notification intent", result.fromNotification)
    }

    @Test
    fun `parseOpenConversation with wrong action returns null`() {
        val intent = Intent().apply {
            action = "com.other.ACTION"
            putExtra(NotificationHelper.EXTRA_DESTINATION_HASH, "hash")
            putExtra(NotificationHelper.EXTRA_PEER_NAME, "Peer")
        }

        val result = NotificationIntentParser.parseOpenConversation(intent)

        assertNull(
            "Non-notification action must return null",
            result,
        )
    }

    @Test
    fun `parseOpenConversation with missing destination hash returns null`() {
        val intent = Intent().apply {
            action = NotificationHelper.ACTION_OPEN_CONVERSATION
            putExtra(NotificationHelper.EXTRA_PEER_NAME, "Peer")
        }

        val result = NotificationIntentParser.parseOpenConversation(intent)

        assertNull(
            "Missing destination hash must return null",
            result,
        )
    }

    @Test
    fun `parseOpenConversation with missing peer name returns null`() {
        val intent = Intent().apply {
            action = NotificationHelper.ACTION_OPEN_CONVERSATION
            putExtra(NotificationHelper.EXTRA_DESTINATION_HASH, "hash")
        }

        val result = NotificationIntentParser.parseOpenConversation(intent)

        assertNull(
            "Missing peer name must return null",
            result,
        )
    }

    @Test
    fun `parseOpenConversation with both extras missing returns null`() {
        val intent = Intent().apply {
            action = NotificationHelper.ACTION_OPEN_CONVERSATION
        }

        val result = NotificationIntentParser.parseOpenConversation(intent)

        assertNull(result)
    }

    @Test
    fun `parseOpenConversation preserves exact peer name including spaces and unicode`() {
        val intent = Intent().apply {
            action = NotificationHelper.ACTION_OPEN_CONVERSATION
            putExtra(NotificationHelper.EXTRA_DESTINATION_HASH, "abc123")
            putExtra(NotificationHelper.EXTRA_PEER_NAME, "  Peer With Spaces  中文字符  ")
        }

        val result = NotificationIntentParser.parseOpenConversation(intent)

        assertNotNull(result)
        assertEquals("  Peer With Spaces  中文字符  ", result!!.peerName)
    }

    @Test
    fun `parseOpenConversation result fromNotification differs from default Conversation`() {
        val intent = Intent().apply {
            action = NotificationHelper.ACTION_OPEN_CONVERSATION
            putExtra(NotificationHelper.EXTRA_DESTINATION_HASH, "hash")
            putExtra(NotificationHelper.EXTRA_PEER_NAME, "Peer")
        }

        val fromParser = NotificationIntentParser.parseOpenConversation(intent)!!
        val defaultNav = PendingNavigation.Conversation("hash", "Peer")

        // fromParser has fromNotification = true, defaultNav has fromNotification = false
        assertFalse(
            "Parsed navigation must differ from default (fromNotification flag)",
            fromParser == defaultNav,
        )
        assertTrue(fromParser.fromNotification)
        assertFalse(defaultNav.fromNotification)
    }
}
