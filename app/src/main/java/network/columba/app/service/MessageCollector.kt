package network.columba.app.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.entity.ContactStatus
import network.columba.app.data.db.entity.PeerIconEntity
import network.columba.app.data.model.InterfaceType
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.notifications.NotificationHelper
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.IconAppearance
import network.columba.app.rns.host.util.PeerNameResolver
import network.columba.app.telemetry.MemoryDiagnosticCounter
import network.columba.app.telemetry.MemoryDiagnostics
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import network.columba.app.data.repository.Message as DataMessage

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
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        private val conversationRepository: ConversationRepository,
        private val announceRepository: AnnounceRepository,
        private val contactRepository: ContactRepository,
        private val identityRepository: IdentityRepository,
        private val notificationHelper: NotificationHelper,
        private val peerIconDao: PeerIconDao,
        private val messageDao: MessageDao,
    ) {
        companion object {
            private const val TAG = "MessageCollector"
            private const val NOTIFICATION_BATCH_SIZE = 32
            private const val NOTIFICATION_RETRY_BASE_MS = 1_000L
            private const val NOTIFICATION_RETRY_MAX_MS = 30_000L
        }

        // Application-scoped coroutine for background message collection
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Single-owner lifecycle: every started job is tracked so stop/start cannot
        // leave duplicate persistence or notification processors running.
        private var messageCollectionJob: Job? = null
        private var announceCollectionJob: Job? = null
        private var notificationCollectionJob: Job? = null

        // Map of peer hashes to names (populated from announces)
        private val peerNames = ConcurrentHashMap<String, String>()

        init {
            MemoryDiagnostics.registerCounter(MemoryDiagnosticCounter.PEER_NAMES) { peerNames.size }
        }

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

            // Collect messages from the Reticulum protocol. Database existence is not
            // notification completion: pending notifications are reconciled separately below.
            suspend fun collectIncomingMessages() {
                try {
                    rnsLxmf.observeMessages().collect { receivedMessage ->
                        // Check if message already exists in database
                        // (may have been persisted by ServicePersistenceManager in service process)
                        val existingMessage = conversationRepository.getMessageById(receivedMessage.messageHash)
                        if (existingMessage != null) {
                            Log.d(TAG, "Message ${receivedMessage.messageHash.take(16)} already in database - persisting icon only")

                            // Service-side persistence also saves icons; retain callback enrichment
                            // for messages stored through paths without icon data.
                            val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }

                            persistIconAppearance(sourceHash, receivedMessage.iconAppearance)

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

                        _messagesCollected.value++

                        val sourceHash = receivedMessage.sourceHash.joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Received new message #${_messagesCollected.value} from $sourceHash")

                        // Create data message for storage
                        val now = System.currentTimeMillis()
                        val dataMessage =
                            DataMessage(
                                id = receivedMessage.messageHash,
                                // From sender's perspective
                                destinationHash = sourceHash,
                                content = receivedMessage.content,
                                // Use sender's timestamp for display; receivedAt for sort ordering
                                timestamp = receivedMessage.timestamp,
                                isFromMe = false,
                                status = "delivered",
                                // LXMF attachments
                                fieldsJson = receivedMessage.fieldsJson,
                                // Routing info (hop count and receiving interface)
                                receivedHopCount = receivedMessage.receivedHopCount,
                                receivedInterface = receivedMessage.receivedInterface,
                                // Signal quality metrics (RNode/BLE; null on TCP/Auto/propagated)
                                receivedRssi = receivedMessage.receivedRssi,
                                receivedSnr = receivedMessage.receivedSnr,
                                // LXMF delivery method ("opportunistic" / "direct" / "propagated"
                                // / "paper"). Surfaced in MessageDetailScreen for received
                                // messages — most of the path-info cards (hop count, interface,
                                // RSSI/SNR) are null for propagation-fetched messages, so
                                // without this the detail screen renders essentially empty.
                                deliveryMethod = receivedMessage.deliveryMethod,
                                // Local reception time for sort ordering
                                receivedAt = now,
                                signatureStatus = receivedMessage.signatureStatus,
                                signatureEvidence = receivedMessage.signatureEvidence,
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

                            persistIconAppearance(sourceHash, receivedMessage.iconAppearance)

                            conversationRepository.saveMessage(sourceHash, peerName, dataMessage, publicKey)
                            Log.d(TAG, "Message saved to database for peer ${sourceHash.take(16)} (hasPublicKey=${publicKey != null})")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save message to database", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in message collection loop", e)
                    // Restart only this loop; notification processing stays single-owner.
                    if (isStarted) {
                        Log.w(TAG, "Restarting message collection after error")
                        messageCollectionJob = scope.launch { collectIncomingMessages() }
                    }
                }
            }
            messageCollectionJob = scope.launch { collectIncomingMessages() }

            // Also observe announces to build peer name mapping, store public keys, and persist to database
            announceCollectionJob = scope.launch {
                try {
                    rnsCore.observeAnnounces().collect { announce ->
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
                            network.columba.app.reticulum.util.AppDataParser.extractPeerName(
                                appData,
                                peerHash,
                                announce.displayName,
                            )

                        // Cache and update peer name if successfully extracted
                        if (PeerNameResolver.isValidPeerName(peerName)) {
                            peerNames[peerHash] = peerName
                            Log.d(TAG, "Learned peer name for ${peerHash.take(16)}")

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
                                        network.columba.app.reticulum.util.AppDataParser
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
                                Log.d(TAG, "Announce already persisted by service: ${peerHash.take(16)}")
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
                                    receivingInterfaceType = InterfaceType.fromName(announce.receivingInterface).storageName,
                                    aspect = announce.aspect,
                                    stampCost = announce.stampCost,
                                    stampCostFlexibility = announce.stampCostFlexibility,
                                    peeringCost = announce.peeringCost,
                                    propagationTransferLimitKb = propagationTransferLimitKb,
                                )
                                Log.d(
                                    TAG,
                                    "Persisted announce to database (fallback): ${peerHash.take(16)}" +
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
                                notificationHelper.notifyAnnounceHeard(
                                    destinationHash = peerHash,
                                    peerName = peerName,
                                    hops = announce.hops,
                                    interfaceType = InterfaceType.fromName(announce.receivingInterface),
                                    receivingInterface = announce.receivingInterface,
                                )
                                Log.d(TAG, "Posted notification for announce")
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

            notificationCollectionJob = scope.launch { observePendingNotifications() }
        }

        private suspend fun persistIconAppearance(
            sourceHash: String,
            appearance: IconAppearance?,
        ) {
            if (appearance == null) return
            if (appearance.iconName.isEmpty() ||
                appearance.foregroundColor.isEmpty() ||
                appearance.backgroundColor.isEmpty()
            ) {
                return
            }
            try {
                peerIconDao.upsertIcon(
                    PeerIconEntity(
                        destinationHash = sourceHash,
                        iconName = appearance.iconName,
                        foregroundColor = appearance.foregroundColor,
                        backgroundColor = appearance.backgroundColor,
                        updatedTimestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save icon appearance for $sourceHash", e)
            }
        }

        /**
         * Reconcile durable notification state owned by this app process. Every message-table
         * invalidation for the active identity is a signal, even when the pending count is
         * unchanged, because the marker - not the message row - means "notified".
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun observePendingNotifications() {
            identityRepository.activeIdentity
                .flatMapLatest { identity ->
                    if (identity == null) {
                        emptyFlow()
                    } else {
                        messageDao
                            .observePendingNotificationCount(identity.identityHash)
                            .map { identity.identityHash }
                    }
                }
                .onEach { identityHash -> reconcilePendingNotifications(identityHash) }
                .retryWhen { cause, attempt ->
                    if (cause is CancellationException) throw cause
                    Log.w(TAG, "Notification observation interrupted; retrying", cause)
                    delay(
                        minOf(
                            NOTIFICATION_RETRY_MAX_MS,
                            NOTIFICATION_RETRY_BASE_MS shl attempt.coerceAtMost(5).toInt(),
                        ),
                    )
                    true
                }.collect()
        }

        /**
         * Process pending rows in bounded batches. A normal return from
         * [NotificationHelper.notifyMessageReceived] means handled (posted or intentionally
         * suppressed); a throw stops this pass without acknowledging so the row is retried
         * on a later invalidation or collector restart.
         */
        private suspend fun reconcilePendingNotifications(identityHash: String) {
            while (identityRepository.getActiveIdentitySync()?.identityHash == identityHash) {
                val batch = messageDao.getPendingNotificationMessages(identityHash, NOTIFICATION_BATCH_SIZE)
                if (batch.isEmpty()) return
                for (pending in batch) {
                    if (identityRepository.getActiveIdentitySync()?.identityHash != identityHash) return
                    try {
                        if (!pending.isRead) {
                            val peerName = getPeerNameWithFallback(pending.conversationHash)
                            notificationHelper.notifyMessageReceived(
                                destinationHash = pending.conversationHash,
                                peerName = peerName,
                                messagePreview = pending.preview,
                                isFavorite = isSavedPeer(pending.conversationHash),
                            )
                        }
                        messageDao.ackNotificationHandled(pending.id, identityHash)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process pending notification ${pending.id.take(16)}", e)
                        return
                    }
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
            return PeerNameResolver.formatHashAsFallback(peerHash)
        }

        /**
         * Determine whether [sourceHash] is a "saved peer" for notification purposes.
         *
         * A peer is considered saved if it is either:
         *  - flagged as a favorite on its announce row (legacy "Save Peer" via the
         *    announce stream star), OR
         *  - present in the active identity's contacts table (written by the
         *    "Save to Contacts" flows in Chats/Messaging, QR import, manual entry,
         *    and propagation-node relay setup, which do not touch the announce flag).
         *
         * Both signals must be checked: the contacts table is the source of truth for
         * "saved" as the user experiences it, and the legacy flag is kept so peers saved
         * before the contacts table existed continue to notify. Either signal being true
         * is sufficient.
         */
        private suspend fun isSavedPeer(sourceHash: String): Boolean {
            val favoriteFlag =
                try {
                    announceRepository.getAnnounce(sourceHash)?.isFavorite ?: false
                } catch (e: Exception) {
                    Log.w(TAG, "Could not check if peer is favorite", e)
                    false
                }
            if (favoriteFlag) return true

            return try {
                contactRepository.hasContact(sourceHash)
            } catch (e: Exception) {
                Log.w(TAG, "Could not check if peer is a saved contact", e)
                false
            }
        }

        /**
         * Get peer name with fallback - uses PeerNameResolver for consistent lookup across the app
         */
        private suspend fun getPeerNameWithFallback(peerHash: String): String {
            val resolvedName =
                PeerNameResolver.resolve(
                    peerHash = peerHash,
                    cachedName = peerNames[peerHash],
                    contactNicknameLookup = {
                        contactRepository.getContact(peerHash)?.customNickname
                    },
                    announcePeerNameLookup = {
                        announceRepository.getAnnounce(peerHash)?.peerName
                    },
                    conversationPeerNameLookup = {
                        conversationRepository.getConversation(peerHash)?.peerName
                    },
                )

            // Cache the resolved name if it's valid
            if (PeerNameResolver.isValidPeerName(resolvedName)) {
                peerNames[peerHash] = resolvedName
            }

            return resolvedName
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
                Log.d(TAG, "Updated peer name for ${peerHash.take(16)}")

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
            messageCollectionJob?.cancel()
            messageCollectionJob = null
            announceCollectionJob?.cancel()
            announceCollectionJob = null
            notificationCollectionJob?.cancel()
            notificationCollectionJob = null
            // CRITICAL: Clear peer names cache to prevent stale data after identity switch
            // This ensures fresh data is fetched from database or announces when restarted
            peerNames.clear()
            Log.i(TAG, "Stopped message collection service (caches cleared)")
        }

        /**
         * Get statistics about collected messages
         */
        fun getStats(): String = "Messages collected: ${_messagesCollected.value}, Known peers: ${peerNames.size}"
    }
