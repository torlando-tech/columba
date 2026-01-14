package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.PairedChildEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for paired children (parent/guardian side).
 */
@Dao
interface PairedChildDao {
    @Query("SELECT * FROM paired_children WHERE guardianIdentityHash = :identityHash ORDER BY pairedTimestamp DESC")
    fun getPairedChildrenForIdentity(identityHash: String): Flow<List<PairedChildEntity>>

    @Query("SELECT * FROM paired_children WHERE guardianIdentityHash = :identityHash ORDER BY pairedTimestamp DESC")
    suspend fun getPairedChildrenForIdentitySync(identityHash: String): List<PairedChildEntity>

    @Query("SELECT * FROM paired_children WHERE childDestinationHash = :childHash")
    suspend fun getPairedChild(childHash: String): PairedChildEntity?

    @Query("SELECT * FROM paired_children WHERE childDestinationHash = :childHash")
    fun observePairedChild(childHash: String): Flow<PairedChildEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(child: PairedChildEntity)

    @Update
    suspend fun update(child: PairedChildEntity)

    @Query("UPDATE paired_children SET isLocked = :isLocked, lockChangedTimestamp = :timestamp WHERE childDestinationHash = :childHash")
    suspend fun updateLockStatus(childHash: String, isLocked: Boolean, timestamp: Long)

    @Query("UPDATE paired_children SET displayName = :displayName WHERE childDestinationHash = :childHash")
    suspend fun updateDisplayName(childHash: String, displayName: String?)

    @Query("UPDATE paired_children SET lastSeenTimestamp = :timestamp WHERE childDestinationHash = :childHash")
    suspend fun updateLastSeen(childHash: String, timestamp: Long)

    @Query("DELETE FROM paired_children WHERE childDestinationHash = :childHash")
    suspend fun delete(childHash: String)

    @Query("DELETE FROM paired_children WHERE guardianIdentityHash = :identityHash")
    suspend fun deleteAllForIdentity(identityHash: String)

    @Query("SELECT COUNT(*) FROM paired_children WHERE guardianIdentityHash = :identityHash")
    suspend fun countChildrenForIdentity(identityHash: String): Int
}
