package com.lxmf.messenger.service.persistence

import android.content.Context
import android.util.Log
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import com.lxmf.messenger.service.di.ServiceDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages database persistence from the service process.
 *
 * This runs in the :reticulum process and writes directly to the database,
 * ensuring data is persisted even when the app process is killed.
 *
 * The service process receives announces and messages from Python/Reticulum,
 * and this manager persists them immediately rather than relying on IPC to
 * the app process (which may be dead).
 */
class ServicePersistenceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ServicePersistenceManager"
    }

    private val database: ColumbaDatabase by lazy {
        ServiceDatabaseProvider.getDatabase(context)
    }

    private val announceDao by lazy { database.announceDao() }
    private val messageDao by lazy { database.messageDao() }
    private val conversationDao by lazy { database.conversationDao() }
    private val localIdentityDao by lazy { database.localIdentityDao() }
    private val peerIdentityDao by lazy { database.peerIdentityDao() }
    private val guardianConfigDao by lazy { database.guardianConfigDao() }
    private val allowedContactDao by lazy { database.allowedContactDao() }

    /**
     * Persist an announce to the database.
     * Called from EventHandler.handleAnnounceEvent() in the service process.
     *
     * This preserves existing favorite status and icon appearance.
     */
    @Suppress("LongParameterList") // Parameters mirror AnnounceEntity fields for direct persistence
    fun persistAnnounce(
        destinationHash: String,
        peerName: String,
        publicKey: ByteArray,
        appData: ByteArray?,
        hops: Int,
        timestamp: Long,
        nodeType: String,
        receivingInterface: String?,
        receivingInterfaceType: String?,
        aspect: String?,
        stampCost: Int?,
        stampCostFlexibility: Int?,
        peeringCost: Int?,
        iconName: String?,
        iconForegroundColor: String?,
        iconBackgroundColor: String?,
        propagationTransferLimitKb: Int?,
    ) {
        scope.launch {
            try {
                // Preserve favorite status and existing icon appearance if announce already exists
                val existing = announceDao.getAnnounce(destinationHash)

                val entity =
                    AnnounceEntity(
                        destinationHash = destinationHash,
                        peerName = peerName,
                        publicKey = publicKey,
                        appData = appData,
                        hops = hops,
                        lastSeenTimestamp = timestamp,
                        nodeType = nodeType,
                        receivingInterface = receivingInterface,
                        receivingInterfaceType = receivingInterfaceType,
                        aspect = aspect,
                        isFavorite = existing?.isFavorite ?: false,
                        favoritedTimestamp = existing?.favoritedTimestamp,
                        stampCost = stampCost,
                        stampCostFlexibility = stampCostFlexibility,
                        peeringCost = peeringCost,
                        // Prefer new icon appearance if provided, otherwise preserve existing
                        iconName = iconName ?: existing?.iconName,
                        iconForegroundColor = iconForegroundColor ?: existing?.iconForegroundColor,
                        iconBackgroundColor = iconBackgroundColor ?: existing?.iconBackgroundColor,
                        propagationTransferLimitKb = propagationTransferLimitKb,
                    )

                announceDao.upsertAnnounce(entity)
                Log.d(TAG, "Service persisted announce: $peerName ($destinationHash)")
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting announce in service: $destinationHash", e)
            }
        }
    }

    /**
     * Persist a peer's public key to the database.
     * Called when we receive an announce with a public key.
     */
    fun persistPeerIdentity(
        peerHash: String,
        publicKey: ByteArray,
    ) {
        scope.launch {
            try {
                val entity =
                    PeerIdentityEntity(
                        peerHash = peerHash,
                        publicKey = publicKey,
                        lastSeenTimestamp = System.currentTimeMillis(),
                    )
                peerIdentityDao.insertPeerIdentity(entity)
                Log.d(TAG, "Service persisted peer identity: $peerHash")
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting peer identity in service: $peerHash", e)
            }
        }
    }

    /**
     * Persist a received message to the database.
     * Called from EventHandler.handleMessageEvent() in the service process.
     *
     * This includes identity scoping to ensure messages are saved to the correct identity.
     *
     * This is a suspend function that completes before returning, ensuring the message
     * is fully persisted before sync completion is reported to the UI.
     */
    @Suppress("LongParameterList") // Parameters mirror MessageEntity fields for direct persistence
    suspend fun persistMessage(
        messageHash: String,
        content: String,
        sourceHash: String,
        timestamp: Long,
        fieldsJson: String?,
        publicKey: ByteArray?,
        replyToMessageId: String?,
        deliveryMethod: String?,
        hasFileAttachments: Boolean = false,
        receivedHopCount: Int? = null,
        receivedInterface: String? = null,
    ) {
        try {
            // Get active identity to scope the message correctly
            val activeIdentity = localIdentityDao.getActiveIdentitySync()
            if (activeIdentity == null) {
                Log.w(TAG, "No active identity - cannot persist message")
                return
            }

            // ============ PARENTAL CONTROL FILTERING ============
            // Check if device is locked and if sender is allowed
            val isLocked = guardianConfigDao.isLocked(activeIdentity.identityHash) == true
            if (isLocked) {
                val guardianHash = guardianConfigDao.getGuardianDestinationHash(activeIdentity.identityHash)
                // Guardian is always allowed
                if (sourceHash != guardianHash) {
                    // Check if sender is in the allow list
                    val isAllowed = allowedContactDao.isContactAllowed(activeIdentity.identityHash, sourceHash)
                    if (!isAllowed) {
                        Log.d(TAG, "Blocked message from non-allowed contact: $sourceHash (device locked)")
                        return // Silently drop
                    }
                }
            }
            // ============ END PARENTAL CONTROL FILTERING ============

            // Check for duplicates (composite key is id + identityHash)
            val existingMessage = messageDao.getMessageById(messageHash, activeIdentity.identityHash)
            if (existingMessage != null) {
                Log.d(TAG, "Message already exists - skipping duplicate: $messageHash")
                return
            }

            // Create/update conversation
            val existingConversation =
                conversationDao.getConversation(
                    sourceHash,
                    activeIdentity.identityHash,
                )

            // Get peer name from existing conversation or use formatted hash
            val peerName =
                existingConversation?.peerName
                    ?: "Peer ${sourceHash.take(8).uppercase()}"

            // Insert/update conversation
            if (existingConversation != null) {
                val updated =
                    existingConversation.copy(
                        lastMessage = content.take(100),
                        lastMessageTimestamp = timestamp,
                        unreadCount = existingConversation.unreadCount + 1,
                        peerPublicKey = publicKey ?: existingConversation.peerPublicKey,
                    )
                conversationDao.updateConversation(updated)
            } else {
                val newConversation =
                    ConversationEntity(
                        peerHash = sourceHash,
                        identityHash = activeIdentity.identityHash,
                        peerName = peerName,
                        peerPublicKey = publicKey,
                        lastMessage = content.take(100),
                        lastMessageTimestamp = timestamp,
                        unreadCount = 1,
                        lastSeenTimestamp = 0,
                    )
                conversationDao.insertConversation(newConversation)
            }

            // Insert message
            val messageEntity =
                MessageEntity(
                    id = messageHash,
                    conversationHash = sourceHash,
                    identityHash = activeIdentity.identityHash,
                    content = content,
                    timestamp = timestamp,
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    fieldsJson = fieldsJson,
                    replyToMessageId = replyToMessageId,
                    deliveryMethod = deliveryMethod,
                    errorMessage = null,
                    receivedHopCount = receivedHopCount,
                    receivedInterface = receivedInterface,
                )
            messageDao.insertMessage(messageEntity)

            // Check if this message has file attachments and should supersede a pending notification
            if (hasFileAttachments) {
                supersedePendingFileNotifications(
                    sourceHash,
                    activeIdentity.identityHash,
                    messageHash,
                )
            }

            // Store peer public key if available
            if (publicKey != null) {
                persistPeerIdentity(sourceHash, publicKey)
            }

            Log.d(TAG, "Service persisted message from $sourceHash: ${content.take(30)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting message in service from $sourceHash", e)
        }
    }

    /**
     * Check if an announce exists (for de-duplication in app process).
     */
    suspend fun announceExists(destinationHash: String): Boolean {
        return try {
            announceDao.announceExists(destinationHash)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking announce existence: $destinationHash", e)
            false
        }
    }

    /**
     * Check if a message exists (for de-duplication in app process).
     */
    suspend fun messageExists(messageHash: String): Boolean {
        return try {
            val activeIdentity = localIdentityDao.getActiveIdentitySync()
            if (activeIdentity == null) {
                Log.w(TAG, "No active identity for message existence check")
                return false
            }
            messageDao.getMessageById(messageHash, activeIdentity.identityHash) != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking message existence: $messageHash", e)
            false
        }
    }

    /**
     * Check if an incoming message with file attachments should supersede
     * a pending file notification, and mark it as superseded if so.
     *
     * Matches notifications by original_message_id (the hash of the file message).
     * Called only when hasFileAttachments=true, so we don't need to check fieldsJson.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth")
    private suspend fun supersedePendingFileNotifications(
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

    /**
     * Close the database connection.
     * Should be called when the service is destroyed.
     */
    fun close() {
        ServiceDatabaseProvider.close()
        Log.d(TAG, "Service database connection closed")
    }
}
