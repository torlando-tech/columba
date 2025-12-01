package com.lxmf.messenger.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Reticulum interface configurations.
 */
@Dao
interface InterfaceDao {
    /**
     * Get all configured interfaces ordered by display order.
     *
     * @return Flow of all interfaces, emitting updates whenever the data changes
     */
    @Query("SELECT * FROM interfaces ORDER BY displayOrder ASC, id ASC")
    fun getAllInterfaces(): Flow<List<InterfaceEntity>>

    /**
     * Get only enabled interfaces ordered by display order.
     *
     * @return Flow of enabled interfaces, emitting updates whenever the data changes
     */
    @Query("SELECT * FROM interfaces WHERE enabled = 1 ORDER BY displayOrder ASC, id ASC")
    fun getEnabledInterfaces(): Flow<List<InterfaceEntity>>

    /**
     * Get a specific interface by ID.
     *
     * @param id The interface ID
     * @return Flow of the interface or null if not found
     */
    @Query("SELECT * FROM interfaces WHERE id = :id")
    fun getInterfaceById(id: Long): Flow<InterfaceEntity?>

    /**
     * Insert a new interface configuration.
     *
     * @param interfaceEntity The interface to insert
     * @return The ID of the newly inserted interface
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterface(interfaceEntity: InterfaceEntity): Long

    /**
     * Update an existing interface configuration.
     *
     * @param interfaceEntity The interface with updated data
     * @return The number of rows updated (should be 1)
     */
    @Update
    suspend fun updateInterface(interfaceEntity: InterfaceEntity): Int

    /**
     * Delete an interface by ID.
     *
     * @param id The ID of the interface to delete
     */
    @Query("DELETE FROM interfaces WHERE id = :id")
    suspend fun deleteInterface(id: Long)

    /**
     * Toggle the enabled state of an interface.
     *
     * @param id The ID of the interface to toggle
     * @param enabled The new enabled state
     */
    @Query("UPDATE interfaces SET enabled = :enabled WHERE id = :id")
    suspend fun setInterfaceEnabled(
        id: Long,
        enabled: Boolean,
    )

    /**
     * Delete all interfaces (useful for testing).
     */
    @Query("DELETE FROM interfaces")
    suspend fun deleteAllInterfaces()

    /**
     * Get the count of enabled interfaces.
     *
     * @return Flow emitting the count of enabled interfaces
     */
    @Query("SELECT COUNT(*) FROM interfaces WHERE enabled = 1")
    fun getEnabledInterfaceCount(): Flow<Int>

    /**
     * Get the count of all interfaces.
     *
     * @return Flow emitting the total count of interfaces
     */
    @Query("SELECT COUNT(*) FROM interfaces")
    fun getTotalInterfaceCount(): Flow<Int>
}
