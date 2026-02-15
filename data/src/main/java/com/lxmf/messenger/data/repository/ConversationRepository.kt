package com.lxmf.messenger.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.Transaction
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.DraftDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.DraftEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import com.lxmf.messenger.data.model.EnrichedConversation
import com.lxmf.messenger.data.storage.AttachmentStorageManager
import com.lxmf.messenger.data.util.TextSanitizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class Conversation(
    val peerHash: String,
    val peerName: String,
    // Display name with priority: customNickname > announceName > peerName > peerHash
    val displayName: String,
    val peerPublicKey: ByteArray? = null,
    val lastMessage: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
    // Profile icon (from announces table)
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
)

data class Message(
    val id: String,
    val destinationHash: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val status: String = "sent",
    val fieldsJson: String? = null,
    val deliveryMethod: String? = null,
    val errorMessage: String? = null,
    val replyToMessageId: String? = null,
    val receivedHopCount: Int? = null,
    val receivedInterface: String? = null,
    val receivedRssi: Int? = null,
    val receivedSnr: Float? = null,
)

/**
 * Lightweight data class for reply preview information.
 * Contains only the fields needed to display a reply preview in the UI.
 */
data class ReplyPreview(
    val messageId: String,
    val senderName: String, // "You" or peer display name
    val contentPreview: String, // Truncated to ~100 chars
    val hasImage: Boolean,
    val hasFileAttachment: Boolean,
    val firstFileName: String?, // For file attachment preview
)

