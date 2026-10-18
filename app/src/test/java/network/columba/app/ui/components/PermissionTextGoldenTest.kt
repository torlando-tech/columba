package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.junit4.createComposeRule
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.util.LocationPermissionManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for the permission-related components: [PermissionDeniedCard],
 * [LocationPermissionBottomSheet], [BackgroundLocationPermissionBottomSheet],
 * [BlePermissionBottomSheet] and [PreciseLocationPermissionPrompt].
 *
 * Each test renders one component state with fixed inputs and records the
 * visible strings via [TextGoldens]; goldens are (re)recorded with
 * `-PrecordTexts` and asserted on every subsequent run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class PermissionTextGoldenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== PermissionDeniedCard ==========

    @Test
    fun `permission denied card default message`() {
        composeRule.setContent {
            MaterialTheme {
                PermissionDeniedCard()
            }
        }
        TextGoldens.assertGolden("permission_denied_card_default", composeRule)
    }

    @Test
    fun `permission denied card custom message`() {
        composeRule.setContent {
            MaterialTheme {
                PermissionDeniedCard(message = CUSTOM_MESSAGE)
            }
        }
        TextGoldens.assertGolden("permission_denied_card_custom_message", composeRule)
    }

    @Test
    fun `permission denied card without settings button`() {
        composeRule.setContent {
            MaterialTheme {
                PermissionDeniedCard(showSettingsButton = false)
            }
        }
        TextGoldens.assertGolden("permission_denied_card_no_settings_button", composeRule)
    }

    // ========== Location permission bottom sheets ==========

    @Test
    fun `location permission bottom sheet`() {
        composeRule.setContent {
            MaterialTheme {
                LocationPermissionBottomSheet(
                    onDismiss = {},
                    onRequestPermissions = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("location_permission_bottom_sheet", composeRule)
    }

    @Test
    fun `background location permission bottom sheet`() {
        composeRule.setContent {
            MaterialTheme {
                BackgroundLocationPermissionBottomSheet(
                    onDismiss = {},
                    onRequestPermission = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("background_location_permission_bottom_sheet", composeRule)
    }

    @Test
    fun `ble permission bottom sheet`() {
        composeRule.setContent {
            MaterialTheme {
                BlePermissionBottomSheet(
                    onDismiss = {},
                    onRequestPermissions = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                )
            }
        }
        TextGoldens.assertGolden("ble_permission_bottom_sheet", composeRule)
    }

    // ========== PreciseLocationPermissionPrompt ==========

    @Test
    fun `precise location permission prompt shows when sharing enabled`() {
        composeRule.setContent {
            MaterialTheme {
                PreciseLocationPermissionPrompt(
                    locationSharingEnabled = true,
                    locationPrecisionRadius = LocationPermissionManager.PRECISE_PRECISION_RADIUS,
                    enabled = true,
                    dismissed = false,
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("precise_location_permission_prompt_sharing_enabled", composeRule)
    }

    @Test
    fun `precise location permission prompt hidden when sharing disabled`() {
        composeRule.setContent {
            MaterialTheme {
                PreciseLocationPermissionPrompt(
                    locationSharingEnabled = false,
                    locationPrecisionRadius = LocationPermissionManager.PRECISE_PRECISION_RADIUS,
                    enabled = true,
                    dismissed = false,
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("precise_location_permission_prompt_sharing_disabled", composeRule)
    }

    @Test
    fun `precise location permission prompt hidden when gate disabled`() {
        composeRule.setContent {
            MaterialTheme {
                PreciseLocationPermissionPrompt(
                    locationSharingEnabled = true,
                    locationPrecisionRadius = LocationPermissionManager.PRECISE_PRECISION_RADIUS,
                    enabled = false,
                    dismissed = false,
                    onDismiss = {},
                )
            }
        }
        TextGoldens.assertGolden("precise_location_permission_prompt_gate_disabled", composeRule)
    }

    private companion object {
        const val CUSTOM_MESSAGE =
            "Location permissions were permanently denied. Open settings to enable them."
    }
}
