package network.columba.app.notifications

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Unit tests for CallNotificationHelper: the app-process side of voice-call
 * notifications, i.e. the ringing (incoming) notification with its full-screen
 * intent and answer/decline actions.
 *
 * The ongoing-call notification is not covered here anymore: it is posted by the
 * `:reticulum` service as its foreground notification (a CallStyle notification
 * must be enqueued through startForeground), so its tests live with the service.
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
    fun `creates only the incoming call channel`() {
        helper = CallNotificationHelper(context)

        val channels = notificationManager.notificationChannels
        assertTrue(
            "Should create incoming call channel",
            channels.any { it.id == "incoming_calls" },
        )
        assertFalse(
            "Ongoing-call channel belongs to the service notification",
            channels.any { it.id == "ongoing_calls" },
        )
    }

    // ========== Incoming Call Notification Tests ==========

    @Test
    fun `showIncomingCallNotification posts notification with correct ID`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123def456789012345678901234567890",
            callerName = "Test Caller",
        )

        val notification =
            shadowOf(notificationManager).getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL)
        assertNotNull("Should post notification with incoming call ID", notification)
    }

    @Test
    fun `showIncomingCallNotification with null name falls back to formatted hash`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123def456789012345678901234567890",
            callerName = null,
        )

        val notification =
            shadowOf(notificationManager).getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL)
        assertNotNull(notification)
        assertEquals("abc123...567890", notification.extras.getCharSequence("android.text"))
    }

    @Test
    fun `showIncomingCallNotification respects permission`() {
        val app = shadowOf(context as Application)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        helper = CallNotificationHelper(context)

        helper.showIncomingCallNotification(identityHash = "abc123", callerName = "Test")

        val notification =
            shadowOf(notificationManager).getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL)
        assertNull("Should not post notification without permission", notification)
    }

    @Test
    fun `incoming call notification is ongoing`() {
        helper.showIncomingCallNotification(identityHash = "abc123", callerName = "Test")

        val notification =
            shadowOf(notificationManager).getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL)
        assertNotNull(notification)
        assertTrue(
            "Incoming call notification should be ongoing",
            notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
    }

    @Test
    fun `cancelIncomingCallNotification cancels correct notification`() {
        helper.showIncomingCallNotification(identityHash = "abc123", callerName = "Test")
        val shadowNotificationManager = shadowOf(notificationManager)
        assertNotNull(
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL),
        )

        helper.cancelIncomingCallNotification()

        assertNull(
            "Notification should be cancelled",
            shadowNotificationManager.getNotification(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL),
        )
    }

    // ========== Full-Screen Intent Permission Tests ==========

    @Test
    fun `full screen intents usable when preflight and appop both allow`() {
        assertTrue(
            helper.isFullScreenIntentUsable(
                preflightAllowed = true,
                appOpMode = AppOpsManager.MODE_ALLOWED,
            ),
        )
    }

    @Test
    fun `full screen intents not usable when appop at default mode`() {
        assertFalse(
            helper.isFullScreenIntentUsable(
                preflightAllowed = true,
                appOpMode = AppOpsManager.MODE_DEFAULT,
            ),
        )
    }

    @Test
    fun `full screen intents not usable when appop ignored`() {
        assertFalse(
            helper.isFullScreenIntentUsable(
                preflightAllowed = true,
                appOpMode = AppOpsManager.MODE_IGNORED,
            ),
        )
    }

    @Test
    fun `full screen intents not usable when preflight check fails`() {
        assertFalse(
            helper.isFullScreenIntentUsable(
                preflightAllowed = false,
                appOpMode = AppOpsManager.MODE_ALLOWED,
            ),
        )
    }
}
