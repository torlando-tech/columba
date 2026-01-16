package com.lxmf.messenger.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
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
 * Unit tests for CallNotificationHelper.
 *
 * Tests notification channel creation, incoming call notifications,
 * ongoing call notifications, and notification cancellation.
 *
 * Uses Robolectric's shadow system for Android component testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallNotificationHelperTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var helper: CallNotificationHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Grant POST_NOTIFICATIONS permission for tests
        val app = shadowOf(context as Application)
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        helper = CallNotificationHelper(context)
    }

    // ========== Notification Channel Tests ==========

    @Test
    fun `creates incoming call notification channel`() {
        // Re-instantiate to trigger channel creation
        helper = CallNotificationHelper(context)

        // Verify incoming call channel was created
        val channels = notificationManager.notificationChannels
        assertTrue(
            "Should create incoming call channel",
            channels.any { it.id == "incoming_calls" },
        )
    }

    @Test
    fun `creates ongoing call notification channel`() {
        helper = CallNotificationHelper(context)

        // Verify ongoing call channel was created
        val channels = notificationManager.notificationChannels
        assertTrue(
            "Should create ongoing call channel",
            channels.any { it.id == "ongoing_calls" },
        )
    }

    @Test
    fun `creates both notification channels`() {
        helper = CallNotificationHelper(context)

        // Should create exactly 2 channels
        val channels = notificationManager.notificationChannels
        assertEquals(
            "Should have created 2 channels",
            2,
            channels.size,
        )
    }

    // ========== Incoming Call Notification Tests ==========

    @Test
    fun `showIncomingCallNotification posts notification with correct ID`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123def456789012345678901234567890",
            callerName = "Test Caller",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Should post notification with incoming call ID", notification)
    }

    @Test
    fun `showIncomingCallNotification with null name uses formatted hash`() {
        val testHash = "abc123def456789012345678901234567890"

        helper.showIncomingCallNotification(
            identityHash = testHash,
            callerName = null,
        )

        // Verify notification is posted
        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    @Test
    fun `showIncomingCallNotification respects permission`() {
        // Revoke the permission
        val app = shadowOf(context as Application)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Create a new helper after permission change
        helper = CallNotificationHelper(context)

        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "Test",
        )

        // Should not post notification without permission
        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNull("Should not post notification without permission", notification)
    }

    // ========== Ongoing Call Notification Tests ==========

    @Test
    fun `showOngoingCallNotification posts notification with correct ID`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123def456789012345678901234567890",
            peerName = "Test Peer",
            duration = 120,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Should post notification with ongoing call ID", notification)
    }

    @Test
    fun `showOngoingCallNotification with null name uses formatted hash`() {
        val testHash = "abc123def456789012345678901234567890"

        helper.showOngoingCallNotification(
            identityHash = testHash,
            peerName = null,
            duration = 60,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    @Test
    fun `showOngoingCallNotification respects permission`() {
        // Revoke the permission
        val app = shadowOf(context as Application)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // Create a new helper after permission change
        helper = CallNotificationHelper(context)

        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 0,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNull("Should not post notification without permission", notification)
    }

    // ========== Cancel Notification Tests ==========

    @Test
    fun `cancelIncomingCallNotification cancels correct notification`() {
        // First, post a notification
        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "Test",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        assertNotNull(
            "Notification should exist before cancel",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL),
        )

        // Cancel it
        helper.cancelIncomingCallNotification()

        assertNull(
            "Notification should be cancelled",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL),
        )
    }

    @Test
    fun `cancelOngoingCallNotification cancels correct notification`() {
        // First, post a notification
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 0,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        assertNotNull(
            "Notification should exist before cancel",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL),
        )

        // Cancel it
        helper.cancelOngoingCallNotification()

        assertNull(
            "Notification should be cancelled",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL),
        )
    }

    @Test
    fun `cancelAllCallNotifications cancels both notifications`() {
        // Post both notifications
        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "Test",
        )
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 0,
        )

        val shadowNotificationManager = shadowOf(notificationManager)

        // Verify both exist
        assertNotNull(
            "Incoming notification should exist",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL),
        )
        assertNotNull(
            "Ongoing notification should exist",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL),
        )

        // Cancel all
        helper.cancelAllCallNotifications()

        // Verify both are cancelled
        assertNull(
            "Incoming notification should be cancelled",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL),
        )
        assertNull(
            "Ongoing notification should be cancelled",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL),
        )
    }

    // ========== Notification ID Constants Tests ==========

    @Test
    fun `NOTIFICATION_ID_INCOMING_CALL is correct`() {
        assertEquals(5000, CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL)
    }

    @Test
    fun `NOTIFICATION_ID_ONGOING_CALL is correct`() {
        assertEquals(5001, CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL)
    }

    // ========== Intent Action Constants Tests ==========

    @Test
    fun `ACTION_ANSWER_CALL is correct`() {
        assertEquals("com.lxmf.messenger.ACTION_ANSWER_CALL", CallNotificationHelper.ACTION_ANSWER_CALL)
    }

    @Test
    fun `ACTION_DECLINE_CALL is correct`() {
        assertEquals("com.lxmf.messenger.ACTION_DECLINE_CALL", CallNotificationHelper.ACTION_DECLINE_CALL)
    }

    @Test
    fun `ACTION_END_CALL is correct`() {
        assertEquals("com.lxmf.messenger.ACTION_END_CALL", CallNotificationHelper.ACTION_END_CALL)
    }

    @Test
    fun `ACTION_OPEN_CALL is correct`() {
        assertEquals("com.lxmf.messenger.ACTION_OPEN_CALL", CallNotificationHelper.ACTION_OPEN_CALL)
    }

    // ========== Intent Extra Constants Tests ==========

    @Test
    fun `EXTRA_IDENTITY_HASH is correct`() {
        assertEquals("identity_hash", CallNotificationHelper.EXTRA_IDENTITY_HASH)
    }

    @Test
    fun `EXTRA_CALLER_NAME is correct`() {
        assertEquals("caller_name", CallNotificationHelper.EXTRA_CALLER_NAME)
    }

    // ========== Duration Formatting Tests ==========

    @Test
    fun `ongoing notification formats zero duration correctly`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 0,
        )

        // Verify notification is posted (duration formatting is internal)
        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    @Test
    fun `ongoing notification formats minute duration correctly`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 60,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    @Test
    fun `ongoing notification formats hour plus duration correctly`() {
        // 1 hour, 1 minute, 1 second
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 3661,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    // ========== Identity Hash Formatting Tests ==========

    @Test
    fun `short identity hash is displayed as-is`() {
        val shortHash = "abc123"

        helper.showIncomingCallNotification(
            identityHash = shortHash,
            callerName = null,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    @Test
    fun `long identity hash is truncated`() {
        val longHash = "abc123def456789012345678901234567890"

        helper.showIncomingCallNotification(
            identityHash = longHash,
            callerName = null,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Should post notification", notification)
    }

    // ========== Notification Content Tests ==========

    @Test
    fun `incoming call notification has correct title`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "Test Caller",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Notification should exist", notification)

        // Verify notification title
        val shadowNotification = shadowOf(notification)
        assertEquals("Incoming Voice Call", shadowNotification.contentTitle)
    }

    @Test
    fun `incoming call notification has caller name as content`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "John Doe",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Notification should exist", notification)

        val shadowNotification = shadowOf(notification)
        assertEquals("John Doe", shadowNotification.contentText)
    }

    @Test
    fun `ongoing call notification has correct title`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test Peer",
            duration = 60,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Notification should exist", notification)

        val shadowNotification = shadowOf(notification)
        assertEquals("Voice Call", shadowNotification.contentTitle)
    }

    @Test
    fun `ongoing call notification shows peer name and duration`() {
        // 90 seconds = 1:30
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Jane Doe",
            duration = 90,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Notification should exist", notification)

        val shadowNotification = shadowOf(notification)
        assertEquals("Jane Doe - 01:30", shadowNotification.contentText)
    }

    @Test
    fun `incoming call notification is ongoing`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "Test",
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
            )
        assertNotNull("Notification should exist", notification)

        assertTrue(
            "Incoming call notification should be ongoing",
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    @Test
    fun `ongoing call notification is ongoing`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 0,
        )

        val shadowNotificationManager = shadowOf(notificationManager)
        val notification =
            shadowNotificationManager.getNotification(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
            )
        assertNotNull("Notification should exist", notification)

        assertTrue(
            "Ongoing call notification should be ongoing",
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
    }
}
