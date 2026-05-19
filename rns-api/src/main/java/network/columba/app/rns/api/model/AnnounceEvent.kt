package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import network.columba.app.rns.api.util.Aspects

@Parcelize
enum class NodeType : Parcelable {
    NODE, // General mesh network node
    PEER, // Node we can message with
    PROPAGATION_NODE, // Relay/repeater node for signal propagation
    PHONE, // lxst.telephony — callable audio/telephony destination
    UNKNOWN, // Aspect didn't resolve to one Columba tracks — don't guess
    ;

    companion object {
        /**
         * Map an announce aspect to its [NodeType]. Centralised here so both
         * backends (kotlin-native + python-flavor) share one source of truth —
         * previously each had its own `when` block and they diverged on the
         * fallback branch (kotlin -> PEER, python -> NODE).
         *
         * Returns [UNKNOWN] for any aspect Columba doesn't explicitly handle,
         * including `null`. Upstream callers normally drop unknown-aspect
         * announces before reaching this — the [UNKNOWN] case is a defensive
         * default, not an assumption.
         */
        fun fromAspect(aspect: String?): NodeType =
            when (aspect) {
                Aspects.LXMF_PROPAGATION -> PROPAGATION_NODE
                Aspects.NOMADNET_NODE -> NODE
                Aspects.LXST_TELEPHONY -> PHONE
                Aspects.LXMF_DELIVERY -> PEER
                else -> UNKNOWN
            }
    }
}

@Parcelize
data class AnnounceEvent(
    val destinationHash: ByteArray,
    val identity: Identity,
    val appData: ByteArray?,
    val hops: Int,
    val timestamp: Long,
    val nodeType: NodeType = NodeType.NODE,
    val receivingInterface: String? = null,
    val aspect: String? = null, // Aspect of the destination (e.g., "lxmf.delivery", "lxst.telephony")
    val displayName: String? = null, // Pre-parsed by LXMF.display_name_from_app_data() in Python
    val stampCost: Int? = null, // Pre-parsed by LXMF stamp cost functions
    val stampCostFlexibility: Int? = null, // For propagation nodes only
    val peeringCost: Int? = null, // For propagation nodes only
) : Parcelable {
    @Suppress("CyclomaticComplexMethod")
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
        if (displayName != other.displayName) return false
        if (stampCost != other.stampCost) return false
        if (stampCostFlexibility != other.stampCostFlexibility) return false
        if (peeringCost != other.peeringCost) return false

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
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (stampCost?.hashCode() ?: 0)
        result = 31 * result + (stampCostFlexibility?.hashCode() ?: 0)
        result = 31 * result + (peeringCost?.hashCode() ?: 0)
        return result
    }
}
