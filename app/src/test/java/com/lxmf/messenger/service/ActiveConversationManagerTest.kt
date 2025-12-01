package com.lxmf.messenger.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ActiveConversationManager.
 * Tests the state management for active conversation tracking.
 */
class ActiveConversationManagerTest {
    private lateinit var manager: ActiveConversationManager

    @Before
    fun setup() {
        manager = ActiveConversationManager()
    }

    @Test
    fun `initial state is null`() =
        runTest {
            assertNull(manager.activeConversation.value)
        }

    @Test
    fun `setActive updates state correctly`() =
        runTest {
            val testHash = "abc123"

            manager.setActive(testHash)

            assertEquals(testHash, manager.activeConversation.first())
        }

    @Test
    fun `setActive with null clears state`() =
        runTest {
            val testHash = "abc123"

            // Set active
            manager.setActive(testHash)
            assertEquals(testHash, manager.activeConversation.first())

            // Clear active
            manager.setActive(null)
            assertNull(manager.activeConversation.first())
        }

    @Test
    fun `setActive can switch between conversations`() =
        runTest {
            val hash1 = "abc123"
            val hash2 = "def456"

            // Set first conversation
            manager.setActive(hash1)
            assertEquals(hash1, manager.activeConversation.first())

            // Switch to second conversation
            manager.setActive(hash2)
            assertEquals(hash2, manager.activeConversation.first())

            // Clear
            manager.setActive(null)
            assertNull(manager.activeConversation.first())
        }

    @Test
    fun `StateFlow emits updates`() =
        runTest {
            val testHash = "abc123"
            val values = mutableListOf<String?>()

            // Collect initial value
            values.add(manager.activeConversation.value)

            // Update and collect
            manager.setActive(testHash)
            values.add(manager.activeConversation.value)

            // Clear and collect
            manager.setActive(null)
            values.add(manager.activeConversation.value)

            assertEquals(listOf(null, testHash, null), values)
        }
}
