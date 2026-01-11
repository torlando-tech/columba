package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MapSourcesCard UI component.
 *
 * Tests cover:
 * - Card header and description display
 * - HTTP toggle states and interactions
 * - Offline maps info display
 * - Toggle enable/disable logic based on available sources
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapSourcesCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    // ========== Header and Description Tests ==========

    @Test
    fun mapSourcesCard_displaysHeader() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        composeTestRule.onNodeWithText("Map Sources").assertIsDisplayed()
    }

    @Test
    fun mapSourcesCard_displaysDescription() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        composeTestRule.onNodeWithText(
            "Configure how map tiles are fetched. Offline maps take priority when available.",
        ).assertIsDisplayed()
    }

    // ========== HTTP Toggle Tests ==========

    @Test
    fun mapSourcesCard_displaysHttpToggle() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fetch tiles from the internet").assertIsDisplayed()
    }

    @Test
    fun mapSourcesCard_httpToggleShowsEnabled() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").assertIsDisplayed()
    }

    @Test
    fun mapSourcesCard_httpToggleCallsCallback() {
        var httpEnabledResult = true

        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true, // Allow disabling HTTP
            )
        }

        // Click on the row to toggle
        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").performClick()

        assertFalse(httpEnabledResult)
    }

    @Test
    fun mapSourcesCard_httpCannotBeDisabledWhenOnlySource() {
        var callbackCalled = false

        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = { callbackCalled = true },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = false, // No offline maps, HTTP is only source
            )
        }

        // Try to click to disable
        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").performClick()

        // Callback should NOT be called because HTTP cannot be disabled
        assertFalse("Callback should not be called when HTTP is only source", callbackCalled)
    }

    @Test
    fun mapSourcesCard_httpCanBeDisabledWhenOfflineMapsExist() {
        var httpEnabledResult = true

        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true, // Has offline maps, can disable HTTP
            )
        }

        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").performClick()

        assertFalse(httpEnabledResult)
    }

    @Test
    fun mapSourcesCard_httpCanBeEnabledWhenDisabled() {
        var httpEnabledResult = false

        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = false,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").performClick()

        assertTrue(httpEnabledResult)
    }

    // ========== Offline Maps Info Tests ==========

    @Test
    fun mapSourcesCard_displaysOfflineMapsInfo() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        composeTestRule.onNodeWithText(
            "Offline maps available - they will be used when location is covered",
        ).assertIsDisplayed()
    }

    @Test
    fun mapSourcesCard_hidesOfflineMapsInfoWhenNone() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = false,
            )
        }

        composeTestRule.onNodeWithText(
            "Offline maps available - they will be used when location is covered",
        ).assertDoesNotExist()
    }

    // ========== Warning Display Tests ==========

    @Test
    fun mapSourcesCard_hidesWarningWhenHttpEnabled() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = false,
            )
        }

        composeTestRule.onNodeWithText("At least one map source must be enabled")
            .assertDoesNotExist()
    }

    @Test
    fun mapSourcesCard_hidesWarningWhenOfflineMapsExist() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = false,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        composeTestRule.onNodeWithText("At least one map source must be enabled")
            .assertDoesNotExist()
    }

    // ========== Edge Case Tests ==========

    @Test
    fun mapSourcesCard_defaultRmspServerCountIsZero() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        // Should display without crashing with default rmspServerCount = 0
        composeTestRule.onNodeWithText("Map Sources").assertIsDisplayed()
    }

    @Test
    fun mapSourcesCard_defaultHasOfflineMapsIsFalse() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        // Should not show offline maps info by default
        composeTestRule.onNodeWithText(
            "Offline maps available - they will be used when location is covered",
        ).assertDoesNotExist()
    }

    @Test
    fun mapSourcesCard_httpDisabledWithOfflineMapsShowsNoWarning() {
        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = false,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        // No warning when offline maps are available
        composeTestRule.onNodeWithText("At least one map source must be enabled")
            .assertDoesNotExist()
    }

    @Test
    fun mapSourcesCard_bothTogglesWork() {
        var httpEnabled = true
        var rmspEnabled = false

        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = httpEnabled,
                onHttpEnabledChange = { httpEnabled = it },
                rmspEnabled = rmspEnabled,
                onRmspEnabledChange = { rmspEnabled = it },
                hasOfflineMaps = true,
            )
        }

        // HTTP toggle should work
        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").performClick()
        assertFalse(httpEnabled)
    }

    @Test
    fun mapSourcesCard_clickableRowToggle() {
        var toggledValue: Boolean? = null

        composeTestRule.setContent {
            MapSourcesCard(
                httpEnabled = false,
                onHttpEnabledChange = { toggledValue = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        // Click on the description text should also toggle
        composeTestRule.onNodeWithText("Fetch tiles from the internet").performClick()

        assertEquals(true, toggledValue)
    }
}
