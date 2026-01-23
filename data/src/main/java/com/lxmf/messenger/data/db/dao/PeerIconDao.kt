package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.PeerIconEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for peer icons stored from LXMF messages (Field 4 - icon appearance).
 *
 * Icons are stored separately from announces because:
 * - Announces are a Reticulum concept (network peer discovery)
 * - Icons are an LXMF concept (transmitted in message field 4)
 *
 * All UI queries (conversations, announces, contacts) JOIN this table
 * to display icons wherever a peer is rendered.
 */
@Dao
interface PeerIconDao {
    /**
     * Insert or update a peer's icon appearance.
     * Uses REPLACE strategy to update existing icons.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIcon(icon: PeerIconEntity)

    /**
     * Get a peer's icon by destination hash.
     */
    @Query("SELECT * FROM peer_icons WHERE destinationHash = :destinationHash")
    suspend fun getIcon(destinationHash: String): PeerIconEntity?

    /**
     * Get a peer's icon as a Flow for reactive updates.
     */
    @Query("SELECT * FROM peer_icons WHERE destinationHash = :destinationHash")
    fun getIconFlow(destinationHash: String): Flow<PeerIconEntity?>

    /**
     * Delete a peer's icon.
     */
    @Query("DELETE FROM peer_icons WHERE destinationHash = :destinationHash")
    suspend fun deleteIcon(destinationHash: String)

    /**
     * Get all peer icons (for debugging/export).
     */
    @Query("SELECT * FROM peer_icons ORDER BY updatedTimestamp DESC")
    suspend fun getAllIcons(): List<PeerIconEntity>

    /**
     * Get count of stored icons.
     */
    @Query("SELECT COUNT(*) FROM peer_icons")
    suspend fun getIconCount(): Int
}
