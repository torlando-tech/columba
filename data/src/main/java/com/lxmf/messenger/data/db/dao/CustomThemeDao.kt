package com.lxmf.messenger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.db.entity.CustomThemeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomThemeDao {
    /**
     * Insert a new custom theme
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTheme(theme: CustomThemeEntity): Long

    /**
     * Update an existing theme
     */
    @Update
    suspend fun updateTheme(theme: CustomThemeEntity)

    /**
     * Get all custom themes, sorted by creation date (newest first)
     */
    @Query("SELECT * FROM custom_themes ORDER BY createdTimestamp DESC")
    fun getAllThemes(): Flow<List<CustomThemeEntity>>

    /**
     * Get a specific theme by ID
     */
    @Query("SELECT * FROM custom_themes WHERE id = :id")
    suspend fun getThemeById(id: Long): CustomThemeEntity?

    /**
     * Get a specific theme by ID as Flow (for observing changes)
     */
    @Query("SELECT * FROM custom_themes WHERE id = :id")
    fun getThemeByIdFlow(id: Long): Flow<CustomThemeEntity?>

    /**
     * Get a theme by name
     */
    @Query("SELECT * FROM custom_themes WHERE name = :name LIMIT 1")
    suspend fun getThemeByName(name: String): CustomThemeEntity?

    /**
     * Check if a theme name already exists
     */
    @Query("SELECT EXISTS(SELECT 1 FROM custom_themes WHERE name = :name AND id != :excludeId)")
    suspend fun themeNameExists(
        name: String,
        excludeId: Long = -1,
    ): Boolean

    /**
     * Delete a theme by ID
     */
    @Query("DELETE FROM custom_themes WHERE id = :id")
    suspend fun deleteTheme(id: Long)

    /**
     * Delete all custom themes (for testing/debugging)
     */
    @Query("DELETE FROM custom_themes")
    suspend fun deleteAllThemes()

    /**
     * Get count of custom themes
     */
    @Query("SELECT COUNT(*) FROM custom_themes")
    suspend fun getThemeCount(): Int

    /**
     * Get count of custom themes as Flow
     */
    @Query("SELECT COUNT(*) FROM custom_themes")
    fun getThemeCountFlow(): Flow<Int>

    /**
     * Update theme name and description
     */
    @Query("UPDATE custom_themes SET name = :name, description = :description, modifiedTimestamp = :modifiedTimestamp WHERE id = :id")
    suspend fun updateThemeMetadata(
        id: Long,
        name: String,
        description: String,
        modifiedTimestamp: Long,
    )

    /**
     * Search themes by name or description
     */
    @Query(
        """
        SELECT * FROM custom_themes
        WHERE name LIKE '%' || :query || '%'
        OR description LIKE '%' || :query || '%'
        ORDER BY createdTimestamp DESC
    """,
    )
    fun searchThemes(query: String): Flow<List<CustomThemeEntity>>
}
