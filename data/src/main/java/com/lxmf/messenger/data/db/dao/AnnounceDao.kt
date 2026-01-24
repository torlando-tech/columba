package com.lxmf.messenger.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.model.EnrichedAnnounce
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // DAO provides comprehensive query interface for announces + icon enrichment
interface AnnounceDao {
    /**
     * Insert or update an announce. If the destinationHash already exists,
     * the entire row is replaced (updating the timestamp and other fields).
     * This implements the "move to top" behavior when a peer re-announces.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnounce(announce: AnnounceEntity)

    /**
     * Get all announces, sorted by most recently seen (descending).
     * Returns a Flow that emits updated lists whenever the database changes.
     */
    @Query("SELECT * FROM announces ORDER BY lastSeenTimestamp DESC")
    fun getAllAnnounces(): Flow<List<AnnounceEntity>>

    /**
     * Get all announces synchronously (for export).
     */
    @Query("SELECT * FROM announces ORDER BY lastSeenTimestamp DESC")
    suspend fun getAllAnnouncesSync(): List<AnnounceEntity>

    /**
     * Insert multiple announces at once (for import).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnounces(announces: List<AnnounceEntity>)

    /**
     * Search announces by peer name or destination hash.
     * Returns a Flow that emits updated lists whenever the database changes.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE (peerName LIKE '%' || :query || '%' OR destinationHash LIKE '%' || :query || '%')
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun searchAnnounces(query: String): Flow<List<AnnounceEntity>>

    /**
     * Get a specific announce by destination hash
     */
    @Query("SELECT * FROM announces WHERE destinationHash = :destinationHash")
    suspend fun getAnnounce(destinationHash: String): AnnounceEntity?

    /**
     * Check if an announce exists for a given destination hash
     */
    @Query("SELECT EXISTS(SELECT 1 FROM announces WHERE destinationHash = :destinationHash)")
    suspend fun announceExists(destinationHash: String): Boolean

    /**
     * Get all favorite announces, sorted by most recently favorited (descending).
     * Returns a Flow that emits updated lists whenever the database changes.
     */
    @Query("SELECT * FROM announces WHERE isFavorite = 1 ORDER BY favoritedTimestamp DESC")
    fun getFavoriteAnnounces(): Flow<List<AnnounceEntity>>

    /**
     * Search favorite announces by peer name or destination hash.
     * Returns a Flow that emits updated lists whenever the database changes.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE isFavorite = 1
        AND (peerName LIKE '%' || :query || '%' OR destinationHash LIKE '%' || :query || '%')
        ORDER BY favoritedTimestamp DESC
        """,
    )
    fun searchFavoriteAnnounces(query: String): Flow<List<AnnounceEntity>>

    /**
     * Toggle favorite status for an announce.
     * If favoriting, sets current timestamp. If unfavoriting, clears timestamp.
     */
    @Query("UPDATE announces SET isFavorite = :isFavorite, favoritedTimestamp = :timestamp WHERE destinationHash = :destinationHash")
    suspend fun updateFavoriteStatus(
        destinationHash: String,
        isFavorite: Boolean,
        timestamp: Long?,
    )

    /**
     * Get count of favorite announces
     */
    @Query("SELECT COUNT(*) FROM announces WHERE isFavorite = 1")
    fun getFavoriteCount(): Flow<Int>

    /**
     * Get a specific announce as Flow (for observing favorite status changes)
     */
    @Query("SELECT * FROM announces WHERE destinationHash = :destinationHash")
    fun getAnnounceFlow(destinationHash: String): Flow<AnnounceEntity?>

    /**
     * Delete an announce
     */
    @Query("DELETE FROM announces WHERE destinationHash = :destinationHash")
    suspend fun deleteAnnounce(destinationHash: String)

    /**
     * Delete all announces (for testing/debugging)
     */
    @Query("DELETE FROM announces")
    suspend fun deleteAllAnnounces()

    /**
     * Get count of all announces
     */
    @Query("SELECT COUNT(*) FROM announces")
    suspend fun getAnnounceCount(): Int

    /**
     * Get count of all announces as a Flow for reactive UI updates.
     */
    @Query("SELECT COUNT(*) FROM announces")
    fun getAnnounceCountFlow(): Flow<Int>

