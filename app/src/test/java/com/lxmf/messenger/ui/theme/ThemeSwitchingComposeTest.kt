package com.lxmf.messenger.ui.theme

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for theme switching behavior.
 *
 * These tests verify that the ColumbaTheme and Material 3 theming system
 * works correctly when switching between preset themes. This is important
 * for validating Compose BOM upgrades don't break theme rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeSwitchingComposeTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Theme Rendering Tests ==========

    @Test
    fun vibrantTheme_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.VIBRANT,
            ) {
                Text(
                    text = "Vibrant Theme Content",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Vibrant Theme Content").assertIsDisplayed()
    }

    @Test
    fun oceanTheme_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.OCEAN,
            ) {
                Text(
                    text = "Ocean Theme Content",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ocean Theme Content").assertIsDisplayed()
    }

    @Test
    fun forestTheme_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.FOREST,
            ) {
                Text(
                    text = "Forest Theme Content",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Forest Theme Content").assertIsDisplayed()
    }

    @Test
    fun sunsetTheme_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.SUNSET,
            ) {
                Text(
                    text = "Sunset Theme Content",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sunset Theme Content").assertIsDisplayed()
    }

    @Test
    fun monochromeTheme_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.MONOCHROME,
            ) {
                Text(
                    text = "Monochrome Theme Content",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Monochrome Theme Content").assertIsDisplayed()
    }

    @Test
    fun expressiveTheme_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.EXPRESSIVE,
            ) {
                Text(
                    text = "Expressive Theme Content",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Expressive Theme Content").assertIsDisplayed()
    }

    // ========== Dark Mode Tests ==========

    @Test
    fun vibrantTheme_darkMode_rendersContent() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = true,
                selectedTheme = PresetTheme.VIBRANT,
            ) {
                Text(
                    text = "Dark Mode Content",
                    modifier = Modifier.testTag("dark-content"),
                )
            }
        }

        composeTestRule.onNodeWithTag("dark-content").assertIsDisplayed()
    }

    @Test
    fun allPresetThemes_renderInDarkMode() {
        // Test all themes in a single composition using state
        var currentTheme by mutableStateOf<AppTheme>(PresetTheme.VIBRANT)

        val themes = listOf(
            PresetTheme.VIBRANT,
            PresetTheme.OCEAN,
            PresetTheme.FOREST,
            PresetTheme.SUNSET,
            PresetTheme.MONOCHROME,
            PresetTheme.EXPRESSIVE,
        )

        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = true,
                selectedTheme = currentTheme,
            ) {
                Text(
                    text = "Theme: ${currentTheme.displayName}",
                    modifier = Modifier.testTag("theme-content"),
                )
            }
        }

        themes.forEach { theme ->
            currentTheme = theme
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("theme-content").assertIsDisplayed()
            composeTestRule.onNodeWithText("Theme: ${theme.displayName}").assertIsDisplayed()
        }
    }

    // ========== Theme Switching Tests ==========

    @Test
    fun themeSwitching_updatesContent() {
        var currentTheme by mutableStateOf<AppTheme>(PresetTheme.VIBRANT)

        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = currentTheme,
            ) {
                Column {
                    Text(
                        text = "Current Theme",
                        modifier = Modifier.testTag("header"),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("primary-color-box"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("primary-color-box").assertIsDisplayed()

        // Switch theme
        currentTheme = PresetTheme.OCEAN
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("primary-color-box").assertIsDisplayed()

        // Switch to another theme
        currentTheme = PresetTheme.SUNSET
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("primary-color-box").assertIsDisplayed()
    }

    @Test
    fun darkModeToggle_updatesTheme() {
        var isDarkMode by mutableStateOf(false)

        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = isDarkMode,
                selectedTheme = PresetTheme.VIBRANT,
            ) {
                Text(
                    text = if (isDarkMode) "Dark Mode" else "Light Mode",
                    modifier = Modifier.testTag("mode-text"),
                )
            }
        }

        composeTestRule.onNodeWithText("Light Mode").assertIsDisplayed()

        // Toggle to dark mode
        isDarkMode = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Dark Mode").assertIsDisplayed()

        // Toggle back to light mode
        isDarkMode = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Light Mode").assertIsDisplayed()
    }

    // ========== Material Theme Integration Tests ==========

    @Test
    fun materialTheme_colorScheme_isAccessible() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.VIBRANT,
            ) {
                // Verify MaterialTheme.colorScheme is accessible
                val colorScheme = MaterialTheme.colorScheme
                Text(
                    text = "Primary color accessible",
                    color = colorScheme.primary,
                    modifier = Modifier.testTag("color-test"),
                )
            }
        }

        composeTestRule.onNodeWithTag("color-test").assertIsDisplayed()
    }

    @Test
    fun materialTheme_typography_isAccessible() {
        composeTestRule.setContent {
            ColumbaTheme(
                darkTheme = false,
                selectedTheme = PresetTheme.VIBRANT,
            ) {
                // Verify MaterialTheme.typography is accessible
                val typography = MaterialTheme.typography
                Text(
                    text = "Typography test",
                    style = typography.headlineMedium,
                    modifier = Modifier.testTag("typography-test"),
                )
            }
        }

        composeTestRule.onNodeWithTag("typography-test").assertIsDisplayed()
    }

    // ========== Color Scheme Difference Tests ==========

    @Test
    fun differentThemes_haveDifferentPrimaryColors() {
        val vibrantScheme = PresetTheme.VIBRANT.getColorScheme(isDarkTheme = false)
        val oceanScheme = PresetTheme.OCEAN.getColorScheme(isDarkTheme = false)
        val forestScheme = PresetTheme.FOREST.getColorScheme(isDarkTheme = false)

        assertNotEquals(
            "Vibrant and Ocean should have different primary colors",
            vibrantScheme.primary,
            oceanScheme.primary,
        )
        assertNotEquals(
            "Ocean and Forest should have different primary colors",
            oceanScheme.primary,
            forestScheme.primary,
        )
    }
}
