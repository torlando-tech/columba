package com.lxmf.messenger.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for MaterialDesignIcons codepoint mapping.
 * Tests the font-based MDI implementation for Sideband/MeshChat interoperability.
 */
class MaterialDesignIconsTest {
    // ========== getCodepoint() Method Tests ==========

    @Test
    fun getCodepoint_account_returnsNonEmptyString() {
        val codepoint = MaterialDesignIcons.getCodepoint("account")
        assertNotNull(codepoint)
        assertTrue("Codepoint should not be empty", codepoint.isNotEmpty())
    }

    @Test
    fun getCodepoint_star_returnsNonEmptyString() {
        val codepoint = MaterialDesignIcons.getCodepoint("star")
        assertNotNull(codepoint)
        assertTrue("Codepoint should not be empty", codepoint.isNotEmpty())
    }

    @Test
    fun getCodepoint_unknownIcon_returnsDefault() {
        val codepoint = MaterialDesignIcons.getCodepoint("nonexistent-icon-xyz")
        assertNotNull("Should return default for unknown icon", codepoint)
        assertTrue("Default should be non-empty", codepoint.isNotEmpty())
    }

    @Test
    fun getCodepoint_emptyString_returnsDefault() {
        val codepoint = MaterialDesignIcons.getCodepoint("")
        assertNotNull("Should return default for empty string", codepoint)
    }

    // ========== getCodepointOrNull() Method Tests ==========

    @Test
    fun getCodepointOrNull_knownIcon_returnsCodepoint() {
        val codepoint = MaterialDesignIcons.getCodepointOrNull("account")
        assertNotNull("Known icon should return codepoint", codepoint)
    }

    @Test
    fun getCodepointOrNull_unknownIcon_returnsNull() {
        val codepoint = MaterialDesignIcons.getCodepointOrNull("not-a-real-icon")
        assertNull("Unknown icon should return null", codepoint)
    }

    @Test
    fun getCodepointOrNull_emptyString_returnsNull() {
        val codepoint = MaterialDesignIcons.getCodepointOrNull("")
        assertNull("Empty string should return null", codepoint)
    }

    // ========== contains() Method Tests ==========

    @Test
    fun contains_knownIcons_returnsTrue() {
        assertTrue(MaterialDesignIcons.contains("account"))
        assertTrue(MaterialDesignIcons.contains("star"))
        assertTrue(MaterialDesignIcons.contains("heart"))
        assertTrue(MaterialDesignIcons.contains("radio"))
        assertTrue(MaterialDesignIcons.contains("wifi"))
    }

    @Test
    fun contains_unknownIcons_returnsFalse() {
        assertFalse(MaterialDesignIcons.contains("not-a-real-icon"))
        assertFalse(MaterialDesignIcons.contains(""))
        assertFalse(MaterialDesignIcons.contains("123-invalid"))
    }

    @Test
    fun contains_isCaseSensitive() {
        assertTrue("Lowercase should be found", MaterialDesignIcons.contains("account"))
        assertFalse("Uppercase should not be found", MaterialDesignIcons.contains("ACCOUNT"))
        assertFalse("Mixed case should not be found", MaterialDesignIcons.contains("Account"))
    }

    // ========== getAllIconNames() Method Tests ==========

    @Test
    fun getAllIconNames_returnsNonEmptyList() {
        val names = MaterialDesignIcons.getAllIconNames()
        assertTrue("Should return non-empty list", names.isNotEmpty())
    }

    @Test
    fun getAllIconNames_isSorted() {
        val names = MaterialDesignIcons.getAllIconNames()
        assertEquals("List should be sorted", names.sorted(), names)
    }

    @Test
    fun getAllIconNames_containsExpectedIcons() {
        val names = MaterialDesignIcons.getAllIconNames()
        assertTrue(names.contains("account"))
        assertTrue(names.contains("star"))
        assertTrue(names.contains("wifi"))
    }

    // ========== iconCount Property Tests ==========

    @Test
    fun iconCount_matchesGetAllIconNamesSize() {
        assertEquals(
            MaterialDesignIcons.iconCount,
            MaterialDesignIcons.getAllIconNames().size,
        )
    }

    @Test
    fun iconCount_isGreaterThan7000() {
        // MDI library has 7000+ icons
        assertTrue(
            "Should have 7000+ icons, got ${MaterialDesignIcons.iconCount}",
            MaterialDesignIcons.iconCount > 7000,
        )
    }

