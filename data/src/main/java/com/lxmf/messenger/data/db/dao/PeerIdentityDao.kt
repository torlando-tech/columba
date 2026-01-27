package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.PeerIdentityEntity

@Dao
interface PeerIdentityDao {
    @Query("SELECT * FROM peer_identities WHERE peerHash = :peerHash")
    suspend fun getPeerIdentity(peerHash: String): PeerIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeerIdentity(peerIdentity: PeerIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeerIdentities(peerIdentities: List<PeerIdentityEntity>)

    @Update
    suspend fun updatePeerIdentity(peerIdentity: PeerIdentityEntity)

    @Query("SELECT * FROM peer_identities")
    suspend fun getAllPeerIdentities(): List<PeerIdentityEntity>

    /**
     * Get peer identities in batches to prevent OOM when loading large amounts of data.
     * Used for identity restoration with pagination.
     * 
     * @param limit Number of peer identities to return in this batch
     * @param offset Number of peer identities to skip (for pagination)
     * @return List of peer identities sorted by most recently seen
     */
    @Query("SELECT * FROM peer_identities ORDER BY lastSeenTimestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPeerIdentitiesBatch(limit: Int, offset: Int): List<PeerIdentityEntity>

    @Query("DELETE FROM peer_identities WHERE peerHash = :peerHash")
    suspend fun deletePeerIdentity(peerHash: String)
}
