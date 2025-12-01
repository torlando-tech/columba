package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.CustomThemeDao
import com.lxmf.messenger.data.db.entity.CustomThemeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Theme color set for one mode (light or dark).
 * Stores ARGB color values for all Material 3 color roles.
 */
data class ThemeColorSet(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
)

/**
 * Data model for custom themes used in the app layer.
 * Stores raw ARGB color values - conversion to ColorScheme happens in the app layer.
 */
data class CustomThemeData(
    val id: Long,
    val name: String,
    val description: String,
    val seedPrimary: Int,
    val seedSecondary: Int,
    val seedTertiary: Int,
    val lightColors: ThemeColorSet,
    val darkColors: ThemeColorSet,
    val baseTheme: String?,
    val createdTimestamp: Long,
    val modifiedTimestamp: Long,
)

/**
 * Repository for managing user-created custom themes.
 *
 * Handles CRUD operations for custom themes and conversion between
 * database entities and app-layer data models.
 */
@Singleton
class CustomThemeRepository
    @Inject
    constructor(
        private val customThemeDao: CustomThemeDao,
    ) {
        /**
         * Get all custom themes as a Flow.
         * Returns CustomThemeData objects with ColorSchemes ready to use.
         */
        fun getAllThemes(): Flow<List<CustomThemeData>> {
            return customThemeDao.getAllThemes().map { entities ->
                entities.map { it.toCustomThemeData() }
            }
        }

        /**
         * Get a specific theme by ID
         */
        suspend fun getThemeById(id: Long): CustomThemeData? {
            return customThemeDao.getThemeById(id)?.toCustomThemeData()
        }

        /**
         * Get a specific theme by ID as Flow (for observing changes)
         */
        fun getThemeByIdFlow(id: Long): Flow<CustomThemeData?> {
            return customThemeDao.getThemeByIdFlow(id).map { it?.toCustomThemeData() }
        }

        /**
         * Save a new custom theme or update an existing one.
         * @return The ID of the saved theme
         */
        suspend fun saveTheme(themeData: CustomThemeData): Long {
            val entity = themeData.toEntity()
            return if (entity.id == 0L) {
                // New theme - insert
                customThemeDao.insertTheme(entity)
            } else {
                // Existing theme - update
                customThemeDao.updateTheme(entity)
                entity.id
            }
        }

        /**
         * Save a custom theme from color sets.
         * Convenience method for creating new themes.
         */
        suspend fun saveTheme(
            name: String,
            description: String,
            seedPrimary: Int,
            seedSecondary: Int,
            seedTertiary: Int,
            lightColors: ThemeColorSet,
            darkColors: ThemeColorSet,
            baseTheme: String? = null,
        ): Long {
            val entity =
                CustomThemeEntity(
                    name = name,
                    description = description,
                    baseTheme = baseTheme,
                    // Seed colors (original user selections)
                    seedPrimary = seedPrimary,
                    seedSecondary = seedSecondary,
                    seedTertiary = seedTertiary,
                    // Light mode colors
                    lightPrimary = lightColors.primary,
                    lightOnPrimary = lightColors.onPrimary,
                    lightPrimaryContainer = lightColors.primaryContainer,
                    lightOnPrimaryContainer = lightColors.onPrimaryContainer,
                    lightSecondary = lightColors.secondary,
                    lightOnSecondary = lightColors.onSecondary,
                    lightSecondaryContainer = lightColors.secondaryContainer,
                    lightOnSecondaryContainer = lightColors.onSecondaryContainer,
                    lightTertiary = lightColors.tertiary,
                    lightOnTertiary = lightColors.onTertiary,
                    lightTertiaryContainer = lightColors.tertiaryContainer,
                    lightOnTertiaryContainer = lightColors.onTertiaryContainer,
                    lightError = lightColors.error,
                    lightOnError = lightColors.onError,
                    lightErrorContainer = lightColors.errorContainer,
                    lightOnErrorContainer = lightColors.onErrorContainer,
                    lightBackground = lightColors.background,
                    lightOnBackground = lightColors.onBackground,
                    lightSurface = lightColors.surface,
                    lightOnSurface = lightColors.onSurface,
                    lightSurfaceVariant = lightColors.surfaceVariant,
                    lightOnSurfaceVariant = lightColors.onSurfaceVariant,
                    lightOutline = lightColors.outline,
                    lightOutlineVariant = lightColors.outlineVariant,
                    // Dark mode colors
                    darkPrimary = darkColors.primary,
                    darkOnPrimary = darkColors.onPrimary,
                    darkPrimaryContainer = darkColors.primaryContainer,
                    darkOnPrimaryContainer = darkColors.onPrimaryContainer,
                    darkSecondary = darkColors.secondary,
                    darkOnSecondary = darkColors.onSecondary,
                    darkSecondaryContainer = darkColors.secondaryContainer,
                    darkOnSecondaryContainer = darkColors.onSecondaryContainer,
                    darkTertiary = darkColors.tertiary,
                    darkOnTertiary = darkColors.onTertiary,
                    darkTertiaryContainer = darkColors.tertiaryContainer,
                    darkOnTertiaryContainer = darkColors.onTertiaryContainer,
                    darkError = darkColors.error,
                    darkOnError = darkColors.onError,
                    darkErrorContainer = darkColors.errorContainer,
                    darkOnErrorContainer = darkColors.onErrorContainer,
                    darkBackground = darkColors.background,
                    darkOnBackground = darkColors.onBackground,
                    darkSurface = darkColors.surface,
                    darkOnSurface = darkColors.onSurface,
                    darkSurfaceVariant = darkColors.surfaceVariant,
                    darkOnSurfaceVariant = darkColors.onSurfaceVariant,
                    darkOutline = darkColors.outline,
                    darkOutlineVariant = darkColors.outlineVariant,
                )
            return customThemeDao.insertTheme(entity)
        }

        /**
         * Update an existing theme with new colors.
         * Convenience method for updating themes.
         */
        suspend fun updateTheme(
            id: Long,
            name: String,
            description: String,
            seedPrimary: Int,
            seedSecondary: Int,
            seedTertiary: Int,
            lightColors: ThemeColorSet,
            darkColors: ThemeColorSet,
            baseTheme: String? = null,
        ) {
            val entity =
                CustomThemeEntity(
                    id = id,
                    name = name,
                    description = description,
                    baseTheme = baseTheme,
                    createdTimestamp = 0, // Will be ignored in update
                    modifiedTimestamp = System.currentTimeMillis(),
                    // Seed colors (original user selections)
                    seedPrimary = seedPrimary,
                    seedSecondary = seedSecondary,
                    seedTertiary = seedTertiary,
                    // Light mode colors
                    lightPrimary = lightColors.primary,
                    lightOnPrimary = lightColors.onPrimary,
                    lightPrimaryContainer = lightColors.primaryContainer,
                    lightOnPrimaryContainer = lightColors.onPrimaryContainer,
                    lightSecondary = lightColors.secondary,
                    lightOnSecondary = lightColors.onSecondary,
                    lightSecondaryContainer = lightColors.secondaryContainer,
                    lightOnSecondaryContainer = lightColors.onSecondaryContainer,
                    lightTertiary = lightColors.tertiary,
                    lightOnTertiary = lightColors.onTertiary,
                    lightTertiaryContainer = lightColors.tertiaryContainer,
                    lightOnTertiaryContainer = lightColors.onTertiaryContainer,
                    lightError = lightColors.error,
                    lightOnError = lightColors.onError,
                    lightErrorContainer = lightColors.errorContainer,
                    lightOnErrorContainer = lightColors.onErrorContainer,
                    lightBackground = lightColors.background,
                    lightOnBackground = lightColors.onBackground,
                    lightSurface = lightColors.surface,
                    lightOnSurface = lightColors.onSurface,
                    lightSurfaceVariant = lightColors.surfaceVariant,
                    lightOnSurfaceVariant = lightColors.onSurfaceVariant,
                    lightOutline = lightColors.outline,
                    lightOutlineVariant = lightColors.outlineVariant,
                    // Dark mode colors
                    darkPrimary = darkColors.primary,
                    darkOnPrimary = darkColors.onPrimary,
                    darkPrimaryContainer = darkColors.primaryContainer,
                    darkOnPrimaryContainer = darkColors.onPrimaryContainer,
                    darkSecondary = darkColors.secondary,
                    darkOnSecondary = darkColors.onSecondary,
                    darkSecondaryContainer = darkColors.secondaryContainer,
                    darkOnSecondaryContainer = darkColors.onSecondaryContainer,
                    darkTertiary = darkColors.tertiary,
                    darkOnTertiary = darkColors.onTertiary,
                    darkTertiaryContainer = darkColors.tertiaryContainer,
                    darkOnTertiaryContainer = darkColors.onTertiaryContainer,
                    darkError = darkColors.error,
                    darkOnError = darkColors.onError,
                    darkErrorContainer = darkColors.errorContainer,
                    darkOnErrorContainer = darkColors.onErrorContainer,
                    darkBackground = darkColors.background,
                    darkOnBackground = darkColors.onBackground,
                    darkSurface = darkColors.surface,
                    darkOnSurface = darkColors.onSurface,
                    darkSurfaceVariant = darkColors.surfaceVariant,
                    darkOnSurfaceVariant = darkColors.onSurfaceVariant,
                    darkOutline = darkColors.outline,
                    darkOutlineVariant = darkColors.outlineVariant,
                )
            customThemeDao.updateTheme(entity)
        }

        /**
         * Delete a theme by ID
         */
        suspend fun deleteTheme(id: Long) {
            customThemeDao.deleteTheme(id)
        }

        /**
         * Update theme metadata (name and description)
         */
        suspend fun updateThemeMetadata(
            id: Long,
            name: String,
            description: String,
        ) {
            customThemeDao.updateThemeMetadata(id, name, description, System.currentTimeMillis())
        }

        /**
         * Check if a theme name already exists (for validation)
         */
        suspend fun themeNameExists(
            name: String,
            excludeId: Long = -1,
        ): Boolean {
            return customThemeDao.themeNameExists(name, excludeId)
        }

        /**
         * Get count of custom themes
         */
        suspend fun getThemeCount(): Int {
            return customThemeDao.getThemeCount()
        }

        /**
         * Search themes by name or description
         */
        fun searchThemes(query: String): Flow<List<CustomThemeData>> {
            return customThemeDao.searchThemes(query).map { entities ->
                entities.map { it.toCustomThemeData() }
            }
        }
    }

