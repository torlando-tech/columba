package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing discovered RMSP (Reticulum Map Service Protocol) servers.
 *
 * RMSP servers announce their presence over Reticulum with information about
 * their map coverage, zoom levels, and available formats. This entity stores
 * that information for use in map tile fetching.
 */
@Entity(
    tableName = "rmsp_servers",
    indices = [
        Index("lastSeenTimestamp"), // For ordering by recency
        Index("hops"), // For selecting nearest servers
    ],
)
data class RmspServerEntity(
    /** Hex destination hash of the RMSP server */
    @PrimaryKey
    val destinationHash: String,
    /** Server display name */
    val serverName: String,
    /** RNS identity public key (for verification) */
    val publicKey: ByteArray,
    /** Comma-separated geohash prefixes that this server covers (e.g., "9q,9n,9p") */
    val coverageGeohashes: String,
    /** Minimum zoom level available */
    val minZoom: Int,
    /** Maximum zoom level available */
    val maxZoom: Int,
    /** Comma-separated supported formats (e.g., "pmtiles,micro") */
    val formats: String,
    /** Comma-separated available layers (e.g., "osm") */
    val layers: String,
    /** Timestamp when the server's map data was last updated */
    val dataUpdatedTimestamp: Long,
    /** Total size of available data in bytes (optional) */
    val dataSize: Long?,
    /** RMSP protocol version (e.g., "0.1.0") */
    val version: String,
    /** When we last received an announce from this server */
    val lastSeenTimestamp: Long,
    /** Network hops to reach this server */
    val hops: Int,
) {
    /**
     * Check if this server covers the given geohash.
     * Use "*" for explicit global coverage; empty means no coverage data available.
     */
    fun coversGeohash(geohash: String): Boolean {
        if (coverageGeohashes.isEmpty()) return false // No coverage data - don't assume global
        if (coverageGeohashes == "*") return true // Explicit global coverage marker

        return coverageGeohashes.split(",").any { prefix ->
            geohash.startsWith(prefix.trim()) || prefix.trim().startsWith(geohash)
        }
    }

    /**
     * Get the coverage geohashes as a list.
     */
    fun getCoverageList(): List<String> {
        return if (coverageGeohashes.isEmpty()) {
            emptyList()
        } else {
            coverageGeohashes.split(",").map { it.trim() }
        }
    }

    /**
     * Get the supported formats as a list.
     */
    fun getFormatsList(): List<String> {
        return if (formats.isEmpty()) {
            listOf("pmtiles")
        } else {
            formats.split(",").map { it.trim() }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RmspServerEntity

        if (destinationHash != other.destinationHash) return false
        if (serverName != other.serverName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (coverageGeohashes != other.coverageGeohashes) return false
        if (minZoom != other.minZoom) return false
        if (maxZoom != other.maxZoom) return false
        if (formats != other.formats) return false
        if (layers != other.layers) return false
        if (dataUpdatedTimestamp != other.dataUpdatedTimestamp) return false
        if (dataSize != other.dataSize) return false
        if (version != other.version) return false
        if (lastSeenTimestamp != other.lastSeenTimestamp) return false
        if (hops != other.hops) return false

        return true
    }

    override fun hashCode(): Int {
        var result = destinationHash.hashCode()
        result = 31 * result + serverName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + coverageGeohashes.hashCode()
        result = 31 * result + minZoom
        result = 31 * result + maxZoom
        result = 31 * result + formats.hashCode()
        result = 31 * result + layers.hashCode()
        result = 31 * result + dataUpdatedTimestamp.hashCode()
        result = 31 * result + (dataSize?.hashCode() ?: 0)
        result = 31 * result + version.hashCode()
        result = 31 * result + lastSeenTimestamp.hashCode()
        result = 31 * result + hops
        return result
    }
}
