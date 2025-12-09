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
    val aspect: String? = null,
    val isFavorite: Boolean = false,
    val favoritedTimestamp: Long? = null,
    val stampCost: Int? = null,
    val stampCostFlexibility: Int? = null,
    val peeringCost: Int? = null,
) {
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
            aspect == other.aspect &&
            isFavorite == other.isFavorite &&
            favoritedTimestamp == other.favoritedTimestamp &&
            stampCost == other.stampCost &&
            stampCostFlexibility == other.stampCostFlexibility &&
            peeringCost == other.peeringCost
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
        result = 31 * result + (aspect?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedTimestamp?.hashCode() ?: 0)
        result = 31 * result + (stampCost?.hashCode() ?: 0)
        result = 31 * result + (stampCostFlexibility?.hashCode() ?: 0)
        result = 31 * result + (peeringCost?.hashCode() ?: 0)
        return result
    }
}
