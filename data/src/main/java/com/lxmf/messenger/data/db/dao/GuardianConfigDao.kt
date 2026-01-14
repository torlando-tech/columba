package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.GuardianConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianConfigDao {
    /**
     * Insert or replace guardian config for an identity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: GuardianConfigEntity)

    /**
     * Get guardian config for an identity
     */
    @Query("SELECT * FROM guardian_config WHERE identityHash = :identityHash")
    suspend fun getConfig(identityHash: String): GuardianConfigEntity?

    /**
     * Get guardian config as Flow for observing changes
     */
    @Query("SELECT * FROM guardian_config WHERE identityHash = :identityHash")
    fun getConfigFlow(identityHash: String): Flow<GuardianConfigEntity?>

    /**
     * Check if a guardian is configured for this identity
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM guardian_config
            WHERE identityHash = :identityHash
            AND guardianDestinationHash IS NOT NULL
        )
        """,
    )
    suspend fun hasGuardian(identityHash: String): Boolean

    /**
     * Check if device is locked
     */
    @Query("SELECT isLocked FROM guardian_config WHERE identityHash = :identityHash")
    suspend fun isLocked(identityHash: String): Boolean?

    /**
     * Get lock state as Flow
     */
    @Query("SELECT isLocked FROM guardian_config WHERE identityHash = :identityHash")
    fun isLockedFlow(identityHash: String): Flow<Boolean?>

    /**
     * Set lock state
     */
    @Query(
        """
        UPDATE guardian_config
        SET isLocked = :isLocked, lockedTimestamp = :timestamp
        WHERE identityHash = :identityHash
        """,
    )
    suspend fun setLockState(identityHash: String, isLocked: Boolean, timestamp: Long)

    /**
     * Update last command info (for anti-replay)
     */
    @Query(
        """
        UPDATE guardian_config
        SET lastCommandNonce = :nonce, lastCommandTimestamp = :timestamp
        WHERE identityHash = :identityHash
        """,
    )
    suspend fun updateLastCommand(identityHash: String, nonce: String, timestamp: Long)

    /**
     * Remove guardian (unpair)
     */
    @Query(
        """
        UPDATE guardian_config
        SET guardianDestinationHash = NULL,
            guardianPublicKey = NULL,
            guardianName = NULL,
            isLocked = 0,
            lockedTimestamp = 0,
            lastCommandNonce = NULL,
            lastCommandTimestamp = 0,
            pairedTimestamp = 0
        WHERE identityHash = :identityHash
        """,
    )
    suspend fun removeGuardian(identityHash: String)

    /**
     * Delete config for an identity
     */
    @Query("DELETE FROM guardian_config WHERE identityHash = :identityHash")
    suspend fun deleteConfig(identityHash: String)

    /**
     * Get guardian destination hash (for message filtering)
     */
    @Query("SELECT guardianDestinationHash FROM guardian_config WHERE identityHash = :identityHash")
    suspend fun getGuardianDestinationHash(identityHash: String): String?
}
