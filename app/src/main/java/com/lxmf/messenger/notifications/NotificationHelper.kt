package com.lxmf.messenger.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.lxmf.messenger.MainActivity
import com.lxmf.messenger.R
import com.lxmf.messenger.data.model.InterfaceType
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
            private const val CHANNEL_ID_SOS = "sos_emergency"

            // Notification IDs
            private const val NOTIFICATION_ID_MESSAGE = 1000
            private const val NOTIFICATION_ID_ANNOUNCE = 2000
            private const val NOTIFICATION_ID_BLE = 3000
            internal const val NOTIFICATION_ID_SOS = 5000

            // Intent actions
            const val ACTION_OPEN_ANNOUNCE = "com.lxmf.messenger.ACTION_OPEN_ANNOUNCE"
            const val ACTION_OPEN_CONVERSATION = "com.lxmf.messenger.ACTION_OPEN_CONVERSATION"
            const val ACTION_SOS_CALL_BACK = "com.lxmf.messenger.ACTION_SOS_CALL_BACK"
            const val ACTION_SOS_VIEW_MAP = "com.lxmf.messenger.ACTION_SOS_VIEW_MAP"
            private const val ACTION_REPLY = "com.lxmf.messenger.ACTION_REPLY"
            private const val ACTION_MARK_READ = "com.lxmf.messenger.ACTION_MARK_READ"

            // Intent extras
            const val EXTRA_DESTINATION_HASH = "destination_hash"
            const val EXTRA_PEER_NAME = "peer_name"
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

                val sosChannel =
                    NotificationChannel(
                        CHANNEL_ID_SOS,
                        "SOS Emergency",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Emergency SOS alerts from contacts"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(messagesChannel)
                manager.createNotificationChannel(announcesChannel)
                manager.createNotificationChannel(bleEventsChannel)
                manager.createNotificationChannel(sosChannel)
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
         */
        suspend fun notifyMessageReceived(
            destinationHash: String,
            peerName: String,
            messagePreview: String,
            isFavorite: Boolean,
        ) {
            // Check master notification toggle
            if (!settingsRepository.notificationsEnabledFlow.first()) return

            // Check specific notification preference
            val generalMessageNotifications = settingsRepository.notificationReceivedMessageFlow.first()
            val favoriteMessageNotifications = settingsRepository.notificationReceivedMessageFavoriteFlow.first()

            val messageNotificationsEnabled =
                if (isFavorite) {
                    // If it's a favorite, check both general messages and favorite messages
                    generalMessageNotifications || favoriteMessageNotifications
                } else {
                    generalMessageNotifications
                }

            if (!messageNotificationsEnabled) return

            // Check permission
            if (!hasNotificationPermission()) return

            // Suppress notification only if this conversation is active AND the app is in the
            // foreground. When the screen is off or the app is backgrounded, the user can't see
            // the conversation, so they should still receive the notification.
            if (isAppInForeground() && activeConversationManager.activeConversation.value == destinationHash) return

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
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            // Create notification
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_MESSAGES)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(peerName)
                    .setContentText(messagePreview)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(openPendingIntent)
                    .build()

            val notificationId = NOTIFICATION_ID_MESSAGE + destinationHash.hashCode()
            try {
                notificationManager.notify(notificationId, notification)
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

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
            val hopsText = if (hops == 1) "1 hop" else "$hops hops"
            val contentText = "via $interfaceLabel \u00b7 $hopsText"

            // Create notification
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_ANNOUNCES)
                    .setSmallIcon(R.mipmap.ic_launcher)
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
                NotificationCompat
                    .Builder(context, CHANNEL_ID_BLE_EVENTS)
                    .setSmallIcon(R.mipmap.ic_launcher)
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
                NotificationCompat
                    .Builder(context, CHANNEL_ID_BLE_EVENTS)
                    .setSmallIcon(R.mipmap.ic_launcher)
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
         * Cancel one or all notifications.
         *
         * @param notificationId The ID of the notification to cancel, or null to cancel all.
         */
        fun cancelNotification(notificationId: Int? = null) {
            if (notificationId != null) {
                notificationManager.cancel(notificationId)
            } else {
                notificationManager.cancelAll()
            }
        }

        /**
         * Post an urgent notification when an SOS message is received.
         */
        fun notifySosReceived(
            destinationHash: String,
            peerName: String,
            messageContent: String,
            latitude: Double? = null,
            longitude: Double? = null,
            isUpdate: Boolean = false,
        ) {
            if (!hasNotificationPermission()) return

            val openPendingIntent =
                createSosPendingIntent(
                    action = ACTION_OPEN_CONVERSATION,
                    requestCode = "sos_open_$destinationHash",
                    destinationHash = destinationHash,
                    peerName = peerName,
                )

            val callBackPendingIntent =
                createSosPendingIntent(
                    action = ACTION_SOS_CALL_BACK,
                    requestCode = "sos_call_$destinationHash",
                    destinationHash = destinationHash,
                    peerName = peerName,
                )

            val contentText =
                if (latitude != null && longitude != null) {
                    "GPS: ${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}"
                } else {
                    messageContent.take(200)
                }

            val bigText =
                buildString {
                    append(messageContent.take(500))
                    if (latitude != null && longitude != null) {
                        append("\n\nLocation: ${"%.5f".format(latitude)}, ${"%.5f".format(longitude)}")
                    }
                }

            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_SOS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("SOS from $peerName")
                    .setContentText(contentText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setContentIntent(openPendingIntent)
                    .addAction(R.mipmap.ic_launcher, "Open Chat", callBackPendingIntent)
                    .apply {
                        if (isUpdate) {
                            setOnlyAlertOnce(true)
                        } else {
                            setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                        }
                    }

            if (latitude != null && longitude != null) {
                val mapPendingIntent =
                    createSosPendingIntent(
                        action = ACTION_SOS_VIEW_MAP,
                        requestCode = "sos_map_$destinationHash",
                        destinationHash = destinationHash,
                        peerName = peerName,
                        latitude = latitude,
                        longitude = longitude,
                    )
                builder.addAction(R.mipmap.ic_launcher, "View on Map", mapPendingIntent)
            }

            val notification = builder.build()
            val notificationId = NOTIFICATION_ID_SOS xor (destinationHash.hashCode() and 0x7FFFFFFF)
            try {
                notificationManager.notify(notificationId, notification)
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        private fun createSosPendingIntent(
            action: String,
            requestCode: String,
            destinationHash: String? = null,
            peerName: String? = null,
            latitude: Double? = null,
            longitude: Double? = null,
        ): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    this.action = action
                    destinationHash?.let { putExtra(EXTRA_DESTINATION_HASH, it) }
                    peerName?.let { putExtra(EXTRA_PEER_NAME, it) }
                    latitude?.let { putExtra("latitude", it) }
                    longitude?.let { putExtra("longitude", it) }
                }
            return PendingIntent.getActivity(
                context,
                requestCode.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Show a persistent notification indicating SOS mode is active (sender side).
         */
        fun showSosActiveNotification(
            contactsNotified: Int,
            failedCount: Int,
        ) {
            if (!hasNotificationPermission()) return

            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            val openPendingIntent =
                PendingIntent.getActivity(
                    context,
                    "sos_active".hashCode(),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val text =
                buildString {
                    append("$contactsNotified contact(s) notified")
                    if (failedCount > 0) append(" ($failedCount failed)")
                }

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID_SOS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("SOS Active")
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setContentIntent(openPendingIntent)
                    .build()

            try {
                notificationManager.notify(NOTIFICATION_ID_SOS, notification)
            } catch (e: SecurityException) {
                // Permission was revoked
            }
        }

        /**
         * Check if a message content is an SOS emergency message.
         */
        fun isSosMessage(content: String): Boolean {
            val upper = content.uppercase().trimStart()
            return (
                upper.startsWith("SOS") ||
                    upper.startsWith("URGENCE") ||
                    upper.startsWith("EMERGENCY")
            ) &&
                !isSosCancelledMessage(content)
        }

        /**
         * Parse GPS coordinates from an SOS message.
         */
        fun parseSosLocation(content: String): Pair<Double, Double>? {
            val regex = Regex("""GPS:\s*(-?[\d.,]+),\s*(-?[\d.,]+)""")
            val match = regex.find(content) ?: return null
            return try {
                val lat = match.groupValues[1].replace(',', '.').toDouble()
                val lng = match.groupValues[2].replace(',', '.').toDouble()
                if (lat in -90.0..90.0 && lng in -180.0..180.0) Pair(lat, lng) else null
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

/**
 * Check if a message is an SOS cancellation. Top-level to avoid TooManyFunctions in NotificationHelper.
 */

/**
 * Check if a message is an SOS periodic update. Top-level to avoid TooManyFunctions in NotificationHelper.
 */
fun isSosUpdate(content: String): Boolean = content.uppercase().trimStart().startsWith("SOS UPDATE")

fun isSosCancelledMessage(content: String): Boolean {
    val upper = content.uppercase().trimStart()
    return upper.startsWith("SOS CANCELLED") || upper.startsWith("SOS CANCELED")
}

/** Extract SOS state from fieldsJson FIELD_COMMANDS. Returns "active", "cancelled", "update", or null. */
fun extractSosState(fieldsJson: String?): String? {
    if (fieldsJson == null) return null
    return try {
        val fields = org.json.JSONObject(fieldsJson)
        fields.optString("sos_state").ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

/** Check if a message is SOS using FIELD_COMMANDS (primary) or text content (fallback). */
fun isSosMessageByField(
    content: String,
    fieldsJson: String?,
): Boolean {
    val sosState = extractSosState(fieldsJson)
    if (sosState != null) return sosState == "active" || sosState == "update"
    // Text-based fallback for Sideband/legacy compatibility
    val upper = content.uppercase().trimStart()
    return (upper.startsWith("SOS") || upper.startsWith("URGENCE") || upper.startsWith("EMERGENCY")) &&
        !isSosCancelledMessage(content)
}

/** Check if a message is SOS cancellation using FIELD_COMMANDS (primary) or text content (fallback). */
fun isSosCancelledByField(
    content: String,
    fieldsJson: String?,
): Boolean {
    val sosState = extractSosState(fieldsJson)
    if (sosState != null) return sosState == "cancelled"
    return isSosCancelledMessage(content)
}

/** Check if a message is SOS update using FIELD_COMMANDS (primary) or text content (fallback). */
fun isSosUpdateByField(
    content: String,
    fieldsJson: String?,
): Boolean {
    val sosState = extractSosState(fieldsJson)
    if (sosState != null) return sosState == "update"
    return isSosUpdate(content)
}
