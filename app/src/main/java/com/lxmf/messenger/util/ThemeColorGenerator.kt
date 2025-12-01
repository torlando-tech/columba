package com.lxmf.messenger.util

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Utility for generating Material 3 color schemes from seed colors.
 * Uses HSL color manipulation for harmonization and palette generation.
 */
object ThemeColorGenerator {
    /**
     * Generate a complete Material 3 color scheme from a single seed color.
     * Creates harmonized color palettes for both light and dark modes using HSL color manipulation.
     *
     * @param seedColor The base color to generate the theme from (in ARGB format)
     * @param isDark Whether to generate a dark or light theme
     * @return Complete Material 3 ColorScheme
     */
    fun generateColorScheme(
        seedColor: Int,
        isDark: Boolean,
    ): ColorScheme {
        // Generate complementary colors using hue rotation
        val primary = if (isDark) lightenColor(seedColor, 0.2f) else darkenColor(seedColor, 0.1f)
        val secondary = generateHarmonizedColor(seedColor, 60f, isDark) // 60° hue shift
        val tertiary = generateHarmonizedColor(seedColor, 120f, isDark) // 120° hue shift

        val error = if (isDark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()

        return if (isDark) {
            darkColorScheme(
                primary = Color(primary),
                onPrimary = Color(getContrastingColor(primary, true)),
                primaryContainer = Color(darkenColor(primary, 0.3f)),
                onPrimaryContainer = Color(lightenColor(primary, 0.4f)),
                secondary = Color(secondary),
                onSecondary = Color(getContrastingColor(secondary, true)),
                secondaryContainer = Color(darkenColor(secondary, 0.3f)),
                onSecondaryContainer = Color(lightenColor(secondary, 0.4f)),
                tertiary = Color(tertiary),
                onTertiary = Color(getContrastingColor(tertiary, true)),
                tertiaryContainer = Color(darkenColor(tertiary, 0.3f)),
                onTertiaryContainer = Color(lightenColor(tertiary, 0.4f)),
                error = Color(error),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
                background = Color(0xFF1A1C1E),
                onBackground = Color(0xFFE2E2E6),
                surface = Color(0xFF1A1C1E),
                onSurface = Color(0xFFE2E2E6),
                surfaceVariant = Color(0xFF43474E),
                onSurfaceVariant = Color(0xFFC3C7CF),
                outline = Color(0xFF8D9199),
                outlineVariant = Color(0xFF43474E),
            )
        } else {
            lightColorScheme(
                primary = Color(primary),
                onPrimary = Color(Color.White.toArgb()),
                primaryContainer = Color(lightenColor(primary, 0.3f)),
                onPrimaryContainer = Color(darkenColor(primary, 0.4f)),
                secondary = Color(secondary),
                onSecondary = Color(Color.White.toArgb()),
                secondaryContainer = Color(lightenColor(secondary, 0.3f)),
                onSecondaryContainer = Color(darkenColor(secondary, 0.4f)),
                tertiary = Color(tertiary),
                onTertiary = Color(Color.White.toArgb()),
                tertiaryContainer = Color(lightenColor(tertiary, 0.3f)),
                onTertiaryContainer = Color(darkenColor(tertiary, 0.4f)),
                error = Color(error),
                onError = Color(Color.White.toArgb()),
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
                background = Color(0xFFFEFBFF),
                onBackground = Color(0xFF1A1C1E),
                surface = Color(0xFFFEFBFF),
                onSurface = Color(0xFF1A1C1E),
                surfaceVariant = Color(0xFFE1E2EC),
                onSurfaceVariant = Color(0xFF43474E),
                outline = Color(0xFF74777F),
                outlineVariant = Color(0xFFC3C7CF),
            )
        }
    }

    /**
     * Generate a complete Material 3 color scheme from three separate seed colors.
     * Allows full customization of primary, secondary, and tertiary color roles.
     *
     * @param primarySeed Base color for primary palette (in ARGB format)
     * @param secondarySeed Base color for secondary palette (in ARGB format)
     * @param tertiarySeed Base color for tertiary palette (in ARGB format)
     * @param isDark Whether to generate a dark or light theme
     * @return Complete Material 3 ColorScheme
     */
    fun generateColorScheme(
        primarySeed: Int,
        secondarySeed: Int,
        tertiarySeed: Int,
        isDark: Boolean,
    ): ColorScheme {
        // Apply light/dark adjustments to each seed color
        val primary = if (isDark) lightenColor(primarySeed, 0.2f) else darkenColor(primarySeed, 0.1f)
        val secondary = if (isDark) lightenColor(secondarySeed, 0.2f) else darkenColor(secondarySeed, 0.1f)
        val tertiary = if (isDark) lightenColor(tertiarySeed, 0.2f) else darkenColor(tertiarySeed, 0.1f)

        val error = if (isDark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()

        return if (isDark) {
            darkColorScheme(
                primary = Color(primary),
                onPrimary = Color(getContrastingColor(primary, true)),
                primaryContainer = Color(darkenColor(primary, 0.3f)),
                onPrimaryContainer = Color(lightenColor(primary, 0.4f)),
                secondary = Color(secondary),
                onSecondary = Color(getContrastingColor(secondary, true)),
                secondaryContainer = Color(darkenColor(secondary, 0.3f)),
                onSecondaryContainer = Color(lightenColor(secondary, 0.4f)),
                tertiary = Color(tertiary),
                onTertiary = Color(getContrastingColor(tertiary, true)),
                tertiaryContainer = Color(darkenColor(tertiary, 0.3f)),
                onTertiaryContainer = Color(lightenColor(tertiary, 0.4f)),
                error = Color(error),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
                background = Color(0xFF1A1C1E),
                onBackground = Color(0xFFE2E2E6),
                surface = Color(0xFF1A1C1E),
                onSurface = Color(0xFFE2E2E6),
                surfaceVariant = Color(0xFF43474E),
                onSurfaceVariant = Color(0xFFC3C7CF),
                outline = Color(0xFF8D9199),
                outlineVariant = Color(0xFF43474E),
            )
        } else {
            lightColorScheme(
                primary = Color(primary),
                onPrimary = Color(Color.White.toArgb()),
                primaryContainer = Color(lightenColor(primary, 0.3f)),
                onPrimaryContainer = Color(darkenColor(primary, 0.4f)),
                secondary = Color(secondary),
                onSecondary = Color(Color.White.toArgb()),
                secondaryContainer = Color(lightenColor(secondary, 0.3f)),
                onSecondaryContainer = Color(darkenColor(secondary, 0.4f)),
                tertiary = Color(tertiary),
                onTertiary = Color(Color.White.toArgb()),
                tertiaryContainer = Color(lightenColor(tertiary, 0.3f)),
                onTertiaryContainer = Color(darkenColor(tertiary, 0.4f)),
                error = Color(error),
                onError = Color(Color.White.toArgb()),
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
                background = Color(0xFFFEFBFF),
                onBackground = Color(0xFF1A1C1E),
                surface = Color(0xFFFEFBFF),
                onSurface = Color(0xFF1A1C1E),
                surfaceVariant = Color(0xFFE1E2EC),
                onSurfaceVariant = Color(0xFF43474E),
                outline = Color(0xFF74777F),
                outlineVariant = Color(0xFFC3C7CF),
            )
        }
    }

    /**
     * Generate both light and dark color schemes from a seed color.
     *
     * @param seedColor The base color to generate the theme from (Compose Color)
     * @return Pair of (lightColorScheme, darkColorScheme)
     */
    fun generateColorSchemes(seedColor: Color): Pair<ColorScheme, ColorScheme> {
        val argb = seedColor.toArgb()
        return Pair(
            generateColorScheme(argb, isDark = false),
            generateColorScheme(argb, isDark = true),
        )
    }

    /**
     * Generate a harmonized color by rotating the hue.
     *
     * @param baseColor Base color (ARGB)
     * @param hueDegrees Degrees to rotate hue (0-360)
     * @param isDark Whether this is for dark mode
     * @return Harmonized color (ARGB)
     */
    private fun generateHarmonizedColor(
        baseColor: Int,
        hueDegrees: Float,
        isDark: Boolean,
    ): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(baseColor, hsl)

        // Rotate hue
        hsl[0] = (hsl[0] + hueDegrees) % 360f

        // Adjust saturation and lightness for harmonization
        hsl[1] = (hsl[1] * 0.9f).coerceIn(0f, 1f)
        hsl[2] =
            if (isDark) {
                (hsl[2] + 0.1f).coerceIn(0.4f, 0.8f)
            } else {
                (hsl[2] - 0.05f).coerceIn(0.3f, 0.7f)
            }

        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Lighten a color by adjusting its lightness in HSL space.
     *
     * @param color Color to lighten (ARGB)
     * @param amount Amount to lighten (0.0 to 1.0)
     * @return Lightened color (ARGB)
     */
    fun lightenColor(
        color: Int,
        amount: Float,
    ): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Darken a color by adjusting its lightness in HSL space.
     *
     * @param color Color to darken (ARGB)
     * @param amount Amount to darken (0.0 to 1.0)
     * @return Darkened color (ARGB)
     */
    fun darkenColor(
        color: Int,
        amount: Float,
    ): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = (hsl[2] - amount).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Get a contrasting color (black or white) for text on the given background.
     *
     * @param backgroundColor Background color (ARGB)
     * @param forceDark Force dark text (for light backgrounds)
     * @return Black or white color with good contrast
     */
    fun getContrastingColor(
        backgroundColor: Int,
        forceDark: Boolean = false,
    ): Int {
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        return if (forceDark) {
            0xFF000000.toInt()
        } else {
            if (luminance > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    /**
     * Check if a color provides sufficient contrast for accessibility.
     *
     * @param foreground Foreground color (ARGB)
     * @param background Background color (ARGB)
     * @param minContrast Minimum required contrast ratio (default 4.5)
     * @return True if contrast is sufficient
     */
    fun hasSufficientContrast(
        foreground: Int,
        background: Int,
        minContrast: Double = 4.5,
    ): Boolean {
        val contrastRatio = ColorUtils.calculateContrast(foreground, background)
        return contrastRatio >= minContrast
    }

    /**
     * Calculate contrast ratio between two colors.
     */
    fun calculateContrastRatio(
        color1: Int,
        color2: Int,
    ): Double {
        return ColorUtils.calculateContrast(color1, color2)
    }

    /**
     * Suggest complementary colors for a seed color.
     * Returns colors that work well together in a theme.
     *
     * @param seedColor The base color (ARGB)
     * @return Triple of (primary, secondary, tertiary) harmonized colors
     */
    fun suggestComplementaryColors(seedColor: Int): Triple<Int, Int, Int> {
        val secondary = generateHarmonizedColor(seedColor, 60f, false)
        val tertiary = generateHarmonizedColor(seedColor, 120f, false)
        return Triple(seedColor, secondary, tertiary)
    }

    /**
     * Convert a hex color string to ARGB Int.
     * Supports formats: "#RGB", "#RRGGBB", "#AARRGGBB"
     *
     * @param hex Hex color string (with or without #)
     * @return ARGB color as Int, or null if invalid
     */
    fun hexToArgb(hex: String): Int? {
        return try {
            val cleanHex = hex.removePrefix("#")
            when (cleanHex.length) {
                3 -> {
                    // #RGB -> #RRGGBB
                    val r = cleanHex[0].toString().repeat(2)
                    val g = cleanHex[1].toString().repeat(2)
                    val b = cleanHex[2].toString().repeat(2)
                    "FF$r$g$b".toLong(16).toInt()
                }
                6 -> {
                    // #RRGGBB -> #AARRGGBB
                    "FF$cleanHex".toLong(16).toInt()
                }
                8 -> {
                    // #AARRGGBB
                    cleanHex.toLong(16).toInt()
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert ARGB Int to hex string.
     *
     * @param argb ARGB color as Int
     * @param includeAlpha Whether to include alpha channel in output
     * @return Hex color string (e.g., "#RRGGBB" or "#AARRGGBB")
     */
    fun argbToHex(
        argb: Int,
        includeAlpha: Boolean = false,
    ): String {
        return if (includeAlpha) {
            String.format("#%08X", argb)
        } else {
            String.format("#%06X", argb and 0xFFFFFF)
        }
    }

    /**
     * Generate a tonal palette from a seed color.
     * Returns shades from lightest to darkest.
     *
     * @param seedColor The base color (ARGB)
     * @return List of color tones
     */
    fun generateTonalPalette(seedColor: Int): List<Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seedColor, hsl)

        return listOf(0.95f, 0.90f, 0.80f, 0.70f, 0.60f, 0.50f, 0.40f, 0.30f, 0.20f, 0.10f).map { lightness ->
            hsl[2] = lightness
            ColorUtils.HSLToColor(hsl)
        }
    }
}
