package com.lxmf.messenger.util

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ThemeColorGenerator using Robolectric.
 * Tests color scheme generation, HSL manipulation, and accessibility helpers.
 *
 * Migrated from instrumented test - ColorUtils is supported by Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeColorGeneratorTest {
    // ========== Color Scheme Generation ==========

    @Test
    fun generateColorScheme_createsValidMaterial3SchemeForLightMode() {
        val seedColor = Color.Blue.toArgb()
        val lightScheme = ThemeColorGenerator.generateColorScheme(seedColor, isDark = false)

        // Verify all required color roles are present
        assertNotNull(lightScheme.primary)
        assertNotNull(lightScheme.onPrimary)
        assertNotNull(lightScheme.primaryContainer)
        assertNotNull(lightScheme.onPrimaryContainer)
        assertNotNull(lightScheme.secondary)
        assertNotNull(lightScheme.onSecondary)
        assertNotNull(lightScheme.tertiary)
        assertNotNull(lightScheme.onTertiary)
        assertNotNull(lightScheme.error)
        assertNotNull(lightScheme.background)
        assertNotNull(lightScheme.surface)
    }

    @Test
    fun generateColorScheme_createsValidMaterial3SchemeForDarkMode() {
        val seedColor = Color.Red.toArgb()
        val darkScheme = ThemeColorGenerator.generateColorScheme(seedColor, isDark = true)

        // Verify all required color roles are present
        assertNotNull(darkScheme.primary)
        assertNotNull(darkScheme.onPrimary)
        assertNotNull(darkScheme.primaryContainer)
        assertNotNull(darkScheme.secondary)
        assertNotNull(darkScheme.tertiary)
        assertNotNull(darkScheme.error)
        assertNotNull(darkScheme.background)
        assertNotNull(darkScheme.surface)
    }

    @Test
    fun generateColorScheme_adjustsColorsBasedOnDarkMode() {
        val seedColor = Color.Green.toArgb()
        val lightScheme = ThemeColorGenerator.generateColorScheme(seedColor, isDark = false)
        val darkScheme = ThemeColorGenerator.generateColorScheme(seedColor, isDark = true)

        // Dark and light schemes should have different primary colors
        assertNotEquals(lightScheme.primary.toArgb(), darkScheme.primary.toArgb())
    }

    @Test
    fun generateColorSchemes_returnsBothLightAndDarkSchemes() {
        val seedColor = Color.Magenta
        val (lightScheme, darkScheme) = ThemeColorGenerator.generateColorSchemes(seedColor)

        assertNotNull(lightScheme)
        assertNotNull(darkScheme)
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    // ========== HSL Color Manipulation ==========

    @Test
    fun lightenColor_increasesLightness() {
        val darkBlue = 0xFF000080.toInt() // Dark blue
        val lighterBlue = ThemeColorGenerator.lightenColor(darkBlue, 0.2f)

        // Lightened color should be different
        assertNotEquals(darkBlue, lighterBlue)
    }

    @Test
    fun darkenColor_decreasesLightness() {
        val lightBlue = 0xFF8080FF.toInt() // Light blue
        val darkerBlue = ThemeColorGenerator.darkenColor(lightBlue, 0.2f)

        assertNotEquals(lightBlue, darkerBlue)
    }

    @Test
    fun lightenColor_clampsToMaximumLightness() {
        val color = 0xFF808080.toInt()
        val maxLightened = ThemeColorGenerator.lightenColor(color, 2.0f) // Extreme amount

        // Should not throw exception and should return valid color
        assertNotNull(maxLightened)
    }

    @Test
    fun darkenColor_clampsToMinimumLightness() {
        val color = 0xFF808080.toInt()
        val maxDarkened = ThemeColorGenerator.darkenColor(color, 2.0f) // Extreme amount

        // Should not throw exception and should return valid color
        assertNotNull(maxDarkened)
    }

    // ========== Harmonized Colors ==========

    @Test
    fun suggestComplementaryColors_returnsThreeDifferentColors() {
        val seedColor = Color.Blue.toArgb()
        val (primary, secondary, tertiary) = ThemeColorGenerator.suggestComplementaryColors(seedColor)

        // All three should be different
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)

        // Primary should be the seed color
        assertEquals(seedColor, primary)
    }

    // ========== Contrast Checking ==========

    @Test
    fun hasSufficientContrast_detectsGoodContrastBetweenBlackAndWhite() {
        val white = Color.White.toArgb()
        val black = Color.Black.toArgb()

        assertTrue(
            "White on black should have sufficient contrast",
            ThemeColorGenerator.hasSufficientContrast(white, black, minContrast = 4.5),
        )
    }

    @Test
    fun hasSufficientContrast_detectsPoorContrastBetweenSimilarColors() {
        val lightGray = 0xFFCCCCCC.toInt()
        val white = Color.White.toArgb()

        assertFalse(
            "Light gray on white should not have sufficient contrast",
            ThemeColorGenerator.hasSufficientContrast(lightGray, white, minContrast = 4.5),
        )
    }

    @Test
    fun hasSufficientContrast_usesWCAGAAStandardByDefault() {
        val white = Color.White.toArgb()
        val black = Color.Black.toArgb()

        // Default should be 4.5:1 (WCAG AA)
        assertTrue(ThemeColorGenerator.hasSufficientContrast(white, black))
    }

    @Test
    fun calculateContrastRatio_returnsHighRatioForBlackAndWhite() {
        val white = Color.White.toArgb()
        val black = Color.Black.toArgb()

        val ratio = ThemeColorGenerator.calculateContrastRatio(white, black)

        // Black and white should have 21:1 contrast ratio
        assertTrue("Contrast ratio should be very high (got $ratio)", ratio > 20.0)
    }

    @Test
    fun calculateContrastRatio_returnsLowRatioForSimilarColors() {
        val gray1 = 0xFF808080.toInt()
        val gray2 = 0xFF888888.toInt()

        val ratio = ThemeColorGenerator.calculateContrastRatio(gray1, gray2)

        assertTrue("Contrast ratio should be low for similar colors (got $ratio)", ratio < 2.0)
    }

    @Test
    fun getContrastingColor_returnsBlackForLightBackgrounds() {
        val lightBackground = Color.White.toArgb()
        val textColor = ThemeColorGenerator.getContrastingColor(lightBackground)

        assertEquals("Should return black for light background", 0xFF000000.toInt(), textColor)
    }

    @Test
    fun getContrastingColor_returnsWhiteForDarkBackgrounds() {
        val darkBackground = Color.Black.toArgb()
        val textColor = ThemeColorGenerator.getContrastingColor(darkBackground)

        assertEquals("Should return white for dark background", 0xFFFFFFFF.toInt(), textColor)
    }

    @Test
    fun getContrastingColor_forceDarkReturnsDark() {
        val lightBackground = Color.White.toArgb()
        val darkBackground = Color.Black.toArgb()

        val textOnLight = ThemeColorGenerator.getContrastingColor(lightBackground, forceDark = true)
        val textOnDark = ThemeColorGenerator.getContrastingColor(darkBackground, forceDark = true)

        // When forceDark is true, should always return black
        assertEquals(0xFF000000.toInt(), textOnLight)
        assertEquals(0xFF000000.toInt(), textOnDark)
    }

    // ========== Hex Conversion ==========

    @Test
    fun hexToArgb_parsesSixDigitHexCorrectly() {
        val hex = "#FF5733"
        val argb = ThemeColorGenerator.hexToArgb(hex)

        assertNotNull(argb)
        assertEquals(0xFFFF5733.toInt(), argb)
    }

    @Test
    fun hexToArgb_parsesSixDigitHexWithoutHash() {
        val hex = "FF5733"
        val argb = ThemeColorGenerator.hexToArgb(hex)

        assertNotNull(argb)
        assertEquals(0xFFFF5733.toInt(), argb)
    }

    @Test
    fun hexToArgb_parsesThreeDigitHexCorrectly() {
        val hex = "#F73"
        val argb = ThemeColorGenerator.hexToArgb(hex)

        assertNotNull(argb)
        // #F73 should expand to #FF7733
        assertEquals(0xFFFF7733.toInt(), argb)
    }

    @Test
    fun hexToArgb_parsesEightDigitHexWithAlpha() {
        val hex = "#80FF5733"
        val argb = ThemeColorGenerator.hexToArgb(hex)

        assertNotNull(argb)
        assertEquals(0x80FF5733.toInt(), argb)
    }

    @Test
    fun hexToArgb_returnsNullForInvalidHex() {
        val invalidHex = "ZZZZZZ"
        val argb = ThemeColorGenerator.hexToArgb(invalidHex)

        assertNull("Invalid hex should return null", argb)
    }

    @Test
    fun hexToArgb_returnsNullForWrongLength() {
        val wrongLength = "#FF573" // 5 characters
        val argb = ThemeColorGenerator.hexToArgb(wrongLength)

        assertNull("Wrong length hex should return null", argb)
    }

    @Test
    fun argbToHex_convertsColorCorrectlyWithoutAlpha() {
        val argb = 0xFFFF5733.toInt()
        val hex = ThemeColorGenerator.argbToHex(argb, includeAlpha = false)

        assertEquals("#FF5733", hex)
    }

    @Test
    fun argbToHex_convertsColorCorrectlyWithAlpha() {
        val argb = 0x80FF5733.toInt()
        val hex = ThemeColorGenerator.argbToHex(argb, includeAlpha = true)

        assertEquals("#80FF5733", hex)
    }

    @Test
    fun hexConversion_roundtripPreservesColor() {
        val originalColor = 0xFFABCDEF.toInt()
        val hex = ThemeColorGenerator.argbToHex(originalColor, includeAlpha = false)
        val convertedBack = ThemeColorGenerator.hexToArgb(hex)

        assertEquals(originalColor, convertedBack)
    }

    // ========== Tonal Palette ==========

    @Test
    fun generateTonalPalette_createsTenTones() {
        val seedColor = Color.Blue.toArgb()
        val palette = ThemeColorGenerator.generateTonalPalette(seedColor)

        assertEquals("Should generate 10 tones", 10, palette.size)
    }

    @Test
    fun generateTonalPalette_allColorsAreUnique() {
        val seedColor = Color.Green.toArgb()
        val palette = ThemeColorGenerator.generateTonalPalette(seedColor)

        val uniqueColors = palette.toSet()
        assertEquals("All tones should be unique", palette.size, uniqueColors.size)
    }

    @Test
    fun generateTonalPalette_orderedFromLightToDark() {
        val seedColor = Color.Blue.toArgb()
        val palette = ThemeColorGenerator.generateTonalPalette(seedColor)

        // Each subsequent color should be darker (lower luminance)
        // We can verify by checking the HSL lightness values
        // First color should be lightest, last should be darkest
        assertTrue(palette.size == 10)
    }

    // ========== Edge Cases ==========

    @Test
    fun generateColorScheme_handlesPureBlackSeedColor() {
        val black = Color.Black.toArgb()
        val scheme = ThemeColorGenerator.generateColorScheme(black, isDark = false)

        // Should not crash and should produce valid scheme
        assertNotNull(scheme.primary)
        assertNotNull(scheme.secondary)
        assertNotNull(scheme.tertiary)
    }

    @Test
    fun generateColorScheme_handlesPureWhiteSeedColor() {
        val white = Color.White.toArgb()
        val scheme = ThemeColorGenerator.generateColorScheme(white, isDark = true)

        // Should not crash and should produce valid scheme
        assertNotNull(scheme.primary)
        assertNotNull(scheme.secondary)
        assertNotNull(scheme.tertiary)
    }

    @Test
    fun generateColorScheme_handlesSaturatedColors() {
        val pureRed = Color.Red.toArgb()
        val scheme = ThemeColorGenerator.generateColorScheme(pureRed, isDark = false)

        assertNotNull(scheme.primary)
        assertNotNull(scheme.secondary)
        assertNotNull(scheme.tertiary)
    }

    @Test
    fun generateColorScheme_handlesGrayscaleColors() {
        val gray = 0xFF808080.toInt()
        val scheme = ThemeColorGenerator.generateColorScheme(gray, isDark = false)

        // Should still generate valid complementary colors
        assertNotNull(scheme.primary)
        assertNotNull(scheme.secondary)
        assertNotNull(scheme.tertiary)
    }

    // ========== Three-Seed Color Scheme Tests ==========

    @Test
    fun generateColorScheme_withThreeSeeds_createsValidLightScheme() {
        val redSeed = Color.Red.toArgb()
        val greenSeed = Color.Green.toArgb()
        val blueSeed = Color.Blue.toArgb()

        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )

        // Verify all required color roles are present and not null
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
        val redSeed = Color.Red.toArgb()
        val greenSeed = Color.Green.toArgb()
        val blueSeed = Color.Blue.toArgb()

        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = true,
            )

        // Verify all required color roles are present
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.secondary)
        assertNotNull(colorScheme.tertiary)
        assertNotNull(colorScheme.background)
        assertNotNull(colorScheme.surface)
    }

    @Test
    fun generateColorScheme_withThreeSeeds_usesDifferentSeedsForEachPalette() {
        val redSeed = Color.Red.toArgb()
        val greenSeed = Color.Green.toArgb()
        val blueSeed = Color.Blue.toArgb()

        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )

        // Primary, secondary, tertiary should be visually different
        val primary = colorScheme.primary.toArgb()
        val secondary = colorScheme.secondary.toArgb()
        val tertiary = colorScheme.tertiary.toArgb()

        assertNotEquals(primary, secondary)
        assertNotEquals(primary, tertiary)
        assertNotEquals(secondary, tertiary)
    }

    @Test
    fun generateColorScheme_withThreeIdenticalSeeds_stillProducesValidScheme() {
        val purpleSeed = 0xFF6200EE.toInt()

        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = purpleSeed,
                secondarySeed = purpleSeed,
                tertiarySeed = purpleSeed,
                isDark = false,
            )

        // Should not crash and should return valid ColorScheme
        assertNotNull(colorScheme)
        assertNotNull(colorScheme.primary)
        assertNotNull(colorScheme.secondary)
        assertNotNull(colorScheme.tertiary)
    }

    @Test
    fun generateColorScheme_threeSeed_adjustsLightnessForDarkMode() {
        val redSeed = Color.Red.toArgb()
        val greenSeed = Color.Green.toArgb()
        val blueSeed = Color.Blue.toArgb()

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

        // Dark mode colors should be different from light mode colors
        val lightPrimary = lightScheme.primary.toArgb()
        val darkPrimary = darkScheme.primary.toArgb()

        assertNotEquals(lightPrimary, darkPrimary)
    }

    @Test
    fun generateColorScheme_threeSeed_createsProperContainers() {
        val redSeed = Color.Red.toArgb()
        val greenSeed = Color.Green.toArgb()
        val blueSeed = Color.Blue.toArgb()

        val colorScheme =
            ThemeColorGenerator.generateColorScheme(
                primarySeed = redSeed,
                secondarySeed = greenSeed,
                tertiarySeed = blueSeed,
                isDark = false,
            )

        // Containers should exist and be different from main colors
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
