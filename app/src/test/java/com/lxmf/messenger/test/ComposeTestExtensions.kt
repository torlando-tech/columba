package com.lxmf.messenger.test

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText

/**
 * Extensions for more reliable Compose UI testing, especially in CI environments.
 * These helpers use waitUntil APIs to handle asynchronous state updates from StateFlow
 * and lazy composition in LazyColumn.
 */

/**
 * Waits for a node with the given tag to exist in the semantic tree.
 * More reliable than waitForIdle() for asynchronous state updates.
 *
 * @param tag The test tag to wait for
 * @param timeoutMillis Maximum time to wait (default: 5 seconds for CI)
 * @return The node if found
 * @throws AssertionError if node not found within timeout
 */
fun ComposeTestRule.waitForNodeWithTag(
    tag: String,
    timeoutMillis: Long = 5000,
): SemanticsNodeInteraction {
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(tag)
            .fetchSemanticsNodes().isNotEmpty()
    }
    return onNodeWithTag(tag)
}

/**
 * Waits for a node with the given text to exist in the semantic tree.
 * More reliable than waitForIdle() for asynchronous state updates.
 *
 * @param text The text to wait for
 * @param substring Whether to match substring (default: false)
 * @param ignoreCase Whether to ignore case (default: false)
 * @param timeoutMillis Maximum time to wait (default: 5 seconds for CI)
 * @return The node if found
 * @throws AssertionError if node not found within timeout
 */
fun ComposeTestRule.waitForNodeWithText(
    text: String,
    substring: Boolean = false,
    ignoreCase: Boolean = false,
    timeoutMillis: Long = 5000,
): SemanticsNodeInteraction {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(text, substring, ignoreCase)
            .fetchSemanticsNodes().isNotEmpty()
    }
    return onNodeWithText(text, substring, ignoreCase)
}

/**
 * Waits for multiple nodes with the given text to reach expected count.
 * Useful for testing LazyColumn items or duplicate text elements.
 *
 * @param text The text to search for
 * @param expectedCount The expected number of nodes
 * @param timeoutMillis Maximum time to wait (default: 5 seconds for CI)
 * @throws AssertionError if expected count not reached within timeout
 */
fun ComposeTestRule.waitForTextCount(
    text: String,
    expectedCount: Int,
    timeoutMillis: Long = 5000,
) {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(text)
            .fetchSemanticsNodes().size == expectedCount
    }
}

/**
 * Waits for a UI state to stabilize by checking condition repeatedly.
 * Useful for complex state transitions.
 *
 * @param timeoutMillis Maximum time to wait
 * @param condition The condition that must become true
 * @throws AssertionError if condition not met within timeout
 */
inline fun ComposeTestRule.waitForCondition(
    timeoutMillis: Long = 5000,
    crossinline condition: () -> Boolean,
) {
    waitUntil(timeoutMillis) { condition() }
}
