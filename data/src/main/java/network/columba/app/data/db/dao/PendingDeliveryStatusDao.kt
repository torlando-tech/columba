package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import network.columba.app.data.db.entity.PendingDeliveryStatusEntity

@Dao
interface PendingDeliveryStatusDao {
    /** Reduce competing lifecycle evidence before the canonical message row exists. */
    @Query(
        """
        INSERT INTO pending_delivery_status(messageHash, status, updatedAt)
        VALUES (:messageHash, :status, :updatedAt)
        ON CONFLICT(messageHash) DO UPDATE SET
            status = excluded.status,
            updatedAt = excluded.updatedAt
        WHERE
            (excluded.status = 'delivered') OR
            (excluded.status = 'propagated' AND pending_delivery_status.status IN
                ('pending', 'sent', 'retrying_propagated', 'failed')) OR
            (excluded.status = 'failed' AND pending_delivery_status.status IN
                ('pending', 'sent', 'retrying_propagated')) OR
            (excluded.status = 'retrying_propagated' AND pending_delivery_status.status IN
                ('pending', 'sent')) OR
            (excluded.status = pending_delivery_status.status)
        """,
    )
    suspend fun reduce(messageHash: String, status: String, updatedAt: Long)

    @Query("SELECT * FROM pending_delivery_status ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingDeliveryStatusEntity>

    @Query("SELECT * FROM pending_delivery_status WHERE messageHash = :messageHash")
    suspend fun get(messageHash: String): PendingDeliveryStatusEntity?

    @Query("DELETE FROM pending_delivery_status WHERE messageHash = :messageHash")
    suspend fun delete(messageHash: String)

    @Query("DELETE FROM pending_delivery_status WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query(
        """
        DELETE FROM pending_delivery_status
        WHERE messageHash NOT IN (
            SELECT messageHash FROM pending_delivery_status ORDER BY updatedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimToNewest(keep: Int): Int
}
