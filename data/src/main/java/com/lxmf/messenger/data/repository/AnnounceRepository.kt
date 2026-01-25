package com.lxmf.messenger.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.model.EnrichedAnnounce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing an announce for UI layer
 */
@androidx.compose.runtime.Stable
data class Announce(
    val destinationHash: String,
    val peerName: String,
    val publicKey: ByteArray,
    val appData: ByteArray?,
    val hops: Int,
    val lastSeenTimestamp: Long,
    val nodeType: String,
    val receivingInterface: String? = null,
    val receivingInterfaceType: String? = null,
    val aspect: String? = null, // Destination aspect (e.g., "lxmf.delivery", "call.audio")
    val isFavorite: Boolean = false,
    val favoritedTimestamp: Long? = null,
    val stampCost: Int? = null, // Stamp cost for message delivery
    val stampCostFlexibility: Int? = null, // Flexibility range for propagation nodes
    val peeringCost: Int? = null, // Peering cost for propagation nodes
    val iconName: String? = null, // Icon name (e.g., Material icon name)
    val iconForegroundColor: String? = null, // Hex RGB e.g., "FFFFFF"
    val iconBackgroundColor: String? = null, // Hex RGB e.g., "1E88E5"
    val propagationTransferLimitKb: Int? = null, // Per-message size limit for propagation nodes (in KB)
) {
    @Suppress("CyclomaticComplexMethod")
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
        if (receivingInterfaceType != other.receivingInterfaceType) return false
        if (isFavorite != other.isFavorite) return false
        if (favoritedTimestamp != other.favoritedTimestamp) return false
        if (stampCost != other.stampCost) return false
        if (stampCostFlexibility != other.stampCostFlexibility) return false
        if (peeringCost != other.peeringCost) return false
        if (iconName != other.iconName) return false
        if (iconForegroundColor != other.iconForegroundColor) return false
        if (iconBackgroundColor != other.iconBackgroundColor) return false
        if (propagationTransferLimitKb != other.propagationTransferLimitKb) return false

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
        result = 31 * result + (receivingInterfaceType?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedTimestamp?.hashCode() ?: 0)
        result = 31 * result + (stampCost?.hashCode() ?: 0)
        result = 31 * result + (stampCostFlexibility?.hashCode() ?: 0)
        result = 31 * result + (peeringCost?.hashCode() ?: 0)
        result = 31 * result + (iconName?.hashCode() ?: 0)
        result = 31 * result + (iconForegroundColor?.hashCode() ?: 0)
        result = 31 * result + (iconBackgroundColor?.hashCode() ?: 0)
        result = 31 * result + (propagationTransferLimitKb?.hashCode() ?: 0)
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
         * Includes icon data from peer_icons table (LXMF message appearances).
         */
        fun getAnnounces(): Flow<List<Announce>> {
            return announceDao.getEnrichedAnnounces().map { enriched ->
                enriched.map { it.toAnnounce() }
            }
        }

        /**
         * Search announces by peer name or destination hash.
         * Automatically updates UI when matching announces are added or updated.
         * Includes icon data from peer_icons table.
         */
        fun searchAnnounces(query: String): Flow<List<Announce>> {
            return announceDao.searchEnrichedAnnounces(query).map { enriched ->
                enriched.map { it.toAnnounce() }
            }
        }

        /**
         * Get announces filtered by node types as a Flow, sorted by most recently seen.
         * Automatically updates UI when announces are added or updated.
         * Includes icon data from peer_icons table.
         * @param nodeTypes List of node types to include (e.g., ["PEER", "NODE"])
         */
        fun getAnnouncesByTypes(nodeTypes: List<String>): Flow<List<Announce>> {
            return announceDao.getEnrichedAnnouncesByTypes(nodeTypes).map { enriched ->
                enriched.map { it.toAnnounce() }
            }
        }

        /**
         * Get top propagation nodes sorted by hop count (ascending).
         * Optimized query with LIMIT in SQL - only fetches the requested number of rows.
         * Used for relay selection UI. Includes icon data from peer_icons table.
         *
         * @param limit Maximum number of nodes to return (default 10)
         * @return Flow of propagation node announces sorted by nearest first
         */
        fun getTopPropagationNodes(limit: Int = 10): Flow<List<Announce>> {
            return announceDao.getEnrichedTopPropagationNodes(limit).map { enriched ->
                enriched.map { it.toAnnounce() }
            }
        }

        /**
         * Get announces with pagination support. Combines node type filtering and search query.
         * Initial load: 30 items, Page size: 30 items, Prefetch distance: 10 items.
         * Includes icon data from peer_icons table.
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
                            announceDao.getEnrichedAnnouncesByTypesAndSearchPaged(nodeTypes, searchQuery)
                        // Filter by node types only
                        nodeTypes.isNotEmpty() ->
                            announceDao.getEnrichedAnnouncesByTypesPaged(nodeTypes)
                        // Filter by search query only
                        searchQuery.isNotEmpty() ->
                            announceDao.searchEnrichedAnnouncesPaged(searchQuery)
                        // No filters
                        else ->
                            announceDao.getEnrichedAnnouncesPaged()
                    }
                },
            ).flow.map { pagingData ->
                pagingData.map { enriched -> enriched.toAnnounce() }
            }
        }

        /**
         * Get a specific announce by destination hash
         */
        suspend fun getAnnounce(destinationHash: String): Announce? {
            return announceDao.getAnnounce(destinationHash)?.toAnnounce()
        }

        /**
         * Find an announce by identity hash.
         * Identity hash is the first 16 bytes of SHA256(public_key).
         * This is useful for LXST calls where we receive identity hash instead of destination hash.
         */
        suspend fun findByIdentityHash(identityHash: String): Announce? {
            val allAnnounces = announceDao.getAllAnnouncesSync()
            for (entity in allAnnounces) {
                val computedHash = computeIdentityHash(entity.publicKey)
                if (computedHash.equals(identityHash, ignoreCase = true)) {
                    return entity.toAnnounce()
                }
            }
            return null
        }

        /**
         * Compute identity hash from public key.
         * In Reticulum: identity_hash = first 16 bytes of SHA256(public_key) as hex.
         */
        private fun computeIdentityHash(publicKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey)
            // Take first 16 bytes and convert to hex
            return hash.take(16).joinToString("") { "%02x".format(it) }
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
        @Suppress("LongParameterList")
        suspend fun saveAnnounce(
            destinationHash: String,
            peerName: String,
            publicKey: ByteArray,
            appData: ByteArray?,
            hops: Int,
            timestamp: Long,
            nodeType: String,
            receivingInterface: String? = null,
            receivingInterfaceType: String? = null,
            aspect: String? = null,
            stampCost: Int? = null,
            stampCostFlexibility: Int? = null,
            peeringCost: Int? = null,
            propagationTransferLimitKb: Int? = null,
        ) {
            // Preserve favorite status if announce already exists
            // Note: Icons are stored separately in peer_icons table (from LXMF messages)
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
                    receivingInterfaceType = receivingInterfaceType,
                    aspect = aspect,
                    isFavorite = existing?.isFavorite ?: false,
                    favoritedTimestamp = existing?.favoritedTimestamp,
                    stampCost = stampCost,
                    stampCostFlexibility = stampCostFlexibility,
                    peeringCost = peeringCost,
                    propagationTransferLimitKb = propagationTransferLimitKb,
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
         * Get total count of announces as a Flow for reactive UI updates.
         */
        fun getAnnounceCountFlow(): Flow<Int> {
            return announceDao.getAnnounceCountFlow()
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
         * Includes icon data from peer_icons table.
         */
        fun getFavoriteAnnounces(): Flow<List<Announce>> {
            return announceDao.getEnrichedFavoriteAnnounces().map { enriched ->
                enriched.map { it.toAnnounce() }
            }
        }

        /**
         * Search favorite announces by peer name or destination hash.
         * Automatically updates UI when matching favorites are added or removed.
         * Includes icon data from peer_icons table.
         */
        fun searchFavoriteAnnounces(query: String): Flow<List<Announce>> {
            return announceDao.searchEnrichedFavoriteAnnounces(query).map { enriched ->
                enriched.map { it.toAnnounce() }
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
         * Includes icon data from peer_icons table.
         */
        fun getAnnounceFlow(destinationHash: String): Flow<Announce?> {
            return announceDao.getEnrichedAnnounceFlow(destinationHash).map { enriched ->
                enriched?.toAnnounce()
            }
        }

        /**
         * Delete all announces (for testing/debugging)
         */
        suspend fun deleteAllAnnounces() {
            announceDao.deleteAllAnnounces()
        }

        /**
         * Get count of announces grouped by nodeType.
         * Used for debugging relay selection issues.
         * Returns list of (nodeType, count) pairs.
         */
        suspend fun getNodeTypeCounts(): List<Pair<String, Int>> {
            return announceDao.getNodeTypeCounts().map { it.nodeType to it.count }
        }

        // Note: This mapping is only used for non-UI operations (export, toggle favorite, etc.)
        // For UI display, use enriched queries that join peer_icons for icon data
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
                receivingInterfaceType = receivingInterfaceType,
                aspect = aspect,
                isFavorite = isFavorite,
                favoritedTimestamp = favoritedTimestamp,
                stampCost = stampCost,
                stampCostFlexibility = stampCostFlexibility,
                peeringCost = peeringCost,
                iconName = null, // Icon data now comes from peer_icons table via enriched queries
                iconForegroundColor = null,
                iconBackgroundColor = null,
                propagationTransferLimitKb = propagationTransferLimitKb,
            )

        private fun EnrichedAnnounce.toAnnounce() =
            Announce(
                destinationHash = destinationHash,
                peerName = peerName,
                publicKey = publicKey,
                appData = appData,
                hops = hops,
                lastSeenTimestamp = lastSeenTimestamp,
                nodeType = nodeType,
                receivingInterface = receivingInterface,
                receivingInterfaceType = receivingInterfaceType,
                aspect = aspect,
                isFavorite = isFavorite,
                favoritedTimestamp = favoritedTimestamp,
                stampCost = stampCost,
                stampCostFlexibility = stampCostFlexibility,
                peeringCost = peeringCost,
                iconName = iconName,
                iconForegroundColor = iconForegroundColor,
                iconBackgroundColor = iconBackgroundColor,
                propagationTransferLimitKb = propagationTransferLimitKb,
            )
    }
