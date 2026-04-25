package network.columba.app.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AdvancedCard composable using Robolectric.
 *
 * Tests the Transport Node toggle that was relocated here from NetworkCard — the
 * card lives between RNode Flasher and About on the Settings page so it's out of
 * the way for typical users but findable for power users.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdvancedCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Header and Content Tests ==========

    @Test
    fun advancedCard_displaysHeader() {
        composeTestRule.setContent {
            AdvancedCard(isExpanded = true, onExpandedChange = {})
        }
        composeTestRule.onNodeWithText("Advanced").assertIsDisplayed()
    }

    // ========== Transport Node Toggle Tests ==========

    @Test
    fun transportNodeToggle_displaysLabel() {
        composeTestRule.setContent {
            AdvancedCard(isExpanded = true, onExpandedChange = {})
        }
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_displaysDescription() {
        composeTestRule.setContent {
            AdvancedCard(isExpanded = true, onExpandedChange = {})
        }
        composeTestRule
            .onNodeWithText(
                "Forward traffic for the mesh network. When disabled, this device will only " +
                    "handle its own traffic and won't relay messages for other peers. " +
                    "It's generally not recommended for mobile devices to be transport nodes. " +
                    "They are less likely to maintain a fixed position in the network, and thus " +
                    "can negatively impact multihop routing. Enabling this will increase data " +
                    "usage and battery drain. However, in a BLE-only mesh, it's required for " +
                    "multi-hop messaging.",
            ).assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_isOn_whenEnabled() {
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = true,
            )
        }
        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun transportNodeToggle_isOff_whenDisabled() {
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = false,
            )
        }
        composeTestRule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun transportNodeToggle_callsCallback_withFalse_whenTurningOff() {
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = true,
                onTransportNodeToggle = { receivedValue = it },
            )
        }
        composeTestRule.onNode(isToggleable()).performClick()
        assertEquals(false, receivedValue)
    }

    @Test
    fun transportNodeToggle_callsCallback_withTrue_whenTurningOn() {
        var receivedValue: Boolean? = null
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = false,
                onTransportNodeToggle = { receivedValue = it },
            )
        }
        composeTestRule.onNode(isToggleable()).performClick()
        assertEquals(true, receivedValue)
    }

    @Test
    fun transportNodeToggle_callbackCalledOnce() {
        var callCount = 0
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = true,
                onTransportNodeToggle = { callCount++ },
            )
        }
        composeTestRule.onNode(isToggleable()).performClick()
        assertEquals("Callback should be called exactly once", 1, callCount)
    }
}
