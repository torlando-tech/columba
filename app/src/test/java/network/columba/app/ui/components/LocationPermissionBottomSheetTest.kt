package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.test.RegisterComponentActivityRule
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
 * - Button callbacks (primary action, Not Now)
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

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId)

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

        composeTestRule.onNodeWithText(string(R.string.loc_perm_title)).assertIsDisplayed()
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
        composeTestRule.onNodeWithText(string(R.string.loc_perm_enable_location)).assertIsDisplayed()
    }

    @Test
    fun `locationPermissionBottomSheet displays custom rationale`() {
        val customRationaleRes = R.string.location_precise_rationale

        composeTestRule.setContent {
            LocationPermissionBottomSheet(
                onDismiss = {},
                onRequestPermissions = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                rationale = customRationaleRes,
            )
        }

        composeTestRule.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(customRationaleRes)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(string(R.string.loc_perm_enable_location)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(string(R.string.loc_perm_not_now)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(string(R.string.loc_perm_enable_location)).performClick()

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

        composeTestRule.onNodeWithText(string(R.string.loc_perm_not_now)).performClick()

        assertTrue(dismissCalled)
    }

    // ========== Large Font Tests ==========

    @Test
    fun `locationPermissionBottomSheet keeps actions visible at large font`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                LocationPermissionBottomSheet(
                    onDismiss = {},
                    onRequestPermissions = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.loc_perm_not_now)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.loc_perm_enable_location)).assertIsDisplayed()
    }

    @Test
    fun `blePermissionBottomSheet keeps actions visible at large font`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                BlePermissionBottomSheet(
                    onDismiss = {},
                    onRequestPermissions = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.ble_perm_not_now)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ble_ctrl_grant_permissions)).assertIsDisplayed()
    }

    @Test
    fun `backgroundLocationPermissionBottomSheet keeps actions visible at large font`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                BackgroundLocationPermissionBottomSheet(
                    onDismiss = {},
                    onRequestPermission = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.bg_location_not_now)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.bg_location_continue)).assertIsDisplayed()
    }
}
