package com.lxmf.messenger.reticulum.model

enum class NodeType {
    NODE, // General mesh network node
    PEER, // Node we can message with
    PROPAGATION_NODE, // Relay/repeater node for signal propagation
}

data class AnnounceEvent(
    val destinationHash: ByteArray,
    val identity: Identity,
    val appData: ByteArray?,
    val hops: Int,
    val timestamp: Long,
    val nodeType: NodeType = NodeType.NODE,
    val receivingInterface: String? = null,
    val aspect: String? = null, // Aspect of the destination (e.g., "lxmf.delivery", "call.audio")
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AnnounceEvent

        if (!destinationHash.contentEquals(other.destinationHash)) return false
        if (identity != other.identity) return false
        if (appData != null) {
            if (other.appData == null) return false
            if (!appData.contentEquals(other.appData)) return false
        } else if (other.appData != null) {
            return false
        }
        if (hops != other.hops) return false
        if (timestamp != other.timestamp) return false
        if (nodeType != other.nodeType) return false
        if (receivingInterface != other.receivingInterface) return false
        if (aspect != other.aspect) return false

        return true
    }

    override fun hashCode(): Int {
        var result = destinationHash.contentHashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + (appData?.contentHashCode() ?: 0)
        result = 31 * result + hops
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nodeType.hashCode()
        result = 31 * result + (receivingInterface?.hashCode() ?: 0)
        result = 31 * result + (aspect?.hashCode() ?: 0)
        return result
    }
}
