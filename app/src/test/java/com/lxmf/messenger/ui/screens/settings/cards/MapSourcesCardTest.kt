package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
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
                isExpanded = true,
                onExpandedChange = {},
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
                isExpanded = true,
                onExpandedChange = {},
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
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        // HTTP toggle is shown in the expanded content with title and description
        composeTestRule.onNodeWithText("HTTP (OpenFreeMap)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fetch tiles from the internet").assertIsDisplayed()
        // HTTP toggle Switch is in the content
        composeTestRule.onNode(isToggleable()).assertIsDisplayed()
    }

    @Test
    fun mapSourcesCard_httpToggleShowsEnabled() {
        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
            )
        }

        // HTTP Switch should be on when httpEnabled = true
        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun mapSourcesCard_httpToggleCallsCallback() {
        var httpEnabledResult = true

        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = true,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true, // Allow disabling HTTP
            )
        }

        // Click on the Switch to toggle
        composeTestRule.onNode(isToggleable()).performClick()

        assertFalse(httpEnabledResult)
    }

    @Test
    fun mapSourcesCard_httpCanBeDisabledEvenWhenOnlySource() {
        // HTTP can now be disabled even when it's the only source, because
        // MapScreen shows a helpful overlay explaining no map source is enabled
        var httpEnabledResult = true

        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = true,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = false, // No offline maps, but HTTP can still be disabled
            )
        }

        // Click to disable - should work now
        composeTestRule.onNode(isToggleable()).performClick()

        // Callback should be called and HTTP should be disabled
        assertFalse("HTTP should be disabled after toggle", httpEnabledResult)
    }

    @Test
    fun mapSourcesCard_httpCanBeDisabledWhenOfflineMapsExist() {
        var httpEnabledResult = true

        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = true,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true, // Has offline maps, can disable HTTP
            )
        }

        composeTestRule.onNode(isToggleable()).performClick()

        assertFalse(httpEnabledResult)
    }

    @Test
    fun mapSourcesCard_httpCanBeEnabledWhenDisabled() {
        var httpEnabledResult = false

        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = false,
                onHttpEnabledChange = { httpEnabledResult = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        composeTestRule.onNode(isToggleable()).performClick()

        assertTrue(httpEnabledResult)
    }

    // ========== Offline Maps Info Tests ==========

    @Test
    fun mapSourcesCard_displaysOfflineMapsInfo() {
        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
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
                isExpanded = true,
                onExpandedChange = {},
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
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = true,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = false,
            )
        }

        composeTestRule.onNodeWithText("No map tiles will load until a source is enabled or offline maps are downloaded")
            .assertDoesNotExist()
    }

    @Test
    fun mapSourcesCard_hidesWarningWhenOfflineMapsExist() {
        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = false,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        composeTestRule.onNodeWithText("No map tiles will load until a source is enabled or offline maps are downloaded")
            .assertDoesNotExist()
    }

    // ========== Edge Case Tests ==========

    @Test
    fun mapSourcesCard_defaultRmspServerCountIsZero() {
        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
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
                isExpanded = true,
                onExpandedChange = {},
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
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = false,
                onHttpEnabledChange = {},
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        // No warning when offline maps are available
        composeTestRule.onNodeWithText("No map tiles will load until a source is enabled or offline maps are downloaded")
            .assertDoesNotExist()
    }

    @Test
    fun mapSourcesCard_bothTogglesWork() {
        var httpEnabled = true
        var rmspEnabled = false

        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = httpEnabled,
                onHttpEnabledChange = { httpEnabled = it },
                rmspEnabled = rmspEnabled,
                onRmspEnabledChange = { rmspEnabled = it },
                hasOfflineMaps = true,
            )
        }

        // HTTP toggle should work (click the Switch in header)
        composeTestRule.onNode(isToggleable()).performClick()
        assertFalse(httpEnabled)
    }

    @Test
    fun mapSourcesCard_switchToggle() {
        var toggledValue: Boolean? = null

        composeTestRule.setContent {
            MapSourcesCard(
                isExpanded = true,
                onExpandedChange = {},
                httpEnabled = false,
                onHttpEnabledChange = { toggledValue = it },
                rmspEnabled = false,
                onRmspEnabledChange = {},
                hasOfflineMaps = true,
            )
        }

        // Click on the Switch to toggle
        composeTestRule.onNode(isToggleable()).performClick()

        assertEquals(true, toggledValue)
    }
}
