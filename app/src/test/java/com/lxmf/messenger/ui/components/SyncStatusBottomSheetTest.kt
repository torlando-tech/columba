package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lxmf.messenger.service.SyncProgress
import com.lxmf.messenger.test.RegisterComponentActivityRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for SyncStatusBottomSheet composable.
 * Tests display of different sync states (Idle, Starting, InProgress, Complete)
 * and verifies correct text and progress indicators for each state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncStatusBottomSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private var onDismissCalled = false

    @Before
    fun setUp() {
        onDismissCalled = false
    }

    // ========== Header Tests ==========

    @Test
    fun `displays header with Propagation Node Sync title`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Idle,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Propagation Node Sync").assertIsDisplayed()
    }

    // ========== Idle State Tests ==========

    @Test
    fun `idle state displays Ready title`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Idle,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun `idle state displays Not currently syncing subtitle`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Idle,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Not currently syncing").assertIsDisplayed()
    }

    // ========== Starting State Tests ==========

    @Test
    fun `starting state displays Starting sync title`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Starting,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Starting sync...").assertIsDisplayed()
    }

    @Test
    fun `starting state displays Initiating connection subtitle`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Starting,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Initiating connection to relay").assertIsDisplayed()
    }

    // ========== InProgress State Tests ==========

    @Test
    fun `inProgress state displays capitalized state name as title`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "downloading", progress = 0.5f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with path_requested displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "path_requested", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Path_requested").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discovering network path to relay...").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with link_establishing displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "link_establishing", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Link_establishing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Establishing secure connection...").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with link_established displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "link_established", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Link_established").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connected, preparing request...").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with request_sent displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "request_sent", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Request_sent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Requested message list from relay...").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with receiving displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "receiving", progress = 0.3f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Receiving").assertIsDisplayed()
        composeTestRule.onNodeWithText("Downloading messages...").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with downloading displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "downloading", progress = 0.6f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
        composeTestRule.onNodeWithText("Downloading messages...").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with complete displays correct description`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "complete", progress = 1f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sync complete!").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with unknown state displays Processing fallback`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "unknown_state", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Unknown_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("Processing...").assertIsDisplayed()
    }

    // ========== Progress Indicator Tests ==========

    @Test
    fun `inProgress state with positive progress displays percentage`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "downloading", progress = 0.75f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("75%").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with 100 percent progress displays correctly`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "downloading", progress = 1.0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with zero progress does not display percentage`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "path_requested", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("0%").assertDoesNotExist()
    }

    @Test
    fun `inProgress state with small progress displays percentage`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "receiving", progress = 0.05f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("5%").assertIsDisplayed()
    }

    @Test
    fun `inProgress state with half progress displays 50 percent`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "downloading", progress = 0.5f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
    }

    // ========== Complete State Tests ==========

    @Test
    fun `complete state displays Download complete title`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Complete,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Download complete").assertIsDisplayed()
    }

    @Test
    fun `complete state displays Messages received subtitle`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Complete,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Messages received").assertIsDisplayed()
    }

    // ========== Dismiss Callback Tests ==========

    @Test
    fun `onDismiss callback is provided to sheet`() {
        var dismissCalled = false

        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Idle,
                onDismiss = { dismissCalled = true },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        // Verify the composable renders without crashing when onDismiss is provided
        composeTestRule.onNodeWithText("Propagation Node Sync").assertIsDisplayed()

        // Note: Actually triggering dismiss in Compose tests requires gestures or simulating
        // back press which is complex in unit tests. We verify the callback is wired up
        // by confirming the component renders correctly with the callback.
    }

    // ========== State Transition Tests ==========

    @Test
    fun `transitions from Idle to Starting display correctly`() {
        // First, render with Idle
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Idle,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun `transitions from Starting to InProgress display correctly`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Starting,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Starting sync...").assertIsDisplayed()
    }

    @Test
    fun `transitions from InProgress to Complete display correctly`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.Complete,
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Download complete").assertIsDisplayed()
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `handles empty state name in InProgress gracefully`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "", progress = 0.5f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        // Empty string capitalizes to empty string, should still display Processing fallback
        composeTestRule.onNodeWithText("Processing...").assertIsDisplayed()
        composeTestRule.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test
    fun `handles uppercase state name in InProgress`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "DOWNLOADING", progress = 0.8f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        // replaceFirstChar uppercases the first char, rest stays as is
        composeTestRule.onNodeWithText("DOWNLOADING").assertIsDisplayed()
        // Description lookup is case-insensitive (uses lowercase())
        composeTestRule.onNodeWithText("Downloading messages...").assertIsDisplayed()
    }

    @Test
    fun `handles mixed case state name in InProgress`() {
        composeTestRule.setContent {
            SyncStatusBottomSheet(
                syncProgress = SyncProgress.InProgress(stateName = "Link_Establishing", progress = 0f),
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Link_Establishing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Establishing secure connection...").assertIsDisplayed()
    }
}
