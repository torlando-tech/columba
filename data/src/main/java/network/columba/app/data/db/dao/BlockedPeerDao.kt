package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import network.columba.app.data.db.entity.BlockedPeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedPeer(entity: BlockedPeerEntity)

    @Query("DELETE FROM blocked_peers WHERE peerHash = :peerHash AND identityHash = :identityHash")
    suspend fun deleteBlockedPeer(
        peerHash: String,
        identityHash: String,
    )

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_peers WHERE peerHash = :peerHash AND identityHash = :identityHash)")
    suspend fun isBlocked(
        peerHash: String,
        identityHash: String,
    ): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_peers WHERE peerHash = :peerHash AND identityHash = :identityHash)")
    fun isBlockedFlow(
        peerHash: String,
        identityHash: String,
    ): Flow<Boolean>

    @Query("SELECT * FROM blocked_peers WHERE identityHash = :identityHash ORDER BY blockedTimestamp DESC")
    fun getBlockedPeers(identityHash: String): Flow<List<BlockedPeerEntity>>

    @Query("SELECT peerHash FROM blocked_peers WHERE identityHash = :identityHash")
    suspend fun getBlockedPeerHashes(identityHash: String): List<String>

    @Query(
        """
        SELECT peerIdentityHash FROM blocked_peers
        WHERE identityHash = :identityHash AND isBlackholeEnabled = 1 AND peerIdentityHash IS NOT NULL
        """,
    )
    suspend fun getBlackholedPeerIdentityHashes(identityHash: String): List<String>

    @Query("UPDATE blocked_peers SET isBlackholeEnabled = :enabled WHERE peerHash = :peerHash AND identityHash = :identityHash")
    suspend fun updateBlackholeEnabled(
        peerHash: String,
        identityHash: String,
        enabled: Boolean,
    )

    @Query("SELECT COUNT(*) FROM blocked_peers WHERE identityHash = :identityHash")
    fun getBlockedPeerCount(identityHash: String): Flow<Int>
}
