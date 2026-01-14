package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.AllowedContactDao
import com.lxmf.messenger.data.db.dao.GuardianConfigDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.AllowedContactEntity
import com.lxmf.messenger.data.db.entity.GuardianConfigEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing parental control (guardian) functionality.
 *
 * Provides methods for:
 * - Guardian pairing and configuration
 * - Lock state management
 * - Allow list management
 * - Contact filtering checks for message blocking
 */
@Singleton
class GuardianRepository
    @Inject
    constructor(
        private val guardianConfigDao: GuardianConfigDao,
        private val allowedContactDao: AllowedContactDao,
        private val localIdentityDao: LocalIdentityDao,
    ) {
        companion object {
            // Commands older than this are rejected (anti-replay)
            const val COMMAND_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        }

        // ========== Guardian Config ==========

        /**
         * Get guardian config for the active identity
         */
        suspend fun getGuardianConfig(): GuardianConfigEntity? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return guardianConfigDao.getConfig(activeIdentity.identityHash)
        }

        /**
         * Get guardian config as Flow for the active identity.
         * Automatically switches when identity changes.
         */
        fun getGuardianConfigFlow(): Flow<GuardianConfigEntity?> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(null)
                } else {
                    guardianConfigDao.getConfigFlow(identity.identityHash)
                }
            }
        }

        /**
         * Check if the active identity has a guardian configured
         */
        suspend fun hasGuardian(): Boolean {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return false
            return guardianConfigDao.hasGuardian(activeIdentity.identityHash)
        }

        /**
         * Set guardian for the active identity (pairing)
         */
        suspend fun setGuardian(
            guardianDestinationHash: String,
            guardianPublicKey: ByteArray,
            guardianName: String? = null,
        ) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val config = GuardianConfigEntity(
                identityHash = activeIdentity.identityHash,
                guardianDestinationHash = guardianDestinationHash,
                guardianPublicKey = guardianPublicKey,
                guardianName = guardianName,
                isLocked = false,
                lockedTimestamp = 0,
                lastCommandNonce = null,
                lastCommandTimestamp = 0,
                pairedTimestamp = System.currentTimeMillis(),
            )
            guardianConfigDao.insertConfig(config)
        }

        /**
         * Remove guardian (unpair) for the active identity.
         * Also clears the allow list.
         */
        suspend fun removeGuardian() {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            guardianConfigDao.removeGuardian(activeIdentity.identityHash)
            allowedContactDao.deleteAllAllowedContacts(activeIdentity.identityHash)
        }

        // ========== Lock State ==========

        /**
         * Check if the active identity is locked
         */
        suspend fun isLocked(): Boolean {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return false
            return guardianConfigDao.isLocked(activeIdentity.identityHash) ?: false
        }

        /**
         * Get lock state as Flow for the active identity.
         * Returns false if no guardian is configured.
         */
        fun isLockedFlow(): Flow<Boolean> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(false)
                } else {
                    guardianConfigDao.isLockedFlow(identity.identityHash).map { it ?: false }
                }
            }
        }

        /**
         * Set lock state for the active identity
         */
        suspend fun setLockState(locked: Boolean) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val timestamp = if (locked) System.currentTimeMillis() else 0L
            guardianConfigDao.setLockState(activeIdentity.identityHash, locked, timestamp)
        }

        // ========== Allow List ==========

        /**
         * Get all allowed contacts for the active identity
         */
        fun getAllowedContacts(): Flow<List<AllowedContactEntity>> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    allowedContactDao.getAllowedContacts(identity.identityHash)
                }
            }
        }

        /**
         * Get allowed contact count for the active identity
         */
        fun getAllowedContactCount(): Flow<Int> {
            return localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(0)
                } else {
                    allowedContactDao.getAllowedContactCount(identity.identityHash)
                }
            }
        }

        /**
         * Add a contact to the allow list
         */
        suspend fun addAllowedContact(contactHash: String, displayName: String? = null) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val entity = AllowedContactEntity(
                identityHash = activeIdentity.identityHash,
                contactHash = contactHash,
                displayName = displayName,
                addedTimestamp = System.currentTimeMillis(),
            )
            allowedContactDao.insertAllowedContact(entity)
        }

        /**
         * Add multiple contacts to the allow list
         */
        suspend fun addAllowedContacts(contacts: List<Pair<String, String?>>) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val now = System.currentTimeMillis()
            val entities = contacts.map { (hash, name) ->
                AllowedContactEntity(
                    identityHash = activeIdentity.identityHash,
                    contactHash = hash,
                    displayName = name,
                    addedTimestamp = now,
                )
            }
            allowedContactDao.insertAllowedContacts(entities)
        }

        /**
         * Remove a contact from the allow list
         */
        suspend fun removeAllowedContact(contactHash: String) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            allowedContactDao.deleteAllowedContact(activeIdentity.identityHash, contactHash)
        }

        /**
         * Replace entire allow list (atomic operation)
         */
        suspend fun setAllowedContacts(contacts: List<Pair<String, String?>>) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            val now = System.currentTimeMillis()
            val entities = contacts.map { (hash, name) ->
                AllowedContactEntity(
                    identityHash = activeIdentity.identityHash,
                    contactHash = hash,
                    displayName = name,
                    addedTimestamp = now,
                )
            }
            allowedContactDao.replaceAllowList(activeIdentity.identityHash, entities)
        }

        // ========== Message Filtering ==========

        /**
         * Check if a contact is allowed to communicate.
         * Returns true if:
         * - Device is not locked
         * - Contact is the guardian
         * - Contact is in the allow list
         */
        suspend fun isContactAllowed(contactHash: String): Boolean {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return true
            val config = guardianConfigDao.getConfig(activeIdentity.identityHash)

            // If no guardian configured or not locked, all contacts allowed
            if (config == null || !config.hasGuardian() || !config.isLocked) {
                return true
            }

            // Guardian is always allowed
            if (contactHash == config.guardianDestinationHash) {
                return true
            }

            // Check allow list
            return allowedContactDao.isContactAllowed(activeIdentity.identityHash, contactHash)
        }

        /**
         * Get the guardian's destination hash (for filtering bypass)
         */
        suspend fun getGuardianDestinationHash(): String? {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return null
            return guardianConfigDao.getGuardianDestinationHash(activeIdentity.identityHash)
        }

        // ========== Anti-Replay ==========

        /**
         * Validate a command's nonce and timestamp (anti-replay protection).
         * Returns true if the command should be processed.
         */
        suspend fun validateCommand(nonce: String, timestamp: Long): Boolean {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return false
            val config = guardianConfigDao.getConfig(activeIdentity.identityHash) ?: return false

            // Check timestamp is within window
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(now - timestamp) > COMMAND_WINDOW_MS) {
                return false
            }

            // Check nonce hasn't been used before
            // (Simple check: if this nonce matches the last one, reject)
            if (config.lastCommandNonce == nonce) {
                return false
            }

            // Check timestamp is newer than last command
            if (timestamp <= config.lastCommandTimestamp) {
                return false
            }

            return true
        }

        /**
         * Record that a command was processed (for anti-replay)
         */
        suspend fun recordProcessedCommand(nonce: String, timestamp: Long) {
            val activeIdentity = localIdentityDao.getActiveIdentitySync() ?: return
            guardianConfigDao.updateLastCommand(activeIdentity.identityHash, nonce, timestamp)
        }
    }
