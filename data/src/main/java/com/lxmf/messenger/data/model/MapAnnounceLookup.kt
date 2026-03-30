package com.lxmf.messenger.data.model

/**
 * Lightweight announce lookup for map marker display.
 *
 * Contains only the fields needed by MapViewModel to resolve display names
 * and icons for location markers. Avoids loading heavy fields like appData
 * that can cause CursorWindow overflow on large announce tables.
 *
 * @see EnrichedAnnounce for the full announce projection used elsewhere.
 */
data class MapAnnounceLookup(
    val destinationHash: String,
    val peerName: String,
    val publicKey: ByteArray,
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MapAnnounceLookup

        if (destinationHash != other.destinationHash) return false
        if (peerName != other.peerName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (iconName != other.iconName) return false
        if (iconForegroundColor != other.iconForegroundColor) return false
        if (iconBackgroundColor != other.iconBackgroundColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = destinationHash.hashCode()
        result = 31 * result + peerName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (iconName?.hashCode() ?: 0)
        result = 31 * result + (iconForegroundColor?.hashCode() ?: 0)
        result = 31 * result + (iconBackgroundColor?.hashCode() ?: 0)
        return result
    }
}
