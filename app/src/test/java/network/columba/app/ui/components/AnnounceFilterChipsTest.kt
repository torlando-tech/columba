package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.data.model.InterfaceType
import network.columba.app.rns.api.model.NodeType
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct component tests for [AnnounceFilterChips] — exercises the
 * collapse/expand contract added for GH issue #922 without spinning up the
 * full [AnnounceStreamScreen] mock surface. The exclusive-chip handlers are
 * covered by `AnnounceStreamScreenTest`; this file pins only the
 * collapsed-state UI and expand-request callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnnounceFilterChipsTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun expanded_rendersBothChipRows() {
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
                showAudio = true,
                selectedInterfaceTypes = emptySet(),
                onNodeTypesChange = {},
                onShowAudioChange = {},
                onInterfaceTypesChange = {},
                expanded = true,
            )
        }

        // Aspect row chips visible.
        composeTestRule.onNodeWithText("Peers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Relays").assertIsDisplayed()
        composeTestRule.onNodeWithText("Audio").assertIsDisplayed()
        // Interface row first chips visible (later ones may be off-viewport in
        // the LazyRow but at least one beyond "All" should be on-screen).
        composeTestRule.onNodeWithText("Local").assertIsDisplayed()
    }

    @Test
    fun collapsed_withNoActiveFilter_rendersNothing() {
        // Canonical All on both rows ⇒ no filter restricting the view ⇒
        // collapsed mode should render nothing so the announce list gets the
        // full vertical real estate (the entire point of GH #922).
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
                showAudio = true,
                selectedInterfaceTypes = emptySet(),
                onNodeTypesChange = {},
                onShowAudioChange = {},
                onInterfaceTypesChange = {},
                expanded = false,
            )
        }

        composeTestRule.onNodeWithText("Peers").assertDoesNotExist()
        composeTestRule.onNodeWithText("Filtering: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun collapsed_withAspectFilter_rendersPillRow() {
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.NODE),
                showAudio = false,
                selectedInterfaceTypes = emptySet(),
                onNodeTypesChange = {},
                onShowAudioChange = {},
                onInterfaceTypesChange = {},
                expanded = false,
            )
        }

        composeTestRule.onNodeWithText("Sites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peers").assertDoesNotExist()
    }

    @Test
    fun collapsed_withBothFiltersActive_pillRowJoinsLabels() {
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.NODE),
                showAudio = false,
                selectedInterfaceTypes = setOf(InterfaceType.TCP_CLIENT),
                onNodeTypesChange = {},
                onShowAudioChange = {},
                onInterfaceTypesChange = {},
                expanded = false,
            )
        }

        composeTestRule.onNodeWithText("Sites • TCP").assertIsDisplayed()
    }

    @Test
    fun collapsed_withMessyMultiAspectState_rendersNothing() {
        // A pre-PR persisted state like {PEER, NODE} (multi-not-full, audio
        // off) reads as "All" under the exclusive contract (size != 1).
        // Collapsed mode should treat it the same way — no pill row.
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.PEER, NodeType.NODE),
                showAudio = false,
                selectedInterfaceTypes = emptySet(),
                onNodeTypesChange = {},
                onShowAudioChange = {},
                onInterfaceTypesChange = {},
                expanded = false,
            )
        }

        composeTestRule.onNodeWithText("Filtering: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun collapsed_tappingPillRow_firesOnExpandRequest() {
        var expandRequested = false
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.NODE),
                showAudio = false,
                selectedInterfaceTypes = emptySet(),
                onNodeTypesChange = {},
                onShowAudioChange = {},
                onInterfaceTypesChange = {},
                expanded = false,
                onExpandRequest = { expandRequested = true },
            )
        }

        // The pill row is one big touch target; the content description
        // contains the active filter for accessibility.
        composeTestRule
            .onNodeWithContentDescription("Active filters: Sites. Tap to edit.")
            .performClick()

        assertTrue(
            "Tapping the active-filter pill row should fire onExpandRequest",
            expandRequested,
        )
    }

    @Test
    fun expanded_chipClicks_stillExclusive() {
        // Smoke check: collapse plumbing didn't regress the exclusive-chip
        // contract from the previous PR. Detailed coverage lives in
        // AnnounceStreamScreenTest; this verifies the click path survives
        // the new AnimatedVisibility wrapper.
        var capturedTypes: Set<NodeType>? = null
        var capturedShowAudio: Boolean? = null
        composeTestRule.setContent {
            AnnounceFilterChips(
                selectedNodeTypes = setOf(NodeType.PEER, NodeType.NODE, NodeType.PROPAGATION_NODE),
                showAudio = true,
                selectedInterfaceTypes = emptySet(),
                onNodeTypesChange = { capturedTypes = it },
                onShowAudioChange = { capturedShowAudio = it },
                onInterfaceTypesChange = {},
                expanded = true,
            )
        }

        composeTestRule.onNodeWithText("Sites").performClick()

        assertEquals(setOf(NodeType.NODE), capturedTypes)
        assertEquals(false, capturedShowAudio)
    }
}
