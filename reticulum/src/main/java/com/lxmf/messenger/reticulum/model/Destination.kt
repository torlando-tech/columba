package com.lxmf.messenger.reticulum.model

data class Destination(
    val hash: ByteArray,
    val hexHash: String,
    val identity: Identity,
    val direction: Direction,
    val type: DestinationType,
    val appName: String,
    val aspects: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Destination

        if (!hash.contentEquals(other.hash)) return false
        if (hexHash != other.hexHash) return false
        if (identity != other.identity) return false
        if (direction != other.direction) return false
        if (type != other.type) return false
        if (appName != other.appName) return false
        if (aspects != other.aspects) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + hexHash.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + appName.hashCode()
        result = 31 * result + aspects.hashCode()
        return result
    }
}
