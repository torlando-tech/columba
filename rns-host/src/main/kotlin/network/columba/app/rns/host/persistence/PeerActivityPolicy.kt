package network.columba.app.rns.host.persistence

/** Protocol-level qualification rules for durable peer activity. */
internal object PeerActivityPolicy {
    /** Only an actual LXMF delivery proof demonstrates a packet returned by the peer. */
    fun isVerifiedDeliveryProof(status: String): Boolean = status.equals("delivered", ignoreCase = true)
}
