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
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for managing notifications.
 * Handles notification channels, posting notifications, and checking preferences.
 */
@Singleton
class NotificationHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val activeConversationManager: com.lxmf.messenger.service.ActiveConversationManager,
    ) {
        companion object {
            // Notification channel IDs
            private const val CHANNEL_ID_MESSAGES = "messages"
            private const val CHANNEL_ID_ANNOUNCES = "announces"
            private const val CHANNEL_ID_BLE_EVENTS = "ble_events"

            // Notification IDs
            private const val NOTIFICATION_ID_MESSAGE = 1000
            private const val NOTIFICATION_ID_ANNOUNCE = 2000
            private const val NOTIFICATION_ID_BLE = 3000

            // Intent actions
            const val ACTION_OPEN_ANNOUNCE = "com.lxmf.messenger.ACTION_OPEN_ANNOUNCE"
            const val ACTION_OPEN_CONVERSATION = "com.lxmf.messenger.ACTION_OPEN_CONVERSATION"
            private const val ACTION_REPLY = "com.lxmf.messenger.ACTION_REPLY"
            private const val ACTION_MARK_READ = "com.lxmf.messenger.ACTION_MARK_READ"

            // Intent extras
            const val EXTRA_DESTINATION_HASH = "destination_hash"
            const val EXTRA_PEER_NAME = "peer_name"
        }

        private val notificationManager = NotificationManagerCompat.from(context)

        init {
            createNotificationChannels()
        }

        /**
         * Create notification channels for different notification types.
         */
        private fun createNotificationChannels() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val messagesChannel =
                    NotificationChannel(
                        CHANNEL_ID_MESSAGES,
                        "Messages",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Notifications for received messages"
                        enableVibration(true)
                    }

                val announcesChannel =
                    NotificationChannel(
                        CHANNEL_ID_ANNOUNCES,
                        "Announces",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "Notifications for heard announces"
                        enableVibration(false)
                    }

                val bleEventsChannel =
                    NotificationChannel(
                        CHANNEL_ID_BLE_EVENTS,
                        "Bluetooth Events",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Notifications for Bluetooth connection events"
                        enableVibration(false)
                    }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(messagesChannel)
                manager.createNotificationChannel(announcesChannel)
                manager.createNotificationChannel(bleEventsChannel)
            }
        }

        /**
         * Check if we have notification permission (Android 13+).
         */
        private fun hasNotificationPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // No permission needed for Android 12 and below
            }
        }

        /**
         * Post a notification for a received message.
         *
         * @param destinationHash The destination hash of the sender
         * @param peerName The display name of the sender
         * @param messagePreview Preview text of the message
         * @param isFavorite Whether the sender is a saved peer
         */
        suspend fun notifyMessageReceived(
            destinationHash: String,
            peerName: String,
            messagePreview: String,
            isFavorite: Boolean,
        ) {
            // Check master notification toggle
            val notificationsEnabled = settingsRepository.notificationsEnabledFlow.first()
            if (!notificationsEnabled) return

            // Check specific notification preference
            val messageNotificationsEnabled =
                if (isFavorite) {
                    // If it's a favorite, check both general messages and favorite messages
                    settingsRepository.notificationReceivedMessageFlow.first() ||
                        settingsRepository.notificationReceivedMessageFavoriteFlow.first()
                } else {
                    settingsRepository.notificationReceivedMessageFlow.first()
                }

            if (!messageNotificationsEnabled) return

            // Check permission
            if (!hasNotificationPermission()) return

            // Suppress notification if this conversation is currently active (visible on screen)
            if (activeConversationManager.activeConversation.value == destinationHash) {
                android.util.Log.d("NotificationHelper", "Suppressing notification for active conversation: $peerName")
                return
            }

            // Create intent to open the conversation
            // Use SINGLE_TOP to reuse existing activity via onNewIntent (avoids splash screen flash)
            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = ACTION_OPEN_CONVERSATION
                    putExtra(EXTRA_DESTINATION_HASH, destinationHash)
                    putExtra(EXTRA_PEER_NAME, peerName)
                }

            val openPendingIntent =
                PendingIntent.getActivity(
                    context,
                    destinationHash.hashCode(),
                    openIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // Create notification
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(peerName)
                    .setContentText(messagePreview)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(openPendingIntent)
                    .build()

            try {
                notificationManager.notify(
                    NOTIFICATION_ID_MESSAGE + destinationHash.hashCode(),
                    notification,
                )
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        /**
         * Post a notification for a heard announce.
         *
         * @param destinationHash The destination hash of the announcing peer
         * @param peerName The display name of the announcing peer
         * @param appData Optional app data from the announce
         */
        suspend fun notifyAnnounceHeard(
            destinationHash: String,
            peerName: String,
            appData: String? = null,
        ) {
            // Check master notification toggle
            val notificationsEnabled = settingsRepository.notificationsEnabledFlow.first()
            if (!notificationsEnabled) return

            // Check specific notification preference
            val announceNotificationsEnabled = settingsRepository.notificationHeardAnnounceFlow.first()
            if (!announceNotificationsEnabled) return

            // Check permission
            if (!hasNotificationPermission()) return

            // Create intent to open announce detail
            // Use SINGLE_TOP to reuse existing activity via onNewIntent (avoids splash screen flash)
            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = ACTION_OPEN_ANNOUNCE
                    putExtra(EXTRA_DESTINATION_HASH, destinationHash)
                }

            val openPendingIntent =
                PendingIntent.getActivity(
                    context,
                    destinationHash.hashCode(),
                    openIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // Build notification text
            val contentText =
                if (appData != null) {
                    "Announce received with data"
                } else {
                    "Announce received"
                }

            // Create notification
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID_ANNOUNCES)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Announce from $peerName")
                    .setContentText(contentText)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(openPendingIntent)
                    .build()

            try {
                notificationManager.notify(
                    NOTIFICATION_ID_ANNOUNCE + destinationHash.hashCode(),
                    notification,
                )
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        /**
         * Post a notification for a BLE peer connection.
         *
         * @param peerAddress The Bluetooth address of the connected peer
         * @param peerName The name of the connected peer (if available)
         */
        suspend fun notifyBleConnected(
            peerAddress: String,
            peerName: String? = null,
        ) {
            // Check master notification toggle
            val notificationsEnabled = settingsRepository.notificationsEnabledFlow.first()
            if (!notificationsEnabled) return

            // Check specific notification preference
            val bleConnectedEnabled = settingsRepository.notificationBleConnectedFlow.first()
            if (!bleConnectedEnabled) return

            // Check permission
            if (!hasNotificationPermission()) return

            val displayName = peerName ?: peerAddress

            // Create notification
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID_BLE_EVENTS)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("BLE Peer Connected")
                    .setContentText("Connected to $displayName")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .build()

            try {
                notificationManager.notify(
                    NOTIFICATION_ID_BLE + peerAddress.hashCode(),
                    notification,
                )
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        /**
         * Post a notification for a BLE peer disconnection.
         *
         * @param peerAddress The Bluetooth address of the disconnected peer
         * @param peerName The name of the disconnected peer (if available)
         */
        suspend fun notifyBleDisconnected(
            peerAddress: String,
            peerName: String? = null,
        ) {
            // Check master notification toggle
            val notificationsEnabled = settingsRepository.notificationsEnabledFlow.first()
            if (!notificationsEnabled) return

            // Check specific notification preference
            val bleDisconnectedEnabled = settingsRepository.notificationBleDisconnectedFlow.first()
            if (!bleDisconnectedEnabled) return

            // Check permission
            if (!hasNotificationPermission()) return

            val displayName = peerName ?: peerAddress

            // Create notification
            val notification =
                NotificationCompat.Builder(context, CHANNEL_ID_BLE_EVENTS)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("BLE Peer Disconnected")
                    .setContentText("Disconnected from $displayName")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .build()

            try {
                notificationManager.notify(
                    NOTIFICATION_ID_BLE + peerAddress.hashCode(),
                    notification,
                )
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        /**
         * Cancel a specific notification.
         *
         * @param notificationId The ID of the notification to cancel
         */
        fun cancelNotification(notificationId: Int) {
            notificationManager.cancel(notificationId)
        }

        /**
         * Cancel all notifications.
         */
        fun cancelAllNotifications() {
            notificationManager.cancelAll()
        }
    }
