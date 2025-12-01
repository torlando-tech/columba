package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a user-created custom theme.
 *
 * Stores all Material 3 color roles for both light and dark modes.
 * Themes can be created from scratch, duplicated from presets, or imported.
 */
@Entity(tableName = "custom_themes")
data class CustomThemeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // Theme metadata
    val name: String,
    val description: String = "",
    val createdTimestamp: Long = System.currentTimeMillis(),
    val modifiedTimestamp: Long = System.currentTimeMillis(),
    val baseTheme: String? = null, // "VIBRANT", "OCEAN", etc. if duplicated from preset
    // Original seed colors (unmodified user selections for re-editing)
    val seedPrimary: Int,
    val seedSecondary: Int,
    val seedTertiary: Int,
    // Light mode colors (Material 3 color roles)
    val lightPrimary: Int,
    val lightOnPrimary: Int,
    val lightPrimaryContainer: Int,
    val lightOnPrimaryContainer: Int,
    val lightSecondary: Int,
    val lightOnSecondary: Int,
    val lightSecondaryContainer: Int,
    val lightOnSecondaryContainer: Int,
    val lightTertiary: Int,
    val lightOnTertiary: Int,
    val lightTertiaryContainer: Int,
    val lightOnTertiaryContainer: Int,
    val lightError: Int,
    val lightOnError: Int,
    val lightErrorContainer: Int,
    val lightOnErrorContainer: Int,
    val lightBackground: Int,
    val lightOnBackground: Int,
    val lightSurface: Int,
    val lightOnSurface: Int,
    val lightSurfaceVariant: Int,
    val lightOnSurfaceVariant: Int,
    val lightOutline: Int,
    val lightOutlineVariant: Int,
    // Dark mode colors (Material 3 color roles)
    val darkPrimary: Int,
    val darkOnPrimary: Int,
    val darkPrimaryContainer: Int,
    val darkOnPrimaryContainer: Int,
    val darkSecondary: Int,
    val darkOnSecondary: Int,
    val darkSecondaryContainer: Int,
    val darkOnSecondaryContainer: Int,
    val darkTertiary: Int,
    val darkOnTertiary: Int,
    val darkTertiaryContainer: Int,
    val darkOnTertiaryContainer: Int,
    val darkError: Int,
    val darkOnError: Int,
    val darkErrorContainer: Int,
    val darkOnErrorContainer: Int,
    val darkBackground: Int,
    val darkOnBackground: Int,
    val darkSurface: Int,
    val darkOnSurface: Int,
    val darkSurfaceVariant: Int,
    val darkOnSurfaceVariant: Int,
    val darkOutline: Int,
    val darkOutlineVariant: Int,
)
