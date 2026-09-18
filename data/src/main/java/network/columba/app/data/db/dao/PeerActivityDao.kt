package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import network.columba.app.data.db.entity.PeerActivityEntity
import network.columba.app.data.db.entity.PeerActivityEventEntity

@Dao
interface PeerActivityDao {
    /**
     * Atomically inserts or advances a peer's activity timestamp. Older and
     * duplicate callbacks are ignored, so out-of-order service events cannot
     * move last-seen backwards or change the winning event type.
     *
     * This deliberately avoids SQLite's newer `ON CONFLICT DO UPDATE` syntax:
     * Columba supports API 24, whose bundled SQLite predates that syntax.
     */
    @Transaction
    suspend fun recordActivity(
        destinationHash: String,
        receivedAt: Long,
        activityType: String,
    ) {
        val normalizedHash = destinationHash.lowercase()
        val inserted =
            insertIfMissing(
                PeerActivityEntity(
                    destinationHash = normalizedHash,
                    lastReceivedAt = receivedAt,
                    activityType = activityType,
                ),
            )
        if (inserted == -1L) {
            advanceActivity(normalizedHash, receivedAt, activityType)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(activity: PeerActivityEntity): Long

    /** Admit a protocol event once, then advance activity in the same writer transaction. */
    @Transaction
    suspend fun recordActivityOnce(
        eventId: String,
        destinationHash: String,
        receivedAt: Long,
        activityType: String,
    ): Boolean {
        if (insertEventIfMissing(PeerActivityEventEntity(eventId.lowercase())) == -1L) return false
        recordActivity(destinationHash, receivedAt, activityType)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventIfMissing(event: PeerActivityEventEntity): Long

    @Query(
        """
        UPDATE peer_activity
        SET lastReceivedAt = :receivedAt, activityType = :activityType
        WHERE destinationHash = :destinationHash
          AND lastReceivedAt < :receivedAt
        """,
    )
    suspend fun advanceActivity(destinationHash: String, receivedAt: Long, activityType: String): Int

    @Query("SELECT * FROM peer_activity WHERE destinationHash = LOWER(:destinationHash)")
    fun observeActivity(destinationHash: String): Flow<PeerActivityEntity?>

    @Query("SELECT * FROM peer_activity WHERE destinationHash = LOWER(:destinationHash)")
    suspend fun getActivity(destinationHash: String): PeerActivityEntity?
}
