package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import network.columba.app.data.db.entity.PeerIdentityEntity

@Dao
interface PeerIdentityDao {
    @Query("SELECT * FROM peer_identities WHERE peerHash = :peerHash")
    suspend fun getPeerIdentity(peerHash: String): PeerIdentityEntity?

    /** Emit on peer-key replacement as well as insertion, even when the count is unchanged. */
    @Query("SELECT COUNT(*) FROM peer_identities")
    fun observePeerIdentityCount(): Flow<Int>

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
    suspend fun getPeerIdentitiesBatch(
        limit: Int,
        offset: Int,
    ): List<PeerIdentityEntity>

    /** Primary-key paging for bounded key discovery, independent of mutable last-seen timestamps. */
    @Query("SELECT * FROM peer_identities WHERE peerHash > :afterPeerHash ORDER BY peerHash LIMIT :limit")
    suspend fun getPeerIdentitiesAfter(
        afterPeerHash: String,
        limit: Int,
    ): List<PeerIdentityEntity>

    @Query("DELETE FROM peer_identities WHERE peerHash = :peerHash")
    suspend fun deletePeerIdentity(peerHash: String)
}
