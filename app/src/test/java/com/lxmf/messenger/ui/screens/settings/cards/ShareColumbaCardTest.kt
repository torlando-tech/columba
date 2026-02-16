package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.theme.ColumbaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareColumbaCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun `displays card title`() {
        composeTestRule.setContent {
            ColumbaTheme {
                ShareColumbaCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Columba").assertIsDisplayed()
    }

    @Test
    fun `displays description when expanded`() {
        composeTestRule.setContent {
            ColumbaTheme {
                ShareColumbaCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Share the Columba app with someone nearby.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `displays share button when expanded`() {
        composeTestRule.setContent {
            ColumbaTheme {
                ShareColumbaCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Columba APK").assertIsDisplayed()
    }

    @Test
    fun `hides content when collapsed`() {
        composeTestRule.setContent {
            ColumbaTheme {
                ShareColumbaCard(
                    isExpanded = false,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Columba APK").assertDoesNotExist()
    }

    @Test
    fun `share button triggers navigation callback`() {
        var navigated = false
        composeTestRule.setContent {
            ColumbaTheme {
                ShareColumbaCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = { navigated = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Share Columba APK").performClick()
        assertTrue(navigated)
    }

    @Test
    fun `expand change callback is invoked`() {
        var expandedValue: Boolean? = null
        composeTestRule.setContent {
            ColumbaTheme {
                ShareColumbaCard(
                    isExpanded = false,
                    onExpandedChange = { expandedValue = it },
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Columba").performClick()
        assertEquals(true, expandedValue)
    }
}
