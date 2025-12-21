package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.model.SharingDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for QuickShareLocationBottomSheet.
 *
 * Tests:
 * - Title and contact name display
 * - Duration chip selection
 * - Start sharing callback
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class QuickShareLocationBottomSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun `quickShareLocationBottomSheet displays title`() {
        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Share your location").assertIsDisplayed()
    }

    @Test
    fun `quickShareLocationBottomSheet displays contact name`() {
        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Bob",
                onDismiss = {},
                onStartSharing = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("with Bob").assertIsDisplayed()
    }

    @Test
    fun `quickShareLocationBottomSheet displays duration label`() {
        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Duration:").assertIsDisplayed()
    }

    @Test
    fun `quickShareLocationBottomSheet displays all duration chips`() {
        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("15 min").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 hour").assertIsDisplayed()
        composeTestRule.onNodeWithText("4 hours").assertIsDisplayed()
        composeTestRule.onNodeWithText("Until midnight").assertIsDisplayed()
        composeTestRule.onNodeWithText("Until I stop").assertIsDisplayed()
    }

    @Test
    fun `quickShareLocationBottomSheet displays start sharing button`() {
        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Start Sharing").assertIsDisplayed()
    }

    // ========== Duration Selection Tests ==========

    @Test
    fun `quickShareLocationBottomSheet default duration is one hour`() {
        var selectedDuration: SharingDuration? = null

        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = { selectedDuration = it },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertEquals(SharingDuration.ONE_HOUR, selectedDuration)
    }

    @Test
    fun `quickShareLocationBottomSheet select 15 min duration`() {
        var selectedDuration: SharingDuration? = null

        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = { selectedDuration = it },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("15 min").performClick()
        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertEquals(SharingDuration.FIFTEEN_MINUTES, selectedDuration)
    }

    @Test
    fun `quickShareLocationBottomSheet select 4 hours duration`() {
        var selectedDuration: SharingDuration? = null

        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = { selectedDuration = it },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("4 hours").performClick()
        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertEquals(SharingDuration.FOUR_HOURS, selectedDuration)
    }

    @Test
    fun `quickShareLocationBottomSheet select until midnight duration`() {
        var selectedDuration: SharingDuration? = null

        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = { selectedDuration = it },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Until midnight").performClick()
        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertEquals(SharingDuration.UNTIL_MIDNIGHT, selectedDuration)
    }

    @Test
    fun `quickShareLocationBottomSheet select indefinite duration`() {
        var selectedDuration: SharingDuration? = null

        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = { selectedDuration = it },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Until I stop").performClick()
        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertEquals(SharingDuration.INDEFINITE, selectedDuration)
    }

    // ========== Callback Tests ==========

    @Test
    fun `quickShareLocationBottomSheet start sharing invokes callback`() {
        var callbackInvoked = false

        composeTestRule.setContent {
            QuickShareLocationBottomSheet(
                contactName = "Alice",
                onDismiss = {},
                onStartSharing = { callbackInvoked = true },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertTrue(callbackInvoked)
    }
}
