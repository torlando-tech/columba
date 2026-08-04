package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.IdentityUnlockUiState
import network.columba.app.viewmodel.IdentityUnlockViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityUnlockScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun hashMismatchIsFailClosedAndDoesNotOfferReplacement() {
        val viewModel = mockk<IdentityUnlockViewModel>()
        every { viewModel.uiState } returns
            MutableStateFlow(
                IdentityUnlockUiState.HashMismatch(
                    importedHash = "11111111111111111111111111111111",
                    activeHash = "22222222222222222222222222222222",
                ),
            )
        every { viewModel.activeIdentity } returns MutableStateFlow(null)
        every { viewModel.cancelHashMismatch() } just Runs

        composeRule.setContent {
            IdentityUnlockScreen(
                onResolved = {},
                viewModel = viewModel,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Different identity").assertIsDisplayed()
        composeRule.onNodeWithText("Choose another file").assertIsDisplayed()
        composeRule.onAllNodesWithText("Replace").assertCountEquals(0)
    }
}
