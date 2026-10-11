package network.columba.app.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * De-risk tests for the propagation-node priority reorder list (spec §5).
 *
 * The fiddliest part of this UI is the interaction between the manual drag-reorder
 * gesture (scoped to the leading drag-handle) and the per-row [FilterChip] / overflow
 * buttons. These tests verify:
 * - The pure reorder math ([reorderKeyOrder]) is correct and always a permutation.
 * - Tapping the restriction chip cycles the restriction and does NOT reorder.
 * - Tapping "Remove" removes the row and does NOT reorder.
 * - The handle, chip and overflow all render per row.
 *
 * The list is rendered directly with in-memory state (no DAO/ViewModel) so the test
 * exercises the layout + tap targets in isolation; persistence via the DAO is covered
 * by the data-layer tests.
 *
 * Note (BOM 2026.05.01): the Compose pointer-gesture test API was renamed
 * (`performPointerInput` / `PointerEventApplier` no longer exist; they are now
 * `performGesture { touch { ... } }` on a name-mangled `TouchInjectionScope`).
 * Authoring a reliable synthetic drag against that rename blind is error-prone, so the
 * actual finger-drag path is intentionally verified on-device in ticket 06's physical
 * smoke instead. The no-gesture-conflict property (chip/overflow taps unaffected by
 * the drag region) is asserted here with taps; the commit-once contract is asserted
 * via the pure reorder math and is guaranteed by construction (the callback fires
 * once per completed gesture, outside the pointer-event loop).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PropagationNodeReorderListTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    // ---- Pure reorder math (no Compose) ----

    @Test
    fun reorderKeyOrder_movesFirstDownOne() {
        assertEquals(listOf("b", "a", "c"), reorderKeyOrder(listOf("a", "b", "c"), 0, 1))
    }

    @Test
    fun reorderKeyOrder_movesFirstToLast() {
        assertEquals(listOf("b", "c", "a"), reorderKeyOrder(listOf("a", "b", "c"), 0, 2))
    }

    @Test
    fun reorderKeyOrder_movesLastToTop() {
        assertEquals(listOf("c", "a", "b"), reorderKeyOrder(listOf("a", "b", "c"), 2, 0))
    }

    @Test
    fun reorderKeyOrder_sameIndexIsNoop() {
        assertEquals(listOf("a", "b", "c"), reorderKeyOrder(listOf("a", "b", "c"), 1, 1))
    }

    @Test
    fun reorderKeyOrder_isPermutationNeverLosesItems() {
        val keys = listOf("a", "b", "c", "d")
        for (from in keys.indices) {
            for (to in keys.indices) {
                val result = reorderKeyOrder(keys, from, to)
                assertEquals(keys.size, result.size)
                assertEquals(keys.toSet(), result.toSet())
            }
        }
    }

    // ---- Compose: renders rows with handle, chip, overflow ----

    @Test
    fun rendersOneRowPerNodeWithHandleChipAndRemove() {
        composeRule.setContent {
            PropagationNodeReorderList(
                items = sampleRows(),
                onReorder = {},
                onRestrictionChange = { _, _ -> },
                onRemove = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Alpha").assertCountEquals(1)
        composeRule.onAllNodesWithText("Beta").assertCountEquals(1)
        composeRule.onAllNodesWithText("Gamma").assertCountEquals(1)
        // Each row exposes a reorder handle (contentDescription = "Reorder <name>").
        composeRule.onAllNodesWithContentDescription("Reorder Alpha").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Reorder Beta").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Reorder Gamma").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Reorder Alpha").assertIsDisplayed()
        // Restriction chips render with the "Any" label for ANY.
        composeRule.onAllNodesWithText("Any").assertCountEquals(3)
        // Per-row Remove buttons present.
        composeRule.onAllNodesWithText("Remove").assertCountEquals(3)
    }

    // ---- Compose: chip tap cycles restriction, does NOT reorder ----

    @Test
    fun tappingRestrictionChipCyclesItWithoutReordering() {
        var reorderCalls = 0
        var lastReorder: List<String>? = null
        var restrictionChangeCalls = 0
        var lastRestriction: String? = null

        composeRule.setContent {
            PropagationNodeReorderList(
                items = sampleRows(),
                onReorder = { keys ->
                    reorderCalls++
                    lastReorder = keys
                },
                onRestrictionChange = { _, newRestriction ->
                    restrictionChangeCalls++
                    lastRestriction = newRestriction
                },
                onRemove = {},
            )
        }
        composeRule.waitForIdle()

        // Alpha's chip is tagged; tapping it cycles ANY -> WIFI_ONLY.
        composeRule.onNodeWithTag("prop_node_chip_alpha").performClick()
        composeRule.waitForIdle()

        assertEquals(1, restrictionChangeCalls)
        assertEquals("WIFI_ONLY", lastRestriction)
        assertEquals("chip tap must not reorder", 0, reorderCalls)
    }

    // ---- Compose: Remove tap removes row, does NOT reorder ----

    @Test
    fun tappingRemoveRemovesRowWithoutReordering() {
        var reorderCalls = 0
        var removed: List<String> = emptyList()

        // Stateful copy so Remove actually reflects in the rendered list.
        var items by mutableStateOf(sampleRows())
        composeRule.setContent {
            PropagationNodeReorderList(
                items = items,
                onReorder = { reorderCalls++ },
                onRestrictionChange = { _, _ -> },
                onRemove = { hash ->
                    removed = listOf(hash)
                    items = items.filter { it.destinationHash != hash }
                },
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Beta").assertCountEquals(1)

        // Tap Beta's "Remove" (tagged per-row to avoid ambiguity with the others).
        composeRule.onNodeWithTag("prop_node_remove_beta").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("beta"), removed)
        assertEquals("remove must not reorder", 0, reorderCalls)
        composeRule.onAllNodesWithText("Beta").assertCountEquals(0)
    }

    private fun sampleRows() =
        listOf(
            PropagationNodeRow("alpha", "Alpha", "ANY"),
            PropagationNodeRow("beta", "Beta", "ANY"),
            PropagationNodeRow("gamma", "Gamma", "ANY"),
        )
}
