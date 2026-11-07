package network.columba.app.notifications

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
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import network.columba.app.MainActivity
import network.columba.app.R
import network.columba.app.data.model.InterfaceType
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val activeConversationManager: network.columba.app.service.ActiveConversationManager,
    ) {
        /**
         * Serialises reading the message privacy level and building/posting a message
         * notification against saving a stricter level and dropping message notifications
         * already posted. Without it, a post that read `FULL` could land in the shade after
         * the user tightened the level and cancelled everything.
         */
        private val messagePrivacyLock = Mutex()

        companion object {
            // Notification channel IDs
            private const val CHANNEL_ID_MESSAGES = "messages"
            private const val CHANNEL_ID_ANNOUNCES = "announces"
            private const val CHANNEL_ID_BLE_EVENTS = "ble_events"

            // Notification IDs
            @VisibleForTesting
            internal const val NOTIFICATION_ID_MESSAGE = 1000
            private const val NOTIFICATION_ID_ANNOUNCE = 2000
            private const val NOTIFICATION_ID_BLE = 3000
            private const val NOTIFICATION_TAG_MESSAGE_PREFIX = "message:"


            // Intent actions
            const val ACTION_OPEN_ANNOUNCE = "network.columba.app.ACTION_OPEN_ANNOUNCE"
            const val ACTION_OPEN_CONVERSATION = "network.columba.app.ACTION_OPEN_CONVERSATION"
            private const val ACTION_REPLY = "network.columba.app.ACTION_REPLY"
            private const val ACTION_MARK_READ = "network.columba.app.ACTION_MARK_READ"

            // Intent extras
            const val EXTRA_DESTINATION_HASH = "destination_hash"
            const val EXTRA_PEER_NAME = "peer_name"
            const val EXTRA_THREAD_ID = "thread_id"

            @VisibleForTesting
            internal fun messageNotificationTag(destinationHash: String): String =
                NOTIFICATION_TAG_MESSAGE_PREFIX + destinationHash
        }

        private val notificationManager = NotificationManagerCompat.from(context)

        @VisibleForTesting
        internal var isAppInForeground: () -> Boolean = {
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }

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
                        context.getString(R.string.notif_channel_messages),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.notif_channel_messages_desc)
                        enableVibration(true)
                    }

                val announcesChannel =
                    NotificationChannel(
                        CHANNEL_ID_ANNOUNCES,
                        context.getString(R.string.notif_channel_announces),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notif_channel_announces_desc)
                        enableVibration(false)
                    }

                val bleEventsChannel =
                    NotificationChannel(
                        CHANNEL_ID_BLE_EVENTS,
                        context.getString(R.string.notif_channel_ble),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.notif_channel_ble_desc)
                        enableVibration(false)
                    }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(messagesChannel)
                manager.createNotificationChannel(announcesChannel)
                manager.createNotificationChannel(bleEventsChannel)

                // Re-creating an existing channel id replaces its name/description with
                // the current-locale text on every app start (user-set importance is
                // preserved by the platform); channel ids never change.
            }
        }

        /**
         * Check if we have notification permission (Android 13+).
         */
        private fun hasNotificationPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // No permission needed for Android 12 and below
            }

        /**
         * Post a notification for a received message.
         *
         * @param destinationHash The destination hash of the sender
         * @param peerName The display name of the sender
         * @param messagePreview Preview text of the message
         * @param isFavorite Whether the sender is a saved peer
         * @param threadIdHex Thread the message belongs to, so tapping opens that thread
         */
        suspend fun notifyMessageReceived(
            destinationHash: String,
            peerName: String,
            messagePreview: String,
            isFavorite: Boolean,
            threadIdHex: String? = null,
        ) {
            if (!shouldNotifyMessage(destinationHash, isFavorite, threadIdHex)) return

            messagePrivacyLock.withLock {
                postMessageNotification(destinationHash, peerName, messagePreview, threadIdHex)
            }
        }

        /**
         * Build and post the message notification while [messagePrivacyLock] is held, so the
         * level read here cannot be overtaken by a tightening that lands after it.
         */
        private suspend fun postMessageNotification(
            destinationHash: String,
            peerName: String,
            messagePreview: String,
            threadIdHex: String?,
        ) {
            // Read once per post so a settings change applies to the next notification.
            val privacyLevel = settingsRepository.notificationPrivacyLevelFlow.first()

            // Create intent to open the conversation
            // Use SINGLE_TOP to reuse existing activity via onNewIntent (avoids splash screen flash)
            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = ACTION_OPEN_CONVERSATION
                    data =
                        Uri
                            .Builder()
                            .scheme("columba")
                            .authority("conversation")
                            .appendPath(destinationHash)
                            .build()
                    putExtra(EXTRA_DESTINATION_HASH, destinationHash)
                    putExtra(EXTRA_PEER_NAME, peerName)
                    // Tapping a thread message must land in that thread, not Main.
                    if (threadIdHex != null) putExtra(EXTRA_THREAD_ID, threadIdHex)
                }

            val openPendingIntent =
                PendingIntent.getActivity(
                    context,
                    destinationHash.hashCode(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                buildMessageNotification(
                    privacyLevel = privacyLevel,
                    peerName = peerName,
                    messagePreview = messagePreview,
                    contentIntent = openPendingIntent,
                )

            try {
                notificationManager.notify(
                    messageNotificationTag(destinationHash),
                    NOTIFICATION_ID_MESSAGE,
                    notification,
                )
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        /**
         * Build the message notification for [privacyLevel].
         *
         * [NotificationPrivacyLevel.FULL] is the only branch that passes [messagePreview] to
         * Android. The other levels build a notification whose title and text are both fixed
         * strings, so the message text never reaches the notification object — and therefore
         * never reaches the lock screen, the system's notification history, or any app
         * listening for notifications.
         */
        private fun buildMessageNotification(
            privacyLevel: NotificationPrivacyLevel,
            peerName: String,
            messagePreview: String,
            contentIntent: PendingIntent,
        ): Notification {
            // Used as the body (NAME_ONLY) or title (NONE); never derived from the preview.
            val genericText = context.getString(R.string.notif_generic_message)
            val title: String
            val text: String?
            when (privacyLevel) {
                NotificationPrivacyLevel.FULL -> {
                    title = peerName
                    text = messagePreview
                }
                NotificationPrivacyLevel.NAME_ONLY -> {
                    title = peerName
                    text = genericText
                }
                NotificationPrivacyLevel.NONE -> {
                    title = genericText
                    text = null
                }
            }

            val builder = messageNotificationBuilder(title, text, contentIntent)
            if (privacyLevel != NotificationPrivacyLevel.FULL) {
                // Marks the notification sensitive on secure lock screens and supplies a
                // redacted twin for them. Both versions are built from the same generic
                // strings; the preview is never part of either.
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                builder.setPublicVersion(messageNotificationBuilder(title, text, contentIntent).build())
            }
            return builder.build()
        }

        private fun messageNotificationBuilder(
            title: String,
            text: String?,
            contentIntent: PendingIntent,
        ): NotificationCompat.Builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification_stat)
                .setContentTitle(title)
                .apply { text?.let { setContentText(it) } }
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)

        /**
         * Extract a user-friendly interface label from the raw interface name.
         * Prefers the user-configured name from brackets (e.g. "TCPClientInterface[Backbone]" -> "Backbone"),
         * falling back to the InterfaceType display label.
         */
        private fun extractInterfaceLabel(
            receivingInterface: String?,
            interfaceType: InterfaceType,
        ): String {
            if (receivingInterface != null) {
                val bracketStart = receivingInterface.indexOf('[')
                val bracketEnd = receivingInterface.indexOf(']')
                if (bracketStart in 0 until bracketEnd) {
                    return receivingInterface.substring(bracketStart + 1, bracketEnd)
                }
            }
            return interfaceType.displayLabel
        }

        /**
         * Notification gates for an incoming message: master toggle, per-type
         * preference, runtime permission, and foreground suppression. Suppression
         * applies only when this conversation is active AND the app is in the
         * foreground; with the screen off or the app backgrounded the user cannot
         * see the conversation, so the notification must still show.
         */
        private suspend fun shouldNotifyMessage(
            destinationHash: String,
            isFavorite: Boolean,
            threadIdHex: String?,
        ): Boolean {
            if (!settingsRepository.notificationsEnabledFlow.first()) return false
            if (!hasNotificationPermission()) return false

            val generalMessageNotifications = settingsRepository.notificationReceivedMessageFlow.first()
            val favoriteMessageNotifications = settingsRepository.notificationReceivedMessageFavoriteFlow.first()
            // A favorite is covered by either preference.
            val preferenceAllows =
                if (isFavorite) {
                    generalMessageNotifications || favoriteMessageNotifications
                } else {
                    generalMessageNotifications
                }
            // Only the displayed target counts as on screen. With threads on,
            // the user sees one thread at a time, so a message landing in Main
            // (or in another thread) still warrants a notification; with the
            // flag off there is one flat list and the gate stays per peer.
            val threadsEnabledNow = settingsRepository.threadsEnabledFlow.first()
            val visibleThread =
                if (threadsEnabledNow) activeConversationManager.activeThread.value else null
            val foregroundSuppressed =
                isAppInForeground() &&
                    activeConversationManager.activeConversation.value == destinationHash &&
                    (!threadsEnabledNow || visibleThread == threadIdHex)
            return preferenceAllows && !foregroundSuppressed
        }

        /**
         * Determine whether an announce notification should be shown, based on
         * the master toggle, per-type preference, direct-only / TCP filters,
         * and runtime permission.
         */
        private suspend fun shouldNotifyAnnounce(
            hops: Int,
            interfaceType: InterfaceType,
        ): Boolean {
            val notificationsEnabled = settingsRepository.notificationsEnabledFlow.first()
            val announceEnabled = settingsRepository.notificationHeardAnnounceFlow.first()
            val directOnly = settingsRepository.notificationAnnounceDirectOnlyFlow.first()
            val excludeTcp = settingsRepository.notificationAnnounceExcludeTcpFlow.first()

            val passesFilters =
                notificationsEnabled &&
                    announceEnabled &&
                    // Direct-only: hops == 1 means direct neighbor
                    !(directOnly && hops != 1) &&
                    // TCP exclusion filter
                    !(excludeTcp && interfaceType == InterfaceType.TCP_CLIENT)

            return passesFilters && hasNotificationPermission()
        }

        /**
         * Post a notification for a heard announce.
         *
         * @param destinationHash The destination hash of the announcing peer
         * @param peerName The display name of the announcing peer
         * @param hops Number of hops to the announcing peer (1 = direct neighbor)
         * @param interfaceType The type of interface the announce was received on
         * @param receivingInterface Raw interface name string (e.g. "TCPClientInterface[Backbone]")
         */
        suspend fun notifyAnnounceHeard(
            destinationHash: String,
            peerName: String,
            hops: Int = 0,
            interfaceType: InterfaceType = InterfaceType.UNKNOWN,
            receivingInterface: String? = null,
        ) {
            if (!shouldNotifyAnnounce(hops, interfaceType)) return

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
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // Build notification text with interface info
            val interfaceLabel = extractInterfaceLabel(receivingInterface, interfaceType)
            val hopsText = context.resources.getQuantityString(R.plurals.notif_hops, hops, hops)
            val contentText = context.getString(R.string.notif_via_hops, interfaceLabel, hopsText)

            // Create notification
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_ANNOUNCES)
                    .setSmallIcon(R.drawable.ic_notification_stat)
                    .setContentTitle(context.getString(R.string.notif_announce_from, peerName))
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
                NotificationCompat
                    .Builder(context, CHANNEL_ID_BLE_EVENTS)
                    .setSmallIcon(R.drawable.ic_notification_stat)
                    .setContentTitle(context.getString(R.string.notif_ble_peer_connected))
                    .setContentText(context.getString(R.string.notif_ble_connected_to, displayName))
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
                NotificationCompat
                    .Builder(context, CHANNEL_ID_BLE_EVENTS)
                    .setSmallIcon(R.drawable.ic_notification_stat)
                    .setContentTitle(context.getString(R.string.notif_ble_peer_disconnected))
                    .setContentText(context.getString(R.string.notif_ble_disconnected_from, displayName))
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
         * Cancel the message notification for a specific conversation destination.
         *
         * Uses the same `(tag, id)` identity as [notifyMessageReceived]. The full destination hash
         * is included in the tag so Java hash collisions cannot overwrite or cancel another
         * conversation's notification.
         *
         * Do not also cancel the legacy untagged hash-derived ID here: that would reintroduce the
         * collision this identity prevents. Android's NotificationManagerService cancels a
         * package's notifications during package replacement, so legacy and tagged identities do
         * not coexist after an installed-app update.
         *
         * This is safe and idempotent — cancelling a nonexistent notification
         * is a no-op. It does not affect announce, BLE, or other conversations'
         * notifications.
         *
         * @param destinationHash The destination hash whose notification should be dismissed
         */
        fun cancelNotificationForConversation(destinationHash: String) {
            notificationManager.cancel(
                messageNotificationTag(destinationHash),
                NOTIFICATION_ID_MESSAGE,
            )
        }

        /**
         * Persist [level] and, when it is stricter than the previous level, drop every message
         * notification already in the shade.
         *
         * Runs under [messagePrivacyLock] so it cannot interleave with a post that already read
         * the looser level: either that post finishes first and is cancelled here, or it starts
         * afterwards and reads the new level. The system's notification history is append-only
         * and cannot be rewritten from here.
         */
        suspend fun setMessagePrivacyLevel(level: NotificationPrivacyLevel) {
            messagePrivacyLock.withLock {
                val previousLevel = settingsRepository.notificationPrivacyLevelFlow.first()
                settingsRepository.saveNotificationPrivacyLevel(level)
                if (level.isMoreRestrictiveThan(previousLevel)) {
                    cancelMessageNotificationsLocked()
                }
            }
        }

        /**
         * Cancel every posted message notification. Callers must hold [messagePrivacyLock].
         *
         * A notification already in the shade keeps whatever text it was built with until it
         * is dismissed. Announce, BLE and foreground-service notifications are untouched.
         */
        private fun cancelMessageNotificationsLocked() {
            notificationManager.activeNotifications
                .filter { it.tag?.startsWith(NOTIFICATION_TAG_MESSAGE_PREFIX) == true }
                .forEach { notificationManager.cancel(it.tag, it.id) }
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
