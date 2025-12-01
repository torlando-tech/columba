package com.lxmf.messenger.reticulum.model

data class PacketReceipt(
    val hash: ByteArray,
    val delivered: Boolean = false,
    val timestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketReceipt

        if (!hash.contentEquals(other.hash)) return false
        if (delivered != other.delivered) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + delivered.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
