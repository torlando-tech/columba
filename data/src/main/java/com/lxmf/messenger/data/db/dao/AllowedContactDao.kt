package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lxmf.messenger.data.db.entity.AllowedContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AllowedContactDao {
    /**
     * Insert or replace an allowed contact
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllowedContact(contact: AllowedContactEntity)

    /**
     * Insert multiple allowed contacts
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllowedContacts(contacts: List<AllowedContactEntity>)

    /**
     * Get all allowed contacts for an identity
     */
    @Query("SELECT * FROM allowed_contacts WHERE identityHash = :identityHash ORDER BY displayName ASC")
    fun getAllowedContacts(identityHash: String): Flow<List<AllowedContactEntity>>

    /**
     * Get all allowed contacts synchronously
     */
    @Query("SELECT * FROM allowed_contacts WHERE identityHash = :identityHash")
    suspend fun getAllowedContactsSync(identityHash: String): List<AllowedContactEntity>

    /**
     * Check if a contact is allowed
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM allowed_contacts
            WHERE identityHash = :identityHash AND contactHash = :contactHash
        )
        """,
    )
    suspend fun isContactAllowed(identityHash: String, contactHash: String): Boolean

    /**
     * Get allowed contact count
     */
    @Query("SELECT COUNT(*) FROM allowed_contacts WHERE identityHash = :identityHash")
    fun getAllowedContactCount(identityHash: String): Flow<Int>

    /**
     * Delete an allowed contact
     */
    @Query("DELETE FROM allowed_contacts WHERE identityHash = :identityHash AND contactHash = :contactHash")
    suspend fun deleteAllowedContact(identityHash: String, contactHash: String)

    /**
     * Delete all allowed contacts for an identity
     */
    @Query("DELETE FROM allowed_contacts WHERE identityHash = :identityHash")
    suspend fun deleteAllAllowedContacts(identityHash: String)

    /**
     * Replace entire allow list (atomic operation)
     */
    @Transaction
    suspend fun replaceAllowList(identityHash: String, contacts: List<AllowedContactEntity>) {
        deleteAllAllowedContacts(identityHash)
        if (contacts.isNotEmpty()) {
            insertAllowedContacts(contacts)
        }
    }
}