    /**
     * Get announces filtered by node types.
     * Returns a Flow that emits updated lists whenever the database changes.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE nodeType IN (:nodeTypes)
        AND (nodeType != 'PROPAGATION_NODE' OR stampCostFlexibility IS NOT NULL)
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun getAnnouncesByTypes(nodeTypes: List<String>): Flow<List<AnnounceEntity>>

    /**
     * Get top propagation nodes sorted for optimal relay selection.
     * Priority: fewer hops (better connectivity), higher transfer limit (larger messages),
     * more recently seen (still active). Nodes with unknown limits sorted last.
     * Optimized query with LIMIT in SQL to avoid fetching all rows.
     *
     * @param limit Maximum number of nodes to return (default 10)
     * @return Flow of propagation node announces sorted by best candidates first
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE nodeType = 'PROPAGATION_NODE'
        AND stampCostFlexibility IS NOT NULL
        ORDER BY
            hops ASC,
            CASE WHEN propagationTransferLimitKb IS NULL THEN 1 ELSE 0 END,
            propagationTransferLimitKb DESC,
            lastSeenTimestamp DESC
        LIMIT :limit
    """,
    )
    fun getTopPropagationNodes(limit: Int = 10): Flow<List<AnnounceEntity>>

    /**
     * Count announces that are reachable via RNS path table.
     * Filters to only count PEER and NODE types (excludes PROPAGATION_NODE).
     *
     * @param pathTableHashes List of destination hashes from RNS path table
     * @return Count of reachable peer/node announces
     */
    @Query(
        """
        SELECT COUNT(*) FROM announces
        WHERE destinationHash IN (:pathTableHashes)
        AND nodeType IN ('PEER', 'NODE')
    """,
    )
    suspend fun countReachableAnnounces(pathTableHashes: List<String>): Int

    // ==================== ENRICHED QUERIES (with peer_icons JOIN) ====================
    // These queries include icon data from peer_icons table for UI display.
    // Icons are an LXMF concept (Field 4 in messages), not part of Reticulum announces.

    /**
     * Get all announces with icon data, sorted by most recently seen.
     * Joins peer_icons to get icon appearance from LXMF messages.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun getEnrichedAnnounces(): Flow<List<EnrichedAnnounce>>

    /**
     * Search announces with icon data by peer name or destination hash.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE (a.peerName LIKE '%' || :query || '%' OR a.destinationHash LIKE '%' || :query || '%')
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun searchEnrichedAnnounces(query: String): Flow<List<EnrichedAnnounce>>

    /**
     * Get announces filtered by node types with icon data.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.nodeType IN (:nodeTypes)
        AND (a.nodeType != 'PROPAGATION_NODE' OR a.stampCostFlexibility IS NOT NULL)
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun getEnrichedAnnouncesByTypes(nodeTypes: List<String>): Flow<List<EnrichedAnnounce>>

    /**
     * Get all favorite announces with icon data, sorted by most recently favorited.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.isFavorite = 1
        ORDER BY a.favoritedTimestamp DESC
        """,
    )
    fun getEnrichedFavoriteAnnounces(): Flow<List<EnrichedAnnounce>>

    /**
     * Search favorite announces with icon data by peer name or destination hash.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.isFavorite = 1
        AND (a.peerName LIKE '%' || :query || '%' OR a.destinationHash LIKE '%' || :query || '%')
        ORDER BY a.favoritedTimestamp DESC
        """,
    )
    fun searchEnrichedFavoriteAnnounces(query: String): Flow<List<EnrichedAnnounce>>

    /**
     * Get a specific announce with icon data as Flow.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.destinationHash = :destinationHash
        """,
    )
    fun getEnrichedAnnounceFlow(destinationHash: String): Flow<EnrichedAnnounce?>

    /**
     * Get top propagation nodes with icon data for relay selection.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.nodeType = 'PROPAGATION_NODE'
        AND a.stampCostFlexibility IS NOT NULL
        ORDER BY
            a.hops ASC,
            CASE WHEN a.propagationTransferLimitKb IS NULL THEN 1 ELSE 0 END,
            a.propagationTransferLimitKb DESC,
            a.lastSeenTimestamp DESC
        LIMIT :limit
        """,
    )
    fun getEnrichedTopPropagationNodes(limit: Int = 10): Flow<List<EnrichedAnnounce>>

    // Paging3 methods for infinite scroll (with peer_icons JOIN)

    /**
     * Get all announces with icon data and pagination support.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE (a.nodeType != 'PROPAGATION_NODE' OR a.stampCostFlexibility IS NOT NULL)
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun getEnrichedAnnouncesPaged(): PagingSource<Int, EnrichedAnnounce>

    /**
     * Get announces filtered by node types with icon data and pagination support.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.nodeType IN (:nodeTypes)
        AND (a.nodeType != 'PROPAGATION_NODE' OR a.stampCostFlexibility IS NOT NULL)
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun getEnrichedAnnouncesByTypesPaged(nodeTypes: List<String>): PagingSource<Int, EnrichedAnnounce>

    /**
     * Search announces with icon data and pagination support.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE (a.peerName LIKE '%' || :query || '%' OR a.destinationHash LIKE '%' || :query || '%')
        AND (a.nodeType != 'PROPAGATION_NODE' OR a.stampCostFlexibility IS NOT NULL)
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun searchEnrichedAnnouncesPaged(query: String): PagingSource<Int, EnrichedAnnounce>

    /**
     * Get announces filtered by node types AND search query with icon data and pagination.
     */
    @Query(
        """
        SELECT
            a.destinationHash,
            a.peerName,
            a.publicKey,
            a.appData,
            a.hops,
            a.lastSeenTimestamp,
            a.nodeType,
            a.receivingInterface,
            a.receivingInterfaceType,
            a.aspect,
            a.isFavorite,
            a.favoritedTimestamp,
            a.stampCost,
            a.stampCostFlexibility,
            a.peeringCost,
            a.propagationTransferLimitKb,
            pi.iconName as iconName,
            pi.foregroundColor as iconForegroundColor,
            pi.backgroundColor as iconBackgroundColor
        FROM announces a
        LEFT JOIN peer_icons pi ON a.destinationHash = pi.destinationHash
        WHERE a.nodeType IN (:nodeTypes)
        AND (a.peerName LIKE '%' || :query || '%' OR a.destinationHash LIKE '%' || :query || '%')
        AND (a.nodeType != 'PROPAGATION_NODE' OR a.stampCostFlexibility IS NOT NULL)
        ORDER BY a.lastSeenTimestamp DESC
        """,
    )
    fun getEnrichedAnnouncesByTypesAndSearchPaged(
        nodeTypes: List<String>,
        query: String,
    ): PagingSource<Int, EnrichedAnnounce>

