package network.columba.app.ui.screens

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
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

    private fun text(@StringRes resource: Int) =
        hasText(ApplicationProvider.getApplicationContext<Application>().getString(resource))

    private fun render(status: SignatureStatus) {
        composeRule.setContent {
            MaterialTheme {
                MessageSignatureInfoCard(status)
            }
        }
    }

    @Test
    fun `unknown identity has its own status and automatic verification explanation`() {
        render(SignatureStatus.SOURCE_UNKNOWN)

        composeRule.onNode(text(R.string.message_detail_signature_unknown)).assertIsDisplayed()
        composeRule.onNode(text(R.string.message_detail_signature_unknown_detail)).assertIsDisplayed()
        composeRule.onAllNodes(text(R.string.message_detail_signature_invalid)).assertCountEquals(0)
    }

    @Test
    fun `invalid signature explains failed check against known identity`() {
        render(SignatureStatus.INVALID)

        composeRule.onNode(text(R.string.message_detail_signature_invalid)).assertIsDisplayed()
        composeRule.onNode(text(R.string.message_detail_signature_invalid_detail)).assertIsDisplayed()
        composeRule.onAllNodes(text(R.string.message_detail_signature_unknown)).assertCountEquals(0)
    }

    @Test
    fun `unverified result does not claim confirmed failure`() {
        render(SignatureStatus.UNVERIFIED)

        composeRule.onNode(text(R.string.message_detail_signature_unverified)).assertIsDisplayed()
        composeRule.onNode(text(R.string.message_detail_signature_unverified_detail)).assertIsDisplayed()
        composeRule.onAllNodes(text(R.string.message_detail_signature_invalid)).assertCountEquals(0)
    }

    @Test
    fun `new verification replaces unknown identity explanation while details remain open`() {
        val status = mutableStateOf(SignatureStatus.SOURCE_UNKNOWN)
        composeRule.setContent {
            MaterialTheme {
                MessageSignatureInfoCard(status.value)
            }
        }
        composeRule.onNode(text(R.string.message_detail_signature_unknown_detail)).assertIsDisplayed()

        composeRule.runOnIdle { status.value = SignatureStatus.VERIFIED }

        composeRule.onAllNodes(text(R.string.message_detail_signature_unknown)).assertCountEquals(0)
        composeRule.onAllNodes(text(R.string.message_detail_signature_unknown_detail)).assertCountEquals(0)
        composeRule.onNode(text(R.string.message_detail_signature_verified)).assertIsDisplayed()
        composeRule.onNode(text(R.string.message_detail_signature_verified_detail)).assertIsDisplayed()
    }
}
