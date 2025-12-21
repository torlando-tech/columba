package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for SharingStatusChip.
 *
 * Tests:
 * - Display with singular/plural text
 * - Stop button callback
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharingStatusChipTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun `sharingStatusChip displays singular text for one person`() {
        composeTestRule.setContent {
            SharingStatusChip(
                sharingWithCount = 1,
                onStopAllClick = {},
            )
        }

        composeTestRule.onNodeWithText("Sharing with 1 person").assertIsDisplayed()
    }

    @Test
    fun `sharingStatusChip displays plural text for multiple people`() {
        composeTestRule.setContent {
            SharingStatusChip(
                sharingWithCount = 3,
                onStopAllClick = {},
            )
        }

        composeTestRule.onNodeWithText("Sharing with 3 people").assertIsDisplayed()
    }

    @Test
    fun `sharingStatusChip displays plural text for zero people`() {
        composeTestRule.setContent {
            SharingStatusChip(
                sharingWithCount = 0,
                onStopAllClick = {},
            )
        }

        composeTestRule.onNodeWithText("Sharing with 0 people").assertIsDisplayed()
    }

    @Test
    fun `sharingStatusChip displays stop button`() {
        composeTestRule.setContent {
            SharingStatusChip(
                sharingWithCount = 2,
                onStopAllClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Stop sharing").assertIsDisplayed()
    }

    @Test
    fun `sharingStatusChip stop button invokes callback`() {
        var stopCalled = false

        composeTestRule.setContent {
            SharingStatusChip(
                sharingWithCount = 2,
                onStopAllClick = { stopCalled = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("Stop sharing").performClick()

        assertTrue(stopCalled)
    }

    @Test
    fun `sharingStatusChip displays with large count`() {
        composeTestRule.setContent {
            SharingStatusChip(
                sharingWithCount = 100,
                onStopAllClick = {},
            )
        }

        composeTestRule.onNodeWithText("Sharing with 100 people").assertIsDisplayed()
    }
}
