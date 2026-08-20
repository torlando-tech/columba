package network.columba.app.data.db.entity

import androidx.room.Entity

/** Service-owned durable inbox for lifecycle events received before their message row. */
@Entity(
    tableName = "pending_delivery_status",
    primaryKeys = ["identityHash", "messageHash"],
)
data class PendingDeliveryStatusEntity(
    val identityHash: String,
    val messageHash: String,
    val status: String,
    val deliveryMethod: String?,
    val updatedAt: Long,
)
