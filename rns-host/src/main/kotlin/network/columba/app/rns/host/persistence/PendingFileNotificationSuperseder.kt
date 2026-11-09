package network.columba.app.rns.host.persistence

import android.util.Log
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.entity.MessageEntity
import org.json.JSONObject

/** Marks pending file notifications as superseded once the full message arrives. */
internal class PendingFileNotificationSuperseder(
    private val messageDao: MessageDao,
) {
    private companion object {
        private const val TAG = "ServicePersistenceManager"
    }

    /**
     * Check if an incoming message with file attachments should supersede
     * a pending file notification, and mark it as superseded if so.
     *
     * Matches notifications by original_message_id (the hash of the file message).
     * Called only when hasFileAttachments=true, so we don't need to check fieldsJson.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth")
    suspend fun supersede(
        peerHash: String,
        identityHash: String,
        incomingMessageId: String,
    ) {
        try {
            Log.d(TAG, "Checking for pending notifications to supersede for message $incomingMessageId")

            // Find pending notifications in this conversation
            val pendingNotifications = messageDao.findPendingFileNotifications(peerHash, identityHash)
            Log.d(TAG, "supersede: Found ${pendingNotifications.size} pending notifications")
            if (pendingNotifications.isEmpty()) return

            // Find matching notification using functional approach
            val match =
                pendingNotifications.firstNotNullOfOrNull { notification ->
                    tryParseNotificationMatch(notification, incomingMessageId)
                }

            if (match != null) {
                val (notification, notificationJson, field16) = match
                Log.d(TAG, "Superseding pending notification ${notification.id} for message $incomingMessageId")

                // Mark as superseded by adding "superseded": true to field 16
                field16.put("superseded", true)
                notificationJson.put("16", field16)

                messageDao.updateMessageFieldsJson(
                    notification.id,
                    identityHash,
                    notificationJson.toString(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking for pending notifications to supersede: ${e.message}")
        }
    }

    /**
     * Try to parse a notification and check if it matches the incoming message ID.
     * Returns a Triple of (notification, json, field16) if matched, null otherwise.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun tryParseNotificationMatch(
        notification: MessageEntity,
        incomingMessageId: String,
    ): Triple<MessageEntity, JSONObject, JSONObject>? {
        return try {
            val notificationFieldsJson = notification.fieldsJson ?: return null
            val notificationJson = JSONObject(notificationFieldsJson)
            val field16 = notificationJson.optJSONObject("16")
            val pendingInfo = field16?.optJSONObject("pending_file_notification")
            val originalMessageId = pendingInfo?.optString("original_message_id", "") ?: ""

            Log.d(TAG, "supersede: Comparing incoming=$incomingMessageId vs original=$originalMessageId")

            if (field16 != null && originalMessageId.isNotEmpty() && originalMessageId == incomingMessageId) {
                Triple(notification, notificationJson, field16)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pending notification ${notification.id}: ${e.message}")
            null
        }
    }
}
