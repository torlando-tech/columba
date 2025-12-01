package com.lxmf.messenger.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.Transaction
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class Conversation(
    val peerHash: String,
    val peerName: String,
    val peerPublicKey: ByteArray? = null,
    val lastMessage: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int,
)

data class Message(
    val id: String,
    val destinationHash: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val status: String = "sent",
    val fieldsJson: String? = null,
)

@Singleton
class ConversationRepository
    @Inject
    constructor(
        private val conversationDao: ConversationDao,
        private val messageDao: MessageDao,
        private val peerIdentityDao: PeerIdentityDao,
        private val localIdentityDao: LocalIdentityDao,
    ) {
        companion object {
            private const val MAX_MESSAGE_LENGTH = 10_000
            private const val MAX_PEER_NAME_LENGTH = 100

            /**
             * Sanitizes text input for database storage.
             * Removes control characters and enforces length limits.
             */
            private fun sanitizeText(
                text: String,
                maxLength: Int,
            ): String {
                return text
                    .trim()
                    .replace(Regex("[\\p{C}&&[^\n\r]]"), "") // Remove control chars except newlines
                    .replace(Regex("[ \\t]+"), " ") // Normalize spaces/tabs, preserve newlines
                    .take(maxLength)
            }
        }

        /**
         * Get all conversations for the active identity, sorted by most recent activity.
         * Automatically switches when identity changes.
         */
        fun getConversations(): Flow<List<Conversation>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
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
                    conversationDao.getAllConversations(identity.identityHash).map { entities ->
                        android.util.Log.d(
                            "ConversationRepository",
                            "getConversations: Loaded ${entities.size} conversations " +
                                "for identity ${identity.identityHash.take(8)}",
                        )
                        entities.map { it.toConversation() }
                    }
                }
            }
        }

        /**
         * Search conversations by peer name for the active identity.
         * Automatically switches when identity changes.
         */
        fun searchConversations(query: String): Flow<List<Conversation>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    conversationDao.searchConversations(identity.identityHash, query).map { entities ->
                        entities.map { it.toConversation() }
                    }
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
        fun getMessages(peerHash: String): Flow<List<Message>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    messageDao.getMessagesForConversation(peerHash, identity.identityHash).map { entities ->
                        entities.map { it.toMessage() }
                    }
                }
            }
        }

        /**
         * Get messages for a specific conversation with pagination support for the active identity.
         * Initial load: 30 messages, Page size: 30 messages.
         * Messages ordered DESC (newest first) for efficient pagination.
         * Note: Paging source requires a fresh identityHash - doesn't auto-switch on identity change.
         */
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
            val sanitizedContent = sanitizeText(message.content, MAX_MESSAGE_LENGTH)

            // VALIDATION: Check content length after sanitization
            if (sanitizedContent.length > MAX_MESSAGE_LENGTH) {
                android.util.Log.w("ConversationRepository", "Message content exceeds max length, truncating")
            }

            // VALIDATION: Sanitize peer name
            val sanitizedPeerName = sanitizeText(peerName, MAX_PEER_NAME_LENGTH)

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
                    fieldsJson = message.fieldsJson, // LXMF fields (attachments, images, etc.)
                )
            messageDao.insertMessage(messageEntity)
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
        suspend fun getPeerPublicKey(peerHash: String): ByteArray? {
            return peerIdentityDao.getPeerIdentity(peerHash)?.publicKey
        }

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
                    android.util.Log.d(
                        "ConversationRepository",
                        "Peer ${peerIdentity.peerHash}: has public key, length=${peerIdentity.publicKey.size}",
                    )
                    peerIdentity.peerHash to peerIdentity.publicKey
                }

            android.util.Log.d("ConversationRepository", "Returning ${identitiesWithKeys.size} peer identities for restoration")
            return identitiesWithKeys
        }

        suspend fun getMessageById(messageId: String): MessageEntity? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return messageDao.getMessageById(messageId, activeIdentity.identityHash)
        }

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
                peerPublicKey = peerPublicKey,
                lastMessage = lastMessage,
                lastMessageTimestamp = lastMessageTimestamp,
                unreadCount = unreadCount,
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
            )
    }
