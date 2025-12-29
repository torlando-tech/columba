package com.lxmf.messenger.ui.compose

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.test.waitForCondition
import com.lxmf.messenger.test.waitForNodeWithTag
import com.lxmf.messenger.test.waitForNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validation tests for Compose BOM upgrade.
 *
 * These tests verify core Compose functionality that could be affected by
 * major Compose BOM version updates (2024.12.01 -> 2025.12.01):
 *
 * 1. LazyColumn with large datasets - Tests pausable composition behavior
 * 2. AnimatedVisibility enter/exit - Tests animation framework changes
 * 3. State recomposition - Tests runtime behavior
 *
 * Run these tests after upgrading Compose BOM to catch regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposeBomUpgradeValidationTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== LazyColumn Large Dataset Tests ==========

    @Test
    fun lazyColumn_withSmallDataset_rendersAllItems() {
        val items = (1..10).map { "Item $it" }

        composeTestRule.setContent {
            MaterialTheme {
                LazyColumn(modifier = Modifier.testTag("lazy-list")) {
                    items(items) { item ->
                        Text(
                            text = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .testTag("item-$item"),
                        )
                    }
                }
            }
        }

        // First items should be visible
        composeTestRule.onNodeWithText("Item 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Item 2").assertIsDisplayed()
    }

    @Test
    fun lazyColumn_withMediumDataset_rendersVisibleItems() {
        val items = (1..50).map { "Item $it" }

        composeTestRule.setContent {
            MaterialTheme {
                LazyColumn(modifier = Modifier.testTag("lazy-list")) {
                    items(items) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .testTag("card-$item"),
                        ) {
                            Text(
                                text = item,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // First items should be visible
        composeTestRule.onNodeWithText("Item 1").assertIsDisplayed()

        // Scroll to middle
        composeTestRule.onNodeWithTag("lazy-list").performScrollToIndex(25)
        composeTestRule.waitForIdle()

        // Item 26 should now be visible (index 25 = "Item 26")
        composeTestRule.onNodeWithText("Item 26").assertExists()
    }

    @Test
    fun lazyColumn_withLargeDataset_scrollsToEnd() {
        val items = (1..100).map { "Item $it" }

        composeTestRule.setContent {
            MaterialTheme {
                LazyColumn(modifier = Modifier.testTag("lazy-list")) {
                    items(items) { item ->
                        Text(
                            text = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        // Scroll to end
        composeTestRule.onNodeWithTag("lazy-list").performScrollToIndex(99)
        composeTestRule.waitForIdle()

        // Last item should be visible
        composeTestRule.onNodeWithText("Item 100").assertExists()
    }

    @Test
    fun lazyColumn_withDynamicDataset_updatesCorrectly() {
        var items by mutableStateOf((1..10).map { "Item $it" })

        composeTestRule.setContent {
            MaterialTheme {
                LazyColumn(modifier = Modifier.testTag("lazy-list")) {
                    items(items) { item ->
                        Text(
                            text = item,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Item 1").assertIsDisplayed()

        // Scroll to end to verify last item exists
        composeTestRule.onNodeWithTag("lazy-list").performScrollToIndex(9)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Item 10").assertExists()

        // Add more items
        items = (1..20).map { "Item $it" }
        composeTestRule.waitForIdle()

        // New items should be accessible
        composeTestRule.onNodeWithTag("lazy-list").performScrollToIndex(19)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Item 20").assertExists()
    }

    // ========== AnimatedVisibility Tests ==========

    @Test
    fun animatedVisibility_initiallyVisible_displaysContent() {
        composeTestRule.setContent {
            MaterialTheme {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = "Visible Content",
                        modifier = Modifier.testTag("animated-content"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("animated-content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Visible Content").assertIsDisplayed()
    }

    @Test
    fun animatedVisibility_initiallyHidden_doesNotDisplayContent() {
        composeTestRule.setContent {
            MaterialTheme {
                AnimatedVisibility(
                    visible = false,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = "Hidden Content",
                        modifier = Modifier.testTag("animated-content"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("animated-content").assertDoesNotExist()
    }

    @Test
    fun animatedVisibility_toggleToVisible_displaysContent() {
        var isVisible by mutableStateOf(false)

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    Text(
                        text = "Header",
                        modifier = Modifier.testTag("header"),
                    )
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            text = "Animated Content",
                            modifier = Modifier.testTag("animated-content"),
                        )
                    }
                }
            }
        }

        // Initially hidden
        composeTestRule.onNodeWithTag("header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("animated-content").assertDoesNotExist()

        // Toggle to visible
        isVisible = true
        composeTestRule.waitForIdle()

        // Wait for animation to complete and content to appear
        composeTestRule.waitForNodeWithTag("animated-content").assertIsDisplayed()
    }

    @Test
    fun animatedVisibility_toggleToHidden_removesContent() {
        var isVisible by mutableStateOf(true)

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    Text(
                        text = "Header",
                        modifier = Modifier.testTag("header"),
                    )
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            text = "Animated Content",
                            modifier = Modifier.testTag("animated-content"),
                        )
                    }
                }
            }
        }

        // Initially visible
        composeTestRule.onNodeWithTag("animated-content").assertIsDisplayed()

        // Toggle to hidden
        isVisible = false
        composeTestRule.waitForIdle()

        // Wait for animation to complete and content to be removed
        composeTestRule.waitForCondition {
            composeTestRule.onAllNodesWithTag("animated-content")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun animatedVisibility_multipleToggles_handlesCorrectly() {
        var isVisible by mutableStateOf(false)

        composeTestRule.setContent {
            MaterialTheme {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = "Toggle Content",
                        modifier = Modifier.testTag("toggle-content"),
                    )
                }
            }
        }

        // Start hidden
        composeTestRule.onNodeWithTag("toggle-content").assertDoesNotExist()

        // Toggle visible
        isVisible = true
        composeTestRule.waitForIdle()
        composeTestRule.waitForNodeWithTag("toggle-content").assertIsDisplayed()

        // Toggle hidden
        isVisible = false
        composeTestRule.waitForIdle()
        composeTestRule.waitForCondition {
            composeTestRule.onAllNodesWithTag("toggle-content")
                .fetchSemanticsNodes().isEmpty()
        }

        // Toggle visible again
        isVisible = true
        composeTestRule.waitForIdle()
        composeTestRule.waitForNodeWithTag("toggle-content").assertIsDisplayed()
    }

    // ========== State Recomposition Tests ==========

    @Test
    fun stateChange_triggersRecomposition() {
        var counter by mutableStateOf(0)

        composeTestRule.setContent {
            MaterialTheme {
                Text(
                    text = "Counter: $counter",
                    modifier = Modifier.testTag("counter-text"),
                )
            }
        }

        composeTestRule.onNodeWithText("Counter: 0").assertIsDisplayed()

        counter = 1
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Counter: 1").assertIsDisplayed()

        counter = 42
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Counter: 42").assertIsDisplayed()
    }

    @Test
    fun listStateChange_triggersRecomposition() {
        var items by mutableStateOf(listOf("A", "B", "C"))

        composeTestRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.testTag("list-column")) {
                    items.forEach { item ->
                        Text(
                            text = item,
                            modifier = Modifier.testTag("item-$item"),
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("A").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
        composeTestRule.onNodeWithText("C").assertIsDisplayed()

        // Modify list
        items = listOf("X", "Y", "Z")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("X").assertIsDisplayed()
        composeTestRule.onNodeWithText("Y").assertIsDisplayed()
        composeTestRule.onNodeWithText("Z").assertIsDisplayed()

        // Old items should not exist
        composeTestRule.onNodeWithText("A").assertDoesNotExist()
    }

    // ========== Pausable Composition Stress Test ==========

    @Test
    fun lazyColumn_rapidScrolling_handlesWithoutCrash() {
        val items = (1..200).map { "Item $it" }

        composeTestRule.setContent {
            MaterialTheme {
                LazyColumn(modifier = Modifier.testTag("stress-list")) {
                    items(items) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                        ) {
                            Text(
                                text = item,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        // Rapid scrolling to test pausable composition
        repeat(5) { iteration ->
            val targetIndex = (iteration + 1) * 40 - 1 // 39, 79, 119, 159, 199
            composeTestRule.onNodeWithTag("stress-list").performScrollToIndex(targetIndex)
            composeTestRule.waitForIdle()
        }

        // Scroll back to beginning
        composeTestRule.onNodeWithTag("stress-list").performScrollToIndex(0)
        composeTestRule.waitForIdle()

        // First item should still be accessible
        composeTestRule.onNodeWithText("Item 1").assertExists()
    }

    // Helper extension for onAllNodesWithTag
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTag(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
}
