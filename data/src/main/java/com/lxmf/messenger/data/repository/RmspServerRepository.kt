package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.RmspServerDao
import com.lxmf.messenger.data.db.entity.RmspServerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data model for RMSP servers used in the app layer.
 */
data class RmspServer(
    val destinationHash: String,
    val serverName: String,
    val publicKey: ByteArray,
    val coverageGeohashes: List<String>,
    val minZoom: Int,
    val maxZoom: Int,
    val formats: List<String>,
    val layers: List<String>,
    val dataUpdatedTimestamp: Long,
    val dataSize: Long?,
    val version: String,
    val lastSeenTimestamp: Long,
    val hops: Int,
) {
    /**
     * Check if this server covers the given geohash.
     */
    fun coversGeohash(geohash: String): Boolean {
        if (coverageGeohashes.isEmpty()) return true // Empty means global coverage
        return coverageGeohashes.any { prefix ->
            geohash.startsWith(prefix) || prefix.startsWith(geohash)
        }
    }

    /**
     * Check if this server supports the given format.
     */
    fun supportsFormat(format: String): Boolean {
        return formats.isEmpty() || formats.contains(format)
    }

    /**
     * Get a human-readable data size string.
     */
    fun getDataSizeString(): String? {
        val size = dataSize ?: return null
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RmspServer

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

/**
 * Repository for managing discovered RMSP servers.
 */
@Singleton
class RmspServerRepository
    @Inject
    constructor(
        private val rmspServerDao: RmspServerDao,
    ) {
        /**
         * Get all known RMSP servers as a Flow.
         */
        fun getAllServers(): Flow<List<RmspServer>> {
            return rmspServerDao.getAllServers().map { entities ->
                entities.map { it.toRmspServer() }
            }
        }

        /**
         * Get servers that might cover a given geohash.
         */
        fun getServersByGeohash(geohash: String): Flow<List<RmspServer>> {
            return rmspServerDao.getServersByGeohashPrefix(geohash).map { entities ->
                entities.map { it.toRmspServer() }.filter { it.coversGeohash(geohash) }
            }
        }

        /**
         * Get the nearest servers by hop count.
         */
        fun getNearestServers(limit: Int = 5): Flow<List<RmspServer>> {
            return rmspServerDao.getNearestServers(limit).map { entities ->
                entities.map { it.toRmspServer() }
            }
        }

        /**
         * Check if any servers are available.
         */
        fun hasServers(): Flow<Boolean> {
            return rmspServerDao.hasServers()
        }

        /**
         * Get a specific server by destination hash.
         */
        suspend fun getServer(destinationHash: String): RmspServer? {
            return rmspServerDao.getServer(destinationHash)?.toRmspServer()
        }

        /**
         * Insert or update a server from an announce.
         */
        suspend fun upsertServer(
            destinationHash: String,
            serverName: String,
            publicKey: ByteArray,
            coverageGeohashes: List<String>,
            minZoom: Int,
            maxZoom: Int,
            formats: List<String>,
            layers: List<String>,
            dataUpdatedTimestamp: Long,
            dataSize: Long?,
            version: String,
            hops: Int,
        ) {
            val entity = RmspServerEntity(
                destinationHash = destinationHash,
                serverName = serverName,
                publicKey = publicKey,
                coverageGeohashes = coverageGeohashes.joinToString(","),
                minZoom = minZoom,
                maxZoom = maxZoom,
                formats = formats.joinToString(","),
                layers = layers.joinToString(","),
                dataUpdatedTimestamp = dataUpdatedTimestamp,
                dataSize = dataSize,
                version = version,
                lastSeenTimestamp = System.currentTimeMillis(),
                hops = hops,
            )
            rmspServerDao.upsertServer(entity)
        }

        /**
         * Delete a server by destination hash.
         */
        suspend fun deleteServer(destinationHash: String) {
            rmspServerDao.deleteServer(destinationHash)
        }

        /**
         * Delete servers that haven't been seen in the given duration.
         */
        suspend fun deleteStaleServers(maxAgeMillis: Long) {
            val cutoffTime = System.currentTimeMillis() - maxAgeMillis
            rmspServerDao.deleteStaleServers(cutoffTime)
        }

        /**
         * Delete all servers.
         */
        suspend fun deleteAll() {
            rmspServerDao.deleteAll()
        }

        /**
         * Get count of known servers.
         */
        suspend fun getCount(): Int {
            return rmspServerDao.getCount()
        }
    }

/**
 * Extension function to convert entity to domain model.
 */
private fun RmspServerEntity.toRmspServer(): RmspServer {
    return RmspServer(
        destinationHash = destinationHash,
        serverName = serverName,
        publicKey = publicKey,
        coverageGeohashes = getCoverageList(),
        minZoom = minZoom,
        maxZoom = maxZoom,
        formats = getFormatsList(),
        layers = if (layers.isEmpty()) emptyList() else layers.split(",").map { it.trim() },
        dataUpdatedTimestamp = dataUpdatedTimestamp,
        dataSize = dataSize,
        version = version,
        lastSeenTimestamp = lastSeenTimestamp,
        hops = hops,
    )
}
