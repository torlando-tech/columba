package com.lxmf.messenger.service.persistence

import android.content.Context
import android.util.Log
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity
import com.lxmf.messenger.data.util.HashUtils
import com.lxmf.messenger.data.util.TextSanitizer
import com.lxmf.messenger.service.di.ServiceDatabaseProvider
import com.lxmf.messenger.service.util.PeerNameResolver
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
    private val settingsAccessor: ServiceSettingsAccessor,
) {
    companion object {
        private const val TAG = "ServicePersistenceManager"
    }

    /**
     * Check if a sender should be blocked based on privacy settings.
     * Returns true if the message should be blocked, false if it should be allowed.
     * Fails open: if checking contact status fails, the message is allowed through.
     */
    private suspend fun shouldBlockUnknownSender(
        sourceHash: String,
        identityHash: String,
    ): Boolean =
        try {
            if (settingsAccessor.getBlockUnknownSenders()) {
                val isKnownContact = contactDao.contactExists(sourceHash, identityHash)
                if (!isKnownContact) {
                    Log.d(TAG, "Blocking message from unknown sender: ${sourceHash.take(16)}")
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            // Fail open: if we can't check the contact list, allow the message through
            Log.w(TAG, "Error checking contact status, allowing message: ${e.message}")
            false
        }

    private val database: ColumbaDatabase by lazy {
        ServiceDatabaseProvider.getDatabase(context)
    }

    private val announceDao by lazy { database.announceDao() }
    private val contactDao by lazy { database.contactDao() }
    private val messageDao by lazy { database.messageDao() }
    private val conversationDao by lazy { database.conversationDao() }
    private val localIdentityDao by lazy { database.localIdentityDao() }
    private val peerIdentityDao by lazy { database.peerIdentityDao() }

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
        propagationTransferLimitKb: Int?,
    ) {
        scope.launch {
            try {
                // Preserve favorite status if announce already exists
                // Note: Icons are stored separately in peer_icons table (from LXMF messages)
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
                        propagationTransferLimitKb = propagationTransferLimitKb,
                        computedIdentityHash = HashUtils.computeIdentityHash(publicKey),
                    )

                announceDao.upsertAnnounce(entity)
                Log.d(TAG, "Service persisted announce: ${destinationHash.take(16)}")
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
     *
     * @return true if the message was persisted (or already exists), false if blocked or error.
     *         The caller should only broadcast to the app process if this returns true.
     */
    @Suppress("LongParameterList", "LongMethod", "ReturnCount") // Parameters mirror MessageEntity fields; early returns for clarity
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
        receivedRssi: Int? = null,
        receivedSnr: Float? = null,
    ): Boolean {
        try {
            // Get active identity to scope the message correctly
            val activeIdentity = localIdentityDao.getActiveIdentitySync()
            if (activeIdentity == null) {
                Log.w(TAG, "No active identity - cannot persist message")
                return false
            }

            // Check if we should block this sender
            if (shouldBlockUnknownSender(sourceHash, activeIdentity.identityHash)) {
                return false
            }

            // Check for duplicates (composite key is id + identityHash)
            val existingMessage = messageDao.getMessageById(messageHash, activeIdentity.identityHash)
            if (existingMessage != null) {
                Log.d(TAG, "Message already exists - skipping duplicate: $messageHash")
                // Return true for duplicates - message exists, app should still show notification
                return true
            }

            // Create/update conversation
            val existingConversation =
                conversationDao.getConversation(
                    sourceHash,
                    activeIdentity.identityHash,
                )

            // Sanitize content to remove control characters and normalize whitespace
            val sanitizedContent = TextSanitizer.sanitizeMessage(content)
            val sanitizedPreview = TextSanitizer.sanitizePreview(content)

            // Get peer name using centralized resolver, then sanitize
            val resolvedName =
                PeerNameResolver.resolve(
                    peerHash = sourceHash,
                    cachedName = existingConversation?.peerName,
                    contactNicknameLookup = {
                        activeIdentity?.let {
                            contactDao.getContact(sourceHash, it.identityHash)?.customNickname
                        }
                    },
                    announcePeerNameLookup = {
                        announceDao.getAnnounce(sourceHash)?.peerName
                    },
                )
            val peerName = TextSanitizer.sanitizePeerName(resolvedName)

            // Insert/update conversation
            if (existingConversation != null) {
                // Update peerName if we resolved a better name (nickname or announce)
                val updatedPeerName =
                    if (PeerNameResolver.isValidPeerName(resolvedName)) peerName else existingConversation.peerName
                val updated =
                    existingConversation.copy(
                        peerName = updatedPeerName,
                        lastMessage = sanitizedPreview,
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
                        lastMessage = sanitizedPreview,
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
                    content = sanitizedContent,
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
                    receivedRssi = receivedRssi,
                    receivedSnr = receivedSnr,
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

            Log.d(TAG, "Service persisted message from ${sourceHash.take(16)}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting message in service from $sourceHash", e)
            return false
        }
    }

    /**
     * Check if an announce exists (for de-duplication in app process).
     */
    suspend fun announceExists(destinationHash: String): Boolean =
        try {
            announceDao.announceExists(destinationHash)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking announce existence: $destinationHash", e)
            false
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
     * Look up display name for a peer with priority:
     * 1. Contact's custom nickname (user-set)
     * 2. Announce peer name (from network)
     * 3. Announce peer name by identity hash (for LXST calls)
     * 4. null (caller should use formatted hash as fallback)
     *
     * Uses PeerNameResolver for standard lookups, with additional identity hash fallback.
     */
    suspend fun lookupDisplayName(destinationHash: String): String? {
        return try {
            Log.d(TAG, "Looking up display name for: $destinationHash")

            // Get active identity for contact lookup
            val activeIdentity = localIdentityDao.getActiveIdentitySync()
            Log.d(TAG, "Active identity: ${activeIdentity?.identityHash?.take(16)}")

            // Use centralized resolver for standard lookups
            val resolvedName =
                PeerNameResolver.resolve(
                    peerHash = destinationHash,
                    contactNicknameLookup = {
                        activeIdentity?.let {
                            contactDao.getContact(destinationHash, it.identityHash)?.customNickname
                        }
                    },
                    announcePeerNameLookup = {
                        announceDao.getAnnounce(destinationHash)?.peerName
                    },
                )

            // If resolver found a valid name (not fallback), return it
            if (PeerNameResolver.isValidPeerName(resolvedName)) {
                return resolvedName
            }

            // For LXST calls, the hash might be the identity hash, not the destination hash.
            // Try to find an announce by matching identity hash (computed from public key).
            Log.d(TAG, "Trying identity hash lookup...")
            val announceByIdentity = findAnnounceByIdentityHash(destinationHash)
            if (announceByIdentity != null && !announceByIdentity.peerName.isNullOrBlank()) {
                Log.d(TAG, "Found by identity hash")
                return announceByIdentity.peerName
            }

            Log.d(TAG, "No display name found for $destinationHash")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up display name for $destinationHash", e)
            null
        }
    }

    /**
     * Find an announce by identity hash using indexed column lookup.
     * Identity hash = first 16 bytes of SHA256(publicKey) as hex.
     */
    private suspend fun findAnnounceByIdentityHash(identityHash: String): AnnounceEntity? =
        try {
            announceDao.getAnnounceByIdentityHash(identityHash.lowercase())
        } catch (e: Exception) {
            Log.e(TAG, "Error finding announce by identity hash", e)
            null
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
