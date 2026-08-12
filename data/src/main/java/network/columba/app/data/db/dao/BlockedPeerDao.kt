package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import network.columba.app.data.db.entity.BlockedPeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedPeer(entity: BlockedPeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedPeers(entities: List<BlockedPeerEntity>)

    @Transaction
    suspend fun insertIdentityBlockWithAliases(entities: List<BlockedPeerEntity>) {
        insertBlockedPeers(entities)
    }

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

    @Query("SELECT * FROM blocked_peers WHERE peerHash = :peerHash AND identityHash = :identityHash LIMIT 1")
    suspend fun getBlockedPeer(
        peerHash: String,
        identityHash: String,
    ): BlockedPeerEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_peers WHERE peerHash = :peerHash AND identityHash = :identityHash)")
    fun isBlockedFlow(
        peerHash: String,
        identityHash: String,
    ): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM blocked_peers
            WHERE identityHash = :localIdentityHash
              AND (peerIdentityHash = :remoteIdentityHash OR peerHash = :remoteIdentityHash)
        )
        """,
    )
    fun isIdentityBlockedFlow(
        remoteIdentityHash: String,
        localIdentityHash: String,
    ): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM blocked_peers
            WHERE identityHash = :localIdentityHash
              AND (peerIdentityHash = :remoteIdentityHash OR peerHash = :remoteIdentityHash)
        )
        """,
    )
    suspend fun isIdentityBlocked(
        remoteIdentityHash: String,
        localIdentityHash: String,
    ): Boolean

    @Query(
        """
        SELECT * FROM blocked_peers
        WHERE identityHash = :localIdentityHash
          AND (peerIdentityHash = :remoteIdentityHash OR peerHash = :remoteIdentityHash)
        LIMIT 1
        """,
    )
    suspend fun getIdentityBlock(
        remoteIdentityHash: String,
        localIdentityHash: String,
    ): BlockedPeerEntity?

    @Query(
        """
        DELETE FROM blocked_peers
        WHERE identityHash = :localIdentityHash
          AND (peerIdentityHash = :remoteIdentityHash OR peerHash = :remoteIdentityHash)
        """,
    )
    suspend fun deleteIdentityBlock(
        remoteIdentityHash: String,
        localIdentityHash: String,
    )

    @Query("SELECT * FROM blocked_peers WHERE identityHash = :identityHash ORDER BY blockedTimestamp DESC")
    fun getBlockedPeers(identityHash: String): Flow<List<BlockedPeerEntity>>

    @Query("SELECT * FROM blocked_peers WHERE identityHash = :identityHash ORDER BY blockedTimestamp DESC")
    suspend fun getBlockedPeersSync(identityHash: String): List<BlockedPeerEntity>

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

    @Query(
        """
        UPDATE blocked_peers SET isBlackholeEnabled = :enabled
        WHERE identityHash = :localIdentityHash
          AND (peerIdentityHash = :remoteIdentityHash OR peerHash = :remoteIdentityHash)
        """,
    )
    suspend fun updateIdentityBlackholeEnabled(
        remoteIdentityHash: String,
        localIdentityHash: String,
        enabled: Boolean,
    )

    @Query(
        "SELECT COUNT(DISTINCT COALESCE(peerIdentityHash, peerHash)) " +
            "FROM blocked_peers WHERE identityHash = :identityHash",
    )
    fun getBlockedPeerCount(identityHash: String): Flow<Int>
}
