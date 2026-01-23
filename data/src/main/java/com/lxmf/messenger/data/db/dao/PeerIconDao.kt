package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxmf.messenger.data.db.entity.PeerIconEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for peer icon appearances.
 *
 * Icons are received via LXMF messages (Field 4) and stored here for consistent
 * display across all UI components (chats, announces, contacts).
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
     * Observe a peer's icon for reactive UI updates.
     */
    @Query("SELECT * FROM peer_icons WHERE destinationHash = :destinationHash")
    fun observeIcon(destinationHash: String): Flow<PeerIconEntity?>

    /**
     * Delete a peer's icon.
     */
    @Query("DELETE FROM peer_icons WHERE destinationHash = :destinationHash")
    suspend fun deleteIcon(destinationHash: String)

    /**
     * Delete all peer icons (for testing/debugging).
     */
    @Query("DELETE FROM peer_icons")
    suspend fun deleteAllIcons()
}
