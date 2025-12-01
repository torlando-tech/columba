package com.lxmf.messenger.reticulum.model

data class ReceivedPacket(
    val data: ByteArray,
    val destination: Destination,
    val link: Link?,
    val timestamp: Long,
    val rssi: Int?,
    val snr: Float?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReceivedPacket

        if (!data.contentEquals(other.data)) return false
        if (destination != other.destination) return false
        if (link != other.link) return false
        if (timestamp != other.timestamp) return false
        if (rssi != other.rssi) return false
        if (snr != other.snr) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + destination.hashCode()
        result = 31 * result + (link?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (rssi ?: 0)
        result = 31 * result + (snr?.hashCode() ?: 0)
        return result
    }
}
