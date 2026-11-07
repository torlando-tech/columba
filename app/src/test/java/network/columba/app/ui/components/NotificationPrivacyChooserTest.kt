package network.columba.app.ui.components

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for [NotificationPrivacyChooser], shared by Notification Settings and onboarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPrivacyChooserTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun chooser_displaysEveryLevel() {
        composeTestRule.setContent {
            NotificationPrivacyChooser(
                selected = NotificationPrivacyLevel.FULL,
                onSelect = {},
            )
        }

        NotificationPrivacyLevel.entries.forEach { level ->
            composeTestRule.onNodeWithText(level.displayName).assertIsDisplayed()
        }
    }

    @Test
    fun chooser_clickingLevelReportsThatLevel() {
        val selected = mutableStateOf(NotificationPrivacyLevel.FULL)
        composeTestRule.setContent {
            NotificationPrivacyChooser(
                selected = selected.value,
                onSelect = { selected.value = it },
            )
        }

        composeTestRule.onNodeWithText("No name or message").performClick()

        assertEquals(NotificationPrivacyLevel.NONE, selected.value)
    }

    @Test
    fun chooser_whenDisabledClicksAreIgnored() {
        val changes = mutableListOf<NotificationPrivacyLevel>()
        composeTestRule.setContent {
            NotificationPrivacyChooser(
                selected = NotificationPrivacyLevel.FULL,
                onSelect = { changes.add(it) },
                enabled = false,
            )
        }

        composeTestRule.onNodeWithText("Name only").performClick()

        assertTrue("Disabled chooser must not emit a selection", changes.isEmpty())
    }
}
