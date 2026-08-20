package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import network.columba.app.data.db.entity.PendingDeliveryStatusEntity

@Dao
interface PendingDeliveryStatusDao {
    /** Reduce competing lifecycle evidence before the canonical message row exists. */
    @Transaction
    suspend fun reduce(
        identityHash: String,
        messageHash: String,
        status: String,
        deliveryMethod: String?,
        updatedAt: Long,
    ) {
        val inserted =
            insertIfMissing(
                PendingDeliveryStatusEntity(identityHash, messageHash, status, deliveryMethod, updatedAt),
            )
        if (inserted == -1L) {
            advance(identityHash, messageHash, status, deliveryMethod, updatedAt)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(pending: PendingDeliveryStatusEntity): Long

    @Query(
        """
        UPDATE pending_delivery_status
        SET status = :status,
            deliveryMethod = COALESCE(:deliveryMethod, deliveryMethod),
            updatedAt = :updatedAt
        WHERE identityHash = :identityHash AND messageHash = :messageHash AND (
            (:status = 'delivered') OR
            (:status = 'propagated' AND status IN
                ('pending', 'sent', 'retrying_propagated', 'failed')) OR
            (:status = 'failed' AND status IN
                ('pending', 'sent', 'retrying_propagated', 'propagated')) OR
            (:status = 'retrying_propagated' AND status IN
                ('pending', 'sent')) OR
            (:status = status)
        )
        """,
    )
    suspend fun advance(
        identityHash: String,
        messageHash: String,
        status: String,
        deliveryMethod: String?,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM pending_delivery_status ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingDeliveryStatusEntity>

    @Query("SELECT * FROM pending_delivery_status WHERE identityHash = :identityHash AND messageHash = :messageHash")
    suspend fun get(
        identityHash: String,
        messageHash: String,
    ): PendingDeliveryStatusEntity?

    @Query("DELETE FROM pending_delivery_status WHERE identityHash = :identityHash AND messageHash = :messageHash")
    suspend fun delete(
        identityHash: String,
        messageHash: String,
    )

    @Query("DELETE FROM pending_delivery_status WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query(
        """
        DELETE FROM pending_delivery_status
        WHERE rowid NOT IN (
            SELECT rowid FROM pending_delivery_status ORDER BY updatedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimToNewest(keep: Int): Int
}
