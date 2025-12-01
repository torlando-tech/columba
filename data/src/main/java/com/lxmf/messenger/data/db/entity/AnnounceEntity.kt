package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "announces")
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AnnounceEntity

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
        if (aspect != other.aspect) return false
        if (isFavorite != other.isFavorite) return false
        if (favoritedTimestamp != other.favoritedTimestamp) return false

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
        result = 31 * result + (aspect?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedTimestamp?.hashCode() ?: 0)
        return result
    }
}