/**
 * Extension function to convert CustomThemeEntity to CustomThemeData.
 * Builds ThemeColorSet objects from stored ARGB color values.
 */
private fun CustomThemeEntity.toCustomThemeData(): CustomThemeData {
    return CustomThemeData(
        id = id,
        name = name,
        description = description,
        seedPrimary = seedPrimary,
        seedSecondary = seedSecondary,
        seedTertiary = seedTertiary,
        lightColors =
            ThemeColorSet(
                primary = lightPrimary,
                onPrimary = lightOnPrimary,
                primaryContainer = lightPrimaryContainer,
                onPrimaryContainer = lightOnPrimaryContainer,
                secondary = lightSecondary,
                onSecondary = lightOnSecondary,
                secondaryContainer = lightSecondaryContainer,
                onSecondaryContainer = lightOnSecondaryContainer,
                tertiary = lightTertiary,
                onTertiary = lightOnTertiary,
                tertiaryContainer = lightTertiaryContainer,
                onTertiaryContainer = lightOnTertiaryContainer,
                error = lightError,
                onError = lightOnError,
                errorContainer = lightErrorContainer,
                onErrorContainer = lightOnErrorContainer,
                background = lightBackground,
                onBackground = lightOnBackground,
                surface = lightSurface,
                onSurface = lightOnSurface,
                surfaceVariant = lightSurfaceVariant,
                onSurfaceVariant = lightOnSurfaceVariant,
                outline = lightOutline,
                outlineVariant = lightOutlineVariant,
            ),
        darkColors =
            ThemeColorSet(
                primary = darkPrimary,
                onPrimary = darkOnPrimary,
                primaryContainer = darkPrimaryContainer,
                onPrimaryContainer = darkOnPrimaryContainer,
                secondary = darkSecondary,
                onSecondary = darkOnSecondary,
                secondaryContainer = darkSecondaryContainer,
                onSecondaryContainer = darkOnSecondaryContainer,
                tertiary = darkTertiary,
                onTertiary = darkOnTertiary,
                tertiaryContainer = darkTertiaryContainer,
                onTertiaryContainer = darkOnTertiaryContainer,
                error = darkError,
                onError = darkOnError,
                errorContainer = darkErrorContainer,
                onErrorContainer = darkOnErrorContainer,
                background = darkBackground,
                onBackground = darkOnBackground,
                surface = darkSurface,
                onSurface = darkOnSurface,
                surfaceVariant = darkSurfaceVariant,
                onSurfaceVariant = darkOnSurfaceVariant,
                outline = darkOutline,
                outlineVariant = darkOutlineVariant,
            ),
        baseTheme = baseTheme,
        createdTimestamp = createdTimestamp,
        modifiedTimestamp = modifiedTimestamp,
    )
}

