package network.columba.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.ThreadPillUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI tests for the message header's thread pill (threads-spec §5.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadPillTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun `main target shows the Main label`() {
        composeTestRule.setContent {
            MaterialTheme {
                ThreadPill(pill = ThreadPillUi(isMain = true), onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Main").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Switch thread: Main")
            .assertIsDisplayed()
    }

    @Test
    fun `thread target shows its title and unread count in the label`() {
        composeTestRule.setContent {
            MaterialTheme {
                ThreadPill(
                    pill = ThreadPillUi(isMain = false, title = "Trip planning", unreadCount = 3),
                    onClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Trip planning").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Switch thread: Trip planning, 3 unread")
            .assertIsDisplayed()
    }

    @Test
    fun `thread without a title falls back to the new-thread default`() {
        composeTestRule.setContent {
            MaterialTheme {
                ThreadPill(pill = ThreadPillUi(isMain = false, title = null), onClick = {})
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Switch thread: New thread")
            .assertIsDisplayed()
    }

    @Test
    fun `tapping the pill runs the click callback`() {
        var clicks = 0
        composeTestRule.setContent {
            MaterialTheme {
                ThreadPill(pill = ThreadPillUi(isMain = true), onClick = { clicks++ })
            }
        }

        composeTestRule.onNodeWithContentDescription("Switch thread: Main").performClick()
        assert(clicks == 1)
    }
}
