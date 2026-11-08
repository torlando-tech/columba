package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageSignatureInfoCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private fun render(status: SignatureStatus) {
        composeRule.setContent {
            MaterialTheme {
                MessageSignatureInfoCard(status)
            }
        }
    }

    @Test
    fun `unknown identity explains unavailable key and automatic verification`() {
        render(SignatureStatus.SOURCE_UNKNOWN)

        composeRule.onNode(hasText("key", substring = true) and hasText("not", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("checked", substring = true) and hasText("not", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("automatically", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `invalid signature explains failed check against known identity`() {
        render(SignatureStatus.INVALID)

        composeRule.onNode(hasText("failed", substring = true) and hasText("known", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("identity", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("forged", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `unverified result does not claim confirmed failure`() {
        render(SignatureStatus.UNVERIFIED)

        composeRule.onNode(hasText("authenticated", substring = true) and hasText("not", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("failure", substring = true) and hasText("not", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `new verification replaces unknown identity explanation while details remain open`() {
        val status = mutableStateOf(SignatureStatus.SOURCE_UNKNOWN)
        composeRule.setContent {
            MaterialTheme {
                MessageSignatureInfoCard(status.value)
            }
        }
        composeRule.onNode(hasText("not available", substring = true)).assertIsDisplayed()

        composeRule.runOnIdle { status.value = SignatureStatus.VERIFIED }

        composeRule.onAllNodes(hasText("not available", substring = true)).assertCountEquals(0)
        composeRule.onNode(hasText("signature matches", substring = true)).assertIsDisplayed()
    }
}
