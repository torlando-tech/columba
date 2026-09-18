package network.columba.app.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedInstanceBannerCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun `hosting card shows copy action and invokes callback`() {
        var copyRequested = false
        renderCard(
            isHostingSharedInstance = true,
            onCopyAccessConfiguration = { copyRequested = true },
        )

        composeRule.onNodeWithText(COPY_ACTION).assertIsDisplayed().performClick()

        assertTrue(copyRequested)
    }

    @Test
    fun `copy action is absent for shared instance client`() {
        renderCard(isUsingSharedInstance = true)
        composeRule.onNodeWithText(COPY_ACTION).assertDoesNotExist()
    }

    @Test
    fun `copy action is absent for standalone instance`() {
        renderCard()
        composeRule.onNodeWithText(COPY_ACTION).assertDoesNotExist()
    }

    @Test
    fun `copy action is absent for hosting conflict`() {
        renderCard(isHostingShareInstanceConflict = true)
        composeRule.onNodeWithText(COPY_ACTION).assertDoesNotExist()
    }

    private fun renderCard(
        isUsingSharedInstance: Boolean = false,
        isHostingSharedInstance: Boolean = false,
        isHostingShareInstanceConflict: Boolean = false,
        onCopyAccessConfiguration: () -> Unit = {},
    ) {
        composeRule.setContent {
            SharedInstanceBannerCard(
                isExpanded = true,
                isUsingSharedInstance = isUsingSharedInstance,
                rpcKey = null,
                sharedInstanceOnline = isUsingSharedInstance,
                isHostingSharedInstance = isHostingSharedInstance,
                isHostingShareInstanceConflict = isHostingShareInstanceConflict,
                onExpandToggle = {},
                onTogglePreferOwnInstance = {},
                onRpcKeyChange = {},
                onCopyAccessConfiguration = onCopyAccessConfiguration,
            )
        }
    }

    private companion object {
        const val COPY_ACTION = "Copy access configuration"
    }
}
