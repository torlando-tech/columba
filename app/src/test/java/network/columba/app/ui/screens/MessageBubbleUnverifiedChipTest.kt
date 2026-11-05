package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.MessageUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The unverified-sender warning is the only signal that an inbound LXMF message
 * might be forged (for a sender whose identity we haven't seen, the signature
 * can't be checked). It must render above the bubble for exactly
 * `signatureVerified == false` — never for `true` or legacy `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageBubbleUnverifiedChipTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val chipText = "Unverified sender — may be forged"

    private fun message(signatureVerified: Boolean?) =
        MessageUi(
            id = "m1",
            destinationHash = "abc123",
            content = "hello",
            timestamp = 1_000L,
            isFromMe = false,
            status = "delivered",
            signatureVerified = signatureVerified,
        )

    private fun render(message: MessageUi) {
        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    isFromMe = false,
                    clipboardManager = LocalClipboardManager.current,
                )
            }
        }
    }

    @Test
    fun `unverified message shows the forgery warning above the bubble`() {
        render(message(signatureVerified = false))

        composeRule.onNodeWithText(chipText).assertIsDisplayed()
        composeRule.onNodeWithText("hello").assertIsDisplayed()
    }

    @Test
    fun `verified message shows no warning`() {
        render(message(signatureVerified = true))

        composeRule.onNodeWithText(chipText).assertDoesNotExist()
    }

    @Test
    fun `legacy message with null signature shows no warning`() {
        render(message(signatureVerified = null))

        composeRule.onNodeWithText(chipText).assertDoesNotExist()
    }
}
