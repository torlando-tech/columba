package network.columba.app.rns.api.model

/**
 * Delivery status update for a sent LXMF message.
 */
data class DeliveryStatusUpdate(
    val messageHash: String,
    /** Status: "delivered", "failed", or "retrying_propagated" (direct failed, retrying via propagation). */
    val status: String,
    val timestamp: Long,
)
