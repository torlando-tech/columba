package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.viewmodel.UiMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for reusable settings/wizard chrome: the collapsible settings
 * card, wizard selection card and bottom bar, the IFAC credential card, and
 * the propagation-node priority list.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens]. The relay
 * failure timestamp is pinned relative to [TextGoldens.FIXED_NOW_MILLIS] so
 * the "5 minutes ago" suffix renders deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== CollapsibleSettingsCard ==========

    @Test
    fun `collapsible settings card expanded`() {
        composeRule.setContent {
            MaterialTheme {
                CollapsibleSettingsCard(
                    title = "Notifications",
                    icon = Icons.Default.Notifications,
                    isExpanded = true,
                    onExpandedChange = {},
                    headerAction = { Switch(checked = true, onCheckedChange = {}) },
                ) {
                    Text("Quiet hours from 22:00 to 07:00")
                }
            }
        }
        TextGoldens.assertGolden("collapsible_settings_card_expanded", composeRule)
    }

    @Test
    fun `collapsible settings card collapsed`() {
        composeRule.setContent {
            MaterialTheme {
                CollapsibleSettingsCard(
                    title = "Notifications",
                    icon = Icons.Default.Notifications,
                    isExpanded = false,
                    onExpandedChange = {},
                ) {
                    Text("Quiet hours from 22:00 to 07:00")
                }
            }
        }
        TextGoldens.assertGolden("collapsible_settings_card_collapsed", composeRule)
    }

    // ========== CustomSettingsCard ==========

    @Test
    fun `custom settings card selected`() {
        composeRule.setContent {
            MaterialTheme {
                CustomSettingsCard(
                    title = "Custom Settings",
                    description = "Enter your own server details",
                    isSelected = true,
                    onClick = {},
                )
            }
        }
        TextGoldens.assertGolden("custom_settings_card_selected", composeRule)
    }

    @Test
    fun `custom settings card unselected`() {
        composeRule.setContent {
            MaterialTheme {
                CustomSettingsCard(
                    title = "Custom Settings",
                    description = "Enter your own server details",
                    isSelected = false,
                    onClick = {},
                )
            }
        }
        TextGoldens.assertGolden("custom_settings_card_unselected", composeRule)
    }

    // ========== WizardBottomBar ==========

    @Test
    fun `wizard bottom bar first step`() {
        composeRule.setContent {
            MaterialTheme {
                WizardBottomBar(
                    currentStepIndex = 0,
                    totalSteps = 3,
                    buttonText = "Next",
                    canProceed = true,
                    isSaving = false,
                    onButtonClick = {},
                )
            }
        }
        TextGoldens.assertGolden("wizard_bottom_bar_first_step", composeRule)
    }

    @Test
    fun `wizard bottom bar last step`() {
        composeRule.setContent {
            MaterialTheme {
                WizardBottomBar(
                    currentStepIndex = 2,
                    totalSteps = 3,
                    buttonText = "Save",
                    canProceed = false,
                    isSaving = false,
                    onButtonClick = {},
                )
            }
        }
        TextGoldens.assertGolden("wizard_bottom_bar_last_step", composeRule)
    }

    @Test
    fun `wizard bottom bar saving`() {
        composeRule.setContent {
            MaterialTheme {
                WizardBottomBar(
                    currentStepIndex = 2,
                    totalSteps = 3,
                    buttonText = "Save",
                    canProceed = true,
                    isSaving = true,
                    onButtonClick = {},
                )
            }
        }
        TextGoldens.assertGolden("wizard_bottom_bar_saving", composeRule)
    }

    // ========== IfacConfigCard ==========

    @Test
    fun `ifac config card passphrase hidden`() {
        composeRule.setContent {
            MaterialTheme {
                IfacConfigCard(
                    networkName = "columba-net",
                    passphrase = "hunter2-passphrase",
                    passphraseVisible = false,
                    onNetworkNameChange = {},
                    onPassphraseChange = {},
                    onPassphraseVisibilityToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("ifac_config_card_passphrase_hidden", composeRule)
    }

    @Test
    fun `ifac config card passphrase visible`() {
        composeRule.setContent {
            MaterialTheme {
                IfacConfigCard(
                    networkName = "columba-net",
                    passphrase = "hunter2-passphrase",
                    passphraseVisible = true,
                    onNetworkNameChange = {},
                    onPassphraseChange = {},
                    onPassphraseVisibilityToggle = {},
                )
            }
        }
        TextGoldens.assertGolden("ifac_config_card_passphrase_visible", composeRule)
    }

    // ========== PropagationNodeReorderList ==========

    @Test
    fun `propagation node reorder list`() {
        composeRule.setContent {
            MaterialTheme {
                PropagationNodeReorderList(
                    items = propagationNodeRows(),
                    onReorder = {},
                    onRestrictionChange = { _, _ -> },
                    onRemove = {},
                )
            }
        }
        TextGoldens.assertGolden("propagation_node_reorder_list", composeRule)
    }

    private companion object {
        /** Now minus 5.5 minutes -> stably renders "5 minutes ago". */
        private const val FAILURE_AGE_MILLIS = 5 * 60 * 1000L + 30 * 1000L

        /** Active row + a failed row (code 9 -> NO_PATH) + a plain restricted row. */
        fun propagationNodeRows(): List<PropagationNodeRow> =
            listOf(
                PropagationNodeRow("alpha", "Alpha", "ANY", isActive = true),
                PropagationNodeRow(
                    destinationHash = "beta",
                    displayName = "Beta",
                    networkRestriction = "WIFI_ONLY",
                    failureReason = UiMessage.Raw("No path to relay"),
                    failureAtMs = System.currentTimeMillis() - FAILURE_AGE_MILLIS,
                    failureCode = 9,
                ),
                PropagationNodeRow("gamma", "Gamma", "CELLULAR_ONLY"),
            )
    }
}
