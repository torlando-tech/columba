package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.ContactEntity
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
         * Add a contact from an announce (when user stars an announce)
         *
         * @param destinationHash The destination hash from the announce
         * @param publicKey The public key from the announce
         * @param announceName Optional name from the announce (stored in customNickname if provided)
         */
        suspend fun addContactFromAnnounce(
            destinationHash: String,
            publicKey: ByteArray,
            announceName: String? = null,
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
                        customNickname = announceName, // Store announce name as nickname initially
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
         * Add a contact from an active conversation (when user saves from Chats tab)
         *
         * @param destinationHash The destination hash from the conversation
         * @param publicKey The public key from the conversation
         * @param peerName The peer name from the conversation (stored as nickname)
         */
        suspend fun addContactFromConversation(
            destinationHash: String,
            publicKey: ByteArray,
            peerName: String? = null,
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
                        customNickname = peerName, // Store conversation peer name as nickname
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
    }
