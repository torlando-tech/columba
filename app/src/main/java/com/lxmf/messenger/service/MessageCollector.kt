package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ConversationRepository
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
 */
@Singleton
class MessageCollector
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val conversationRepository: ConversationRepository,
        private val announceRepository: AnnounceRepository,
        private val identityRepository: IdentityRepository,
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
                        // De-duplicate: Skip if we've already processed this message
                        if (receivedMessage.messageHash in processedMessageIds) {
                            Log.d(TAG, "Skipping duplicate message ${receivedMessage.messageHash.take(16)} (from replay buffer)")
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

                        processedMessageIds.add(receivedMessage.messageHash)
                        _messagesCollected.value++

                        val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Received new message #${_messagesCollected.value} from $sourceHash")

                        // Create data message for storage
                        val dataMessage =
                            DataMessage(
                                id = receivedMessage.messageHash,
                                destinationHash = sourceHash, // From sender's perspective
                                content = receivedMessage.content,
                                timestamp = System.currentTimeMillis(), // Use local reception time for consistent ordering
                                isFromMe = false,
                                status = "delivered",
                                fieldsJson = receivedMessage.fieldsJson, // LXMF attachments
                            )

                        // Get peer name from cache, existing conversation, or use formatted hash
                        val peerName = getPeerNameWithFallback(sourceHash)

                        // Save to database - this creates/updates the conversation and adds the message
                        try {
                            // Look up public key from peer_identities if available
                            // This must be done OUTSIDE the transaction to avoid nesting issues
                            val publicKey = conversationRepository.getPeerPublicKey(sourceHash)

                            conversationRepository.saveMessage(sourceHash, peerName, dataMessage, publicKey)
                            Log.d(TAG, "Message saved to database for peer: $peerName ($sourceHash)")

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
                                    messagePreview = receivedMessage.content.take(100), // Truncate preview
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
                        val appData = announce.appData
                        val peerName =
                            com.lxmf.messenger.reticulum.util.AppDataParser.extractPeerName(
                                appData,
                                peerHash,
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
                        try {
                            announceRepository.saveAnnounce(
                                destinationHash = peerHash,
                                peerName = peerName,
                                publicKey = publicKey,
                                appData = appData,
                                hops = announce.hops,
                                timestamp = announce.timestamp,
                                nodeType = announce.nodeType.name,
                                receivingInterface = announce.receivingInterface,
                                aspect = announce.aspect,
                            )
                            Log.d(TAG, "Persisted announce to database: $peerName ($peerHash)")

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
