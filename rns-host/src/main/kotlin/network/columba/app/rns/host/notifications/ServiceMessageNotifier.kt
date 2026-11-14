package network.columba.app.rns.host.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import network.columba.app.rns.api.notify.MessageNotificationBridge
import network.columba.app.rns.host.R
import network.columba.app.rns.host.persistence.NotificationMirror
import network.columba.app.rns.host.persistence.ReceivedMessagePersist
import network.columba.app.rns.host.persistence.ServicePersistenceManager
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor

/**
 * Posts received-message notifications from the `:reticulum` process.
 *
 * The app process posts the same notification while it is alive (its
 * `MessageCollector` reconciler, which also owns foreground/thread suppression).
 * This class exists for everything else: backgrounded with the process frozen,
 * killed, or never started after a reboot. Exactly one poster wins each message
 * because the notification is *claimed* here — a conditional UPDATE on
 * `messages.notificationHandled` — before anything is posted.
 *
 * Rendering deliberately mirrors `NotificationHelper` (same channel, tag, id,
 * icon, category, privacy levels and deep link) so a notification posted here is
 * indistinguishable from one posted by the app.
 */
class ServiceMessageNotifier(
    private val context: Context,
    private val persistence: ServicePersistenceManager,
    private val settingsAccessor: ServiceSettingsAccessor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal companion object {
        private const val TAG = "ServiceMessageNotifier"

        /**
         * Test seams. Production always uses the real poster and the real permission
         * check; tests need to force a throw (claim release) and a denied permission
         * without an Android permission manager.
         */
        @VisibleForTesting
        var postOverride: ((String, Int, Notification) -> Unit)? = null

        @VisibleForTesting
        var notificationsEnabledOverride: (() -> Boolean)? = null
    }

    /**
     * Create the message channel. Must run in this process too: on API 26+ a
     * notification posted to a channel that does not exist is silently dropped,
     * and the app process may not have run yet (post-reboot delivery).
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                MessageNotificationBridge.CHANNEL_ID_MESSAGES,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_messages_desc)
                enableVibration(true)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Post the notification for a message this process just stored.
     *
     * Gates, in order: master toggle, per-type preference, runtime permission,
     * "the UI is foregrounded (so it owns this)", and the claim itself.
     */
    suspend fun notifyReceivedMessage(inserted: ReceivedMessagePersist.Inserted) {
        val mirror =
            settingsAccessor.getNotificationMirror()
                ?: run {
                    // The app process has never written the mirror (booted on this build before
                    // its first launch). Its settings are unknowable here, so stay silent and
                    // let the app's reconciler post once it runs.
                    Log.i(TAG, "Notification mirror not written yet; not posting")
                    return
                }

        if (!gatesAllow(inserted, mirror)) return
        try {
            claimAndPost(inserted, mirror)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A notification is advisory: a failing claim/DB read must not propagate into
            // the service's message loop, which would stop message delivery entirely.
            Log.w(TAG, "Notification attempt failed for ${inserted.messageId.take(16)}", e)
        }
    }

    /**
     * Whether this process may post for [inserted]: master toggle, per-type preference,
     * runtime permission, and "the UI is foregrounded, so it owns this".
     */
    private fun gatesAllow(
        inserted: ReceivedMessagePersist.Inserted,
        mirror: NotificationMirror,
    ): Boolean {
        // A saved peer is covered by either preference (same rule as the app).
        val preferenceAllows =
            if (inserted.isSavedPeer) {
                mirror.receivedMessage || mirror.favoriteAllowed
            } else {
                mirror.receivedMessage
            }
        val foregrounded = settingsAccessor.isUiForeground(now())
        if (foregrounded) {
            // Its reconciler claims and posts with full chat/thread suppression. Leaving the
            // row unclaimed is required — claiming here would silence a notification the
            // user has not seen.
            Log.d(TAG, "UI foregrounded; leaving ${inserted.messageId.take(16)} to the app process")
        }
        return mirror.enabled && preferenceAllows && hasNotificationPermission() && !foregrounded
    }

    /** Claim the notification, then post it; undo the claim when the post fails. */
    private suspend fun claimAndPost(
        inserted: ReceivedMessagePersist.Inserted,
        mirror: NotificationMirror,
    ) {
        if (!persistence.claimNotification(inserted.messageId, inserted.identityHash)) {
            // Already claimed by the app's reconciler, or by an earlier pass.
            Log.d(TAG, "Notification for ${inserted.messageId.take(16)} already claimed")
            return
        }

        try {
            post(inserted, mirror)
            Log.d(TAG, "Posted notification for ${inserted.messageId.take(16)}")
        } catch (e: Exception) {
            // The claim must not outlive a failed post: release it so the app (or the
            // next service pass) retries instead of losing the message silently.
            Log.w(TAG, "Could not post notification for ${inserted.messageId.take(16)}", e)
            persistence.releaseNotification(inserted.messageId, inserted.identityHash)
        }
    }

    private fun post(
        inserted: ReceivedMessagePersist.Inserted,
        mirror: NotificationMirror,
    ) {
        val contentIntent = buildContentIntent(inserted)
        val genericText = context.getString(R.string.notif_generic_message)
        val revealsBoth = mirror.revealSender && mirror.revealPreview
        val title: String
        val text: String?
        when {
            revealsBoth -> {
                title = inserted.peerName
                text = inserted.preview
            }
            mirror.revealSender -> {
                title = inserted.peerName
                text = genericText
            }
            else -> {
                title = genericText
                text = null
            }
        }

        val builder =
            NotificationCompat
                .Builder(context, MessageNotificationBridge.CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification_stat)
                .setContentTitle(title)
                .apply { text?.let { setContentText(it) } }
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)

        if (!revealsBoth) {
            // Marks the notification sensitive on a secure lock screen and supplies a
            // redacted twin. The preview is never part of either version.
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            builder.setPublicVersion(
                NotificationCompat
                    .Builder(context, MessageNotificationBridge.CHANNEL_ID_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification_stat)
                    .setContentTitle(title)
                    .apply { text?.let { setContentText(it) } }
                    .setContentIntent(contentIntent)
                    .build(),
            )
        }

        val tag = MessageNotificationBridge.tag(inserted.peerHash)
        val notification = builder.build()
        val override = postOverride
        if (override != null) {
            override(tag, MessageNotificationBridge.NOTIFICATION_ID_MESSAGE, notification)
        } else {
            NotificationManagerCompat.from(context)
                .notify(tag, MessageNotificationBridge.NOTIFICATION_ID_MESSAGE, notification)
        }
    }

    /**
     * Same deep link the app builds, created by class name because `:rns-host` cannot
     * reference the app's `MainActivity` type.
     */
    private fun buildContentIntent(inserted: ReceivedMessagePersist.Inserted): PendingIntent {
        val openIntent =
            Intent()
                .setClassName(context.packageName, MessageNotificationBridge.MAIN_ACTIVITY_CLASS)
                .apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = MessageNotificationBridge.ACTION_OPEN_CONVERSATION
                    data =
                        Uri
                            .Builder()
                            .scheme("columba")
                            .authority("conversation")
                            .appendPath(inserted.peerHash)
                            .build()
                    putExtra(MessageNotificationBridge.EXTRA_DESTINATION_HASH, inserted.peerHash)
                    putExtra(MessageNotificationBridge.EXTRA_PEER_NAME, inserted.peerName)
                    inserted.threadIdHex?.let {
                        putExtra(MessageNotificationBridge.EXTRA_THREAD_ID, it)
                    }
                }
        return PendingIntent.getActivity(
            context,
            inserted.peerHash.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** POST_NOTIFICATIONS (API 33+) plus the user-level "notifications off" switch. */
    private fun hasNotificationPermission(): Boolean {
        notificationsEnabledOverride?.let { return it() }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