/**
 * Extension function to convert CustomThemeData to CustomThemeEntity.
 */
private fun CustomThemeData.toEntity(): CustomThemeEntity {
    return CustomThemeEntity(
        id = id,
        name = name,
        description = description,
        baseTheme = baseTheme,
        modifiedTimestamp = System.currentTimeMillis(),
        createdTimestamp = createdTimestamp,
        // Seed colors (original user selections)
        seedPrimary = seedPrimary,
        seedSecondary = seedSecondary,
        seedTertiary = seedTertiary,
        // Light mode colors
        lightPrimary = lightColors.primary,
        lightOnPrimary = lightColors.onPrimary,
        lightPrimaryContainer = lightColors.primaryContainer,
        lightOnPrimaryContainer = lightColors.onPrimaryContainer,
        lightSecondary = lightColors.secondary,
        lightOnSecondary = lightColors.onSecondary,
        lightSecondaryContainer = lightColors.secondaryContainer,
        lightOnSecondaryContainer = lightColors.onSecondaryContainer,
        lightTertiary = lightColors.tertiary,
        lightOnTertiary = lightColors.onTertiary,
        lightTertiaryContainer = lightColors.tertiaryContainer,
        lightOnTertiaryContainer = lightColors.onTertiaryContainer,
        lightError = lightColors.error,
        lightOnError = lightColors.onError,
        lightErrorContainer = lightColors.errorContainer,
        lightOnErrorContainer = lightColors.onErrorContainer,
        lightBackground = lightColors.background,
        lightOnBackground = lightColors.onBackground,
        lightSurface = lightColors.surface,
        lightOnSurface = lightColors.onSurface,
        lightSurfaceVariant = lightColors.surfaceVariant,
        lightOnSurfaceVariant = lightColors.onSurfaceVariant,
        lightOutline = lightColors.outline,
        lightOutlineVariant = lightColors.outlineVariant,
        // Dark mode colors
        darkPrimary = darkColors.primary,
        darkOnPrimary = darkColors.onPrimary,
        darkPrimaryContainer = darkColors.primaryContainer,
        darkOnPrimaryContainer = darkColors.onPrimaryContainer,
        darkSecondary = darkColors.secondary,
        darkOnSecondary = darkColors.onSecondary,
        darkSecondaryContainer = darkColors.secondaryContainer,
        darkOnSecondaryContainer = darkColors.onSecondaryContainer,
        darkTertiary = darkColors.tertiary,
        darkOnTertiary = darkColors.onTertiary,
        darkTertiaryContainer = darkColors.tertiaryContainer,
        darkOnTertiaryContainer = darkColors.onTertiaryContainer,
        darkError = darkColors.error,
        darkOnError = darkColors.onError,
        darkErrorContainer = darkColors.errorContainer,
        darkOnErrorContainer = darkColors.onErrorContainer,
        darkBackground = darkColors.background,
        darkOnBackground = darkColors.onBackground,
        darkSurface = darkColors.surface,
        darkOnSurface = darkColors.onSurface,
        darkSurfaceVariant = darkColors.surfaceVariant,
        darkOnSurfaceVariant = darkColors.onSurfaceVariant,
        darkOutline = darkColors.outline,
        darkOutlineVariant = darkColors.outlineVariant,
    )
}
