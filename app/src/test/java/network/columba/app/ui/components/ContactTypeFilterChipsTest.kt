package network.columba.app.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.ContactTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct component tests for [ContactTypeFilterChips] (GH issue #863) — covers
 * the exclusive single-select contract and the collapse/expand pill behaviour
 * shared with [AnnounceFilterChips] (GH issue #922).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactTypeFilterChipsTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun expanded_rendersAllChips() {
        composeTestRule.setContent {
            ContactTypeFilterChips(
                selected = ContactTypeFilter.ALL,
                onSelectedChange = {},
                expanded = true,
            )
        }

        composeTestRule.onNodeWithText("All").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Relays").assertIsDisplayed()
        composeTestRule.onNodeWithText("Audio").assertIsDisplayed()
    }

    @Test
    fun collapsed_withAllSelected_rendersNothing() {
        // No type restriction ⇒ collapsed mode renders nothing so the list
        // gets the full vertical real estate.
        composeTestRule.setContent {
            ContactTypeFilterChips(
                selected = ContactTypeFilter.ALL,
                onSelectedChange = {},
                expanded = false,
            )
        }

        composeTestRule.onNodeWithText("Peers").assertDoesNotExist()
        composeTestRule.onNodeWithText("Filtering: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun collapsed_withActiveFilter_rendersPillRow() {
        composeTestRule.setContent {
            ContactTypeFilterChips(
                selected = ContactTypeFilter.SITES,
                onSelectedChange = {},
                expanded = false,
            )
        }

        // Chip row hidden, pill row summarises the active filter.
        composeTestRule.onNodeWithText("Peers").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sites").assertIsDisplayed()
    }

    @Test
    fun collapsed_tappingPillRow_firesOnExpandRequest() {
        var expandRequested = false
        composeTestRule.setContent {
            ContactTypeFilterChips(
                selected = ContactTypeFilter.RELAYS,
                onSelectedChange = {},
                expanded = false,
                onExpandRequest = { expandRequested = true },
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Active filter: Relays. Tap to edit.")
            .performClick()

        assertTrue(
            "Tapping the active-filter pill row should fire onExpandRequest",
            expandRequested,
        )
    }

    @Test
    fun expanded_tappingChip_selectsThatType() {
        var captured: ContactTypeFilter? = null
        composeTestRule.setContent {
            ContactTypeFilterChips(
                selected = ContactTypeFilter.ALL,
                onSelectedChange = { captured = it },
                expanded = true,
            )
        }

        composeTestRule.onNodeWithText("Sites").performClick()

        assertEquals(ContactTypeFilter.SITES, captured)
    }

    @Test
    fun expanded_tappingActiveChip_snapsBackToAll() {
        var captured: ContactTypeFilter? = null
        composeTestRule.setContent {
            ContactTypeFilterChips(
                selected = ContactTypeFilter.SITES,
                onSelectedChange = { captured = it },
                expanded = true,
            )
        }

        // Tapping the currently-active chip is the no-hunt return path to All.
        composeTestRule.onNodeWithText("Sites").performClick()

        assertEquals(ContactTypeFilter.ALL, captured)
    }
}
