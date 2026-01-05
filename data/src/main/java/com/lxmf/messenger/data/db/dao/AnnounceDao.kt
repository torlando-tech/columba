package com.lxmf.messenger.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import kotlinx.coroutines.flow.Flow

@Dao
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

    // Paging3 methods for infinite scroll

    /**
     * Get all announces with pagination support, sorted by most recently seen (descending).
     * Returns a PagingSource for use with Paging 3 library.
     */
    @Query("SELECT * FROM announces ORDER BY lastSeenTimestamp DESC")
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
     */
    @Query(
        """
        SELECT * FROM announces
        WHERE (peerName LIKE '%' || :query || '%' OR destinationHash LIKE '%' || :query || '%')
        ORDER BY lastSeenTimestamp DESC
        """,
    )
    fun searchAnnouncesPaged(query: String): PagingSource<Int, AnnounceEntity>

    /**
     * Get announces filtered by node types AND search query with pagination support.
     * Returns a PagingSource for use with Paging 3 library.
     */
    @Query(
        "SELECT * FROM announces WHERE nodeType IN (:nodeTypes) AND " +
            "(peerName LIKE '%' || :query || '%' OR destinationHash LIKE '%' || :query || '%') " +
            "ORDER BY lastSeenTimestamp DESC",
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

    /**
     * Update the icon appearance for an announce.
     * @param destinationHash The destination hash of the announce
     * @param iconName The icon name (e.g., Material icon name)
     * @param foregroundColor Hex RGB color for icon foreground (e.g., "FFFFFF")
     * @param backgroundColor Hex RGB color for icon background (e.g., "1E88E5")
     */
    @Query(
        "UPDATE announces SET iconName = :iconName, iconForegroundColor = :foregroundColor, iconBackgroundColor = :backgroundColor WHERE destinationHash = :destinationHash",
    )
    suspend fun updateIconAppearance(
        destinationHash: String,
        iconName: String?,
        foregroundColor: String?,
        backgroundColor: String?,
    )
}

/**
 * Data class for nodeType count results.
 */
data class NodeTypeCount(
    val nodeType: String,
    val count: Int,
)
