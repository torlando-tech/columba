package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Service-owned durable inbox for lifecycle events received before their message row. */
@Entity(tableName = "pending_delivery_status")
data class PendingDeliveryStatusEntity(
    @PrimaryKey val messageHash: String,
    val status: String,
    val updatedAt: Long,
)
