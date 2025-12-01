package com.lxmf.messenger.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for ThemeColorGenerator three-seed color scheme generation.
 * Tests the new overload that accepts three separate seed colors.
 *
 * Requires instrumented test (AndroidJUnit4) because ThemeColorGenerator uses
 * Android ColorUtils which is not available in unit tests.
 */
@RunWith(AndroidJUnit4::class)
class ThemeColorGeneratorThreeSeedTest {
    // Test seed colors (distinct hues for clear differentiation)
    private val redSeed = Color.Red.toArgb()
    private val greenSeed = Color.Green.toArgb()
    private val blueSeed = Color.Blue.toArgb()

    @Test
    fun generateColorScheme_withThreeSeeds_createsValidLightScheme() {
        // When
        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )

        // Then - verify all required color roles are present and not null
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.onPrimary)
        assertNotNull(colorScheme.primaryContainer)
        assertNotNull(colorScheme.onPrimaryContainer)
        assertNotNull(colorScheme.secondary)
        assertNotNull(colorScheme.onSecondary)
        assertNotNull(colorScheme.secondaryContainer)
        assertNotNull(colorScheme.onSecondaryContainer)
        assertNotNull(colorScheme.tertiary)
        assertNotNull(colorScheme.onTertiary)
        assertNotNull(colorScheme.tertiaryContainer)
        assertNotNull(colorScheme.onTertiaryContainer)
        assertNotNull(colorScheme.error)
        assertNotNull(colorScheme.onError)
        assertNotNull(colorScheme.background)
        assertNotNull(colorScheme.onBackground)
        assertNotNull(colorScheme.surface)
        assertNotNull(colorScheme.onSurface)
    }

    @Test
    fun generateColorScheme_withThreeSeeds_createsValidDarkScheme() {
        // When
        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = true,
            )

        // Then - verify all required color roles are present
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.secondary)
        assertNotNull(colorScheme.tertiary)
        assertNotNull(colorScheme.background)
        assertNotNull(colorScheme.surface)
    }

    @Test
    fun generateColorScheme_withThreeSeeds_usesDifferentSeedsForEachPalette() {
        // When
        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )

        // Then - primary, secondary, tertiary should be visually different
        // Extract the actual palette colors
        val primary = colorScheme.primary.toArgb()
        val secondary = colorScheme.secondary.toArgb()
        val tertiary = colorScheme.tertiary.toArgb()

        // Verify they're all different (not identical colors)
        assertNotEquals(primary, secondary)
        assertNotEquals(primary, tertiary)
        assertNotEquals(secondary, tertiary)
    }

    @Test
    fun generateColorScheme_withThreeIdenticalSeeds_stillProducesValidScheme() {
        // Given - edge case: all three seeds are the same
        val purpleSeed = 0xFF6200EE.toInt()

        // When
        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = purpleSeed,
                secondarySeed = purpleSeed,
                tertiarySeed = purpleSeed,
                isDark = false,
            )

        // Then - should not crash and should return valid ColorScheme
        assertNotNull(colorScheme)
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.secondary)
        assertNotNull(colorScheme.tertiary)
    }

    @Test
    fun generateColorScheme_threeSeed_adjustsLightnessForDarkMode() {
        // When
        val lightScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )
        val darkScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = true,
            )

        // Then - dark mode colors should be lighter than light mode colors
        // Extract lightness from colors (approximation via comparing RGB)
        val lightPrimary = lightScheme.primary.toArgb()
        val darkPrimary = darkScheme.primary.toArgb()

        // Dark mode should produce lighter colors (higher RGB values on average)
        // This is a simplified check - actual HSL lightness would be more precise
        assertNotEquals(lightPrimary, darkPrimary)
    }

    @Test
    fun generateColorScheme_threeSeed_createsProperContainers() {
        // When
        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )

        // Then - containers should exist and be different from main colors
        val primary = colorScheme.primary.toArgb()
        val primaryContainer = colorScheme.primaryContainer.toArgb()
        val secondary = colorScheme.secondary.toArgb()
        val secondaryContainer = colorScheme.secondaryContainer.toArgb()
        val tertiary = colorScheme.tertiary.toArgb()
        val tertiaryContainer = colorScheme.tertiaryContainer.toArgb()

        // Containers should be lighter/darker versions (different from main colors)
        assertNotEquals(primary, primaryContainer)
        assertNotEquals(secondary, secondaryContainer)
        assertNotEquals(tertiary, tertiaryContainer)
    }
}
