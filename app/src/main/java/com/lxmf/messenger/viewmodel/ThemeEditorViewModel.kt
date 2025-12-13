package com.lxmf.messenger.viewmodel

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.CustomThemeRepository
import com.lxmf.messenger.data.repository.ThemeColorSet
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.util.ThemeColorGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Convert a Compose ColorScheme to ThemeColorSet (ARGB ints).
 */
private fun ColorScheme.toThemeColorSet(): ThemeColorSet =
    ThemeColorSet(
        primary = primary.toArgb(),
        onPrimary = onPrimary.toArgb(),
        primaryContainer = primaryContainer.toArgb(),
        onPrimaryContainer = onPrimaryContainer.toArgb(),
        secondary = secondary.toArgb(),
        onSecondary = onSecondary.toArgb(),
        secondaryContainer = secondaryContainer.toArgb(),
        onSecondaryContainer = onSecondaryContainer.toArgb(),
        tertiary = tertiary.toArgb(),
        onTertiary = onTertiary.toArgb(),
        tertiaryContainer = tertiaryContainer.toArgb(),
        onTertiaryContainer = onTertiaryContainer.toArgb(),
        error = error.toArgb(),
        onError = onError.toArgb(),
        errorContainer = errorContainer.toArgb(),
        onErrorContainer = onErrorContainer.toArgb(),
        background = background.toArgb(),
        onBackground = onBackground.toArgb(),
        surface = surface.toArgb(),
        onSurface = onSurface.toArgb(),
        surfaceVariant = surfaceVariant.toArgb(),
        onSurfaceVariant = onSurfaceVariant.toArgb(),
        outline = outline.toArgb(),
        outlineVariant = outlineVariant.toArgb(),
    )

/**
 * Color role selection for theme editing
 */
enum class ColorRole {
    PRIMARY,
    SECONDARY,
    TERTIARY,
}

/**
 * State for the theme editor screen
 */
