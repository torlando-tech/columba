package network.columba.app.rns.api.model

/**
 * Delivery methods for LXMF messages.
 */
enum class DeliveryMethod {
    /** Single-packet delivery, max 295 bytes content, no link required. */
    OPPORTUNISTIC,

    /** Link-based delivery, unlimited size, with retries. */
    DIRECT,

    /** Delivery via propagation node for offline recipients. */
    PROPAGATED,
}
