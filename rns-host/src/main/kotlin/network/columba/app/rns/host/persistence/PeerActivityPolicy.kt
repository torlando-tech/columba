package network.columba.app.rns.host.persistence

import network.columba.app.rns.api.model.DeliveryStatus

/** Protocol-level qualification rules for durable peer activity. */
internal object PeerActivityPolicy {
    /** Only an actual LXMF delivery proof demonstrates a packet returned by the peer. */
    fun isVerifiedDeliveryProof(status: DeliveryStatus): Boolean = status == DeliveryStatus.DELIVERED
}
