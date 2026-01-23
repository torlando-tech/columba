package com.lxmf.messenger.data.model

/**
 * Enriched announce data combining announces table with peer_icons.
 *
 * This model represents the result of a join query that combines:
 * - Announce data (from announces table - Reticulum network discovery)
 * - Profile icon (from peer_icons table - LXMF message icon appearances)
 *
 * Icons are an LXMF concept transmitted in message Field 4, while announces
 * are a Reticulum concept for network peer discovery. This model bridges both.
 */
data class EnrichedAnnounce(
    val destinationHash: String,
    val peerName: String,
    val publicKey: ByteArray,
    val appData: ByteArray?,
    val hops: Int,
    val lastSeenTimestamp: Long,
    val nodeType: String,
    val receivingInterface: String?,
    val receivingInterfaceType: String?,
    val aspect: String?,
    val isFavorite: Boolean,
    val favoritedTimestamp: Long?,
    val stampCost: Int?,
    val stampCostFlexibility: Int?,
    val peeringCost: Int?,
    val propagationTransferLimitKb: Int?,
    // Profile icon (from peer_icons table)
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
) {
    @Suppress("CyclomaticComplexMethod")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EnrichedAnnounce

        if (destinationHash != other.destinationHash) return false
        if (peerName != other.peerName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (appData != null) {
            if (other.appData == null) return false
            if (!appData.contentEquals(other.appData)) return false
        } else if (other.appData != null) {
            return false
        }
        if (hops != other.hops) return false
        if (lastSeenTimestamp != other.lastSeenTimestamp) return false
        if (nodeType != other.nodeType) return false
        if (receivingInterface != other.receivingInterface) return false
        if (receivingInterfaceType != other.receivingInterfaceType) return false
        if (aspect != other.aspect) return false
        if (isFavorite != other.isFavorite) return false
        if (favoritedTimestamp != other.favoritedTimestamp) return false
        if (stampCost != other.stampCost) return false
        if (stampCostFlexibility != other.stampCostFlexibility) return false
        if (peeringCost != other.peeringCost) return false
        if (propagationTransferLimitKb != other.propagationTransferLimitKb) return false
        if (iconName != other.iconName) return false
        if (iconForegroundColor != other.iconForegroundColor) return false
        if (iconBackgroundColor != other.iconBackgroundColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = destinationHash.hashCode()
        result = 31 * result + peerName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (appData?.contentHashCode() ?: 0)
        result = 31 * result + hops
        result = 31 * result + lastSeenTimestamp.hashCode()
        result = 31 * result + nodeType.hashCode()
        result = 31 * result + (receivingInterface?.hashCode() ?: 0)
        result = 31 * result + (receivingInterfaceType?.hashCode() ?: 0)
        result = 31 * result + (aspect?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedTimestamp?.hashCode() ?: 0)
        result = 31 * result + (stampCost?.hashCode() ?: 0)
        result = 31 * result + (stampCostFlexibility?.hashCode() ?: 0)
        result = 31 * result + (peeringCost?.hashCode() ?: 0)
        result = 31 * result + (propagationTransferLimitKb?.hashCode() ?: 0)
        result = 31 * result + (iconName?.hashCode() ?: 0)
        result = 31 * result + (iconForegroundColor?.hashCode() ?: 0)
        result = 31 * result + (iconBackgroundColor?.hashCode() ?: 0)
        return result
    }
}
