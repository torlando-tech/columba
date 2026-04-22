package network.columba.app.service

import android.util.Log
import network.columba.app.data.db.entity.ContactStatus
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.reticulum.protocol.ReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages background identity resolution for pending contacts.
 *
 * This manager:
 * 1. Requests paths for the 3 most recent conversations at startup
 * 2. Periodically checks pending contacts (every 3h, gives up after 24h)
 * 3. Persists transport data for crash resilience
 *
 * All path requests go through [requestPathIfNeeded] which checks hasPath first.
 */
@Singleton
class IdentityResolutionManager
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
        private val conversationRepository: ConversationRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) {
        companion object {
            private const val TAG = "IdentityResolutionMgr"

            // Check interval: 3 hours
            private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L

            // Resolution timeout: 24 hours
            private const val RESOLUTION_TIMEOUT_MS = 24 * 60 * 60 * 1000L

            // Stagger path requests to avoid flooding the network
            private const val PATH_REQUEST_STAGGER_MS = 2_000L

            // Delay before startup sweep to let Reticulum initialize
            private const val STARTUP_SWEEP_DELAY_MS = 5_000L

            // Number of recent conversations to request paths for at startup
            private const val STARTUP_SWEEP_LIMIT = 3
        }

        private var resolutionJob: Job? = null
        private var startupSweepJob: Job? = null

        /**
         * Start the periodic identity resolution checks.
         * Should be called after Reticulum is initialized.
         */
        fun start(scope: CoroutineScope) {
            if (resolutionJob?.isActive == true) {
                Log.d(TAG, "Resolution manager already running")
                return
            }

            Log.d(TAG, "Starting identity resolution manager")
            resolutionJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            checkPendingContacts()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during identity resolution check", e)
                        }

                        delay(CHECK_INTERVAL_MS)
                    }
                }

            // One-shot startup sweep: request paths for 3 most recent conversations
            startupSweepJob =
                scope.launch(Dispatchers.IO) {
                    delay(STARTUP_SWEEP_DELAY_MS)
                    requestPathsForRecentConversations()
                }
        }

        /**
         * Stop the periodic identity resolution checks.
         */
        fun stop() {
            Log.d(TAG, "Stopping identity resolution manager")
            resolutionJob?.cancel()
            resolutionJob = null
            startupSweepJob?.cancel()
            startupSweepJob = null
        }

        /**
         * Check all pending contacts and attempt to resolve their identities.
         */
        private suspend fun checkPendingContacts() {
            val pendingContacts =
                contactRepository.getContactsByStatus(listOf(ContactStatus.PENDING_IDENTITY))

            if (pendingContacts.isEmpty()) {
                Log.d(TAG, "No pending contacts to resolve")
            } else {
                Log.d(TAG, "Checking ${pendingContacts.size} pending contact(s)")

                val currentTime = System.currentTimeMillis()

                for (contact in pendingContacts) {
                    try {
                        // Check if resolution has timed out (24 hours)
                        val age = currentTime - contact.addedTimestamp
                        if (age > RESOLUTION_TIMEOUT_MS) {
                            Log.d(TAG, "Contact ${contact.destinationHash.take(8)}... timed out after 24h")
                            contactRepository.updateContactStatus(
                                destinationHash = contact.destinationHash,
                                status = ContactStatus.UNRESOLVED,
                            )
                            continue
                        }

                        // Try to recall identity from Reticulum's cache
                        val destHashBytes =
                            contact.destinationHash
                                .chunked(2)
                                .map { it.toInt(16).toByte() }
                                .toByteArray()

                        val identity = reticulumProtocol.recallIdentity(destHashBytes)

                        if (identity != null && identity.publicKey != null) {
                            // Identity found! Update the contact
                            Log.i(TAG, "Resolved identity for ${contact.destinationHash.take(8)}...")
                            contactRepository.updateContactWithIdentity(
                                destinationHash = contact.destinationHash,
                                publicKey = identity.publicKey,
                            )
                        } else {
                            // Not in cache, request path (guarded) to trigger network search
                            requestPathIfNeeded(destHashBytes, contact.destinationHash)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing contact ${contact.destinationHash.take(8)}...", e)
                    }
                }
            }

            // Periodically persist transport data (paths, destinations) to survive process kills
            try {
                reticulumProtocol.persistTransportData()
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting transport data", e)
            }
        }

        /**
         * Request a path for a single contact if one doesn't already exist.
         * Used when adding a new contact or opening a conversation.
         */
        suspend fun requestPathForContact(destinationHash: String) {
            try {
                val destHashBytes =
                    destinationHash
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()

                requestPathIfNeeded(destHashBytes, destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting path for ${destinationHash.take(8)}...", e)
            }
        }

        /**
         * Request paths for the N most recent conversations.
         * Called once at startup to ensure the most relevant peers are reachable.
         */
        private suspend fun requestPathsForRecentConversations() {
            try {
                val recentPeerHashes = conversationRepository.getRecentPeerHashes(STARTUP_SWEEP_LIMIT)

                if (recentPeerHashes.isEmpty()) {
                    Log.d(TAG, "Startup sweep: no recent conversations")
                    return
                }

                Log.d(TAG, "Startup sweep: requesting paths for ${recentPeerHashes.size} recent conversation(s)")

                for (peerHash in recentPeerHashes) {
                    try {
                        val destHashBytes =
                            peerHash
                                .chunked(2)
                                .map { it.toInt(16).toByte() }
                                .toByteArray()

                        requestPathIfNeeded(destHashBytes, peerHash)
                    } catch (e: Exception) {
                        Log.e(TAG, "Startup sweep: error for ${peerHash.take(8)}...", e)
                    }
                    delay(PATH_REQUEST_STAGGER_MS)
                }

                Log.d(TAG, "Startup sweep complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error during startup path sweep", e)
            }
        }

        /**
         * Manually trigger a resolution check for a specific contact.
         * Used when user taps "retry" on an unresolved contact.
         *
         * Note: The caller (ContactsViewModel.retryIdentityResolution) is responsible
         * for calling contactRepository.resetContactForRetry() before invoking this.
         */
        suspend fun retryResolution(destinationHash: String) {
            Log.d(TAG, "Retry resolution for ${destinationHash.take(8)}...")

            try {
                val destHashBytes =
                    destinationHash
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()

                requestPathIfNeeded(destHashBytes, destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error in retryResolution for ${destinationHash.take(8)}...", e)
            }
        }

        /**
         * Central path request method — checks hasPath before requesting.
         * All path requests in this class must go through here.
         */
        private suspend fun requestPathIfNeeded(
            destHashBytes: ByteArray,
            displayHash: String,
        ) {
            if (reticulumProtocol.hasPath(destHashBytes)) {
                Log.d(TAG, "Path exists for ${displayHash.take(8)}..., skipping request")
                return
            }

            Log.d(TAG, "Requesting path for ${displayHash.take(8)}...")
            reticulumProtocol.requestPath(destHashBytes)
        }
    }
