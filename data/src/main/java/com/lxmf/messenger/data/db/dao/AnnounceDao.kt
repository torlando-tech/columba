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
    @Query("SELECT * FROM announces WHERE nodeType IN (:nodeTypes) ORDER BY lastSeenTimestamp DESC")
    fun getAnnouncesByTypes(nodeTypes: List<String>): Flow<List<AnnounceEntity>>

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
    @Query("SELECT * FROM announces WHERE nodeType IN (:nodeTypes) ORDER BY lastSeenTimestamp DESC")
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
}
