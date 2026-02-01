package com.lxmf.messenger.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.service.ActiveConversationManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

    // ========== Active Conversation Suppression Tests ==========

    @Test
    fun `notifyMessageReceived suppressed when conversation is active`() =
        runBlocking {
            // Given: This conversation is currently active (visible on screen)
            activeConversationFlow.value = "abc123def456"

            // When
            notificationHelper.notifyMessageReceived(
                destinationHash = "abc123def456",
                peerName = "Active Conversation Peer",
                messagePreview = "Hello",
                isFavorite = false,
            )

            // Then: Notification NOT posted (suppressed)
            val shadowNotificationManager = shadowOf(notificationManager)
            val notifications = shadowNotificationManager.allNotifications
            assertTrue("Notification should be suppressed for active conversation", notifications.isEmpty())
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
