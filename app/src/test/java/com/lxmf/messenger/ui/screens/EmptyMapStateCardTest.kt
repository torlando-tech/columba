package com.lxmf.messenger.ui.screens

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
 * UI tests for EmptyMapStateCard.
 *
 * Tests:
 * - Displays location permission required message
 * - Displays dismiss button
 * - Dismiss button invokes callback
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmptyMapStateCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun `emptyMapStateCard displays title text`() {
        composeTestRule.setContent {
            EmptyMapStateCard(
                contactCount = 0,
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Location permission required").assertIsDisplayed()
    }

    @Test
    fun `emptyMapStateCard displays description text`() {
        composeTestRule.setContent {
            EmptyMapStateCard(
                contactCount = 0,
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Enable location access to see your position on the map.")
            .assertIsDisplayed()
    }

    @Test
    fun `emptyMapStateCard displays dismiss button`() {
        composeTestRule.setContent {
            EmptyMapStateCard(
                contactCount = 0,
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
    }

    // ========== Callback Tests ==========

    @Test
    fun `emptyMapStateCard dismiss button invokes callback`() {
        var dismissed = false

        composeTestRule.setContent {
            EmptyMapStateCard(
                contactCount = 0,
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("Dismiss").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun `emptyMapStateCard dismiss button can be clicked multiple times`() {
        var dismissCount = 0

        composeTestRule.setContent {
            EmptyMapStateCard(
                contactCount = 0,
                onDismiss = { dismissCount++ },
            )
        }

        composeTestRule.onNodeWithContentDescription("Dismiss").performClick()
        composeTestRule.onNodeWithContentDescription("Dismiss").performClick()

        assertTrue(dismissCount >= 2)
    }
}
