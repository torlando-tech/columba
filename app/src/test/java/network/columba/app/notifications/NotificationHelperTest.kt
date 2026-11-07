package network.columba.app.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.ActiveConversationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for NotificationHelper.
 * Tests notification posting logic, permission checks, and preference filtering.
 *
 * Uses Robolectric's shadow system for Android component testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationHelperTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: NotificationHelper

    private val activeConversationFlow = MutableStateFlow<String?>(null)

    private fun messageNotification(destinationHash: String) =
        notificationManager.activeNotifications.firstOrNull {
            it.id == NotificationHelper.NOTIFICATION_ID_MESSAGE &&
                it.tag == NotificationHelper.messageNotificationTag(destinationHash)
        }

    /**
     * Everything a notification listener or the system's notification history can read
     * from a notification: the extras bundle (title, text, any style lines) and ticker,
     * including the lock-screen public version.
     */
    private fun notificationDump(notification: Notification): String =
        listOfNotNull(
            notification.extras.toString(),
            notification.tickerText?.toString(),
            notification.publicVersion?.let { public ->
                public.extras.toString() + public.tickerText?.toString().orEmpty()
            },
        ).joinToString("\n")

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Grant POST_NOTIFICATIONS permission for tests
        val app = shadowOf(context as Application)
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // All repository methods are explicitly stubbed below (no relaxed mocks needed)
        settingsRepository = mockk()
        activeConversationManager = mockk()

        // Mock active conversation
        every { activeConversationManager.activeConversation } returns activeConversationFlow

        // Default settings: all notifications enabled
        coEvery { settingsRepository.notificationsEnabledFlow } returns flowOf(true)
        coEvery { settingsRepository.notificationReceivedMessageFlow } returns flowOf(true)
        coEvery { settingsRepository.notificationReceivedMessageFavoriteFlow } returns flowOf(true)
        coEvery { settingsRepository.notificationHeardAnnounceFlow } returns flowOf(true)
        coEvery { settingsRepository.notificationBleConnectedFlow } returns flowOf(true)
        coEvery { settingsRepository.notificationBleDisconnectedFlow } returns flowOf(true)
        coEvery { settingsRepository.notificationPrivacyLevelFlow } returns flowOf(NotificationPrivacyLevel.FULL)
        coEvery { settingsRepository.saveNotificationPrivacyLevel(any()) } just Runs

        notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)
    }

    // ========== Master Toggle Tests ==========

    @Test
    fun `notifyMessageReceived does not post when master toggle is off`() =
        runBlocking {
            // Given: Master toggle is OFF
            coEvery { settingsRepository.notificationsEnabledFlow } returns flowOf(false)

            // Recreate helper with new settings
            notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: No notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("No notifications should be posted when master toggle is off", notifications.isEmpty())
        }

    @Test
    fun `notifyMessageReceived posts when master toggle is on`() =
        runBlocking {
            // Given: Master toggle is ON (default)

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted", notifications.isNotEmpty())
        }

    // ========== Message Notification Preference Tests ==========

    @Test
    fun `notifyMessageReceived blocked when message notifications disabled for non-favorite`() =
        runBlocking {
            // Given: General message notifications OFF
            coEvery { settingsRepository.notificationReceivedMessageFlow } returns flowOf(false)
            coEvery { settingsRepository.notificationReceivedMessageFavoriteFlow } returns flowOf(false)
            notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)

            // When: Non-favorite peer sends message
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Regular Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: No notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("No notification should be posted for non-favorite when disabled", notifications.isEmpty())
        }

    @Test
    fun `notifyMessageReceived allowed for favorite when favorite notifications enabled`() =
        runBlocking {
            // Given: General OFF but favorite ON
            coEvery { settingsRepository.notificationReceivedMessageFlow } returns flowOf(false)
            coEvery { settingsRepository.notificationReceivedMessageFavoriteFlow } returns flowOf(true)
            notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)

            // When: Favorite peer sends message
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Favorite Peer",
                messagePreview = "Hello",
                isFavorite = true,
            )

            // Then: Notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted for favorite", notifications.isNotEmpty())
        }

    @Test
    fun `notifyMessageReceived allowed for favorite when general notifications enabled`() =
        runBlocking {
            // Given: General ON, favorite OFF
            coEvery { settingsRepository.notificationReceivedMessageFlow } returns flowOf(true)
            coEvery { settingsRepository.notificationReceivedMessageFavoriteFlow } returns flowOf(false)
            notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)

            // When: Favorite peer sends message
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Favorite Peer",
                messagePreview = "Hello",
                isFavorite = true,
            )

            // Then: Notification posted (general covers favorites too)
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted (general covers favorites)", notifications.isNotEmpty())
        }

    // ========== Message Privacy Tests ==========

    @Test
    fun `notifyMessageReceived FULL keeps the preview in the notification`() =
        runBlocking {
            coEvery { settingsRepository.notificationPrivacyLevelFlow } returns
                flowOf(NotificationPrivacyLevel.FULL)

            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "Hello there",
                isFavorite = false,
            )

            val posted = messageNotification("abc123def456")
            assertNotNull("Notification should be posted", posted)
            assertEquals("Test Peer", posted!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("Hello there", posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        }

    @Test
    fun `notifyMessageReceived NAME_ONLY keeps sender name but never the preview`() =
        runBlocking {
            coEvery { settingsRepository.notificationPrivacyLevelFlow } returns
                flowOf(NotificationPrivacyLevel.NAME_ONLY)

            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "SECRET-PREVIEW-123",
                isFavorite = false,
            )

            val posted = messageNotification("abc123def456")
            assertNotNull("Notification should be posted", posted)
            val notification = posted!!.notification
            assertEquals("Test Peer", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("New message", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertFalse(
                "Preview must not appear anywhere in the notification",
                notificationDump(notification).contains("SECRET-PREVIEW-123"),
            )
            assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
            assertNotNull("Privacy levels must supply a redacted lock-screen version", notification.publicVersion)
        }

    @Test
    fun `notifyMessageReceived NONE keeps neither sender name nor preview`() =
        runBlocking {
            coEvery { settingsRepository.notificationPrivacyLevelFlow } returns
                flowOf(NotificationPrivacyLevel.NONE)

            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "SECRET-PREVIEW-123",
                isFavorite = false,
            )

            val posted = messageNotification("abc123def456")
            assertNotNull("Notification should be posted", posted)
            val notification = posted!!.notification
            assertEquals("New message", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            val dump = notificationDump(notification)
            assertFalse("Sender name must not appear", dump.contains("Test Peer"))
            assertFalse("Preview must not appear anywhere, including the lock-screen version", dump.contains("SECRET-PREVIEW-123"))
        }

    @Test
    fun `setMessagePrivacyLevel tightening cancels message notifications only`() =
        runBlocking {
            coEvery { settingsRepository.notificationAnnounceDirectOnlyFlow } returns flowOf(false)
            coEvery { settingsRepository.notificationAnnounceExcludeTcpFlow } returns flowOf(false)
            val savedLevels = mutableListOf<NotificationPrivacyLevel>()
            coEvery { settingsRepository.saveNotificationPrivacyLevel(capture(savedLevels)) } answers {
                // Model the DataStore write so a later post would read it.
                Unit
            }

            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )
            notificationHelper.notifyAnnounceHeard(
                destinationHash = "ffeeddccbbaa",
                peerName = "Announcer",
                hops = 2,
            )

            notificationHelper.setMessagePrivacyLevel(NotificationPrivacyLevel.NONE)

            assertEquals(
                "The level must be persisted before cancelling",
                listOf(NotificationPrivacyLevel.NONE),
                savedLevels,
            )
            assertNull("Message notification should be cancelled", messageNotification("abc123def456"))
            val remaining = shadowOf(notificationManager).allNotifications
            assertTrue(
                "Announce notification should survive",
                remaining.any {
                    it.extras.getCharSequence(Notification.EXTRA_TITLE) == "Announce from Announcer"
                },
            )
        }

    @Test
    fun `setMessagePrivacyLevel loosening keeps posted message notifications`() =
        runBlocking {
            coEvery { settingsRepository.notificationPrivacyLevelFlow } returns
                flowOf(NotificationPrivacyLevel.NONE)
            val savedLevels = mutableListOf<NotificationPrivacyLevel>()
            coEvery { settingsRepository.saveNotificationPrivacyLevel(capture(savedLevels)) } answers { Unit }

            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Test Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )
            assertNotNull("Notification should be posted first", messageNotification("abc123def456"))

            notificationHelper.setMessagePrivacyLevel(NotificationPrivacyLevel.FULL)

            assertEquals(listOf(NotificationPrivacyLevel.FULL), savedLevels)
            val after = messageNotification("abc123def456")
            assertNotNull("Loosening must not cancel the posted notification", after)
            assertEquals(
                "The posted notification keeps the text it was built with",
                "New message",
                after!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
        }

    // ========== Active Conversation Suppression Tests ==========

    @Test
    fun `notifyMessageReceived suppressed when conversation is active and app in foreground`() =
        runBlocking {
            // Given: This conversation is currently active AND app is in foreground
            activeConversationFlow.value = "abc123def456"
            notificationHelper.isAppInForeground = { true }

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Active Conversation Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification NOT posted (suppressed — user can see the conversation)
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be suppressed for active conversation in foreground", notifications.isEmpty())
        }

    @Test
    fun `notifyMessageReceived not suppressed when conversation is active but app backgrounded`() =
        runBlocking {
            // Given: This conversation is active but app is backgrounded (screen off, etc.)
            activeConversationFlow.value = "abc123def456"
            notificationHelper.isAppInForeground = { false }

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Active Conversation Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification IS posted (user can't see the conversation)
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted when app is backgrounded", notifications.isNotEmpty())
        }

    @Test
    fun `notifyMessageReceived not suppressed for different conversation`() =
        runBlocking {
            // Given: Different conversation is active
            activeConversationFlow.value = "different_hash"

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Background Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted for different conversation", notifications.isNotEmpty())
        }

    @Test
    fun `notifyMessageReceived not suppressed when no conversation active`() =
        runBlocking {
            // Given: No active conversation
            activeConversationFlow.value = null

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted when no conversation active", notifications.isNotEmpty())
        }

    // ========== Permission Tests ==========

    @Test
    @Config(sdk = [33]) // Android 13
    fun `notifyMessageReceived blocked when permission not granted on Android 13+`() =
        runBlocking {
            // Given: Permission NOT granted
            val app = shadowOf(context as Application)
            app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

            // Recreate helper after permission change
            notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: No notification posted
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("No notification should be posted without permission", notifications.isEmpty())
        }

    @Test
    @Config(sdk = [31]) // Android 12
    fun `notifyMessageReceived allowed without permission on Android 12`() =
        runBlocking {
            // Given: Android 12 (no POST_NOTIFICATIONS permission needed)
            // No permission setup needed

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification posted (no permission needed on Android 12)
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted on Android 12", notifications.isNotEmpty())
        }

    // ========== Notification Content Tests ==========

    @Test
    fun `notifyMessageReceived notification has peer name as title`() =
        runBlocking {
            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "John Doe",
                messagePreview = "Hello world",
                isFavorite = false,
            )

            // Then
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted", notifications.isNotEmpty())

            val notification = notifications.first()
            val shadowNotification = shadowOf(notification)
            assertTrue(
                "Notification title should contain peer name",
                shadowNotification.contentTitle?.toString()?.contains("John Doe") == true,
            )
        }

    @Test
    fun `notifyMessageReceived notification has message preview as content`() =
        runBlocking {
            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "John Doe",
                messagePreview = "Hello world",
                isFavorite = false,
            )

            // Then
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be posted", notifications.isNotEmpty())

            val notification = notifications.first()
            val shadowNotification = shadowOf(notification)
            assertTrue(
                "Notification content should contain message preview",
                shadowNotification.contentText?.toString()?.contains("Hello world") == true,
            )
        }

    // ========== Notification ID Tests ==========

    @Test
    fun `hash-colliding conversations remain independently cancellable`() =
        runBlocking {
            val hashA = "FB"
            val hashB = "Ea"
            assertEquals(hashA.hashCode(), hashB.hashCode())

            notificationHelper.notifyMessageReceived(
                destinationHash = hashA,
                peerName = "Peer A",
                messagePreview = "From A",
                isFavorite = false,
            )
            notificationHelper.notifyMessageReceived(
                destinationHash = hashB,
                peerName = "Peer B",
                messagePreview = "From B",
                isFavorite = false,
            )

            val posted = notificationManager.activeNotifications
            assertEquals(2, posted.size)
            assertNotEquals(
                posted[0].notification.contentIntent,
                posted[1].notification.contentIntent,
            )

            notificationHelper.cancelNotificationForConversation(hashA)

            val remaining = notificationManager.activeNotifications
            assertEquals(1, remaining.size)
            assertEquals("Peer B", shadowOf(remaining.single().notification).contentTitle)
        }

    @Test
    fun `notifyMessageReceived uses unique ID per sender`() =
        runBlocking {
            // Given: Two different senders
            val hash1 = "sender_one_hash_1234"
            val hash2 = "sender_two_hash_5678"

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = hash1,
                peerName = "Sender One",
                messagePreview = "Hello",
                isFavorite = false,
            )

            notificationHelper.notifyMessageReceived(
                destinationHash = hash2,
                peerName = "Sender Two",
                messagePreview = "Hi",
                isFavorite = false,
            )

            // Then: Two separate notifications posted (different IDs)
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Should have 2 notifications", notifications.size >= 2)
        }

    // ========== Notification Channel Tests ==========

    @Test
    fun `notification channels are created on init`() {
        // When: Helper is created (in setup)

        // Then: Channels should exist
        val channels = notificationManager.notificationChannels
        assertTrue(
            "Messages channel should exist",
            channels.any { it.id == "messages" },
        )
        assertTrue(
            "Announces channel should exist",
            channels.any { it.id == "announces" },
        )
        assertTrue(
            "BLE events channel should exist",
            channels.any { it.id == "ble_events" },
        )
    }

    // ========== Cancel Notification Tests ==========

    @Test
    fun `cancelNotificationForConversation cancels only the matching message notification`() =
        runBlocking {
            // Given: Two conversations with notifications
            val hashA = "conversation_a_hash_xyz"
            val hashB = "conversation_b_hash_abc"
            notificationHelper.notifyMessageReceived(
                destinationHash = hashA,
                peerName = "Peer A",
                messagePreview = "Hello from A",
                isFavorite = false,
            )
            notificationHelper.notifyMessageReceived(
                destinationHash = hashB,
                peerName = "Peer B",
                messagePreview = "Hello from B",
                isFavorite = false,
            )
            assertTrue("Notification A should exist", messageNotification(hashA) != null)
            assertTrue("Notification B should exist", messageNotification(hashB) != null)
            // When: Cancel only conversation A
            notificationHelper.cancelNotificationForConversation(hashA)
            // Then: Only A's notification is cancelled
            assertTrue("Notification for conversation A should be cancelled", messageNotification(hashA) == null)
            assertTrue("Notification for conversation B should still exist", messageNotification(hashB) != null)
        }
    @Test
    fun `cancelNotificationForConversation is idempotent`() =
        runBlocking {
            // Given: One notification
            val hash = "single_conversation_hash"
            notificationHelper.notifyMessageReceived(
                destinationHash = hash,
                peerName = "Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )
            // When: Cancel twice
            notificationHelper.cancelNotificationForConversation(hash)
            notificationHelper.cancelNotificationForConversation(hash)
            // Then: No error, notification is cancelled
            assertTrue("Notification should be cancelled after idempotent calls", messageNotification(hash) == null)
        }

    @Test
    fun `cancelNotificationForConversation is safe when no notification exists`() =
        runBlocking {
            // Given: No notifications posted

            // When: Cancel a conversation that has no notification
            val result = runCatching {
                notificationHelper.cancelNotificationForConversation("nonexistent_hash")
            }

            // Then: No exception thrown
            assertTrue("Cancellation of nonexistent notification should not throw", result.isSuccess)
        }

    @Test
    fun `cancelAllNotifications clears all notifications`() =
        runBlocking {
            // Given: Post a notification
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            val shadowNotificationManager = shadowOf(notificationManager)
            assertTrue("Notification should exist", shadowNotificationManager.allNotifications.isNotEmpty())

            // When
            notificationHelper.cancelAllNotifications()

            // Then
            assertTrue("All notifications should be cancelled", shadowNotificationManager.allNotifications.isEmpty())
        }
}
