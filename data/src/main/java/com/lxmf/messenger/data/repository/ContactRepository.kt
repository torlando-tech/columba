package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.model.EnrichedContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing user contacts.
 *
 * Contacts can be added from:
 * - Starred announces from the network
 * - QR code scans
 * - Manual entry
 * - Active conversations
 *
 * This repository combines contacts with network status (announces) and conversation data.
 */
@Singleton
class ContactRepository
    @Inject
    constructor(
        private val contactDao: ContactDao,
        private val localIdentityDao: LocalIdentityDao,
        private val announceDao: AnnounceDao,
    ) {
        /**
         * Get enriched contacts with data from announces and conversations for the active identity.
         * Combines contact data with network status and conversation info.
         * Automatically switches when identity changes.
         *
         * Online threshold is set to 5 minutes - peers seen within this time are considered online.
         */
        fun getEnrichedContacts(): Flow<List<EnrichedContact>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    val onlineThreshold = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes
                    contactDao.getEnrichedContacts(identity.identityHash, onlineThreshold)
                }
            }
        }

        /**
         * Get all contacts for the active identity (basic entity data only, no enrichment).
         * Automatically switches when identity changes.
         */
        fun getAllContacts(): Flow<List<ContactEntity>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    contactDao.getAllContacts(identity.identityHash)
                }
            }
        }

        /**
         * Get a specific contact by destination hash for the active identity
         */
        suspend fun getContact(destinationHash: String): ContactEntity? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return contactDao.getContact(destinationHash, activeIdentity.identityHash)
        }

        /**
         * Get a specific contact as Flow (for observing changes) for the active identity.
         * Automatically switches when identity changes.
         */
        fun getContactFlow(destinationHash: String): Flow<ContactEntity?> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(null)
                } else {
                    contactDao.getContactFlow(destinationHash, identity.identityHash)
                }
            }
        }

        /**
         * Check if a contact exists for the active identity (for star button state in announce stream)
         */
        suspend fun hasContact(destinationHash: String): Boolean {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return false
            return contactDao.contactExists(destinationHash, activeIdentity.identityHash)
        }

        /**
         * Check if a contact exists as Flow (for observing star button state) for the active identity.
         * Automatically switches when identity changes.
         */
        fun hasContactFlow(destinationHash: String): Flow<Boolean> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(false)
                } else {
                    contactDao.contactExistsFlow(destinationHash, identity.identityHash)
                }
            }
        }

        /**
         * Add a contact from an announce (when user stars an announce).
         * Display name will automatically use the announce's peerName via database COALESCE.
         *
         * @param destinationHash The destination hash from the announce
         * @param publicKey The public key from the announce
         */
        suspend fun addContactFromAnnounce(
            destinationHash: String,
            publicKey: ByteArray,
        ): Result<Unit> {
            return try {
                // Get active identity hash
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                val contact =
                    ContactEntity(
                        destinationHash = destinationHash,
                        identityHash = activeIdentity.identityHash,
                        publicKey = publicKey,
                        customNickname = null, // Let displayName fall through to announce name
                        notes = null,
                        tags = null,
                        addedTimestamp = System.currentTimeMillis(),
                        addedVia = "ANNOUNCE",
                        lastInteractionTimestamp = 0,
                        isPinned = false,
                    )
                contactDao.insertContact(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Add a contact from a QR code scan
         *
         * @param destinationHash The destination hash from the QR code
         * @param publicKey The public key from the QR code
         * @param nickname Optional custom nickname for this contact
         */
        suspend fun addContactFromQrCode(
            destinationHash: String,
            publicKey: ByteArray,
            nickname: String? = null,
        ): Result<Unit> {
            return try {
                // Get active identity hash
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                val contact =
                    ContactEntity(
                        destinationHash = destinationHash,
                        identityHash = activeIdentity.identityHash,
                        publicKey = publicKey,
                        customNickname = nickname,
                        notes = null,
                        tags = null,
                        addedTimestamp = System.currentTimeMillis(),
                        addedVia = "QR_CODE",
                        lastInteractionTimestamp = 0,
                        isPinned = false,
                    )
                contactDao.insertContact(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Add a contact via manual entry
         *
         * @param destinationHash The destination hash entered manually
         * @param publicKey The public key entered manually
         * @param nickname Optional custom nickname for this contact
         */
        suspend fun addContactManually(
            destinationHash: String,
            publicKey: ByteArray,
            nickname: String? = null,
        ): Result<Unit> {
            return try {
                // Get active identity hash
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                val contact =
                    ContactEntity(
                        destinationHash = destinationHash,
                        identityHash = activeIdentity.identityHash,
                        publicKey = publicKey,
                        customNickname = nickname,
                        notes = null,
                        tags = null,
                        addedTimestamp = System.currentTimeMillis(),
                        addedVia = "MANUAL",
                        lastInteractionTimestamp = 0,
                        isPinned = false,
                    )
                contactDao.insertContact(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Add a contact from an active conversation (when user saves from Chats tab).
         * Display name will automatically use the announce's peerName via database COALESCE.
         *
         * @param destinationHash The destination hash from the conversation
         * @param publicKey The public key from the conversation
         */
        suspend fun addContactFromConversation(
            destinationHash: String,
            publicKey: ByteArray,
        ): Result<Unit> {
            return try {
                // Get active identity hash
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                val contact =
                    ContactEntity(
                        destinationHash = destinationHash,
                        identityHash = activeIdentity.identityHash,
                        publicKey = publicKey,
                        customNickname = null, // Let displayName fall through to announce/conversation name
                        notes = null,
                        tags = null,
                        addedTimestamp = System.currentTimeMillis(),
                        addedVia = "CONVERSATION",
                        lastInteractionTimestamp = System.currentTimeMillis(), // Set last interaction to now
                        isPinned = false,
                    )
                contactDao.insertContact(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Delete a contact for the active identity
         */
        suspend fun deleteContact(destinationHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.deleteContact(destinationHash, activeIdentity.identityHash)
        }

        /**
         * Update contact nickname for the active identity
         */
        suspend fun updateNickname(
            destinationHash: String,
            nickname: String?,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.updateNickname(destinationHash, activeIdentity.identityHash, nickname)
        }

        /**
         * Update contact notes for the active identity
         */
        suspend fun updateNotes(
            destinationHash: String,
            notes: String?,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.updateNotes(destinationHash, activeIdentity.identityHash, notes)
        }

        /**
         * Update contact tags (JSON array string) for the active identity
         */
        suspend fun updateTags(
            destinationHash: String,
            tags: String?,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.updateTags(destinationHash, activeIdentity.identityHash, tags)
        }

        /**
         * Toggle pin status for a contact for the active identity
         */
        suspend fun togglePin(destinationHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val current = contactDao.getContact(destinationHash, activeIdentity.identityHash)?.isPinned ?: false
            contactDao.updatePinned(destinationHash, activeIdentity.identityHash, !current)
        }

        /**
         * Update last interaction timestamp (when user sends/receives message) for the active identity
         */
        suspend fun updateLastInteraction(
            destinationHash: String,
            timestamp: Long,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.updateLastInteraction(destinationHash, activeIdentity.identityHash, timestamp)
        }

        /**
         * Get contact count for the active identity
         */
        suspend fun getContactCount(): Int {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return 0
            return contactDao.getContactCount(activeIdentity.identityHash)
        }

        /**
         * Get contact count as Flow for the active identity.
         * Automatically switches when identity changes.
         */
        fun getContactCountFlow(): Flow<Int> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(0)
                } else {
                    contactDao.getContactCountFlow(identity.identityHash)
                }
            }
        }

        /**
         * Search contacts by nickname, announce name, or hash for the active identity.
         * Automatically switches when identity changes.
         */
        fun searchContacts(query: String): Flow<List<ContactEntity>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    contactDao.searchContacts(identity.identityHash, query)
                }
            }
        }

        /**
         * Get all pinned contacts for the active identity.
         * Automatically switches when identity changes.
         */
        fun getPinnedContacts(): Flow<List<ContactEntity>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    contactDao.getPinnedContacts(identity.identityHash)
                }
            }
        }

        /**
         * Delete all contacts for the active identity (for testing/debugging)
         */
        suspend fun deleteAllContacts() {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.deleteAllContacts(activeIdentity.identityHash)
        }

        // ========== PENDING IDENTITY RESOLUTION ==========

        /**
         * Add a pending contact with only a destination hash (no public key).
         * Used when importing contacts from Sideband using only the destination hash.
         *
         * The contact will be created with PENDING_IDENTITY status and null public key.
         * Background workers will attempt to resolve the identity from the network.
         *
         * @param destinationHash The 32-character hex destination hash
         * @param nickname Optional display name for the contact
         * @return Result indicating success or failure
         */

        /**
         * Sealed class to represent the result of adding a pending contact.
         * Used to communicate whether the contact was resolved immediately or is still pending.
         */
        sealed class AddPendingResult {
            /** Contact was added with full identity (from existing announce) */
            object ResolvedImmediately : AddPendingResult()

            /** Contact was added as pending (no existing announce found) */
            object AddedAsPending : AddPendingResult()
        }

        suspend fun addPendingContact(
            destinationHash: String,
            nickname: String? = null,
        ): Result<AddPendingResult> {
            return try {
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                // Check if we already have an announce for this destination hash
                // If so, we can resolve the contact immediately!
                val existingAnnounce = announceDao.getAnnounce(destinationHash)

                if (existingAnnounce != null && existingAnnounce.publicKey.isNotEmpty()) {
                    // Great! We have the public key from a previous announce
                    val contact =
                        ContactEntity(
                            destinationHash = destinationHash,
                            identityHash = activeIdentity.identityHash,
                            publicKey = existingAnnounce.publicKey,
                            customNickname = nickname,
                            notes = null,
                            tags = null,
                            addedTimestamp = System.currentTimeMillis(),
                            addedVia = "MANUAL",
                            lastInteractionTimestamp = 0,
                            isPinned = false,
                            status = ContactStatus.ACTIVE,
                        )
                    contactDao.insertContact(contact)
                    Result.success(AddPendingResult.ResolvedImmediately)
                } else {
                    // No existing announce - add as pending
                    val contact =
                        ContactEntity(
                            destinationHash = destinationHash,
                            identityHash = activeIdentity.identityHash,
                            publicKey = null, // Will be filled when identity is resolved
                            customNickname = nickname,
                            notes = null,
                            tags = null,
                            addedTimestamp = System.currentTimeMillis(),
                            addedVia = "MANUAL_PENDING",
                            lastInteractionTimestamp = 0,
                            isPinned = false,
                            status = ContactStatus.PENDING_IDENTITY,
                        )
                    contactDao.insertContact(contact)
                    Result.success(AddPendingResult.AddedAsPending)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Updates a pending contact with resolved identity information.
         *
         * Called when the network returns the public key for a pending contact.
         * Changes status from PENDING_IDENTITY to ACTIVE.
         *
         * @param destinationHash The contact's destination hash
         * @param publicKey The resolved 64-byte public key
         * @return Result indicating success or failure
         */
        suspend fun updateContactWithIdentity(
            destinationHash: String,
            publicKey: ByteArray,
        ): Result<Unit> {
            return try {
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                contactDao.updateContactIdentity(
                    destinationHash = destinationHash,
                    identityHash = activeIdentity.identityHash,
                    publicKey = publicKey,
                    status = ContactStatus.ACTIVE.name,
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Updates a contact's status (e.g., to UNRESOLVED after timeout).
         *
         * @param destinationHash The contact's destination hash
         * @param status The new status
         * @return Result indicating success or failure
         */
        suspend fun updateContactStatus(
            destinationHash: String,
            status: ContactStatus,
        ): Result<Unit> {
            return try {
                val activeIdentity =
                    localIdentityDao.getActiveIdentitySync()
                        ?: return Result.failure(IllegalStateException("No active identity found"))

                contactDao.updateContactStatus(
                    destinationHash = destinationHash,
                    identityHash = activeIdentity.identityHash,
                    status = status.name,
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        /**
         * Gets all contacts with specified statuses (for background identity resolution).
         * Returns contacts from ALL identities, not just the active one.
         *
         * @param statuses List of statuses to query
         * @return List of contacts matching the statuses
         */
        suspend fun getContactsByStatus(statuses: List<ContactStatus>): List<ContactEntity> {
            return contactDao.getContactsByStatus(statuses.map { it.name })
        }

        /**
         * Gets all contacts with specified statuses for the active identity.
         *
         * @param statuses List of statuses to query
         * @return List of contacts matching the statuses for the active identity
         */
        suspend fun getContactsByStatusForActiveIdentity(statuses: List<ContactStatus>): List<ContactEntity> {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return emptyList()
            return contactDao.getContactsByStatusForIdentity(
                activeIdentity.identityHash,
                statuses.map { it.name },
            )
        }

        // ========== PROPAGATION NODE RELAY MANAGEMENT ==========

        /**
         * Set a contact as the user's propagation node relay.
         * Clears any existing relay first (only one relay per identity).
         *
         * @param destinationHash The destination hash of the propagation node
         * @param clearOther If true, clears other relays first (default true)
         */
        suspend fun setAsMyRelay(
            destinationHash: String,
            clearOther: Boolean = true,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync()
            if (activeIdentity == null) {
                android.util.Log.e("ContactRepository", "setAsMyRelay: No active identity!")
                return
            }
            android.util.Log.d("ContactRepository", "setAsMyRelay: dest=$destinationHash, identity=${activeIdentity.identityHash}")
            if (clearOther) {
                contactDao.clearMyRelay(activeIdentity.identityHash)
            }
            contactDao.setAsMyRelay(destinationHash, activeIdentity.identityHash)
            // Verify update
            val contact = contactDao.getContact(destinationHash, activeIdentity.identityHash)
            android.util.Log.d("ContactRepository", "setAsMyRelay: after update, contact exists=${contact != null}, isMyRelay=${contact?.isMyRelay}")
        }

        /**
         * Clear the current relay for the active identity.
         */
        suspend fun clearMyRelay() {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            contactDao.clearMyRelay(activeIdentity.identityHash)
        }

        /**
         * Get the current relay contact for the active identity.
         */
        suspend fun getMyRelay(): ContactEntity? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return contactDao.getMyRelay(activeIdentity.identityHash)
        }

        /**
         * Get the current relay contact as Flow for observing changes.
         * Automatically switches when identity changes.
         */
        fun getMyRelayFlow(): Flow<ContactEntity?> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(null)
                } else {
                    contactDao.getMyRelayFlow(identity.identityHash)
                }
            }
        }

        /**
         * Check if a specific contact is the user's relay.
         * Automatically switches when identity changes.
         */
        fun isMyRelayFlow(destinationHash: String): Flow<Boolean> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(false)
                } else {
                    contactDao.isMyRelayFlow(destinationHash, identity.identityHash)
                        .flatMapLatest { isRelay -> flowOf(isRelay == true) }
                }
            }
        }
    }
