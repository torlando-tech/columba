package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
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
 * This manager periodically:
 * 1. Checks pending contacts against Reticulum's identity cache
 * 2. Requests paths for contacts that need resolution
 * 3. Marks contacts as UNRESOLVED after 24 hours
 */
@Singleton
class IdentityResolutionManager
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) {
        companion object {
            private const val TAG = "IdentityResolutionMgr"

            // Check interval: 15 minutes
            private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

            // Resolution timeout: 24 hours
            private const val RESOLUTION_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        }

        private var resolutionJob: Job? = null

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
        }

        /**
         * Stop the periodic identity resolution checks.
         */
        fun stop() {
            Log.d(TAG, "Stopping identity resolution manager")
            resolutionJob?.cancel()
            resolutionJob = null
        }

        /**
         * Check all pending contacts and attempt to resolve their identities.
         */
        private suspend fun checkPendingContacts() {
            val pendingContacts =
                contactRepository.getContactsByStatus(listOf(ContactStatus.PENDING_IDENTITY))

            if (pendingContacts.isEmpty()) {
                Log.d(TAG, "No pending contacts to resolve")
                return
            }

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
                        // Not in cache, request path to trigger network search
                        Log.d(TAG, "Requesting path for ${contact.destinationHash.take(8)}...")
                        reticulumProtocol.requestPath(destHashBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing contact ${contact.destinationHash.take(8)}...", e)
                }
            }
        }

        /**
         * Manually trigger a resolution check for a specific contact.
         * Used when user taps "retry" on an unresolved contact.
         */
        suspend fun retryResolution(destinationHash: String) {
            Log.d(TAG, "Retry resolution for ${destinationHash.take(8)}...")

            // Reset status to PENDING_IDENTITY and restart 48-hour timeout
            contactRepository.resetContactForRetry(destinationHash)

            // Request path on network
            val destHashBytes =
                destinationHash
                    .chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()

            reticulumProtocol.requestPath(destHashBytes)
        }
    }
