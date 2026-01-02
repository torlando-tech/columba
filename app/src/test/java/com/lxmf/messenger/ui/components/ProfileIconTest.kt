package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for ProfileIcon composable.
 * Tests the profile icon display with custom icons and colors,
 * as well as fallback to Identicon when icon data is incomplete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileIconTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Helper Methods ==========

    private fun createTestHash(): ByteArray {
        // Create a deterministic hash for testing
        return byteArrayOf(
            0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F,
            0x7A.toByte(), 0x8B.toByte(), 0x9C.toByte(), 0xAD.toByte(),
            0xBE.toByte(), 0xCF.toByte(), 0xD0.toByte(), 0xE1.toByte(),
            0xF2.toByte(), 0x03,
        )
    }

    // ========== Display Tests - All Parameters Provided ==========

    @Test
    fun profileIcon_showsIcon_whenAllParametersProvided() {
        // Given: All icon parameters are provided
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon is displayed (content description is "Profile icon")
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_showsIcon_withDifferentIconName() {
        // Given: Different icon name
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "star",
                foregroundColor = "000000",
                backgroundColor = "FFD700",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon is displayed
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_showsIcon_withVariousColors() {
        // Given: Various color combinations
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "heart",
                foregroundColor = "FF0000",
                backgroundColor = "FFFFFF",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon is displayed
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    // ========== Fallback Tests - Missing iconName ==========

    @Test
    fun profileIcon_fallsBackToIdenticon_whenIconNameIsNull() {
        // Given: iconName is null
        composeTestRule.setContent {
            ProfileIcon(
                iconName = null,
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon content description should NOT be present
        // (Identicon doesn't have the "Profile icon" content description)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }

    // ========== Fallback Tests - Missing foregroundColor ==========

    @Test
    fun profileIcon_fallsBackToIdenticon_whenForegroundColorIsNull() {
        // Given: foregroundColor is null
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = null,
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should fall back to identicon
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }

    // ========== Fallback Tests - Missing backgroundColor ==========

    @Test
    fun profileIcon_fallsBackToIdenticon_whenBackgroundColorIsNull() {
        // Given: backgroundColor is null
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = null,
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should fall back to identicon
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }

    // ========== Fallback Tests - All Null ==========

    @Test
    fun profileIcon_fallsBackToIdenticon_whenAllIconParametersNull() {
        // Given: All icon parameters are null
        composeTestRule.setContent {
            ProfileIcon(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should fall back to identicon
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }

    // ========== Invalid Color Tests ==========

    @Test
    fun profileIcon_handlesInvalidForegroundColor_fallsBackToDefaultColor() {
        // Given: Invalid hex color for foreground
        // ProfileIcon should catch the exception and use Color.White
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "INVALID",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon should still be displayed (uses fallback color)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_handlesInvalidBackgroundColor_fallsBackToDefaultColor() {
        // Given: Invalid hex color for background
        // ProfileIcon should catch the exception and use Color.Gray
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "NOTAHEXCOLOR",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon should still be displayed (uses fallback color)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_handlesBothInvalidColors_fallsBackToDefaultColors() {
        // Given: Both colors are invalid
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "ZZZZZZ",
                backgroundColor = "YYYYYY",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon should still be displayed (uses fallback colors)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_handlesPartialHexColor() {
        // Given: Partial hex color (too short)
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFF", // Too short for parseColor with #
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon should still be displayed (uses fallback color)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_handlesEmptyColorString() {
        // Given: Empty color string
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon should still be displayed (uses fallback color)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    // ========== Size Tests ==========

    @Test
    fun profileIcon_acceptsSmallSize() {
        // Given: Small size
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 24.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon is displayed
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_acceptsMediumSize() {
        // Given: Medium size
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon is displayed
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_acceptsLargeSize() {
        // Given: Large size
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 128.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Profile icon is displayed
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    // ========== Valid Color Tests ==========

    @Test
    fun profileIcon_validWhiteColor() {
        // Given: Valid white color (FFFFFF)
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "000000",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should display correctly
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_validBlackColor() {
        // Given: Valid black color (000000)
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "000000",
                backgroundColor = "FFFFFF",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should display correctly
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_validBlueColor() {
        // Given: Valid blue color (1E88E5 - Material Blue)
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should display correctly
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_validLowercaseHexColor() {
        // Given: Valid lowercase hex color
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "ffffff",
                backgroundColor = "1e88e5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should display correctly (parseColor handles lowercase)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    @Test
    fun profileIcon_validMixedCaseHexColor() {
        // Given: Valid mixed case hex color
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "account",
                foregroundColor = "FfFfFf",
                backgroundColor = "1E88e5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should display correctly (parseColor handles mixed case)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertIsDisplayed()
    }

    // ========== Edge Cases ==========

    @Test
    fun profileIcon_handlesUnknownIconName_fallsBackToIdenticon() {
        // Given: Unknown icon name - should fall back to Identicon
        composeTestRule.setContent {
            ProfileIcon(
                iconName = "this-icon-does-not-exist",
                foregroundColor = "FFFFFF",
                backgroundColor = "1E88E5",
                size = 48.dp,
                fallbackHash = createTestHash(),
            )
        }

        // Then: Should fall back to Identicon (no "Profile icon" content description)
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }

    @Test
    fun profileIcon_handlesEmptyHash() {
        // Given: Empty hash (should handle gracefully if falling back)
        composeTestRule.setContent {
            ProfileIcon(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                size = 48.dp,
                fallbackHash = byteArrayOf(),
            )
        }

        // Then: Should not crash - Identicon handles empty hash
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }

    @Test
    fun profileIcon_handlesMinimalHash() {
        // Given: Very small hash (Identicon needs at least 6 bytes)
        composeTestRule.setContent {
            ProfileIcon(
                iconName = null,
                foregroundColor = null,
                backgroundColor = null,
                size = 48.dp,
                fallbackHash = byteArrayOf(0x01, 0x02, 0x03),
            )
        }

        // Then: Should not crash
        composeTestRule.onNodeWithContentDescription("Profile icon").assertDoesNotExist()
    }
}
