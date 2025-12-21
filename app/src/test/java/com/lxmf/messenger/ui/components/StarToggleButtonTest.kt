package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for StarToggleButton composable.
 * Tests the star toggle button used for adding/removing contacts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StarToggleButtonTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun starToggleButton_whenNotStarred_showsSaveToContactsDescription() {
        // Given
        composeTestRule.setContent {
            StarToggleButton(
                isStarred = false,
                onClick = {},
            )
        }

        // Then - shows "Save to contacts" content description
        composeTestRule.onNodeWithContentDescription("Save to contacts").assertIsDisplayed()
    }

    @Test
    fun starToggleButton_whenStarred_showsRemoveFromContactsDescription() {
        // Given
        composeTestRule.setContent {
            StarToggleButton(
                isStarred = true,
                onClick = {},
            )
        }

        // Then - shows "Remove from contacts" content description
        composeTestRule.onNodeWithContentDescription("Remove from contacts").assertIsDisplayed()
    }

    // ========== Interaction Tests ==========

    @Test
    fun starToggleButton_whenClicked_callsOnClick() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            StarToggleButton(
                isStarred = false,
                onClick = { clickCount++ },
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Save to contacts").performClick()

        // Then
        assertEquals(1, clickCount)
    }

    @Test
    fun starToggleButton_whenStarred_clickCallsOnClick() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            StarToggleButton(
                isStarred = true,
                onClick = { clicked = true },
            )
        }

        // When
        composeTestRule.onNodeWithContentDescription("Remove from contacts").performClick()

        // Then
        assertTrue(clicked)
    }

    @Test
    fun starToggleButton_multipleClicks_callsOnClickEachTime() {
        // Given
        var clickCount = 0
        composeTestRule.setContent {
            StarToggleButton(
                isStarred = false,
                onClick = { clickCount++ },
            )
        }

        // When - click multiple times
        repeat(3) {
            composeTestRule.onNodeWithContentDescription("Save to contacts").performClick()
        }

        // Then
        assertEquals(3, clickCount)
    }

    // ========== State Change Tests ==========

    @Test
    fun starToggleButton_stateChange_updatesContentDescription() {
        // Given - use mutableStateOf for recomposition
        val isStarred = androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            StarToggleButton(
                isStarred = isStarred.value,
                onClick = { isStarred.value = !isStarred.value },
            )
        }

        // Initially shows "Save to contacts"
        composeTestRule.onNodeWithContentDescription("Save to contacts").assertIsDisplayed()

        // When - toggle state (triggers recomposition)
        isStarred.value = true
        composeTestRule.waitForIdle()

        // Then - shows "Remove from contacts"
        composeTestRule.onNodeWithContentDescription("Remove from contacts").assertIsDisplayed()
    }
}
