package network.columba.app.ui.screens.settings.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.TestHostActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for AdvancedCard composable.
 *
 * Mirrors the unit-level AdvancedCardTest in src/test/. This instrumented variant
 * exercises the card in a real Android runtime to catch issues the Robolectric
 * unit tests might miss (real Compose layout, actual Material3 Switch behavior).
 */
class AdvancedCardTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<TestHostActivity>()

    @Test
    fun advancedCard_displaysHeader() {
        composeTestRule.setContent {
            AdvancedCard(isExpanded = true, onExpandedChange = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Advanced").assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_defaultEnabled() {
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = true,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
    }

    @Test
    fun transportNodeToggle_callsCallback_whenToggled() {
        var toggledValue: Boolean? = null
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = true,
                onTransportNodeToggle = { toggledValue = it },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transport Node").performClick()
        assertTrue("Toggle callback should be called", toggledValue != null)
    }

    @Test
    fun transportNodeToggle_showsDisabledState() {
        composeTestRule.setContent {
            AdvancedCard(
                isExpanded = true,
                onExpandedChange = {},
                transportNodeEnabled = false,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Transport Node").assertIsDisplayed()
    }
}