    // Paging3 methods for infinite scroll

    /**
     * Get all announces with pagination support, sorted by most recently seen (descending).
     * Returns a PagingSource for use with Paging 3 library.
     *
     * Note: Deprecated propagation nodes (stampCostFlexibility IS NULL) are filtered out
     * for consistency with relay selection. These nodes have outdated announce formats
     * that cause sync failures.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE (nodeType != 'PROPAGATION_NODE' OR stampCostFlexibility IS NOT NULL)
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun getAllAnnouncesPaged(): PagingSource<Int, AnnounceEntity>

    /**
     * Get announces filtered by node types with pagination support.
     * Returns a PagingSource for use with Paging 3 library.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE nodeType IN (:nodeTypes)
        AND (nodeType != 'PROPAGATION_NODE' OR stampCostFlexibility IS NOT NULL)
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun getAnnouncesByTypesPaged(nodeTypes: List<String>): PagingSource<Int, AnnounceEntity>

    /**
     * Search announces by peer name or destination hash with pagination support.
     * Returns a PagingSource for use with Paging 3 library.
     *
     * Note: Deprecated propagation nodes (stampCostFlexibility IS NULL) are filtered out
     * for consistency with relay selection.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE (peerName LIKE '%' || :query || '%' OR destinationHash LIKE '%' || :query || '%')
        AND (nodeType != 'PROPAGATION_NODE' OR stampCostFlexibility IS NOT NULL)
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun searchAnnouncesPaged(query: String): PagingSource<Int, AnnounceEntity>

    /**
     * Get announces filtered by node types AND search query with pagination support.
     * Returns a PagingSource for use with Paging 3 library.
     *
     * Note: Deprecated propagation nodes (stampCostFlexibility IS NULL) are filtered out
     * for consistency with relay selection.
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE nodeType IN (:nodeTypes)
        AND (peerName LIKE '%' || :query || '%' OR destinationHash LIKE '%' || :query || '%')
        AND (nodeType != 'PROPAGATION_NODE' OR stampCostFlexibility IS NOT NULL)
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun getAnnouncesByTypesAndSearchPaged(
        nodeTypes: List<String>,
        query: String,
    ): PagingSource<Int, AnnounceEntity>

    // Debug methods for troubleshooting

    /**
     * Get count of announces grouped by nodeType.
     * Used for debugging relay selection issues.
     * Returns Map with keys like "PEER", "NODE", "PROPAGATION_NODE"
     */
    @Query("SELECT nodeType, COUNT(*) as count FROM announces GROUP BY nodeType")
    suspend fun getNodeTypeCounts(): List<NodeTypeCount>
}

/**
 * Data class for nodeType count results.
 */
data class NodeTypeCount(
    val nodeType: String,
    val count: Int,
)
