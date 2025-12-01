package com.lxmf.messenger.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing an announce for UI layer
 */
data class Announce(
    val destinationHash: String,
    val peerName: String,
    val publicKey: ByteArray,
    val appData: ByteArray?,
    val hops: Int,
    val lastSeenTimestamp: Long,
    val nodeType: String,
    val receivingInterface: String? = null,
    val aspect: String? = null, // Destination aspect (e.g., "lxmf.delivery", "call.audio")
    val isFavorite: Boolean = false,
    val favoritedTimestamp: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Announce

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
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedTimestamp?.hashCode() ?: 0)
        return result
    }
}

/**
 * Repository for managing network announces.
 * Announces are global (not identity-scoped) since they represent
 * other peers on the network regardless of which local identity is active.
 */
@Singleton
class AnnounceRepository
    @Inject
    constructor(
        private val announceDao: AnnounceDao,
    ) {
        /**
         * Get all announces as a Flow, sorted by most recently seen.
         * Automatically updates UI when announces are added or updated.
         */
        fun getAnnounces(): Flow<List<Announce>> {
            return announceDao.getAllAnnounces().map { entities ->
                entities.map { it.toAnnounce() }
            }
        }

        /**
         * Search announces by peer name or destination hash.
         * Automatically updates UI when matching announces are added or updated.
         */
        fun searchAnnounces(query: String): Flow<List<Announce>> {
            return announceDao.searchAnnounces(query).map { entities ->
                entities.map { it.toAnnounce() }
            }
        }

        /**
         * Get announces filtered by node types as a Flow, sorted by most recently seen.
         * Automatically updates UI when announces are added or updated.
         * @param nodeTypes List of node types to include (e.g., ["PEER", "NODE"])
         */
        fun getAnnouncesByTypes(nodeTypes: List<String>): Flow<List<Announce>> {
            return announceDao.getAnnouncesByTypes(nodeTypes).map { entities ->
                entities.map { it.toAnnounce() }
            }
        }

        /**
         * Get announces with pagination support. Combines node type filtering and search query.
         * Initial load: 30 items, Page size: 30 items, Prefetch distance: 10 items.
         *
         * @param nodeTypes List of node types to filter by (empty = all types)
         * @param searchQuery Search query (empty = no search filter)
         * @return Flow of PagingData for infinite scroll
         */
        fun getAnnouncesPaged(
            nodeTypes: List<String>,
            searchQuery: String,
        ): Flow<PagingData<Announce>> {
            return Pager(
                config =
                    PagingConfig(
                        pageSize = 30,
                        initialLoadSize = 30,
                        prefetchDistance = 10,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    when {
                        // Filter by node types AND search query
                        nodeTypes.isNotEmpty() && searchQuery.isNotEmpty() ->
                            announceDao.getAnnouncesByTypesAndSearchPaged(nodeTypes, searchQuery)
                        // Filter by node types only
                        nodeTypes.isNotEmpty() ->
                            announceDao.getAnnouncesByTypesPaged(nodeTypes)
                        // Filter by search query only
                        searchQuery.isNotEmpty() ->
                            announceDao.searchAnnouncesPaged(searchQuery)
                        // No filters
                        else ->
                            announceDao.getAllAnnouncesPaged()
                    }
                },
            ).flow.map { pagingData ->
                pagingData.map { entity -> entity.toAnnounce() }
            }
        }

        /**
         * Get a specific announce by destination hash
         */
        suspend fun getAnnounce(destinationHash: String): Announce? {
            return announceDao.getAnnounce(destinationHash)?.toAnnounce()
        }

        /**
         * Save or update an announce. If an announce with the same destinationHash
         * already exists, it will be updated with the new timestamp, effectively
         * moving it to the top of the list. Preserves favorite status when updating.
         *
         * @param destinationHash Hex string of the destination hash
         * @param peerName Display name for this peer
         * @param publicKey Public key bytes
         * @param appData App data from announce
         * @param hops Number of hops to reach this peer
         * @param timestamp When this announce was seen
         * @param nodeType Type of node ("NODE", "PEER", or "PROPAGATION_NODE")
         * @param receivingInterface Name of the interface through which the announce was received
         */
        suspend fun saveAnnounce(
            destinationHash: String,
            peerName: String,
            publicKey: ByteArray,
            appData: ByteArray?,
            hops: Int,
            timestamp: Long,
            nodeType: String,
            receivingInterface: String? = null,
            aspect: String? = null,
        ) {
            // Preserve favorite status if announce already exists
            val existing = announceDao.getAnnounce(destinationHash)

            val entity =
                AnnounceEntity(
                    destinationHash = destinationHash,
                    peerName = peerName,
                    publicKey = publicKey,
                    appData = appData,
                    hops = hops,
                    lastSeenTimestamp = timestamp,
                    nodeType = nodeType,
                    receivingInterface = receivingInterface,
                    aspect = aspect,
                    isFavorite = existing?.isFavorite ?: false,
                    favoritedTimestamp = existing?.favoritedTimestamp,
                )
            announceDao.upsertAnnounce(entity)
        }

        /**
         * Delete an announce
         */
        suspend fun deleteAnnounce(destinationHash: String) {
            announceDao.deleteAnnounce(destinationHash)
        }

        /**
         * Check if an announce exists
         */
        suspend fun announceExists(destinationHash: String): Boolean {
            return announceDao.announceExists(destinationHash)
        }

        /**
         * Get total count of announces
         */
        suspend fun getAnnounceCount(): Int {
            return announceDao.getAnnounceCount()
        }

        /**
         * Count announces that match the given path table hashes.
         * Filters to only count PEER and NODE types (excludes PROPAGATION_NODE).
         *
         * @param pathTableHashes List of destination hashes from RNS path table
         * @return Count of matching announces
         */
        suspend fun countReachableAnnounces(pathTableHashes: List<String>): Int {
            if (pathTableHashes.isEmpty()) {
                return 0
            }
            return announceDao.countReachableAnnounces(pathTableHashes)
        }

        /**
         * Get all favorite announces as a Flow, sorted by most recently favorited.
         * Automatically updates UI when favorites are added or removed.
         */
        fun getFavoriteAnnounces(): Flow<List<Announce>> {
            return announceDao.getFavoriteAnnounces().map { entities ->
                entities.map { it.toAnnounce() }
            }
        }

        /**
         * Search favorite announces by peer name or destination hash.
         * Automatically updates UI when matching favorites are added or removed.
         */
        fun searchFavoriteAnnounces(query: String): Flow<List<Announce>> {
            return announceDao.searchFavoriteAnnounces(query).map { entities ->
                entities.map { it.toAnnounce() }
            }
        }

        /**
         * Toggle favorite status for an announce.
         * If currently not favorited, sets to favorite and records timestamp.
         * If currently favorited, removes favorite status and clears timestamp.
         */
        suspend fun toggleFavorite(destinationHash: String) {
            val announce = announceDao.getAnnounce(destinationHash) ?: return
            val newFavoriteStatus = !announce.isFavorite
            val timestamp = if (newFavoriteStatus) System.currentTimeMillis() else null
            announceDao.updateFavoriteStatus(destinationHash, newFavoriteStatus, timestamp)
        }

        /**
         * Set favorite status for an announce explicitly
         */
        suspend fun setFavorite(
            destinationHash: String,
            isFavorite: Boolean,
        ) {
            val timestamp = if (isFavorite) System.currentTimeMillis() else null
            announceDao.updateFavoriteStatus(destinationHash, isFavorite, timestamp)
        }

        /**
         * Get count of favorite announces as a Flow.
         */
        fun getFavoriteCount(): Flow<Int> {
            return announceDao.getFavoriteCount()
        }

        /**
         * Get a specific announce as Flow (for observing favorite status changes).
         */
        fun getAnnounceFlow(destinationHash: String): Flow<Announce?> {
            return announceDao.getAnnounceFlow(destinationHash).map { entity ->
                entity?.toAnnounce()
            }
        }

        /**
         * Delete all announces (for testing/debugging)
         */
        suspend fun deleteAllAnnounces() {
            announceDao.deleteAllAnnounces()
        }

        private fun AnnounceEntity.toAnnounce() =
            Announce(
                destinationHash = destinationHash,
                peerName = peerName,
                publicKey = publicKey,
                appData = appData,
                hops = hops,
                lastSeenTimestamp = lastSeenTimestamp,
                nodeType = nodeType,
                receivingInterface = receivingInterface,
                aspect = aspect,
                isFavorite = isFavorite,
                favoritedTimestamp = favoritedTimestamp,
            )
    }
