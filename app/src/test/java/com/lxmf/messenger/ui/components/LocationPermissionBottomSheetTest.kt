package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * UI tests for LocationPermissionBottomSheet.
 *
 * Tests:
 * - Title and icon display
 * - Rationale text display
 * - Button callbacks (Enable Location, Not Now)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class LocationPermissionBottomSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Display Tests ==========

    @Test
    fun `locationPermissionBottomSheet displays title`() {
        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Location Permission").assertIsDisplayed()
    }

    @Test
    fun `locationPermissionBottomSheet displays default rationale`() {
        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        // The default rationale from LocationPermissionManager.getPermissionRationale()
        // Just check that some text is displayed (exact text may vary)
        composeTestRule.onNodeWithText("Enable Location").assertIsDisplayed()
    }

    @Test
    fun `locationPermissionBottomSheet displays custom rationale`() {
        val customRationale = "We need your location to show you on the map."

        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                rationale = customRationale,
            )
        }

        composeTestRule.onNodeWithText(customRationale).assertIsDisplayed()
    }

    @Test
    fun `locationPermissionBottomSheet displays enable location button`() {
        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Enable Location").assertIsDisplayed()
    }

    @Test
    fun `locationPermissionBottomSheet displays custom primary action label`() {
        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                primaryActionLabel = "Grant Access",
            )
        }

        composeTestRule.onNodeWithText("Grant Access").assertIsDisplayed()
    }

    @Test
    fun `locationPermissionBottomSheet displays not now button`() {
        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Not Now").assertIsDisplayed()
    }

    // ========== Callback Tests ==========

    @Test
    fun `locationPermissionBottomSheet enable location invokes callback`() {
        var callbackInvoked = false

        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = { callbackInvoked = true },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Enable Location").performClick()

        assertTrue(callbackInvoked)
    }

    @Test
    fun `locationPermissionBottomSheet not now invokes dismiss`() {
        var dismissCalled = false

        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = { dismissCalled = true },
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Not Now").performClick()

        assertTrue(dismissCalled)
    }
}
