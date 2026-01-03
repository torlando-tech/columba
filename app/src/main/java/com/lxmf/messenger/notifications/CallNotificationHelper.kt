package com.lxmf.messenger.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for managing voice call notifications.
 *
 * Handles:
 * - Incoming call notifications with full-screen intent, answer/decline actions
 * - Ongoing call notifications with end call action
 */
@Singleton
class CallNotificationHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            // Notification channel IDs
            private const val CHANNEL_ID_INCOMING_CALL = "incoming_calls"
            private const val CHANNEL_ID_ONGOING_CALL = "ongoing_calls"

            // Notification IDs
            const val NOTIFICATION_ID_INCOMING_CALL = 5000
            const val NOTIFICATION_ID_ONGOING_CALL = 5001

            // Intent actions
            const val ACTION_ANSWER_CALL = "com.lxmf.messenger.ACTION_ANSWER_CALL"
            const val ACTION_DECLINE_CALL = "com.lxmf.messenger.ACTION_DECLINE_CALL"
            const val ACTION_END_CALL = "com.lxmf.messenger.ACTION_END_CALL"
            const val ACTION_OPEN_CALL = "com.lxmf.messenger.ACTION_OPEN_CALL"

            // Intent extras
            const val EXTRA_IDENTITY_HASH = "identity_hash"
            const val EXTRA_CALLER_NAME = "caller_name"
        }

        private val notificationManager = NotificationManagerCompat.from(context)

        init {
            createNotificationChannels()
        }

        /**
         * Create notification channels for call notifications.
         */
        private fun createNotificationChannels() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Incoming call channel - high importance for full-screen intent
                val incomingChannel =
                    NotificationChannel(
                        CHANNEL_ID_INCOMING_CALL,
                        "Incoming Calls",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Notifications for incoming voice calls"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 500, 200, 500)
                        setSound(null, null) // We'll handle ringtone separately
                        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                        setBypassDnd(true)
                    }

                // Ongoing call channel - low importance, persistent
                val ongoingChannel =
                    NotificationChannel(
                        CHANNEL_ID_ONGOING_CALL,
                        "Ongoing Calls",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Notifications for active voice calls"
                        enableVibration(false)
                        setSound(null, null)
                    }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(incomingChannel)
                manager.createNotificationChannel(ongoingChannel)
            }
        }

        /**
         * Show notification for incoming call.
         *
         * @param identityHash The caller's identity hash
         * @param callerName Display name of the caller
         */
        fun showIncomingCallNotification(
            identityHash: String,
            callerName: String?,
        ) {
            val displayName = callerName ?: formatIdentityHash(identityHash)

            // Full-screen intent to open incoming call screen
            val fullScreenIntent =
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_OPEN_CALL
                    putExtra(EXTRA_IDENTITY_HASH, identityHash)
                    putExtra(EXTRA_CALLER_NAME, displayName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }

            val fullScreenPendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    fullScreenIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // Answer action - use Activity intent directly for reliability
            // Use SINGLE_TOP to reuse existing activity via onNewIntent instead of recreating
            val answerIntent =
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_ANSWER_CALL
                    putExtra(EXTRA_IDENTITY_HASH, identityHash)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            val answerPendingIntent =
                PendingIntent.getActivity(
                    context,
                    1,
                    answerIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // Decline action
            val declineIntent =
                Intent(context, CallActionReceiver::class.java).apply {
                    action = ACTION_DECLINE_CALL
                    putExtra(EXTRA_IDENTITY_HASH, identityHash)
                }
            val declinePendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    2,
                    declineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_INCOMING_CALL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Incoming Voice Call")
                    .setContentText(displayName)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Decline",
                        declinePendingIntent,
                    ).addAction(
                        android.R.drawable.ic_menu_call,
                        "Answer",
                        answerPendingIntent,
                    ).build()

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(NOTIFICATION_ID_INCOMING_CALL, notification)
            }
        }

        /**
         * Show notification for ongoing call.
         *
         * @param identityHash The peer's identity hash
         * @param peerName Display name of the peer
         * @param duration Current call duration in seconds
         */
        fun showOngoingCallNotification(
            identityHash: String,
            peerName: String?,
            duration: Long,
        ) {
            val displayName = peerName ?: formatIdentityHash(identityHash)
            val durationText = formatDuration(duration)

            // Open call screen on tap
            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_OPEN_CALL
                    putExtra(EXTRA_IDENTITY_HASH, identityHash)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            val openPendingIntent =
                PendingIntent.getActivity(
                    context,
                    3,
                    openIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // End call action
            val endIntent =
                Intent(context, CallActionReceiver::class.java).apply {
                    action = ACTION_END_CALL
                    putExtra(EXTRA_IDENTITY_HASH, identityHash)
                }
            val endPendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    4,
                    endIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_ONGOING_CALL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Voice Call")
                    .setContentText("$displayName - $durationText")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(openPendingIntent)
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "End Call",
                        endPendingIntent,
                    ).build()

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(NOTIFICATION_ID_ONGOING_CALL, notification)
            }
        }

        /**
         * Cancel incoming call notification.
         */
        fun cancelIncomingCallNotification() {
            notificationManager.cancel(NOTIFICATION_ID_INCOMING_CALL)
        }

        /**
         * Cancel ongoing call notification.
         */
        fun cancelOngoingCallNotification() {
            notificationManager.cancel(NOTIFICATION_ID_ONGOING_CALL)
        }

        /**
         * Cancel all call notifications.
         */
        fun cancelAllCallNotifications() {
            cancelIncomingCallNotification()
            cancelOngoingCallNotification()
        }

        private fun formatIdentityHash(hash: String): String {
            return if (hash.length > 12) {
                "${hash.take(6)}...${hash.takeLast(6)}"
            } else {
                hash
            }
        }

        private fun formatDuration(seconds: Long): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
