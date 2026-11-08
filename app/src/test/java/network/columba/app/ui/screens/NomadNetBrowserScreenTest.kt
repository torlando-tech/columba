package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.micron.MicronDocument
import network.columba.app.micron.MicronElement
import network.columba.app.micron.MicronLine
import network.columba.app.micron.MicronStyle
import network.columba.app.nomadnet.NomadNetShortcutSpec
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.NomadNetBrowserViewModel.BrowserState
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

    private fun resText(id: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(id)

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

        composeTestRule.onNodeWithText(resText(R.string.nomadnet_close_site)).assertIsDisplayed()
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

        composeTestRule.onNodeWithText(resText(R.string.nomadnet_close_site)).performClick()

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

    @Test
    fun nomadNetAddToHomeScreenMenuItem_pinsTheDisplayedPage() {
        var pinned: NomadNetShortcutSpec? = null
        val page = loadedPage()

        composeTestRule.setContent {
            val expanded = mutableStateOf(true)
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                NomadNetAddToHomeScreenMenuItem(
                    page = page,
                    onAddToHomeScreen = {
                        pinned = it
                        true
                    },
                    onUnsupportedLauncher = {},
                    onDismissMenu = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add to home screen").performClick()

        val spec = requireNotNull(pinned)
        assertEquals(NODE_HASH, spec.nodeHash)
        // Label comes from the page heading, not the generic path.
        assertEquals("Columba Test Node", spec.shortLabel)
        // Link fields survive so the shortcut reopens the same var-bearing page.
        assertEquals(
            "nomadnetwork://$NODE_HASH:/page/forum/thread.mu`thread=how-to-rngit|cat=help",
            spec.deepLink,
        )
    }

    @Test
    fun nomadNetAddToHomeScreenMenuItem_reportsUnsupportedLauncher() {
        var unsupported = 0

        composeTestRule.setContent {
            val expanded = mutableStateOf(true)
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                NomadNetAddToHomeScreenMenuItem(
                    page = loadedPage(),
                    onAddToHomeScreen = { false },
                    onUnsupportedLauncher = { unsupported++ },
                    onDismissMenu = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add to home screen").performClick()

        assertEquals(1, unsupported)
    }

    private fun loadedPage() =
        BrowserState.PageLoaded(
            document =
                MicronDocument(
                    lines =
                        listOf(
                            MicronLine(
                                elements = listOf(MicronElement.Text("Columba Test Node", MicronStyle())),
                                isHeading = true,
                            ),
                        ),
                ),
            path = "/page/forum/thread.mu",
            nodeHash = NODE_HASH,
            fieldTokens = listOf("thread=how-to-rngit", "cat=help"),
        )

    private companion object {
        const val NODE_HASH = "0d1ddfcc8a820d2fe2fe1d4c895e18fc"
    }
}
