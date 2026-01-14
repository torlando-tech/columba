package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.model.InterfaceType
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.GuardianRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.lxmf.messenger.data.repository.Message as DataMessage

/**
 * Application-level service that continuously collects messages and announces from the Reticulum protocol
 * and saves them to the database. This runs independently of any UI components, ensuring
 * messages and announces are received and stored even when no screens are open.
 *
 * This solves the issue where data was only collected when specific screens were active.
 *
 * Note: As of the service-side persistence implementation, announces and messages are now
 * primarily persisted by ServicePersistenceManager in the :reticulum service process.
 * This collector serves as a fallback/safety net and handles:
 * - Notifications for new messages/announces
 * - UI updates via repository flows
 * - De-duplication to avoid double-persistence
 */
@Singleton
class MessageCollector
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val conversationRepository: ConversationRepository,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: ContactRepository,
        private val identityRepository: IdentityRepository,
        private val guardianRepository: GuardianRepository,
        private val guardianCommandProcessor: GuardianCommandProcessor,
        private val notificationHelper: NotificationHelper,
    ) {
        companion object {
            private const val TAG = "MessageCollector"
        }

        // Application-scoped coroutine for background message collection
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Track processed message IDs to avoid duplicates
        private val processedMessageIds = ConcurrentHashMap.newKeySet<String>()

        // Map of peer hashes to names (populated from announces)
        private val peerNames = ConcurrentHashMap<String, String>()

        // Statistics for monitoring
        private val _messagesCollected = MutableStateFlow(0)
        val messagesCollected: StateFlow<Int> = _messagesCollected

        private var isStarted = false

        /**
         * Start collecting messages. Should be called once during app initialization.
         */
        fun startCollecting() {
            if (isStarted) {
                Log.w(TAG, "Message collection already started")
                return
            }

            isStarted = true
            Log.i(TAG, "Starting message collection service")

            // Collect messages from the Reticulum protocol
            scope.launch {
                try {
                    reticulumProtocol.observeMessages().collect { receivedMessage ->
                        // ============ PAIR_ACK CHECK (must run before duplicate check) ============
                        // PAIR_ACK messages may be persisted by ServicePersistenceManager first,
                        // so we need to check and process them before the duplicate check skips them.
                        if (guardianCommandProcessor.isPairAckMessage(receivedMessage)) {
                            val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }
                            // Only process if we haven't already (check in-memory cache)
                            if (receivedMessage.messageHash !in processedMessageIds) {
                                Log.d(TAG, "Processing PAIR_ACK from $sourceHash")
                                guardianCommandProcessor.processPairAck(receivedMessage)
                                processedMessageIds.add(receivedMessage.messageHash)
                            }
                            // Don't store PAIR_ACK messages in the regular message list
                            return@collect
                        }
                        // ============ END PAIR_ACK CHECK ============

                        // De-duplicate: Skip if we've already processed this message in-memory
                        if (receivedMessage.messageHash in processedMessageIds) {
                            Log.d(TAG, "Skipping duplicate message ${receivedMessage.messageHash.take(16)} (in-memory cache)")
                            return@collect
                        }

                        // De-duplicate: Check if message already exists in database
                        // (may have been persisted by ServicePersistenceManager in service process)
                        val existingMessage = conversationRepository.getMessageById(receivedMessage.messageHash)
                        if (existingMessage != null) {
                            processedMessageIds.add(receivedMessage.messageHash) // Add to cache to avoid repeat DB checks
                            Log.d(TAG, "Skipping duplicate message ${receivedMessage.messageHash.take(16)} (already in database)")
                            return@collect
                        }

                        // CRITICAL: Verify the message was sent to the current active identity
                        // This prevents messages from being saved to the wrong identity after switching
                        val activeIdentity = identityRepository.getActiveIdentitySync()
                        if (activeIdentity == null) {
                            Log.w(TAG, "No active identity - skipping message")
                            return@collect
                        }

                        val messageDestHash = receivedMessage.destinationHash.joinToString("") { "%02x".format(it) }
                        if (messageDestHash != activeIdentity.destinationHash) {
                            Log.w(
                                TAG,
                                "Message destination $messageDestHash doesn't match active identity " +
                                    "${activeIdentity.destinationHash} - skipping (sent to different identity)",
                            )
                            return@collect
                        }

                        // ============ PARENTAL CONTROL FILTERING ============
                        val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }
                        val guardianConfig = guardianRepository.getGuardianConfig()

                        // Check if this is a guardian command message
                        if (guardianCommandProcessor.isGuardianCommand(receivedMessage, guardianConfig)) {
                            Log.d(TAG, "Processing guardian command from $sourceHash")
                            val success = guardianCommandProcessor.processCommand(receivedMessage, guardianConfig!!)
                            if (!success) {
                                Log.w(TAG, "Failed to process guardian command")
                            }
                            // Continue processing - guardian messages are also saved as regular messages
                            // so the parent can see their own commands in the chat history
                        }

                        // Note: PAIR_ACK check moved to top of collect block (before duplicate check)

                        // Apply allow-list filtering when device is locked
                        if (guardianConfig != null && guardianConfig.hasGuardian() && guardianConfig.isLocked) {
                            // Guardian is always allowed
                            if (sourceHash != guardianConfig.guardianDestinationHash) {
                                // Check if sender is in the allow list
                                if (!guardianRepository.isContactAllowed(sourceHash)) {
                                    Log.d(TAG, "Blocked message from non-allowed contact: $sourceHash (device locked)")
                                    return@collect // Silently drop
                                }
                            }
                        }
                        // ============ END PARENTAL CONTROL FILTERING ============

                        processedMessageIds.add(receivedMessage.messageHash)
                        _messagesCollected.value++

                        Log.d(TAG, "Received new message #${_messagesCollected.value} from $sourceHash")

                        // Create data message for storage
                        val dataMessage =
                            DataMessage(
                                id = receivedMessage.messageHash,
                                // From sender's perspective
                                destinationHash = sourceHash,
                                content = receivedMessage.content,
                                // Use local reception time for consistent ordering
                                timestamp = System.currentTimeMillis(),
                                isFromMe = false,
                                status = "delivered",
                                // LXMF attachments
                                fieldsJson = receivedMessage.fieldsJson,
                                // Routing info (hop count and receiving interface)
                                receivedHopCount = receivedMessage.receivedHopCount,
                                receivedInterface = receivedMessage.receivedInterface,
                            )

                        // Get peer name from cache, existing conversation, or use formatted hash
                        val peerName = getPeerNameWithFallback(sourceHash)

                        // Save to database - this creates/updates the conversation and adds the message
                        try {
                            // Prefer public key from message (directly from RNS identity cache)
                            // Fall back to peer_identities lookup if not in message
                            val messagePublicKey = receivedMessage.publicKey
                            val publicKey =
                                messagePublicKey
                                    ?: conversationRepository.getPeerPublicKey(sourceHash)

                            // Store public key to peer_identities if we got it from the message
                            // This ensures future lookups will find it
                            if (messagePublicKey != null) {
                                conversationRepository.updatePeerPublicKey(sourceHash, messagePublicKey)
                                Log.d(TAG, "Stored sender's public key for $sourceHash")
                            }

                            // Store sender's icon appearance if present (Sideband/MeshChat interop)
                            receivedMessage.iconAppearance?.let { appearance ->
                                try {
                                    announceRepository.updateIconAppearance(
                                        destinationHash = sourceHash,
                                        iconName = appearance.iconName,
                                        foregroundColor = appearance.foregroundColor,
                                        backgroundColor = appearance.backgroundColor,
                                    )
                                    Log.d(TAG, "Updated icon appearance for $sourceHash: ${appearance.iconName}")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to update icon appearance for $sourceHash", e)
                                }
                            }

                            conversationRepository.saveMessage(sourceHash, peerName, dataMessage, publicKey)
                            Log.d(TAG, "Message saved to database for peer: $peerName ($sourceHash) (hasPublicKey=${publicKey != null})")

                            // Check if sender is a saved peer (favorite)
                            val isFavorite =
                                try {
                                    announceRepository.getAnnounce(sourceHash)?.isFavorite ?: false
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not check if peer is favorite", e)
                                    false
                                }

                            // Show notification for received message
                            try {
                                notificationHelper.notifyMessageReceived(
                                    destinationHash = sourceHash,
                                    peerName = peerName,
                                    // Truncate preview
                                    messagePreview = receivedMessage.content.take(100),
                                    isFavorite = isFavorite,
                                )
                                Log.d(TAG, "Posted notification for message from $peerName (favorite: $isFavorite)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to post message notification", e)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save message to database", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message collection loop", e)
                    // Restart collection after error
                    if (isStarted) {
                        Log.w(TAG, "Restarting message collection after error")
                        isStarted = false
                        startCollecting()
                    }
                }
            }

            // Also observe announces to build peer name mapping, store public keys, and persist to database
            scope.launch {
                try {
                    reticulumProtocol.observeAnnounces().collect { announce ->
                        // Conversations are keyed by destination hash (LXMF destination)
                        // But we also need the identity hash for some operations
                        val destinationHash = announce.destinationHash.joinToString("") { "%02x".format(it) }
                        val identityHash = announce.identity.hash.joinToString("") { "%02x".format(it) }

                        Log.d(TAG, "Processing announce: destHash=$destinationHash, identityHash=$identityHash")

                        // Store the public key mapped to the IDENTITY hash (for Reticulum identity restoration)
                        // Note: Conversations are keyed by destination hash, but peer identities use identity hash
                        val peerHash = destinationHash // For conversation/announce operations
                        val publicKey = announce.identity.publicKey
                        if (publicKey.isNotEmpty()) {
                            try {
                                // CRITICAL: Use identity hash for peer identity storage, not destination hash
                                // This allows Reticulum to properly restore identities (hash must match public key)
                                conversationRepository.updatePeerPublicKey(identityHash, publicKey)
                                Log.d(TAG, "Stored public key for peer identity: $identityHash (${publicKey.size} bytes)")
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not store public key for peer", e)
                            }
                        }

                        // Extract name from app_data using smart parser
                        // Prefers displayName from Python's LXMF.display_name_from_app_data()
                        val appData = announce.appData
                        val peerName =
                            com.lxmf.messenger.reticulum.util.AppDataParser.extractPeerName(
                                appData,
                                peerHash,
                                announce.displayName,
                            )

                        // Cache and update peer name if successfully extracted
                        if (peerName != "Peer ${peerHash.take(8).uppercase()}" && peerName != "Unknown Peer") {
                            peerNames[peerHash] = peerName
                            Log.d(TAG, "Learned peer name: $peerName for $peerHash")

                            // Update existing conversation with the new name
                            conversationRepository.updatePeerName(peerHash, peerName)
                        }

                        // Persist announce to database - this ensures announces are saved even when
                        // Discovered Nodes page is not open
                        // Note: ServicePersistenceManager may have already persisted this announce;
                        // saveAnnounce uses UPSERT, so re-saving is safe (just updates timestamp)
                        try {
                            // For propagation nodes, extract transfer size limit from app_data
                            val propagationTransferLimitKb =
                                if (announce.nodeType.name == "PROPAGATION_NODE") {
                                    val metadata =
                                        com.lxmf.messenger.reticulum.util.AppDataParser
                                            .extractPropagationNodeMetadata(appData)
                                    metadata.transferLimitKb
                                } else {
                                    null
                                }

                            // Check if announce already exists with recent timestamp (saved by service)
                            // If it exists and was updated within last 5 seconds, skip to avoid unnecessary write
                            // Exception: always update propagation nodes missing their transfer limit
                            val existingAnnounce = announceRepository.getAnnounce(peerHash)
                            val fiveSecondsAgo = System.currentTimeMillis() - 5000
                            val needsTransferLimitUpdate =
                                existingAnnounce != null &&
                                    existingAnnounce.nodeType == "PROPAGATION_NODE" &&
                                    existingAnnounce.propagationTransferLimitKb == null &&
                                    propagationTransferLimitKb != null

                            if (existingAnnounce != null &&
                                existingAnnounce.lastSeenTimestamp > fiveSecondsAgo &&
                                !needsTransferLimitUpdate
                            ) {
                                Log.d(TAG, "Announce already persisted by service: $peerName ($peerHash)")
                            } else {
                                announceRepository.saveAnnounce(
                                    destinationHash = peerHash,
                                    peerName = peerName,
                                    publicKey = publicKey,
                                    appData = appData,
                                    hops = announce.hops,
                                    timestamp = announce.timestamp,
                                    nodeType = announce.nodeType.name,
                                    receivingInterface = announce.receivingInterface,
                                    receivingInterfaceType = InterfaceType.fromInterfaceName(announce.receivingInterface).name,
                                    aspect = announce.aspect,
                                    stampCost = announce.stampCost,
                                    stampCostFlexibility = announce.stampCostFlexibility,
                                    peeringCost = announce.peeringCost,
                                    propagationTransferLimitKb = propagationTransferLimitKb,
                                )
                                Log.d(
                                    TAG,
                                    "Persisted announce to database (fallback): $peerName ($peerHash)" +
                                        if (propagationTransferLimitKb != null) " (transfer limit: ${propagationTransferLimitKb}KB)" else "",
                                )
                            }

                            // Check if this announce resolves a pending contact
                            if (publicKey.isNotEmpty()) {
                                try {
                                    val pendingContact = contactRepository.getContact(peerHash)
                                    if (pendingContact?.status == ContactStatus.PENDING_IDENTITY) {
                                        contactRepository.updateContactWithIdentity(peerHash, publicKey)
                                        Log.i(TAG, "Resolved pending contact from announce: $peerHash")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error checking/updating pending contact", e)
                                }
                            }

                            // Show notification for heard announce
                            try {
                                val appDataString =
                                    if (appData != null && appData.isNotEmpty()) {
                                        String(appData, Charsets.UTF_8)
                                    } else {
                                        null
                                    }
                                notificationHelper.notifyAnnounceHeard(
                                    destinationHash = peerHash,
                                    peerName = peerName,
                                    appData = appDataString,
                                )
                                Log.d(TAG, "Posted notification for announce from $peerName")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to post announce notification", e)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist announce to database", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing announces for names", e)
                }
            }
        }

        /**
         * Get a display name for a peer, using cached name or formatted hash
         */
        private fun getPeerName(peerHash: String): String {
            // Check if we have a cached name from announces
            peerNames[peerHash]?.let { return it }

            // Format the hash as a short, readable identifier
            return if (peerHash.length >= 8) {
                "Peer ${peerHash.take(8).uppercase()}"
            } else {
                "Unknown Peer"
            }
        }

        /**
         * Get peer name with fallback - checks cache, database, then uses formatted hash
         */
        private suspend fun getPeerNameWithFallback(peerHash: String): String {
            // First check our in-memory cache from announces
            peerNames[peerHash]?.let {
                Log.d(TAG, "Found peer name in cache: $it")
                return it
            }

            // Check if we have an existing conversation with this peer
            try {
                val existingConversation = conversationRepository.getConversation(peerHash)
                if (existingConversation != null && existingConversation.peerName != "Unknown" &&
                    !existingConversation.peerName.startsWith("Peer ")
                ) {
                    // Cache it for future use
                    peerNames[peerHash] = existingConversation.peerName
                    Log.d(TAG, "Found peer name in database: ${existingConversation.peerName}")
                    return existingConversation.peerName
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking database for peer name", e)
            }

            // Fall back to formatted hash
            val fallbackName =
                if (peerHash.length >= 8) {
                    "Peer ${peerHash.take(8).uppercase()}"
                } else {
                    "Unknown Peer"
                }
            Log.d(TAG, "Using fallback name for peer: $fallbackName")
            return fallbackName
        }

        /**
         * Update peer name (can be called when announces are received)
         */
        fun updatePeerName(
            peerHash: String,
            name: String,
        ) {
            if (name.isNotBlank()) {
                peerNames[peerHash] = name
                Log.d(TAG, "Updated peer name: $name for $peerHash")

                // Update in database too
                scope.launch {
                    try {
                        conversationRepository.updatePeerName(peerHash, name)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update peer name in database", e)
                    }
                }
            }
        }

        /**
         * Stop collecting messages (for cleanup)
         */
        fun stopCollecting() {
            isStarted = false
            // CRITICAL: Clear peer names cache to prevent stale data after identity switch
            // This ensures fresh data is fetched from database or announces when restarted
            peerNames.clear()
            processedMessageIds.clear()
            Log.i(TAG, "Stopped message collection service (caches cleared)")
        }

        /**
         * Get statistics about collected messages
         */
        fun getStats(): String {
            return "Messages collected: ${_messagesCollected.value}, Known peers: ${peerNames.size}"
        }
    }
