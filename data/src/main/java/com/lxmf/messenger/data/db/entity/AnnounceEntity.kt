package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "announces",
    indices = [
        Index("lastSeenTimestamp"), // For ordering by date
        Index("isFavorite", "favoritedTimestamp"), // For favorite queries
        Index("nodeType", "lastSeenTimestamp"), // For filtering by type and ordering
    ],
)
data class AnnounceEntity(
    @PrimaryKey
    val destinationHash: String,
    val peerName: String,
    val publicKey: ByteArray,
    val appData: ByteArray?,
    val hops: Int,
    val lastSeenTimestamp: Long,
    val nodeType: String,
    val receivingInterface: String?,
    val receivingInterfaceType: String? = null,
    val aspect: String? = null,
    val isFavorite: Boolean = false,
    val favoritedTimestamp: Long? = null,
    val stampCost: Int? = null,
    val stampCostFlexibility: Int? = null,
    val peeringCost: Int? = null,
    val iconName: String? = null,
    val iconForegroundColor: String? = null, // Hex RGB e.g., "FFFFFF"
    val iconBackgroundColor: String? = null, // Hex RGB e.g., "1E88E5"
    val propagationTransferLimitKb: Int? = null, // Per-message size limit for propagation nodes (in KB)
) {
    @Suppress("CyclomaticComplexMethod") // Equals must compare all fields for correctness
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnnounceEntity) return false

        return destinationHash == other.destinationHash &&
            peerName == other.peerName &&
            publicKey.contentEquals(other.publicKey) &&
            appData.contentEqualsNullable(other.appData) &&
            hops == other.hops &&
            lastSeenTimestamp == other.lastSeenTimestamp &&
            nodeType == other.nodeType &&
            receivingInterface == other.receivingInterface &&
            receivingInterfaceType == other.receivingInterfaceType &&
            aspect == other.aspect &&
            isFavorite == other.isFavorite &&
            favoritedTimestamp == other.favoritedTimestamp &&
            stampCost == other.stampCost &&
            stampCostFlexibility == other.stampCostFlexibility &&
            peeringCost == other.peeringCost &&
            iconName == other.iconName &&
            iconForegroundColor == other.iconForegroundColor &&
            iconBackgroundColor == other.iconBackgroundColor &&
            propagationTransferLimitKb == other.propagationTransferLimitKb
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
        return when {
            this == null && other == null -> true
            this != null && other != null -> this.contentEquals(other)
            else -> false
        }
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
        result = 31 * result + (iconName?.hashCode() ?: 0)
        result = 31 * result + (iconForegroundColor?.hashCode() ?: 0)
        result = 31 * result + (iconBackgroundColor?.hashCode() ?: 0)
        result = 31 * result + (propagationTransferLimitKb?.hashCode() ?: 0)
        return result
    }
}
