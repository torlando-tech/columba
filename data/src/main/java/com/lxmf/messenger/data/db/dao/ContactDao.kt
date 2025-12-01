package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.model.EnrichedContact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    /**
     * Insert or replace a contact
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    /**
     * Get all contacts for an identity, sorted by pinned status then nickname
     */
    @Query(
        """
        SELECT * FROM contacts WHERE identityHash = :identityHash
        ORDER BY isPinned DESC, customNickname ASC, destinationHash ASC
        """,
    )
    fun getAllContacts(identityHash: String): Flow<List<ContactEntity>>

    /**
     * Get enriched contacts with data from announces and conversations.
     * Combines contact data with network status and conversation info.
     * Filters by identity hash to ensure data isolation between identities.
     */
    @Query(
        """
        SELECT
            c.destinationHash,
            c.publicKey,
            c.customNickname,
            COALESCE(c.customNickname, a.peerName, c.destinationHash) as displayName,
            a.peerName as announceName,
            a.lastSeenTimestamp,
            a.hops,
            CASE WHEN a.lastSeenTimestamp > :onlineThreshold THEN 1 ELSE 0 END as isOnline,
            CASE WHEN conv.peerHash IS NOT NULL THEN 1 ELSE 0 END as hasConversation,
            COALESCE(conv.unreadCount, 0) as unreadCount,
            conv.lastMessageTimestamp,
            c.notes,
            c.tags,
            c.addedTimestamp,
            c.addedVia,
            c.isPinned
        FROM contacts c
        LEFT JOIN announces a ON c.destinationHash = a.destinationHash
        LEFT JOIN conversations conv ON c.destinationHash = conv.peerHash AND c.identityHash = conv.identityHash
        WHERE c.identityHash = :identityHash
        ORDER BY c.isPinned DESC, displayName ASC
    """,
    )
    fun getEnrichedContacts(
        identityHash: String,
        onlineThreshold: Long,
    ): Flow<List<EnrichedContact>>

    /**
     * Get a specific contact by destination hash and identity
     */
    @Query("SELECT * FROM contacts WHERE destinationHash = :destinationHash AND identityHash = :identityHash")
    suspend fun getContact(
        destinationHash: String,
        identityHash: String,
    ): ContactEntity?

    /**
     * Get a specific contact as Flow (for observing changes)
     */
    @Query("SELECT * FROM contacts WHERE destinationHash = :destinationHash AND identityHash = :identityHash")
    fun getContactFlow(
        destinationHash: String,
        identityHash: String,
    ): Flow<ContactEntity?>

    /**
     * Check if a contact exists for this identity
     */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM contacts
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash)
        """,
    )
    suspend fun contactExists(
        destinationHash: String,
        identityHash: String,
    ): Boolean

    /**
     * Get contact existence as Flow (for observing star button state)
     */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM contacts
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash)
        """,
    )
    fun contactExistsFlow(
        destinationHash: String,
        identityHash: String,
    ): Flow<Boolean>

    /**
     * Delete a contact
     */
    @Query("DELETE FROM contacts WHERE destinationHash = :destinationHash AND identityHash = :identityHash")
    suspend fun deleteContact(
        destinationHash: String,
        identityHash: String,
    )

    /**
     * Update nickname
     */
    @Query(
        """
        UPDATE contacts SET customNickname = :nickname
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash
        """,
    )
    suspend fun updateNickname(
        destinationHash: String,
        identityHash: String,
        nickname: String?,
    )

    /**
     * Update notes
     */
    @Query(
        """
        UPDATE contacts SET notes = :notes
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash
        """,
    )
    suspend fun updateNotes(
        destinationHash: String,
        identityHash: String,
        notes: String?,
    )

    /**
     * Update tags
     */
    @Query(
        """
        UPDATE contacts SET tags = :tags
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash
        """,
    )
    suspend fun updateTags(
        destinationHash: String,
        identityHash: String,
        tags: String?,
    )

    /**
     * Update pinned status
     */
    @Query(
        """
        UPDATE contacts SET isPinned = :isPinned
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash
        """,
    )
    suspend fun updatePinned(
        destinationHash: String,
        identityHash: String,
        isPinned: Boolean,
    )

    /**
     * Update last interaction timestamp
     */
    @Query(
        """
        UPDATE contacts SET lastInteractionTimestamp = :timestamp
        WHERE destinationHash = :destinationHash AND identityHash = :identityHash
        """,
    )
    suspend fun updateLastInteraction(
        destinationHash: String,
        identityHash: String,
        timestamp: Long,
    )

    /**
     * Get contact count for an identity
     */
    @Query("SELECT COUNT(*) FROM contacts WHERE identityHash = :identityHash")
    suspend fun getContactCount(identityHash: String): Int

    /**
     * Get contact count as Flow
     */
    @Query("SELECT COUNT(*) FROM contacts WHERE identityHash = :identityHash")
    fun getContactCountFlow(identityHash: String): Flow<Int>

    /**
     * Search contacts by nickname, announce name, or hash
     */
    @Query(
        """
        SELECT * FROM contacts
        WHERE identityHash = :identityHash
        AND (customNickname LIKE '%' || :query || '%'
        OR destinationHash LIKE '%' || :query || '%')
        ORDER BY isPinned DESC, customNickname ASC
    """,
    )
    fun searchContacts(
        identityHash: String,
        query: String,
    ): Flow<List<ContactEntity>>

    /**
     * Get all pinned contacts for an identity
     */
    @Query("SELECT * FROM contacts WHERE identityHash = :identityHash AND isPinned = 1 ORDER BY customNickname ASC")
    fun getPinnedContacts(identityHash: String): Flow<List<ContactEntity>>

    /**
     * Delete all contacts for an identity (for testing/debugging)
     */
    @Query("DELETE FROM contacts WHERE identityHash = :identityHash")
    suspend fun deleteAllContacts(identityHash: String)
}
