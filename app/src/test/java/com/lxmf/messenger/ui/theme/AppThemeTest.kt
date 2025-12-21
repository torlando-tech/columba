package com.lxmf.messenger.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AppTheme.kt.
 * Tests PresetTheme enum and CustomTheme data class without using Compose.
 */
class AppThemeTest {
    // ========== PresetTheme Tests ==========

    @Test
    fun presetTheme_vibrant_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.VIBRANT.displayName.isNotEmpty())
        assertEquals("Vibrant", PresetTheme.VIBRANT.displayName)
    }

    @Test
    fun presetTheme_vibrant_hasNonEmptyDescription() {
        assertTrue(PresetTheme.VIBRANT.description.isNotEmpty())
    }

    @Test
    fun presetTheme_dynamic_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.DYNAMIC.displayName.isNotEmpty())
        assertEquals("Dynamic", PresetTheme.DYNAMIC.displayName)
    }

    @Test
    fun presetTheme_dynamic_hasNonEmptyDescription() {
        assertTrue(PresetTheme.DYNAMIC.description.isNotEmpty())
    }

    @Test
    fun presetTheme_ocean_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.OCEAN.displayName.isNotEmpty())
        assertEquals("Ocean", PresetTheme.OCEAN.displayName)
    }

    @Test
    fun presetTheme_ocean_hasNonEmptyDescription() {
        assertTrue(PresetTheme.OCEAN.description.isNotEmpty())
    }

    @Test
    fun presetTheme_forest_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.FOREST.displayName.isNotEmpty())
        assertEquals("Forest", PresetTheme.FOREST.displayName)
    }

    @Test
    fun presetTheme_forest_hasNonEmptyDescription() {
        assertTrue(PresetTheme.FOREST.description.isNotEmpty())
    }

    @Test
    fun presetTheme_sunset_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.SUNSET.displayName.isNotEmpty())
        assertEquals("Sunset", PresetTheme.SUNSET.displayName)
    }

    @Test
    fun presetTheme_sunset_hasNonEmptyDescription() {
        assertTrue(PresetTheme.SUNSET.description.isNotEmpty())
    }

    @Test
    fun presetTheme_monochrome_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.MONOCHROME.displayName.isNotEmpty())
        assertEquals("Monochrome", PresetTheme.MONOCHROME.displayName)
    }

    @Test
    fun presetTheme_monochrome_hasNonEmptyDescription() {
        assertTrue(PresetTheme.MONOCHROME.description.isNotEmpty())
    }

    @Test
    fun presetTheme_expressive_hasNonEmptyDisplayName() {
        assertTrue(PresetTheme.EXPRESSIVE.displayName.isNotEmpty())
        assertEquals("Expressive", PresetTheme.EXPRESSIVE.displayName)
    }

    @Test
    fun presetTheme_expressive_hasNonEmptyDescription() {
        assertTrue(PresetTheme.EXPRESSIVE.description.isNotEmpty())
    }

    // ========== PresetTheme Identifier Tests ==========

    @Test
    fun presetTheme_vibrant_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:VIBRANT", PresetTheme.VIBRANT.getIdentifier())
    }

    @Test
    fun presetTheme_dynamic_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:DYNAMIC", PresetTheme.DYNAMIC.getIdentifier())
    }

    @Test
    fun presetTheme_ocean_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:OCEAN", PresetTheme.OCEAN.getIdentifier())
    }

    @Test
    fun presetTheme_forest_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:FOREST", PresetTheme.FOREST.getIdentifier())
    }

    @Test
    fun presetTheme_sunset_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:SUNSET", PresetTheme.SUNSET.getIdentifier())
    }

    @Test
    fun presetTheme_monochrome_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:MONOCHROME", PresetTheme.MONOCHROME.getIdentifier())
    }

    @Test
    fun presetTheme_expressive_getIdentifier_returnsCorrectFormat() {
        assertEquals("preset:EXPRESSIVE", PresetTheme.EXPRESSIVE.getIdentifier())
    }

    // ========== PresetTheme ColorScheme Tests ==========

    @Test
    fun presetTheme_vibrant_lightScheme_isNotNull() {
        val scheme = PresetTheme.VIBRANT.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_vibrant_darkScheme_isNotNull() {
        val scheme = PresetTheme.VIBRANT.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_vibrant_lightDark_areDifferent() {
        val lightScheme = PresetTheme.VIBRANT.getColorScheme(isDarkTheme = false)
        val darkScheme = PresetTheme.VIBRANT.getColorScheme(isDarkTheme = true)
        // Compare primary colors to ensure they're different schemes
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    @Test
    fun presetTheme_ocean_lightScheme_isNotNull() {
        val scheme = PresetTheme.OCEAN.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_ocean_darkScheme_isNotNull() {
        val scheme = PresetTheme.OCEAN.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_ocean_lightDark_areDifferent() {
        val lightScheme = PresetTheme.OCEAN.getColorScheme(isDarkTheme = false)
        val darkScheme = PresetTheme.OCEAN.getColorScheme(isDarkTheme = true)
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    @Test
    fun presetTheme_forest_lightScheme_isNotNull() {
        val scheme = PresetTheme.FOREST.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_forest_darkScheme_isNotNull() {
        val scheme = PresetTheme.FOREST.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_forest_lightDark_areDifferent() {
        val lightScheme = PresetTheme.FOREST.getColorScheme(isDarkTheme = false)
        val darkScheme = PresetTheme.FOREST.getColorScheme(isDarkTheme = true)
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    @Test
    fun presetTheme_sunset_lightScheme_isNotNull() {
        val scheme = PresetTheme.SUNSET.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_sunset_darkScheme_isNotNull() {
        val scheme = PresetTheme.SUNSET.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_sunset_lightDark_areDifferent() {
        val lightScheme = PresetTheme.SUNSET.getColorScheme(isDarkTheme = false)
        val darkScheme = PresetTheme.SUNSET.getColorScheme(isDarkTheme = true)
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    @Test
    fun presetTheme_monochrome_lightScheme_isNotNull() {
        val scheme = PresetTheme.MONOCHROME.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_monochrome_darkScheme_isNotNull() {
        val scheme = PresetTheme.MONOCHROME.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_monochrome_lightDark_areDifferent() {
        val lightScheme = PresetTheme.MONOCHROME.getColorScheme(isDarkTheme = false)
        val darkScheme = PresetTheme.MONOCHROME.getColorScheme(isDarkTheme = true)
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    @Test
    fun presetTheme_expressive_lightScheme_isNotNull() {
        val scheme = PresetTheme.EXPRESSIVE.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_expressive_darkScheme_isNotNull() {
        val scheme = PresetTheme.EXPRESSIVE.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_expressive_lightDark_areDifferent() {
        val lightScheme = PresetTheme.EXPRESSIVE.getColorScheme(isDarkTheme = false)
        val darkScheme = PresetTheme.EXPRESSIVE.getColorScheme(isDarkTheme = true)
        assertNotEquals(lightScheme.primary, darkScheme.primary)
    }

    @Test
    fun presetTheme_dynamic_lightScheme_isNotNull() {
        val scheme = PresetTheme.DYNAMIC.getColorScheme(isDarkTheme = false)
        assertNotNull(scheme)
    }

    @Test
    fun presetTheme_dynamic_darkScheme_isNotNull() {
        val scheme = PresetTheme.DYNAMIC.getColorScheme(isDarkTheme = true)
        assertNotNull(scheme)
    }

    // ========== PresetTheme Preview Colors Tests ==========

    @Test
    fun presetTheme_vibrant_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.VIBRANT.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_vibrant_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.VIBRANT.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_vibrant_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.VIBRANT.getPreviewColors(isDarkTheme = false)
        // All three colors should be different
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    @Test
    fun presetTheme_ocean_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.OCEAN.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_ocean_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.OCEAN.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_ocean_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.OCEAN.getPreviewColors(isDarkTheme = false)
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    @Test
    fun presetTheme_forest_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.FOREST.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_forest_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.FOREST.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_forest_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.FOREST.getPreviewColors(isDarkTheme = false)
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    @Test
    fun presetTheme_sunset_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.SUNSET.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_sunset_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.SUNSET.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_sunset_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.SUNSET.getPreviewColors(isDarkTheme = false)
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    @Test
    fun presetTheme_monochrome_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.MONOCHROME.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_monochrome_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.MONOCHROME.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_monochrome_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.MONOCHROME.getPreviewColors(isDarkTheme = false)
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    @Test
    fun presetTheme_expressive_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.EXPRESSIVE.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_expressive_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.EXPRESSIVE.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_expressive_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.EXPRESSIVE.getPreviewColors(isDarkTheme = false)
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    @Test
    fun presetTheme_dynamic_getPreviewColors_light_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.DYNAMIC.getPreviewColors(isDarkTheme = false)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_dynamic_getPreviewColors_dark_returnsThreeColors() {
        val (primary, secondary, tertiary) = PresetTheme.DYNAMIC.getPreviewColors(isDarkTheme = true)
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotNull(tertiary)
    }

    @Test
    fun presetTheme_dynamic_previewColors_areDistinct() {
        val (primary, secondary, tertiary) = PresetTheme.DYNAMIC.getPreviewColors(isDarkTheme = false)
        assertNotEquals(primary, secondary)
        assertNotEquals(secondary, tertiary)
        assertNotEquals(primary, tertiary)
    }

    // ========== CustomTheme Tests ==========

    @Test
    fun customTheme_getIdentifier_returnsCorrectFormat() {
        val theme =
            CustomTheme(
                id = 123L,
                displayName = "My Theme",
                description = "Custom theme",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )
        assertEquals("custom:123", theme.getIdentifier())
    }

    @Test
    fun customTheme_getIdentifier_withDifferentId_returnsCorrectFormat() {
        val theme =
            CustomTheme(
                id = 456L,
                displayName = "Another Theme",
                description = "Another custom theme",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )
        assertEquals("custom:456", theme.getIdentifier())
    }

    @Test
    fun customTheme_getColorScheme_light_returnsLightScheme() {
        val lightScheme = lightColorScheme(primary = Color.Red)
        val darkScheme = darkColorScheme(primary = Color.Blue)
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Test Theme",
                description = "Test",
                lightColorScheme = lightScheme,
                darkColorScheme = darkScheme,
            )

        val result = theme.getColorScheme(isDarkTheme = false)
        assertEquals(lightScheme.primary, result.primary)
        assertEquals(Color.Red, result.primary)
    }

    @Test
    fun customTheme_getColorScheme_dark_returnsDarkScheme() {
        val lightScheme = lightColorScheme(primary = Color.Red)
        val darkScheme = darkColorScheme(primary = Color.Blue)
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Test Theme",
                description = "Test",
                lightColorScheme = lightScheme,
                darkColorScheme = darkScheme,
            )

        val result = theme.getColorScheme(isDarkTheme = true)
        assertEquals(darkScheme.primary, result.primary)
        assertEquals(Color.Blue, result.primary)
    }

    @Test
    fun customTheme_getPreviewColors_light_extractsFromLightScheme() {
        val lightScheme =
            lightColorScheme(
                primary = Color.Red,
                secondary = Color.Green,
                tertiary = Color.Blue,
            )
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Test Theme",
                description = "Test",
                lightColorScheme = lightScheme,
                darkColorScheme = darkColorScheme(),
            )

        val (primary, secondary, tertiary) = theme.getPreviewColors(isDarkTheme = false)
        assertEquals(Color.Red, primary)
        assertEquals(Color.Green, secondary)
        assertEquals(Color.Blue, tertiary)
    }

    @Test
    fun customTheme_getPreviewColors_dark_extractsFromDarkScheme() {
        val darkScheme =
            darkColorScheme(
                primary = Color.Yellow,
                secondary = Color.Cyan,
                tertiary = Color.Magenta,
            )
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Test Theme",
                description = "Test",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkScheme,
            )

        val (primary, secondary, tertiary) = theme.getPreviewColors(isDarkTheme = true)
        assertEquals(Color.Yellow, primary)
        assertEquals(Color.Cyan, secondary)
        assertEquals(Color.Magenta, tertiary)
    }

    @Test
    fun customTheme_withBaseTheme_storesReference() {
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Vibrant Copy",
                description = "Based on Vibrant",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
                baseTheme = PresetTheme.VIBRANT,
            )

        assertEquals(PresetTheme.VIBRANT, theme.baseTheme)
    }

    @Test
    fun customTheme_withoutBaseTheme_isNull() {
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Original Theme",
                description = "Not based on preset",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )

        assertEquals(null, theme.baseTheme)
    }

    @Test
    fun customTheme_displayName_isStored() {
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "My Custom Theme",
                description = "Test",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )

        assertEquals("My Custom Theme", theme.displayName)
    }

    @Test
    fun customTheme_description_isStored() {
        val theme =
            CustomTheme(
                id = 1L,
                displayName = "Test",
                description = "A beautiful custom theme",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )

        assertEquals("A beautiful custom theme", theme.description)
    }

    // ========== Equality Tests ==========

    @Test
    fun customTheme_equalIds_areEqual() {
        // Use same ColorScheme instances since data class equality compares all fields
        val light = lightColorScheme()
        val dark = darkColorScheme()
        val theme1 =
            CustomTheme(
                id = 1L,
                displayName = "Theme",
                description = "Desc",
                lightColorScheme = light,
                darkColorScheme = dark,
            )
        val theme2 =
            CustomTheme(
                id = 1L,
                displayName = "Theme",
                description = "Desc",
                lightColorScheme = light,
                darkColorScheme = dark,
            )

        assertEquals(theme1, theme2)
    }

    @Test
    fun customTheme_differentIds_areNotEqual() {
        val theme1 =
            CustomTheme(
                id = 1L,
                displayName = "Theme",
                description = "Desc",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )
        val theme2 =
            CustomTheme(
                id = 2L,
                displayName = "Theme",
                description = "Desc",
                lightColorScheme = lightColorScheme(),
                darkColorScheme = darkColorScheme(),
            )

        assertNotEquals(theme1, theme2)
    }

    // ========== All PresetThemes Have Unique Identifiers ==========

    @Test
    fun allPresetThemes_haveUniqueIdentifiers() {
        val identifiers = PresetTheme.entries.map { it.getIdentifier() }
        val uniqueIdentifiers = identifiers.toSet()

        assertEquals(
            "All preset themes should have unique identifiers",
            identifiers.size,
            uniqueIdentifiers.size,
        )
    }

    @Test
    fun allPresetThemes_identifiersStartWithPreset() {
        PresetTheme.entries.forEach { theme ->
            assertTrue(
                "Theme ${theme.name} identifier should start with 'preset:'",
                theme.getIdentifier().startsWith("preset:"),
            )
        }
    }
}