@androidx.compose.runtime.Immutable
data class ThemeEditorState(
    val themeId: Long? = null,
    val themeName: String = "",
    val themeDescription: String = "",
    // Default purple
    val primarySeedColor: Color = Color(0xFF6200EE),
    // Default teal
    val secondarySeedColor: Color = Color(0xFF03DAC6),
    // Default red
    val tertiarySeedColor: Color = Color(0xFFB00020),
    val selectedColorRole: ColorRole = ColorRole.PRIMARY,
    // Auto-generate secondary/tertiary from primary
    val useHarmonizedColors: Boolean = true,
    // Backup of custom colors before harmonization (for toggle restoration)
    val lastCustomSecondaryColor: Color? = null,
    val lastCustomTertiaryColor: Color? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for creating and editing custom themes.
 * Manages theme editor state and handles saving themes to the database.
 */
@HiltViewModel
class ThemeEditorViewModel
    @Inject
    constructor(
        private val customThemeRepository: CustomThemeRepository,
        private val settingsRepository: SettingsRepository,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        companion object {
            private const val TAG = "ThemeEditorViewModel"
            private const val THEME_ID_KEY = "themeId"
        }

        private val _state = MutableStateFlow(ThemeEditorState())
        val state: StateFlow<ThemeEditorState> = _state.asStateFlow()

        init {
            // Load theme if editing existing theme
            val themeId = savedStateHandle.get<Long>(THEME_ID_KEY)
            if (themeId != null) {
                loadTheme(themeId)
            }
        }

        /**
         * Load an existing theme for editing
         */
        private fun loadTheme(themeId: Long) {
            viewModelScope.launch {
                _state.value = _state.value.copy(isLoading = true)
                try {
                    val theme = customThemeRepository.getThemeById(themeId)
                    if (theme != null) {
                        // Extract original seed colors (not transformed palette colors)
                        val primaryColor = Color(theme.seedPrimary)
                        val secondaryColor = Color(theme.seedSecondary)
                        val tertiaryColor = Color(theme.seedTertiary)

                        _state.value =
                            _state.value.copy(
                                themeId = theme.id,
                                themeName = theme.name,
                                themeDescription = theme.description,
                                primarySeedColor = primaryColor,
                                secondarySeedColor = secondaryColor,
                                tertiarySeedColor = tertiaryColor,
                                // Initialize backups with loaded seed colors for toggle preservation
                                lastCustomSecondaryColor = secondaryColor,
                                lastCustomTertiaryColor = tertiaryColor,
                                // Existing themes use custom colors
                                useHarmonizedColors = false,
                                isLoading = false,
                            )
                    } else {
                        _state.value =
                            _state.value.copy(
                                isLoading = false,
                                error = "Theme not found",
                            )
                    }
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            error = "Failed to load theme: ${e.message}",
                        )
                }
            }
        }

        /**
         * Update the theme name
         */
        fun updateThemeName(name: String) {
            _state.value = _state.value.copy(themeName = name)
        }

        /**
         * Update the theme description
         */
        fun updateThemeDescription(description: String) {
            _state.value = _state.value.copy(themeDescription = description)
        }

        /**
         * Select which color role is being edited
         */
        fun selectColorRole(role: ColorRole) {
            _state.value = _state.value.copy(selectedColorRole = role)
        }

        /**
         * Update the currently selected color
         * In harmonized mode, updating primary regenerates secondary/tertiary
         * In custom mode, updates backup to preserve custom colors across toggle
         */
        fun updateSelectedColor(color: Color) {
            val currentState = _state.value

            if (currentState.useHarmonizedColors && currentState.selectedColorRole == ColorRole.PRIMARY) {
                // In harmonized mode, updating primary regenerates secondary/tertiary
                val (primary, secondary, tertiary) = ThemeColorGenerator.suggestComplementaryColors(color.toArgb())
                _state.value =
                    currentState.copy(
                        primarySeedColor = color,
                        secondarySeedColor = Color(secondary),
                        tertiarySeedColor = Color(tertiary),
                    )
            } else {
                // In custom mode, update the selected color AND its backup
                _state.value =
                    when (currentState.selectedColorRole) {
                        ColorRole.PRIMARY -> currentState.copy(primarySeedColor = color)
                        ColorRole.SECONDARY ->
                            currentState.copy(
                                secondarySeedColor = color,
                                // Update backup
                                lastCustomSecondaryColor = color,
                            )
                        ColorRole.TERTIARY ->
                            currentState.copy(
                                tertiarySeedColor = color,
                                // Update backup
                                lastCustomTertiaryColor = color,
                            )
                    }
            }
        }

        /**
         * Get the currently selected color
         */
        fun getSelectedColor(): Color {
            return when (_state.value.selectedColorRole) {
                ColorRole.PRIMARY -> _state.value.primarySeedColor
                ColorRole.SECONDARY -> _state.value.secondarySeedColor
                ColorRole.TERTIARY -> _state.value.tertiarySeedColor
            }
        }

        /**
         * Toggle between harmonized and custom color modes
         * Preserves custom colors when toggling by backing them up before harmonization
         */
        fun toggleHarmonizedMode() {
            val currentState = _state.value
            val newUseHarmonized = !currentState.useHarmonizedColors

            if (newUseHarmonized) {
                // Switching OFF → ON: Save current colors as backup, then harmonize
                val (primary, secondary, tertiary) = ThemeColorGenerator.suggestComplementaryColors(currentState.primarySeedColor.toArgb())
                _state.value =
                    currentState.copy(
                        useHarmonizedColors = true,
                        // Save current colors to backup before overwriting
                        lastCustomSecondaryColor = currentState.secondarySeedColor,
                        lastCustomTertiaryColor = currentState.tertiarySeedColor,
                        // Apply harmonized colors
                        secondarySeedColor = Color(secondary),
                        tertiarySeedColor = Color(tertiary),
                    )
            } else {
                // Switching ON → OFF: Restore backed-up custom colors if available
                _state.value =
                    currentState.copy(
                        useHarmonizedColors = false,
                        // Restore from backup, or keep current if no backup exists
                        secondarySeedColor = currentState.lastCustomSecondaryColor ?: currentState.secondarySeedColor,
                        tertiarySeedColor = currentState.lastCustomTertiaryColor ?: currentState.tertiarySeedColor,
                    )
            }
        }

        /**
         * Save the theme to the database
         */
        fun saveTheme() {
            viewModelScope.launch {
                if (_state.value.themeName.isBlank()) {
                    _state.value = _state.value.copy(error = "Theme name cannot be empty")
                    return@launch
                }

                _state.value = _state.value.copy(isSaving = true)
                try {
                    val primaryArgb = _state.value.primarySeedColor.toArgb()
                    val secondaryArgb = _state.value.secondarySeedColor.toArgb()
                    val tertiaryArgb = _state.value.tertiarySeedColor.toArgb()

                    // Generate light and dark color schemes from three seed colors
                    val lightScheme =
                        ThemeColorGenerator.generateColorScheme(
                            primarySeed = primaryArgb,
                            secondarySeed = secondaryArgb,
                            tertiarySeed = tertiaryArgb,
                            isDark = false,
                        )
                    val darkScheme =
                        ThemeColorGenerator.generateColorScheme(
                            primarySeed = primaryArgb,
                            secondarySeed = secondaryArgb,
                            tertiarySeed = tertiaryArgb,
                            isDark = true,
                        )

                    // Convert ColorScheme to ThemeColorSet (ARGB ints)
                    val lightColors = lightScheme.toThemeColorSet()
                    val darkColors = darkScheme.toThemeColorSet()

                    // Save or update theme
                    val existingThemeId = _state.value.themeId
                    if (existingThemeId != null) {
                        customThemeRepository.updateTheme(
                            id = existingThemeId,
                            name = _state.value.themeName,
                            description = _state.value.themeDescription,
                            seedPrimary = primaryArgb,
                            seedSecondary = secondaryArgb,
                            seedTertiary = tertiaryArgb,
                            lightColors = lightColors,
                            darkColors = darkColors,
                            // Not derived from a preset theme
                            baseTheme = null,
                        )
                    } else {
                        customThemeRepository.saveTheme(
                            name = _state.value.themeName,
                            description = _state.value.themeDescription,
                            seedPrimary = primaryArgb,
                            seedSecondary = secondaryArgb,
                            seedTertiary = tertiaryArgb,
                            lightColors = lightColors,
                            darkColors = darkColors,
                            baseTheme = null,
                        )
                    }

                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            error = null,
                        )
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            error = "Failed to save theme: ${e.message}",
                        )
                }
            }
        }

        /**
         * Save the theme and immediately apply it as the active theme
         */
        fun saveAndApplyTheme() {
            viewModelScope.launch {
                if (_state.value.themeName.isBlank()) {
                    _state.value = _state.value.copy(error = "Theme name cannot be empty")
                    return@launch
                }

                _state.value = _state.value.copy(isSaving = true)
                try {
                    val primaryArgb = _state.value.primarySeedColor.toArgb()
                    val secondaryArgb = _state.value.secondarySeedColor.toArgb()
                    val tertiaryArgb = _state.value.tertiarySeedColor.toArgb()

                    // Generate light and dark color schemes
                    val lightScheme =
                        ThemeColorGenerator.generateColorScheme(
                            primarySeed = primaryArgb,
                            secondarySeed = secondaryArgb,
                            tertiarySeed = tertiaryArgb,
                            isDark = false,
                        )
                    val darkScheme =
                        ThemeColorGenerator.generateColorScheme(
                            primarySeed = primaryArgb,
                            secondarySeed = secondaryArgb,
                            tertiarySeed = tertiaryArgb,
                            isDark = true,
                        )

                    // Convert to ThemeColorSet
                    val lightColors = lightScheme.toThemeColorSet()
                    val darkColors = darkScheme.toThemeColorSet()

                    // Save the theme and get its ID
                    val currentThemeId = _state.value.themeId
                    val savedThemeId =
                        if (currentThemeId != null) {
                            customThemeRepository.updateTheme(
                                id = currentThemeId,
                                name = _state.value.themeName,
                                description = _state.value.themeDescription,
                                seedPrimary = primaryArgb,
                                seedSecondary = secondaryArgb,
                                seedTertiary = tertiaryArgb,
                                lightColors = lightColors,
                                darkColors = darkColors,
                                baseTheme = null,
                            )
                            currentThemeId
                        } else {
                            customThemeRepository.saveTheme(
                                name = _state.value.themeName,
                                description = _state.value.themeDescription,
                                seedPrimary = primaryArgb,
                                seedSecondary = secondaryArgb,
                                seedTertiary = tertiaryArgb,
                                lightColors = lightColors,
                                darkColors = darkColors,
                                baseTheme = null,
                            )
                        }

                    // Load the saved theme and apply it
                    val themeData = customThemeRepository.getThemeById(savedThemeId)
                    if (themeData != null) {
                        val appTheme = settingsRepository.customThemeDataToAppTheme(themeData)
                        settingsRepository.saveThemePreference(appTheme)
                    }

                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            error = null,
                        )
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            error = "Failed to save and apply theme: ${e.message}",
                        )
                }
            }
        }

        /**
         * Clear any error message
         */
        fun clearError() {
            _state.value = _state.value.copy(error = null)
        }
    }
