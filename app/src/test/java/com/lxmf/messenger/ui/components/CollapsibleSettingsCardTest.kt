package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * UI tests for CollapsibleSettingsCard composable.
 * Tests the collapsible card component used in the Settings screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollapsibleSettingsCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun collapsibleSettingsCard_displaysTitle() {
        // Given
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = {},
            ) {
                Text("Content")
            }
        }

        // Then
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun collapsibleSettingsCard_whenCollapsed_showsExpandContentDescription() {
        // Given
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = {},
            ) {
                Text("Content")
            }
        }

        // Then - shows "Expand" content description on chevron
        composeTestRule.onNodeWithContentDescription("Expand").assertIsDisplayed()
    }

    @Test
    fun collapsibleSettingsCard_whenExpanded_showsCollapseContentDescription() {
        // Given
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = true,
                onExpandedChange = {},
            ) {
                Text("Content")
            }
        }

        // Then - shows "Collapse" content description on chevron
        composeTestRule.onNodeWithContentDescription("Collapse").assertIsDisplayed()
    }

    @Test
    fun collapsibleSettingsCard_whenExpanded_showsContent() {
        // Given
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = true,
                onExpandedChange = {},
            ) {
                Text("Expanded Content Here")
            }
        }

        // Then
        composeTestRule.onNodeWithText("Expanded Content Here").assertIsDisplayed()
    }

    @Test
    fun collapsibleSettingsCard_whenCollapsed_hidesContent() {
        // Given
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = {},
            ) {
                Text("Expanded Content Here")
            }
        }

        // Then - content should not exist when collapsed
        composeTestRule.onNodeWithText("Expanded Content Here").assertDoesNotExist()
    }

    // ========== Chevron Click Tests (Issue #305 fix) ==========

    @Test
    fun collapsibleSettingsCard_chevronClick_whenCollapsed_callsOnExpandedChangeWithTrue() {
        // Given
        var expandedChangeValue: Boolean? = null
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = { expandedChangeValue = it },
            ) {
                Text("Content")
            }
        }

        // When - click the chevron (identified by content description)
        composeTestRule.onNodeWithContentDescription("Expand").performClick()

        // Then - onExpandedChange should be called with true (to expand)
        assertEquals(true, expandedChangeValue)
    }

    @Test
    fun collapsibleSettingsCard_chevronClick_whenExpanded_callsOnExpandedChangeWithFalse() {
        // Given
        var expandedChangeValue: Boolean? = null
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = true,
                onExpandedChange = { expandedChangeValue = it },
            ) {
                Text("Content")
            }
        }

        // When - click the chevron (identified by content description)
        composeTestRule.onNodeWithContentDescription("Collapse").performClick()

        // Then - onExpandedChange should be called with false (to collapse)
        assertEquals(false, expandedChangeValue)
    }

    // ========== Title Click Tests ==========

    @Test
    fun collapsibleSettingsCard_titleClick_whenCollapsed_callsOnExpandedChange() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = { clicked = true },
            ) {
                Text("Content")
            }
        }

        // When - click the title
        composeTestRule.onNodeWithText("Notifications").performClick()

        // Then
        assertTrue(clicked)
    }

    @Test
    fun collapsibleSettingsCard_titleClick_whenExpanded_callsOnExpandedChange() {
        // Given
        var clicked = false
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = true,
                onExpandedChange = { clicked = true },
            ) {
                Text("Content")
            }
        }

        // When - click the title
        composeTestRule.onNodeWithText("Notifications").performClick()

        // Then
        assertTrue(clicked)
    }

    // ========== State Change Tests ==========

    @Test
    fun collapsibleSettingsCard_stateChange_updatesChevronContentDescription() {
        // Given
        val isExpanded = mutableStateOf(false)

        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = isExpanded.value,
                onExpandedChange = { isExpanded.value = it },
            ) {
                Text("Content")
            }
        }

        // Initially shows "Expand"
        composeTestRule.onNodeWithContentDescription("Expand").assertIsDisplayed()

        // When - expand
        isExpanded.value = true
        composeTestRule.waitForIdle()

        // Then - shows "Collapse"
        composeTestRule.onNodeWithContentDescription("Collapse").assertIsDisplayed()
    }

    @Test
    fun collapsibleSettingsCard_stateChange_showsAndHidesContent() {
        // Given
        val isExpanded = mutableStateOf(false)

        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = isExpanded.value,
                onExpandedChange = { isExpanded.value = it },
            ) {
                Text("Expanded Content")
            }
        }

        // Initially content is hidden
        composeTestRule.onNodeWithText("Expanded Content").assertDoesNotExist()

        // When - expand
        isExpanded.value = true
        composeTestRule.waitForIdle()

        // Then - content is visible
        composeTestRule.onNodeWithText("Expanded Content").assertIsDisplayed()

        // When - collapse
        isExpanded.value = false
        composeTestRule.waitForIdle()

        // Then - content is hidden again
        composeTestRule.onNodeWithText("Expanded Content").assertDoesNotExist()
    }

    // ========== Header Action Tests ==========

    @Test
    fun collapsibleSettingsCard_withHeaderAction_displaysHeaderAction() {
        // Given
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = {},
                headerAction = {
                    Switch(checked = true, onCheckedChange = {})
                },
            ) {
                Text("Content")
            }
        }

        // Then - title and chevron should both be displayed alongside header action
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand").assertIsDisplayed()
    }

    @Test
    fun collapsibleSettingsCard_chevronClickWithHeaderAction_stillWorks() {
        // Given - card with a header action (switch)
        var expandedChangeCount = 0
        composeTestRule.setContent {
            CollapsibleSettingsCard(
                title = "Notifications",
                icon = Icons.Default.Notifications,
                isExpanded = false,
                onExpandedChange = { expandedChangeCount++ },
                headerAction = {
                    Switch(checked = true, onCheckedChange = {})
                },
            ) {
                Text("Content")
            }
        }

        // When - click the chevron
        composeTestRule.onNodeWithContentDescription("Expand").performClick()

        // Then - onExpandedChange should be called
        assertEquals(1, expandedChangeCount)
    }
}
