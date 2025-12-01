package com.lxmf.messenger.reticulum.model

data class Identity(
    val hash: ByteArray,
    val publicKey: ByteArray,
    val privateKey: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Identity

        if (!hash.contentEquals(other.hash)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (privateKey != null) {
            if (other.privateKey == null) return false
            if (!privateKey.contentEquals(other.privateKey)) return false
        } else if (other.privateKey != null) {
            return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (privateKey?.contentHashCode() ?: 0)
        return result
    }
}