    // ========== getPopularIcons() Method Tests ==========

    @Test
    fun getPopularIcons_returnsNonEmptyList() {
        val popular = MaterialDesignIcons.getPopularIcons()
        assertTrue("Should return non-empty list", popular.isNotEmpty())
    }

    @Test
    fun getPopularIcons_allIconsExist() {
        val popular = MaterialDesignIcons.getPopularIcons()
        popular.forEach { iconName ->
            assertTrue(
                "Popular icon '$iconName' should exist",
                MaterialDesignIcons.contains(iconName),
            )
        }
    }

    @Test
    fun getPopularIcons_containsExpectedIcons() {
        val popular = MaterialDesignIcons.getPopularIcons()
        assertTrue(popular.contains("account"))
        assertTrue(popular.contains("star"))
        assertTrue(popular.contains("heart"))
    }

    // ========== iconsByCategory Property Tests ==========

    @Test
    fun iconsByCategory_isNotEmpty() {
        val categories = MaterialDesignIcons.iconsByCategory
        assertTrue("Should have categories", categories.isNotEmpty())
    }

    @Test
    fun iconsByCategory_containsExpectedCategories() {
        val categories = MaterialDesignIcons.iconsByCategory.keys
        assertTrue(categories.contains("People"))
        assertTrue(categories.contains("Animals"))
        assertTrue(categories.contains("Technology"))
        assertTrue(categories.contains("Communication"))
    }

    @Test
    fun iconsByCategory_allIconsExist() {
        MaterialDesignIcons.iconsByCategory.forEach { (category, icons) ->
            icons.forEach { iconName ->
                assertTrue(
                    "Icon '$iconName' in '$category' should exist",
                    MaterialDesignIcons.contains(iconName),
                )
            }
        }
    }

    @Test
    fun iconsByCategory_allCategoriesHaveIcons() {
        MaterialDesignIcons.iconsByCategory.forEach { (category, icons) ->
            assertTrue(
                "Category '$category' should have icons",
                icons.isNotEmpty(),
            )
        }
    }

    // ========== Codepoint Format Tests ==========

    @Test
    fun codepoints_areSurrogatePairs() {
        // MDI icons are in Supplementary Private Use Area (above U+FFFF)
        // They should be represented as UTF-16 surrogate pairs (2 chars)
        val codepoint = MaterialDesignIcons.getCodepoint("account")
        assertEquals("Codepoint should be 2 chars (surrogate pair)", 2, codepoint.length)
    }

    @Test
    fun codepoints_haveValidSurrogates() {
        val codepoint = MaterialDesignIcons.getCodepoint("account")
        // High surrogate: 0xD800-0xDBFF
        assertTrue(
            "First char should be high surrogate",
            codepoint[0].code in 0xD800..0xDBFF,
        )
        // Low surrogate: 0xDC00-0xDFFF
        assertTrue(
            "Second char should be low surrogate",
            codepoint[1].code in 0xDC00..0xDFFF,
        )
    }

    @Test
    fun differentIcons_haveDifferentCodepoints() {
        val account = MaterialDesignIcons.getCodepoint("account")
        val star = MaterialDesignIcons.getCodepoint("star")
        val heart = MaterialDesignIcons.getCodepoint("heart")

        assertTrue("account and star should differ", account != star)
        assertTrue("account and heart should differ", account != heart)
        assertTrue("star and heart should differ", star != heart)
    }

    // ========== Sideband Interoperability Tests ==========

    @Test
    fun sidebandCommonIcons_areAvailable() {
        // Icons commonly used in Sideband
        val sidebandIcons =
            listOf(
                "radio",
                "account",
                "alien",
                "alien-outline",
                "robot",
                "robot-outline",
                "cat",
                "dog",
                "star",
                "heart",
                "skull",
                "ghost",
            )
        sidebandIcons.forEach { iconName ->
            assertTrue(
                "Sideband icon '$iconName' should be available",
                MaterialDesignIcons.contains(iconName),
            )
        }
    }

    @Test
    fun mdiIconVariants_areAvailable() {
        // MDI has variants like "outline", "filled" etc.
        assertTrue(MaterialDesignIcons.contains("account"))
        assertTrue(MaterialDesignIcons.contains("account-outline"))
        assertTrue(MaterialDesignIcons.contains("account-circle"))
        assertTrue(MaterialDesignIcons.contains("heart"))
        assertTrue(MaterialDesignIcons.contains("heart-outline"))
    }
}
