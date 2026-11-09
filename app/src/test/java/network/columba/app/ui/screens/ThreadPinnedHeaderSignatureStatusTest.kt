package network.columba.app.ui.screens

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.ThreadHeaderUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadPinnedHeaderSignatureStatusTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private fun warning(@StringRes resource: Int) =
        hasContentDescription(ApplicationProvider.getApplicationContext<Application>().getString(resource))

    private val signatureError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    private fun header(status: SignatureStatus?, isFromMe: Boolean = false) =
        ThreadHeaderUi.Root(
            content = "Root message",
            isFromMe = isFromMe,
            timestamp = 1_000L,
            signatureStatus = status,
        )

    @Test
    fun `unknown and inconclusive roots warn without claiming signature failure`() {
        val state = mutableStateOf(header(SignatureStatus.SOURCE_UNKNOWN))
        composeRule.setContent {
            MaterialTheme { ThreadPinnedHeader(state.value, replyCount = 2) }
        }

        composeRule.onNode(warning(R.string.message_detail_signature_unknown)).assertIsDisplayed()
        composeRule.onAllNodes(signatureError).assertCountEquals(0)

        composeRule.runOnIdle { state.value = header(SignatureStatus.UNVERIFIED) }

        composeRule.onNode(warning(R.string.message_detail_signature_unverified)).assertIsDisplayed()
        composeRule.onAllNodes(warning(R.string.message_detail_signature_unknown)).assertCountEquals(0)
        composeRule.onAllNodes(signatureError).assertCountEquals(0)
    }

    @Test
    fun `invalid resolution replaces unknown warning with accessible error and verification removes it`() {
        val state = mutableStateOf(header(SignatureStatus.SOURCE_UNKNOWN))
        composeRule.setContent {
            MaterialTheme { ThreadPinnedHeader(state.value, replyCount = 2) }
        }
        composeRule.onNode(warning(R.string.message_detail_signature_unknown)).assertIsDisplayed()

        composeRule.runOnIdle { state.value = header(SignatureStatus.INVALID) }

        composeRule.onAllNodes(warning(R.string.message_detail_signature_unknown)).assertCountEquals(0)
        composeRule.onNode(warning(R.string.message_detail_signature_invalid)).assertIsDisplayed()
        composeRule.onNode(signatureError).assertIsDisplayed()

        composeRule.runOnIdle { state.value = header(SignatureStatus.VERIFIED) }

        composeRule.onAllNodes(warning(R.string.message_detail_signature_invalid)).assertCountEquals(0)
        composeRule.onAllNodes(signatureError).assertCountEquals(0)
    }

    @Test
    fun `legacy and sent roots have no signature warning`() {
        val state = mutableStateOf(header(null))
        composeRule.setContent {
            MaterialTheme { ThreadPinnedHeader(state.value, replyCount = 2) }
        }
        val anyWarning =
            warning(R.string.message_detail_signature_unknown) or
                warning(R.string.message_detail_signature_unverified) or
                warning(R.string.message_detail_signature_invalid) or signatureError

        composeRule.onAllNodes(anyWarning).assertCountEquals(0)

        SignatureStatus.entries.forEach { status ->
            composeRule.runOnIdle { state.value = header(status, isFromMe = true) }
            composeRule.onAllNodes(anyWarning).assertCountEquals(0)
        }
    }
}
