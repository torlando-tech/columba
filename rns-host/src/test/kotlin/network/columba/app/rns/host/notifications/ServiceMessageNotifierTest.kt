package network.columba.app.rns.host.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.database.sqlite.SQLiteException
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import network.columba.app.rns.api.notify.MessageNotificationBridge
import network.columba.app.rns.host.persistence.ReceivedMessagePersist
import network.columba.app.rns.host.persistence.ServicePersistenceManager
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage of the `:reticulum`-side notification path: this is the only poster while the
 * app process is frozen, killed, or not started yet (post-reboot delivery).
 *
 * Every gate here is a "must not post" that the app-side path also has, plus the two
 * cross-process ones: the foreground heartbeat (the app owns the notification then) and
 * the compare-and-set claim (exactly one of the two posters wins).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceMessageNotifierTest {
    private lateinit var context: Context
    private lateinit var persistence: ServicePersistenceManager
    private lateinit var accessor: ServiceSettingsAccessor
    private lateinit var notifier: ServiceMessageNotifier
    private val posted = mutableListOf<Notification>()
    private val postedTags = mutableListOf<String>()
    private val postOrder = mutableListOf<String>()

    private val inserted =
        ReceivedMessagePersist.Inserted(
            identityHash = "identity",
            messageId = "message-id",
            peerHash = "peer-hash",
            peerName = "Alice",
            preview = "Secret preview",
            threadIdHex = "00112233",
            isSavedPeer = false,
        )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // SharedPreferences outlive a single Robolectric test; start from nothing so the
        // "app never wrote the mirror" case is not masked by a previous test's write.
        @Suppress("DEPRECATION")
        context
            .getSharedPreferences(MessageNotificationBridge.CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)
            .edit()
            .clear()
            .commit()
        persistence = mockk()
        accessor = ServiceSettingsAccessor(context)
        posted.clear()
        postedTags.clear()
        postOrder.clear()

        coEvery { persistence.claimNotification(any(), any()) } answers {
            postOrder += "claim"
            true
        }
        coEvery { persistence.releaseNotification(any(), any()) } returns Unit

        ServiceMessageNotifier.postOverride = { tag, _, notification ->
            postOrder += "post"
            postedTags += tag
            posted += notification
        }
        ServiceMessageNotifier.notificationsEnabledOverride = { true }

        notifier =
            ServiceMessageNotifier(
                context = context,
                persistence = persistence,
                settingsAccessor = accessor,
                now = { FROZEN_NOW },
            )
    }

    @After
    fun tearDown() {
        ServiceMessageNotifier.postOverride = null
        ServiceMessageNotifier.notificationsEnabledOverride = null
        @Suppress("DEPRECATION")
        context
            .getSharedPreferences(MessageNotificationBridge.CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)
            .edit()
            .clear()
            .commit()
    }

    private fun mirror(
        enabled: Boolean = true,
        receivedMessage: Boolean = true,
        favoriteAllowed: Boolean = true,
        revealPreview: Boolean = true,
        revealSender: Boolean = true,
        uiForegroundAt: Long = 0L,
    ) {
        @Suppress("DEPRECATION")
        context
            .getSharedPreferences(MessageNotificationBridge.CROSS_PROCESS_PREFS_NAME, Context.MODE_MULTI_PROCESS)
            .edit()
            .putBoolean(MessageNotificationBridge.KEY_MIRROR_WRITTEN, true)
            .putBoolean(MessageNotificationBridge.KEY_NOTIFICATIONS_ENABLED, enabled)
            .putBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE, receivedMessage)
            .putBoolean(MessageNotificationBridge.KEY_RECEIVED_MESSAGE_FAVORITE, favoriteAllowed)
            .putBoolean(MessageNotificationBridge.KEY_REVEAL_PREVIEW, revealPreview)
            .putBoolean(MessageNotificationBridge.KEY_REVEAL_SENDER, revealSender)
            .putLong(MessageNotificationBridge.KEY_UI_FOREGROUND_AT, uiForegroundAt)
            .apply()
    }

    private fun notificationDump(notification: Notification): String =
        listOfNotNull(
            notification.extras.toString(),
            notification.publicVersion?.extras?.toString(),
        ).joinToString("\n")

    @Test
    fun `an unwritten mirror from the app process posts nothing`() =
        runBlocking {
            // A fresh install that rebooted before ever launching this build: the app's
            // privacy level is unknowable here, so the service must stay silent.
            notifier.notifyReceivedMessage(inserted)

            assertTrue(posted.isEmpty())
            coVerify(exactly = 0) { persistence.claimNotification(any(), any()) }
        }

    @Test
    fun `claim happens before the post`() =
        runBlocking {
            mirror()
            notifier.notifyReceivedMessage(inserted)

            assertEquals(listOf("claim", "post"), postOrder)
            assertEquals(MessageNotificationBridge.tag("peer-hash"), postedTags.single())
        }

    @Test
    fun `master toggle off posts nothing and never claims`() =
        runBlocking {
            mirror(enabled = false)
            notifier.notifyReceivedMessage(inserted)

            assertTrue(posted.isEmpty())
            coVerify(exactly = 0) { persistence.claimNotification(any(), any()) }
        }

    @Test
    fun `saved-peer preference only applies to saved peers`() =
        runBlocking {
            mirror(receivedMessage = false, favoriteAllowed = true)

            notifier.notifyReceivedMessage(inserted.copy(isSavedPeer = false))
            assertTrue("non-saved peer is gated by the general preference", posted.isEmpty())

            notifier.notifyReceivedMessage(inserted.copy(isSavedPeer = true))
            assertEquals("saved peer is covered by either preference", 1, posted.size)

            posted.clear()
            mirror(receivedMessage = true, favoriteAllowed = false)
            notifier.notifyReceivedMessage(inserted.copy(isSavedPeer = true))
            assertEquals(1, posted.size)
        }

    @Test
    fun `notification permission denial posts nothing`() =
        runBlocking {
            mirror()
            ServiceMessageNotifier.notificationsEnabledOverride = { false }

            notifier.notifyReceivedMessage(inserted)

            assertTrue(posted.isEmpty())
            coVerify(exactly = 0) { persistence.claimNotification(any(), any()) }
        }

    @Test
    fun `fresh foreground heartbeat leaves the message to the app process`() =
        runBlocking {
            mirror(uiForegroundAt = FROZEN_NOW - 1_000L)

            notifier.notifyReceivedMessage(inserted)

            assertTrue("the app posts this one, with its chat/thread suppression", posted.isEmpty())
            coVerify(exactly = 0) { persistence.claimNotification(any(), any()) }
        }

    @Test
    fun `stale heartbeat means the service owns the post`() =
        runBlocking {
            mirror(uiForegroundAt = FROZEN_NOW - MessageNotificationBridge.UI_FOREGROUND_STALE_AFTER_MS - 1)

            notifier.notifyReceivedMessage(inserted)

            assertEquals(1, posted.size)
        }

    @Test
    fun `losing the claim posts nothing`() =
        runBlocking {
            mirror()
            coEvery { persistence.claimNotification(any(), any()) } returns false

            notifier.notifyReceivedMessage(inserted)

            assertTrue(posted.isEmpty())
        }

    @Test
    fun `a database failure while claiming does not escape into the message loop`() =
        runBlocking {
            mirror()
            coEvery { persistence.claimNotification(any(), any()) } throws SQLiteException("database is locked")

            // Advisory only: the service's receive loop must survive a failed claim.
            notifier.notifyReceivedMessage(inserted)

            assertTrue(posted.isEmpty())
        }

    @Test
    fun `failed post releases the claim so the message is retried`() =
        runBlocking {
            mirror()
            ServiceMessageNotifier.postOverride = { _, _, _ -> throw SecurityException("permission revoked") }

            notifier.notifyReceivedMessage(inserted)

            assertTrue("nothing reached the notification manager", posted.isEmpty())
            coVerify(exactly = 1) { persistence.releaseNotification("message-id", "identity") }
        }

    @Test
    fun `full privacy shows the sender and the preview`() =
        runBlocking {
            mirror()
            notifier.notifyReceivedMessage(inserted)

            val notification = posted.single()
            assertEquals("Alice", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Secret preview", notification.extras.getString(Notification.EXTRA_TEXT))
            assertNull("no redacted twin needed at FULL", notification.publicVersion)
        }

    @Test
    fun `name-only privacy never carries the preview`() =
        runBlocking {
            mirror(revealPreview = false)
            notifier.notifyReceivedMessage(inserted)

            val notification = posted.single()
            assertEquals("Alice", notification.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(notificationDump(notification).contains("Secret preview"))
            assertNotNull("name-only is VISIBILITY_PRIVATE", notification.publicVersion)
        }

    @Test
    fun `none privacy reveals neither sender nor preview even on the public version`() =
        runBlocking {
            mirror(revealPreview = false, revealSender = false)
            notifier.notifyReceivedMessage(inserted)

            val dump = notificationDump(posted.single())
            assertFalse(dump.contains("Alice"))
            assertFalse(dump.contains("Secret preview"))
            assertTrue(dump.contains(context.getString(network.columba.app.rns.host.R.string.notif_generic_message)))
        }

    @Test
    fun `content intent targets the app main activity with the conversation deep link`() =
        runBlocking {
            mirror()
            notifier.notifyReceivedMessage(inserted)

            val intent =
                org.robolectric.Shadows
                    .shadowOf(posted.single().contentIntent)
                    .savedIntent
            assertEquals(MessageNotificationBridge.ACTION_OPEN_CONVERSATION, intent.action)
            assertEquals("peer-hash", intent.getStringExtra(MessageNotificationBridge.EXTRA_DESTINATION_HASH))
            assertEquals("00112233", intent.getStringExtra(MessageNotificationBridge.EXTRA_THREAD_ID))
            assertEquals(
                MessageNotificationBridge.MAIN_ACTIVITY_CLASS,
                intent.component?.className,
            )
        }

    @Test
    fun `createChannels creates the shared messages channel`() {
        notifier.createChannels()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(MessageNotificationBridge.CHANNEL_ID_MESSAGES)
        assertNotNull("the service must own the channel before the app ever runs", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    private companion object {
        const val FROZEN_NOW = 1_700_000_000_000L
    }
}
