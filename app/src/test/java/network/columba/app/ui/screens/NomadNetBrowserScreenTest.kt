package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NomadNetBrowserScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    @Test
    fun browserCloseSiteMenuItem_displaysCloseSiteText() {
        composeTestRule.setContent {
            val expanded = mutableStateOf(true)
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                BrowserCloseSiteMenuItem(onCloseSite = {})
            }
        }

        composeTestRule.onNodeWithText("Close site").assertIsDisplayed()
    }

    @Test
    fun browserCloseSiteMenuItem_whenClicked_invokesOnCloseSiteExactlyOnce() {
        var calls = 0

        composeTestRule.setContent {
            val expanded = mutableStateOf(true)
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                BrowserCloseSiteMenuItem(onCloseSite = { calls++ })
            }
        }

        composeTestRule.onNodeWithText("Close site").performClick()

        assertEquals(1, calls)
    }

    @Test
    fun browserCloseSiteMenuItem_doesNotRenderIdentifyToNodeText() {
        composeTestRule.setContent {
            val expanded = mutableStateOf(true)
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                BrowserCloseSiteMenuItem(onCloseSite = {})
            }
        }

        composeTestRule.onNodeWithText("Identify to node").assertDoesNotExist()
        composeTestRule.onNodeWithText("Identify to Node").assertDoesNotExist()
    }
}
