package network.columba.app.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.rns.api.model.BatteryProfile
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for BatteryOptimizationCard composable using Robolectric.
 *
 * Covers the Persistent Wake Lock toggle that lives on the "Battery &
 * Background" card — the natural home for the mesh daemon's CPU wake
 * policy next to the battery-profile picker and the OS exemption section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatteryOptimizationCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private fun toggleFor(tag: String) = composeTestRule.onNodeWithTag(tag)

    @Test
    fun persistentWakeLockToggle_displaysLabelAndDescription() {
        composeTestRule.setContent {
            BatteryOptimizationCard(
                isExpanded = true,
                onExpandedChange = {},
                batteryProfile = BatteryProfile.BALANCED,
                onBatteryProfileChange = {},
            )
        }
        composeTestRule.onNodeWithText("Persistent Wake Lock").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Keep the CPU awake so incoming mesh packets are processed the " +
                    "instant they arrive. On (default) is the most responsive but " +
                    "drains battery while backgrounded all day. Off lets the CPU " +
                    "sleep between traffic; a short lock still covers active " +
                    "transfers, and delivery resumes within seconds of each wake. " +
                    "Changes apply immediately.",
            ).assertIsDisplayed()
    }

    @Test
    fun persistentWakeLockToggle_isOn_whenEnabled() {
        composeTestRule.setContent {
            BatteryOptimizationCard(
                isExpanded = true,
                onExpandedChange = {},
                batteryProfile = BatteryProfile.BALANCED,
                onBatteryProfileChange = {},
                persistentWakeLockEnabled = true,
            )
        }
        toggleFor("persistent_wake_lock_toggle").assertIsOn()
    }

    @Test
    fun persistentWakeLockToggle_isOff_whenDisabled() {
        composeTestRule.setContent {
            BatteryOptimizationCard(
                isExpanded = true,
                onExpandedChange = {},
                batteryProfile = BatteryProfile.BALANCED,
                onBatteryProfileChange = {},
                persistentWakeLockEnabled = false,
            )
        }
        toggleFor("persistent_wake_lock_toggle").assertIsOff()
    }

    @Test
    fun persistentWakeLockToggle_callsCallback_withFalse_whenTurningOff() {
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            BatteryOptimizationCard(
                isExpanded = true,
                onExpandedChange = {},
                batteryProfile = BatteryProfile.BALANCED,
                onBatteryProfileChange = {},
                persistentWakeLockEnabled = true,
                onPersistentWakeLockToggle = { receivedValue = it },
            )
        }
        toggleFor("persistent_wake_lock_toggle").performClick()
        assertEquals(false, receivedValue)
    }
}
