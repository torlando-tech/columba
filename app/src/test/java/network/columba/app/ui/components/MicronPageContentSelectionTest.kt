package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import network.columba.app.micron.MicronDocument
import network.columba.app.micron.MicronElement
import network.columba.app.micron.MicronLine
import network.columba.app.micron.MicronStyle
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.NomadNetBrowserViewModel.RenderingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #869: long-press text selection on rendered NomadNet pages.
 *
 * The structural contract is that every line in `MicronPageContent` is rendered
 * inside a `SelectionContainer`, which is what enables Compose's stock long-press
 * selection + system copy toolbar. Compose UI tests cannot drive selection-handle
 * gestures, so we assert the structural invariant via testTag plus a regression
 * guard that link clicks still reach `onLinkClick` while inside the container.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MicronPageContentSelectionTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val composeTestRule get() = composeRule

    @Test
    fun micronPageContent_wrapsBodyInSelectionContainer() {
        val line1 = "first selectable line"
        val line2 = "second selectable line"
        val document =
            MicronDocument(
                lines =
                    listOf(
                        MicronLine(elements = listOf(MicronElement.Text(line1, MicronStyle()))),
                        MicronLine(elements = listOf(MicronElement.Text(line2, MicronStyle()))),
                    ),
            )

        composeTestRule.setContent {
            MicronPageContent(
                document = document,
                formFields = emptyMap(),
                renderingMode = RenderingMode.PROPORTIONAL_WRAP,
                onLinkClick = { _, _ -> },
                onFieldUpdate = { _, _ -> },
            )
        }

        composeTestRule
            .onNodeWithTag("micron-selection-container")
            .assertExists()
            .assertHasNoClickAction()

        composeTestRule.onAllNodesWithText(line1).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(line2).assertCountEquals(1)
    }

    @Test
    fun micronPageContent_clickableLinesStillReceiveClicksInsideSelectionContainer() {
        val linkLabel = "tap target"
        val expectedDestination = ":/page/index.mu"
        val expectedFieldNames = listOf("alpha", "beta")
        val calls = mutableListOf<Pair<String, List<String>>>()

        val document =
            MicronDocument(
                lines =
                    listOf(
                        MicronLine(
                            elements =
                                listOf(
                                    MicronElement.Link(
                                        label = linkLabel,
                                        destination = expectedDestination,
                                        fieldNames = expectedFieldNames,
                                        style = MicronStyle(),
                                    ),
                                ),
                        ),
                    ),
            )

        composeTestRule.setContent {
            MicronPageContent(
                document = document,
                formFields = emptyMap(),
                renderingMode = RenderingMode.PROPORTIONAL_WRAP,
                onLinkClick = { destination, fieldNames -> calls.add(destination to fieldNames) },
                onFieldUpdate = { _, _ -> },
            )
        }

        // ClickableText handles taps via pointerInput (not Modifier.clickable), so it does not
        // expose a Click semantics action. Drive a real down/up gesture. The line is laid out
        // with fillMaxWidth() + defaultMinSize(48.dp), so `center` sits in the empty padding;
        // tap near the top-left where the link label is actually rendered.
        composeTestRule.onNodeWithText(linkLabel).performTouchInput {
            down(Offset(2f, 2f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(1, calls.size)
        assertEquals(expectedDestination, calls.single().first)
        assertEquals(expectedFieldNames, calls.single().second)
    }
}
