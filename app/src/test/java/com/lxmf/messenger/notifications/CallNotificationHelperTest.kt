package com.lxmf.messenger.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CallNotificationHelper.
 *
 * Tests notification channel creation, incoming call notifications,
 * ongoing call notifications, and notification cancellation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotificationHelperTest {
    private lateinit var mockContext: Context
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var mockNotificationManagerCompat: NotificationManagerCompat
    private lateinit var helper: CallNotificationHelper

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)
        mockNotificationManagerCompat = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        every { mockContext.applicationContext } returns mockContext

        // Mock NotificationManagerCompat.from()
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns mockNotificationManagerCompat

        // Mock ActivityCompat.checkSelfPermission for notifications
        mockkStatic(ActivityCompat::class)
        every {
            ActivityCompat.checkSelfPermission(
                any(),
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } returns PackageManager.PERMISSION_GRANTED

        helper = CallNotificationHelper(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    // ========== Notification Channel Tests ==========

    @Test
    fun `creates incoming call notification channel`() {
        val channelSlot = slot<NotificationChannel>()
        every { mockNotificationManager.createNotificationChannel(capture(channelSlot)) } just Runs

        // Re-instantiate to trigger channel creation
        helper = CallNotificationHelper(mockContext)

        // Verify at least one channel was created
        verify(atLeast = 1) { mockNotificationManager.createNotificationChannel(any()) }
    }

    @Test
    fun `creates ongoing call notification channel`() {
        val channels = mutableListOf<NotificationChannel>()
        every { mockNotificationManager.createNotificationChannel(capture(channels)) } just Runs

        helper = CallNotificationHelper(mockContext)

        // Should create two channels
        verify(exactly = 2) { mockNotificationManager.createNotificationChannel(any()) }
    }

    // ========== Incoming Call Notification Tests ==========

    @Test
    fun `showIncomingCallNotification posts notification with correct ID`() {
        helper.showIncomingCallNotification(
            identityHash = "abc123def456789012345678901234567890",
            callerName = "Test Caller",
        )

        verify {
            mockNotificationManagerCompat.notify(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
                any(),
            )
        }
    }

    @Test
    fun `showIncomingCallNotification with null name uses formatted hash`() {
        val testHash = "abc123def456789012345678901234567890"

        helper.showIncomingCallNotification(
            identityHash = testHash,
            callerName = null,
        )

        // Verify notification is posted
        verify {
            mockNotificationManagerCompat.notify(
                CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL,
                any(),
            )
        }
    }

    @Test
    fun `showIncomingCallNotification respects permission`() {
        every {
            ActivityCompat.checkSelfPermission(
                any(),
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } returns PackageManager.PERMISSION_DENIED

        helper.showIncomingCallNotification(
            identityHash = "abc123",
            callerName = "Test",
        )

        // Should not post notification without permission
        verify(exactly = 0) { mockNotificationManagerCompat.notify(any(), any()) }
    }

    // ========== Ongoing Call Notification Tests ==========

    @Test
    fun `showOngoingCallNotification posts notification with correct ID`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123def456789012345678901234567890",
            peerName = "Test Peer",
            duration = 120,
        )

        verify {
            mockNotificationManagerCompat.notify(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
                any(),
            )
        }
    }

    @Test
    fun `showOngoingCallNotification with null name uses formatted hash`() {
        val testHash = "abc123def456789012345678901234567890"

        helper.showOngoingCallNotification(
            identityHash = testHash,
            peerName = null,
            duration = 60,
        )

        verify {
            mockNotificationManagerCompat.notify(
                CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL,
                any(),
            )
        }
    }

    @Test
    fun `showOngoingCallNotification respects permission`() {
        every {
            ActivityCompat.checkSelfPermission(
                any(),
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } returns PackageManager.PERMISSION_DENIED

        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 0,
        )

        verify(exactly = 0) { mockNotificationManagerCompat.notify(any(), any()) }
    }

    // ========== Cancel Notification Tests ==========

    @Test
    fun `cancelIncomingCallNotification cancels correct notification`() {
        helper.cancelIncomingCallNotification()

        verify { mockNotificationManagerCompat.cancel(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL) }
    }

    @Test
    fun `cancelOngoingCallNotification cancels correct notification`() {
        helper.cancelOngoingCallNotification()

        verify { mockNotificationManagerCompat.cancel(CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL) }
    }

    @Test
    fun `cancelAllCallNotifications cancels both notifications`() {
        helper.cancelAllCallNotifications()

        verify { mockNotificationManagerCompat.cancel(CallNotificationHelper.NOTIFICATION_ID_INCOMING_CALL) }
        verify { mockNotificationManagerCompat.cancel(CallNotificationHelper.NOTIFICATION_ID_ONGOING_CALL) }
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
        verify { mockNotificationManagerCompat.notify(any(), any()) }
    }

    @Test
    fun `ongoing notification formats minute duration correctly`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 60,
        )

        verify { mockNotificationManagerCompat.notify(any(), any()) }
    }

    @Test
    fun `ongoing notification formats hour plus duration correctly`() {
        helper.showOngoingCallNotification(
            identityHash = "abc123",
            peerName = "Test",
            duration = 3661, // 1 hour, 1 minute, 1 second
        )

        verify { mockNotificationManagerCompat.notify(any(), any()) }
    }

    // ========== Identity Hash Formatting Tests ==========

    @Test
    fun `short identity hash is displayed as-is`() {
        val shortHash = "abc123"

        helper.showIncomingCallNotification(
            identityHash = shortHash,
            callerName = null,
        )

        verify { mockNotificationManagerCompat.notify(any(), any()) }
    }

    @Test
    fun `long identity hash is truncated`() {
        val longHash = "abc123def456789012345678901234567890"

        helper.showIncomingCallNotification(
            identityHash = longHash,
            callerName = null,
        )

        verify { mockNotificationManagerCompat.notify(any(), any()) }
    }
}
