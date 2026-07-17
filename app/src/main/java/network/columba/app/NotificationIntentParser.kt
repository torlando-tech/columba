package network.columba.app

import android.content.Intent
import network.columba.app.notifications.NotificationHelper

/**
 * Pure helper that extracts a [PendingNavigation.Conversation] from a
 * notification-tap [Intent]. Returns `null` when the intent does not carry
 * the required extras.
 *
 * This is the seam between the Android notification system and the navigation
 * layer — testable without Hilt or a running Activity.
 */
object NotificationIntentParser {
    /**
     * Parse a notification-open Intent into a [PendingNavigation.Conversation].
     *
     * The Intent must have:
     * - [NotificationHelper.ACTION_OPEN_CONVERSATION] as its action
     * - [NotificationHelper.EXTRA_DESTINATION_HASH] extra
     * - [NotificationHelper.EXTRA_PEER_NAME] extra
     *
     * @return a [PendingNavigation.Conversation] with [fromNotification] = `true`, or `null`
     */
    fun parseOpenConversation(intent: Intent): PendingNavigation.Conversation? {
        if (intent.action != NotificationHelper.ACTION_OPEN_CONVERSATION) return null

        val destinationHash = intent.getStringExtra(NotificationHelper.EXTRA_DESTINATION_HASH)
        val peerName = intent.getStringExtra(NotificationHelper.EXTRA_PEER_NAME)

        if (destinationHash == null || peerName == null) return null

        return PendingNavigation.Conversation(
            destinationHash = destinationHash,
            peerName = peerName,
            fromNotification = true,
        )
    }
}