@Suppress("LargeClass")
@Singleton
class ConversationRepository
    @Inject
    constructor(
        private val conversationDao: ConversationDao,
        private val messageDao: MessageDao,
        private val peerIdentityDao: PeerIdentityDao,
        private val localIdentityDao: LocalIdentityDao,
        private val attachmentStorage: AttachmentStorageManager,
        private val draftDao: DraftDao,
    ) {
        /**
         * Get all conversations for the active identity, sorted by most recent activity.
         * Includes profile icon data from announces table.
         * Automatically switches when identity changes.
         */
        fun getConversations(): Flow<List<Conversation>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                android.util.Log.d(
                    "ConversationRepository",
                    "getConversations: Active identity changed to " +
                        "${identity?.displayName} (${identity?.identityHash?.take(8)})",
                )
                if (identity == null) {
                    android.util.Log.d(
                        "ConversationRepository",
                        "getConversations: No active identity, returning empty list",
                    )
                    flowOf(emptyList())
                } else {
                    conversationDao.getEnrichedConversations(identity.identityHash).map { enriched ->
                        android.util.Log.d(
                            "ConversationRepository",
                            "getConversations: Loaded ${enriched.size} conversations " +
                                "for identity ${identity.identityHash.take(8)}",
                        )
                        enriched.map { it.toConversation() }
                    }
                }
            }

        /**
         * Search conversations by peer name for the active identity.
         * Includes profile icon data from announces table.
         * Automatically switches when identity changes.
         */
        fun searchConversations(query: String): Flow<List<Conversation>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    conversationDao.searchEnrichedConversations(identity.identityHash, query).map { enriched ->
                        enriched.map { it.toConversation() }
                    }
                }
            }

        /**
         * Get a single conversation by peer hash for the active identity
         */
        suspend fun getConversation(peerHash: String): Conversation? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return conversationDao.getConversation(peerHash, activeIdentity.identityHash)?.toConversation()
        }

        /**
         * Get all messages for a specific conversation for the active identity.
         * Automatically switches when identity changes.
         */
        fun getMessages(peerHash: String): Flow<List<Message>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    messageDao.getMessagesForConversation(peerHash, identity.identityHash).map { entities ->
                        entities.map { it.toMessage() }
                    }
                }
            }

        /**
         * Get messages for a specific conversation with pagination support for the active identity.
         * Initial load: 30 messages, Page size: 30 messages.
         * Messages ordered DESC (newest first) for efficient pagination.
         * Note: Paging source requires a fresh identityHash - doesn't auto-switch on identity change.
         */
        @Suppress("SuspendFunWithFlowReturnType") // Suspend is needed to fetch active identity before creating Flow
        suspend fun getMessagesPaged(peerHash: String): Flow<PagingData<Message>> {
            val activeIdentity =
                localIdentityDao.getActiveIdentitySync()
                    ?: return emptyFlow()
            val identityHash = activeIdentity.identityHash

            return Pager(
                config =
                    PagingConfig(
                        pageSize = 30,
                        initialLoadSize = 30,
                        prefetchDistance = 20,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    messageDao.getMessagesForConversationPaged(peerHash, identityHash)
                },
            ).flow.map { pagingData ->
                pagingData.map { entity -> entity.toMessage() }
            }
        }

        /**
         * Save a message and update the conversation
         * Creates conversation if it doesn't exist
         */
        @Transaction
        suspend fun saveMessage(
            peerHash: String,
            peerName: String,
            message: Message,
            peerPublicKey: ByteArray? = null,
        ) {
            // VALIDATION: Sanitize message content before storage
            val sanitizedContent = TextSanitizer.sanitizeMessage(message.content)

            // VALIDATION: Sanitize peer name
            val sanitizedPeerName = TextSanitizer.sanitizePeerName(peerName)

            // Get active identity hash (required for all data)
            val activeIdentity =
                localIdentityDao.getActiveIdentitySync()
                    ?: throw IllegalStateException("No active identity found")
            val identityHash = activeIdentity.identityHash

            // Check if message already exists to avoid duplicate processing
            val messageExists = messageDao.messageExists(message.id, identityHash)

            // CRITICAL: Create/update conversation FIRST (before message insert)
            // This ensures foreign key constraint is satisfied
            val existingConversation = conversationDao.getConversation(peerHash, identityHash)

            if (existingConversation != null) {
                // Update existing conversation
                // Only increment unread if: NOT from us, AND message is NEW (not duplicate)
                val shouldIncrementUnread = !message.isFromMe && !messageExists

                val updatedConversation =
                    existingConversation.copy(
                        peerName = sanitizedPeerName, // Update name (SANITIZED) in case it changed from new announce
                        peerPublicKey = peerPublicKey ?: existingConversation.peerPublicKey, // Update public key if provided
                        lastMessage = sanitizedContent, // Store SANITIZED content
                        lastMessageTimestamp = message.timestamp,
                        unreadCount =
                            if (shouldIncrementUnread) {
                                existingConversation.unreadCount + 1 // Increment only for NEW received messages
                            } else {
                                existingConversation.unreadCount // Keep existing count for duplicates/own messages
                            },
                    )
                conversationDao.updateConversation(updatedConversation)
            } else {
                // Create new conversation
                // Note: We look up public key from peer_identities, but this must be done
                // OUTSIDE of this transaction to avoid nested transaction issues
                // The caller should pass peerPublicKey if available
                val newConversation =
                    ConversationEntity(
                        peerHash = peerHash,
                        identityHash = identityHash,
                        peerName = sanitizedPeerName, // Use SANITIZED name
                        peerPublicKey = peerPublicKey, // Use provided key or null
                        lastMessage = sanitizedContent, // Store SANITIZED content
                        lastMessageTimestamp = message.timestamp,
                        unreadCount = if (message.isFromMe) 0 else 1,
                        lastSeenTimestamp = 0,
                    )
                conversationDao.insertConversation(newConversation)

                if (peerPublicKey != null) {
                    android.util.Log.d("ConversationRepository", "Created new conversation with public key: $peerHash")
                } else {
                    android.util.Log.w("ConversationRepository", "Created new conversation without public key: $peerHash")
                }
            }

            // NOW insert the message (after conversation exists) with SANITIZED content
            // Only insert if message doesn't already exist - prevents LXMF replay from
            // overwriting imported messages with new timestamps (fixes ordering bug)
            if (!messageExists) {
                // Extract large attachments to disk to avoid SQLite CursorWindow limit (~2MB)
                val processedFieldsJson = extractLargeAttachments(message.id, message.fieldsJson)

                val messageEntity =
                    MessageEntity(
                        id = message.id,
                        conversationHash = peerHash,
                        identityHash = identityHash,
                        content = sanitizedContent, // Store SANITIZED content
                        timestamp = message.timestamp,
                        isFromMe = message.isFromMe,
                        status = message.status,
                        isRead = message.isFromMe, // Our own messages are always "read"
                        fieldsJson = processedFieldsJson, // LXMF fields with large attachments extracted
                        deliveryMethod = message.deliveryMethod,
                        errorMessage = message.errorMessage,
                        replyToMessageId = message.replyToMessageId, // Reply reference
                    )
                messageDao.insertMessage(messageEntity)

                // Check if this message has file attachments and should supersede a pending notification
                if (!message.isFromMe && message.fieldsJson != null) {
                    supersedePendingFileNotifications(
                        peerHash,
                        identityHash,
                        message.id,
                        message.fieldsJson,
                    )
                }
            }
        }

        /**
         * Mark conversation as read (clears unread count) for the active identity
         */
        suspend fun markConversationAsRead(peerHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            android.util.Log.d("ConversationRepository", "Marking conversation $peerHash as read")
            conversationDao.markAsRead(peerHash, activeIdentity.identityHash)
            messageDao.markMessagesAsRead(peerHash, activeIdentity.identityHash)
            android.util.Log.d("ConversationRepository", "Conversation marked as read, unread count should be 0")
        }

        /**
         * Mark conversation as unread (sets unread count to 1) for the active identity
         * Useful for flagging important conversations
         */
        suspend fun markConversationAsUnread(peerHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val conversation = conversationDao.getConversation(peerHash, activeIdentity.identityHash)
            conversation?.let {
                val updatedConversation = it.copy(unreadCount = 1)
                conversationDao.updateConversation(updatedConversation)
                android.util.Log.d("ConversationRepository", "Marked conversation $peerHash as unread")
            }
        }

        /**
         * Delete a conversation and all its messages for the active identity
         */
        suspend fun deleteConversation(peerHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val conversation = conversationDao.getConversation(peerHash, activeIdentity.identityHash)
            conversation?.let {
                conversationDao.deleteConversation(it)
                // Messages will be cascade-deleted due to foreign key
            }
        }

        /**
         * Update peer name for a conversation (e.g., from fresh announce) for the active identity
         */
        suspend fun updatePeerName(
            peerHash: String,
            peerName: String,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            conversationDao.updatePeerName(peerHash, activeIdentity.identityHash, peerName)
        }

        /**
         * Store or update a peer's public key in the peer_identities table.
         * Peer identities are global (not identity-scoped) since they represent
         * other nodes on the network.
         * This does NOT create a conversation - that only happens when the user initiates chat.
         */
        suspend fun updatePeerPublicKey(
            peerHash: String,
            publicKey: ByteArray,
        ) {
            try {
                val peerIdentity =
                    PeerIdentityEntity(
                        peerHash = peerHash,
                        publicKey = publicKey,
                        lastSeenTimestamp = System.currentTimeMillis(),
                    )
                peerIdentityDao.insertPeerIdentity(peerIdentity)
                android.util.Log.d("ConversationRepository", "Stored public key for peer: $peerHash (${publicKey.size} bytes)")
            } catch (e: Exception) {
                android.util.Log.e("ConversationRepository", "Error storing peer public key", e)
            }
        }

        /**
         * Update an existing conversation's public key for the active identity.
         * This is separate from updatePeerPublicKey to avoid transaction conflicts.
         */
        suspend fun updateConversationPublicKey(
            peerHash: String,
            publicKey: ByteArray,
        ) {
            try {
                val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
                val conversation = conversationDao.getConversation(peerHash, activeIdentity.identityHash)
                conversation?.let {
                    val updatedConversation = it.copy(peerPublicKey = publicKey)
                    conversationDao.updateConversation(updatedConversation)
                    android.util.Log.d("ConversationRepository", "Updated public key for existing conversation: $peerHash")
                }
            } catch (e: Exception) {
                android.util.Log.e("ConversationRepository", "Error updating conversation public key", e)
            }
        }

        /**
         * Get a peer's public key from the peer_identities table.
         * Peer identities are global (not identity-scoped).
         */
        suspend fun getPeerPublicKey(peerHash: String): ByteArray? = peerIdentityDao.getPeerIdentity(peerHash)?.publicKey

        /**
         * Get all known peer identities for identity restoration.
         * Peer identities are global (not identity-scoped) since they represent
         * other nodes on the network.
         */
        suspend fun getAllPeerIdentities(): List<Pair<String, ByteArray>> {
            val allPeerIdentities = peerIdentityDao.getAllPeerIdentities()
            android.util.Log.d("ConversationRepository", "Found ${allPeerIdentities.size} peer identities")

            val identitiesWithKeys =
                allPeerIdentities.map { peerIdentity ->
                    peerIdentity.peerHash to peerIdentity.publicKey
                }

            android.util.Log.d("ConversationRepository", "Returning ${identitiesWithKeys.size} peer identities for restoration")
            return identitiesWithKeys
        }

        /**
         * Get peer identities in batches to prevent OOM when loading large amounts of data.
         * Used for batched identity restoration.
         *
         * @param limit Number of peer identities to return in this batch
         * @param offset Number of peer identities to skip (for pagination)
         * @return List of peer identities as (peerHash, publicKey) pairs
         */
        suspend fun getPeerIdentitiesBatch(
            limit: Int,
            offset: Int,
        ): List<Pair<String, ByteArray>> {
            val peerIdentitiesBatch = peerIdentityDao.getPeerIdentitiesBatch(limit, offset)

            val identitiesWithKeys =
                peerIdentitiesBatch.map { peerIdentity ->
                    peerIdentity.peerHash to peerIdentity.publicKey
                }

            return identitiesWithKeys
        }

        suspend fun getMessageById(messageId: String): MessageEntity? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return messageDao.getMessageById(messageId, activeIdentity.identityHash)
        }

        /**
         * Observe a message by ID for real-time updates (e.g., status changes from pending → delivered).
         * Returns a Flow that emits whenever the message changes in the database.
         */
        fun observeMessageById(messageId: String): Flow<MessageEntity?> = messageDao.observeMessageById(messageId)

        suspend fun updateMessageStatus(
            messageId: String,
            status: String,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            messageDao.updateMessageStatus(messageId, activeIdentity.identityHash, status)
            android.util.Log.d("ConversationRepository", "Updated message $messageId status to $status")
        }

        private fun ConversationEntity.toConversation() =
            Conversation(
                peerHash = peerHash,
                peerName = peerName,
                displayName = peerName, // Entity doesn't have enriched displayName, use peerName
                peerPublicKey = peerPublicKey,
                lastMessage = lastMessage,
                lastMessageTimestamp = lastMessageTimestamp,
                unreadCount = unreadCount,
            )

        private fun EnrichedConversation.toConversation() =
            Conversation(
                peerHash = peerHash,
                peerName = peerName,
                displayName = displayName,
                peerPublicKey = peerPublicKey,
                lastMessage = lastMessage,
                lastMessageTimestamp = lastMessageTimestamp,
                unreadCount = unreadCount,
                iconName = iconName,
                iconForegroundColor = iconForegroundColor,
                iconBackgroundColor = iconBackgroundColor,
            )

        private fun MessageEntity.toMessage() =
            Message(
                id = id,
                destinationHash = conversationHash,
                content = content,
                timestamp = timestamp,
                isFromMe = isFromMe,
                status = status,
                fieldsJson = fieldsJson,
                deliveryMethod = deliveryMethod,
                errorMessage = errorMessage,
                replyToMessageId = replyToMessageId,
                receivedHopCount = receivedHopCount,
                receivedInterface = receivedInterface,
            )

        /**
         * Update message delivery details (method and error) for the active identity
         */
        suspend fun updateMessageDeliveryDetails(
            messageId: String,
            deliveryMethod: String?,
            errorMessage: String?,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            messageDao.updateMessageDeliveryDetails(
                messageId,
                activeIdentity.identityHash,
                deliveryMethod,
                errorMessage,
            )
            android.util.Log.d(
                "ConversationRepository",
                "Updated message $messageId delivery details: method=$deliveryMethod, error=$errorMessage",
            )
        }

        /**
         * Update message reactions in fieldsJson.
         * Used when adding/updating emoji reactions on a message.
         *
         * @param messageId The ID of the message to update
         * @param updatedFieldsJson The new fieldsJson containing updated reactions
         */
        suspend fun updateMessageReactions(
            messageId: String,
            updatedFieldsJson: String,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            messageDao.updateMessageFieldsJson(
                messageId,
                activeIdentity.identityHash,
                updatedFieldsJson,
            )
            android.util.Log.d(
                "ConversationRepository",
                "Updated message $messageId reactions",
            )
        }

        /**
         * Update a message's ID (for retry scenarios where the message hash changes).
         * Since Room doesn't allow updating primary keys, this deletes the old message
         * and inserts a new one with the updated ID.
         *
         * @param oldMessageId The current message ID
         * @param newMessageId The new message ID to use
         */
        suspend fun updateMessageId(
            oldMessageId: String,
            newMessageId: String,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val oldMessage = messageDao.getMessageById(oldMessageId, activeIdentity.identityHash) ?: return

            // Create new message with updated ID
            val newMessage = oldMessage.copy(id = newMessageId, status = "pending")

            // Delete old and insert new atomically would require @Transaction,
            // but for simplicity we do it in sequence
            messageDao.deleteMessageById(oldMessageId, activeIdentity.identityHash)
            messageDao.insertMessage(newMessage)

            android.util.Log.d(
                "ConversationRepository",
                "Updated message ID from $oldMessageId to $newMessageId",
            )
        }

        /**
         * Get reply preview data for a message.
         * Used when displaying a reply to another message.
         *
         * @param messageId The ID of the message being replied to
         * @param peerName The display name of the peer (used when message is from them)
         * @return ReplyPreview or null if message not found
         */
        suspend fun getReplyPreview(
            messageId: String,
            peerName: String,
        ): ReplyPreview? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            val previewEntity =
                messageDao.getReplyPreviewData(messageId, activeIdentity.identityHash)
                    ?: return null

            // Parse fieldsJson to detect attachments
            val hasImage = previewEntity.fieldsJson?.contains("\"6\"") == true
            val hasFileAttachment = previewEntity.fieldsJson?.contains("\"5\"") == true

            // Extract first filename if file attachment exists
            val firstFileName =
                if (hasFileAttachment && previewEntity.fieldsJson != null) {
                    extractFirstFileName(previewEntity.fieldsJson)
                } else {
                    null
                }

            // Truncate content for preview
            val contentPreview =
                previewEntity.content.take(TextSanitizer.MAX_PREVIEW_LENGTH).let {
                    if (previewEntity.content.length > TextSanitizer.MAX_PREVIEW_LENGTH) "$it..." else it
                }

            return ReplyPreview(
                messageId = previewEntity.id,
                senderName = if (previewEntity.isFromMe) "You" else peerName,
                contentPreview = contentPreview,
                hasImage = hasImage,
                hasFileAttachment = hasFileAttachment,
                firstFileName = firstFileName,
            )
        }

        // --- Draft operations ---

        /**
         * Save a draft message for a conversation.
         * If content is blank, the draft is deleted instead.
         */
        suspend fun saveDraft(peerHash: String, content: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            if (content.isBlank()) {
                draftDao.deleteDraft(peerHash, activeIdentity.identityHash)
            } else {
                draftDao.insertOrReplaceDraft(
                    DraftEntity(
                        conversationHash = peerHash,
                        identityHash = activeIdentity.identityHash,
                        content = content,
                        updatedTimestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }

        /**
         * Get the saved draft for a conversation, if any.
         */
        suspend fun getDraft(peerHash: String): String? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return draftDao.getDraft(peerHash, activeIdentity.identityHash)?.content
        }

        /**
         * Clear the draft for a conversation (e.g., after sending a message).
         */
        suspend fun clearDraft(peerHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            draftDao.deleteDraft(peerHash, activeIdentity.identityHash)
        }

        /**
         * Observe all drafts for the active identity as a map of peerHash -> draft content.
         * Used by the conversation list to show draft previews.
         */
        fun observeDrafts(): Flow<Map<String, String>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyMap())
                } else {
                    draftDao.observeDraftsForIdentity(identity.identityHash).map { drafts ->
                        drafts.associate { it.conversationHash to it.content }
                    }
                }
            }

        /**
         * Extract the first filename from LXMF file attachments field.
         * Field 5 format: [[filename, size, mimetype, data], ...]
         */
        @Suppress("SwallowedException", "ReturnCount") // JSON parsing errors are expected
        private fun extractFirstFileName(fieldsJson: String): String? {
            return try {
                val json = org.json.JSONObject(fieldsJson)
                val field5 = json.optJSONArray("5") ?: return null
                if (field5.length() == 0) return null
                val firstAttachment = field5.optJSONArray(0) ?: return null
                firstAttachment.optString(0).takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Check if an incoming message with file attachments should supersede
         * a pending file notification, and mark it as superseded if so.
         *
         * Matches notifications by original_message_id (the hash of the file message).
         * This is the most reliable match since the message ID is unique.
         */
        @Suppress("SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth")
        private suspend fun supersedePendingFileNotifications(
            peerHash: String,
            identityHash: String,
            incomingMessageId: String,
            incomingFieldsJson: String,
        ) {
            try {
                // Check if incoming message has file attachments (field 5)
                if (!hasFileAttachments(incomingFieldsJson, incomingMessageId)) return

                android.util.Log.d(
                    "ConversationRepository",
                    "Checking for pending notifications to supersede for message $incomingMessageId",
                )

                // Find pending notifications in this conversation
                val pendingNotifications = messageDao.findPendingFileNotifications(peerHash, identityHash)
                android.util.Log.d(
                    "ConversationRepository",
                    "supersede: Found ${pendingNotifications.size} pending notifications",
                )
                if (pendingNotifications.isEmpty()) return

                // Find matching notification using functional approach
                val match =
                    pendingNotifications.firstNotNullOfOrNull { notification ->
                        tryParseNotificationMatch(notification, incomingMessageId)
                    }

                if (match != null) {
                    val (notification, notificationJson, field16) = match
                    android.util.Log.d(
                        "ConversationRepository",
                        "Superseding pending notification ${notification.id} for message $incomingMessageId",
                    )

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
                android.util.Log.w(
                    "ConversationRepository",
                    "Error checking for pending notifications to supersede: ${e.message}",
                )
            }
        }

        /** Check if incoming message has file attachments (field 5). */
        private fun hasFileAttachments(
            incomingFieldsJson: String,
            incomingMessageId: String,
        ): Boolean {
            val incomingJson = JSONObject(incomingFieldsJson)
            val field5 = incomingJson.optJSONArray("5")
            if (field5 == null) {
                android.util.Log.d(
                    "ConversationRepository",
                    "supersede: No field 5 array in incoming message $incomingMessageId",
                )
                return false
            }
            if (field5.length() == 0) {
                android.util.Log.d(
                    "ConversationRepository",
                    "supersede: Empty field 5 array in incoming message $incomingMessageId",
                )
                return false
            }
            return true
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
                val originalMessageId = pendingInfo?.optString("original_message_id", "").orEmpty()

                android.util.Log.d(
                    "ConversationRepository",
                    "supersede: Comparing incoming=$incomingMessageId vs original=$originalMessageId",
                )

                if (field16 != null && originalMessageId.isNotEmpty() && originalMessageId == incomingMessageId) {
                    Triple(notification, notificationJson, field16)
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConversationRepository",
                    "Failed to parse pending notification ${notification.id}: ${e.message}",
                )
                null
            }
        }

        /**
         * Extract large attachments from fieldsJson and save to disk.
         *
         * If the total fieldsJson size exceeds the threshold, large fields are saved to disk
         * and replaced with file references. This prevents SQLite CursorWindow overflow
         * when loading messages (~2MB row limit).
         *
         * @param messageId Message identifier for storage path
         * @param fieldsJson Original fields JSON string
         * @return Modified fields JSON with file references for large attachments, or original if no extraction needed
         */
        @Suppress("SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth")
        private fun extractLargeAttachments(
            messageId: String,
            fieldsJson: String?,
        ): String? {
            if (fieldsJson == null) return null

            val totalSize = fieldsJson.length
            if (totalSize < AttachmentStorageManager.SIZE_THRESHOLD) {
                return fieldsJson // No extraction needed
            }

            android.util.Log.d(
                "ConversationRepository",
                "Fields size ($totalSize chars) exceeds threshold, extracting large attachments for message $messageId",
            )

            return try {
                val fields = JSONObject(fieldsJson)
                val modifiedFields = JSONObject()
                val keys = fields.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = fields.opt(key)

                    // Special handling for field 5 (file attachments array)
                    // Extract each file's data separately, keep metadata inline
                    if (key == "5" && value is JSONArray) {
                        val extractedArray = extractFileAttachmentsForSent(messageId, value)
                        modifiedFields.put("5", extractedArray)
                        continue
                    }

                    // Get string representation of value for size check
                    val valueStr = value?.toString().orEmpty()

                    if (valueStr.length > AttachmentStorageManager.SIZE_THRESHOLD) {
                        // Save large field to disk
                        val filePath = attachmentStorage.saveAttachment(messageId, key, valueStr)
                        if (filePath != null) {
                            // Replace with file reference
                            val refObj =
                                JSONObject().apply {
                                    put(AttachmentStorageManager.FILE_REF_KEY, filePath)
                                }
                            modifiedFields.put(key, refObj)
                            android.util.Log.i(
                                "ConversationRepository",
                                "Extracted field '$key' (${valueStr.length} chars) to disk: $filePath",
                            )
                        } else {
                            // Save failed, keep original (may still fail at load, but at least try)
                            modifiedFields.put(key, value)
                            android.util.Log.w(
                                "ConversationRepository",
                                "Failed to extract field '$key', keeping inline",
                            )
                        }
                    } else {
                        // Keep small fields inline
                        modifiedFields.put(key, value)
                    }
                }

                val newSize = modifiedFields.toString().length
                android.util.Log.d(
                    "ConversationRepository",
                    "Fields size reduced from $totalSize to $newSize chars",
                )
                modifiedFields.toString()
            } catch (e: Exception) {
                android.util.Log.e("ConversationRepository", "Error extracting attachments", e)
                fieldsJson // Return original on error
            }
        }

        /**
         * Extract file attachment data to disk for sent messages, keeping metadata inline.
         *
         * Input format: [{"filename": "doc.pdf", "size": 12345, "data": "hex..."}, ...]
         * Output format: [{"filename": "doc.pdf", "size": 12345, "_data_ref": "/path/to/file"}, ...]
         *
         * This matches the format used by EventHandler for received messages, ensuring
         * consistent handling in MessageMapper.
         *
         * @param messageId Message identifier for storage path
         * @param attachments Original file attachments array
         * @return Modified array with data extracted to disk
         */
        @Suppress("SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth")
        private fun extractFileAttachmentsForSent(
            messageId: String,
            attachments: JSONArray,
        ): JSONArray {
            val result = JSONArray()

            for (i in 0 until attachments.length()) {
                try {
                    val attachment = attachments.getJSONObject(i)
                    val filename = attachment.optString("filename", "unknown")
                    val size = attachment.optInt("size", 0)
                    val data = attachment.optString("data", "")

                    val modifiedAttachment =
                        JSONObject().apply {
                            put("filename", filename)
                            put("size", size)
                        }

                    // Extract data to disk if present and non-empty
                    if (data.isNotEmpty()) {
                        val filePath =
                            attachmentStorage.saveAttachment(
                                messageId,
                                "5_$i", // Unique key per file: "5_0", "5_1", etc.
                                data,
                            )
                        if (filePath != null) {
                            modifiedAttachment.put("_data_ref", filePath)
                            android.util.Log.d(
                                "ConversationRepository",
                                "Extracted sent file '$filename' data to: $filePath",
                            )
                        } else {
                            // Keep data inline if save failed
                            modifiedAttachment.put("data", data)
                            android.util.Log.w(
                                "ConversationRepository",
                                "Failed to extract sent file '$filename', keeping inline",
                            )
                        }
                    }

                    result.put(modifiedAttachment)
                } catch (e: Exception) {
                    android.util.Log.w(
                        "ConversationRepository",
                        "Failed to process sent file attachment at index $i",
                        e,
                    )
                    // Keep original attachment if processing fails
                    result.put(attachments.opt(i))
                }
            }

            android.util.Log.i(
                "ConversationRepository",
                "Extracted ${attachments.length()} sent file attachment(s) to disk",
            )
            return result
        }
    }
