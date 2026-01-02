package com.lxmf.messenger.service.manager

import com.chaquo.python.PyObject
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for EventHandler.
 *
 * Tests the event-driven message delivery and startup drain functionality.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventHandlerTest {
    private lateinit var state: ServiceState
    private lateinit var wrapperManager: PythonWrapperManager
    private lateinit var broadcaster: CallbackBroadcaster
    private lateinit var testScope: TestScope
    private lateinit var eventHandler: EventHandler

    @Before
    fun setup() {
        state = ServiceState()
        wrapperManager = mockk(relaxed = true)
        broadcaster = mockk(relaxed = true)
        testScope = TestScope(UnconfinedTestDispatcher())

        eventHandler =
            EventHandler(
                state = state,
                wrapperManager = wrapperManager,
                broadcaster = broadcaster,
                scope = testScope,
                attachmentStorage = null,
            )
    }

    // ========== drainPendingMessages() Tests ==========

    @Test
    fun `drainPendingMessages handles empty queue gracefully`() =
        runTest {
            // Setup: Mock wrapper returning empty list
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns emptyList()

            // Act
            eventHandler.drainPendingMessages()
            testScope.advanceUntilIdle()

            // Assert: No messages broadcast
            verify(exactly = 0) { broadcaster.broadcastMessage(any()) }
        }

    @Test
    fun `drainPendingMessages handles null response gracefully`() =
        runTest {
            // Setup: Mock wrapper returning null
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns null

            // Act
            eventHandler.drainPendingMessages()
            testScope.advanceUntilIdle()

            // Assert: No messages broadcast, no exception
            verify(exactly = 0) { broadcaster.broadcastMessage(any()) }
        }

    @Test
    fun `drainPendingMessages handles exception gracefully`() =
        runTest {
            // Setup: Mock wrapper throwing exception
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } throws RuntimeException("Test error")

            // Act - should not throw
            eventHandler.drainPendingMessages()
            testScope.advanceUntilIdle()

            // Assert: No crash, no messages broadcast
            verify(exactly = 0) { broadcaster.broadcastMessage(any()) }
        }

    // ========== handleMessageReceivedEvent() Tests ==========

    @Test
    fun `handleMessageReceivedEvent handles empty queue gracefully`() =
        runTest {
            // Setup: Mock wrapper returning empty list
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns emptyList()

            // Act
            eventHandler.handleMessageReceivedEvent("{\"event\": \"message\"}")
            testScope.advanceUntilIdle()

            // Assert: No messages broadcast
            verify(exactly = 0) { broadcaster.broadcastMessage(any()) }
        }

    @Test
    fun `handleMessageReceivedEvent handles null response gracefully`() =
        runTest {
            // Setup: Mock wrapper returning null
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns null

            // Act
            eventHandler.handleMessageReceivedEvent("{\"event\": \"message\"}")
            testScope.advanceUntilIdle()

            // Assert: No messages broadcast
            verify(exactly = 0) { broadcaster.broadcastMessage(any()) }
        }

    @Test
    fun `handleMessageReceivedEvent handles exception gracefully`() =
        runTest {
            // Setup: Mock wrapper throwing exception
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } throws RuntimeException("Test error")

            // Act - should not throw
            eventHandler.handleMessageReceivedEvent("{\"event\": \"message\"}")
            testScope.advanceUntilIdle()

            // Assert: No crash
            verify(exactly = 0) { broadcaster.broadcastMessage(any()) }
        }

    // ========== setConversationActive() Tests ==========

    @Test
    fun `setConversationActive sets state to true`() {
        // Initial state
        assertFalse(state.isConversationActive.get())

        // Act
        eventHandler.setConversationActive(true)

        // Assert
        assertTrue(state.isConversationActive.get())
    }

    @Test
    fun `setConversationActive sets state to false`() {
        // Setup
        state.isConversationActive.set(true)
        assertTrue(state.isConversationActive.get())

        // Act
        eventHandler.setConversationActive(false)

        // Assert
        assertFalse(state.isConversationActive.get())
    }

    // ========== stopAll() Tests ==========

    @Test
    fun `stopAll can be called without issues in event-driven mode`() {
        // In event-driven mode, stopAll() is a no-op that just logs
        // This test ensures it can be called without throwing exceptions

        // Act - should not throw
        eventHandler.stopAll()

        // No assertions needed - just verifying no exceptions
    }

    @Test
    fun `stopAll handles null job gracefully`() {
        // Setup: No job set (default in event-driven mode)
        assertNull(state.pollingJob)

        // Act - should not throw
        eventHandler.stopAll()

        // Assert: Job remains null
        assertNull(state.pollingJob)
    }

    // ========== handleDeliveryStatusEvent() Tests ==========

    @Test
    fun `handleDeliveryStatusEvent broadcasts status`() {
        val statusJson = "{\"status\": \"delivered\"}"

        // Act
        eventHandler.handleDeliveryStatusEvent(statusJson)

        // Assert
        verify { broadcaster.broadcastDeliveryStatus(statusJson) }
    }

    @Test
    fun `handleDeliveryStatusEvent handles exception in broadcaster gracefully`() {
        val statusJson = "{\"status\": \"delivered\"}"
        every { broadcaster.broadcastDeliveryStatus(any()) } throws RuntimeException("Broadcast error")

        // Act - should not throw (exception is caught internally)
        eventHandler.handleDeliveryStatusEvent(statusJson)

        // Assert: Method was called (exception handling is internal)
        verify { broadcaster.broadcastDeliveryStatus(statusJson) }
    }

    // ========== handleReactionReceivedEvent() Tests ==========

    @Test
    fun `handleReactionReceivedEvent broadcasts reaction`() {
        val reactionJson = """{"reaction_to": "msg123", "emoji": "👍", "sender": "abc"}"""

        // Act
        eventHandler.handleReactionReceivedEvent(reactionJson)

        // Assert
        verify { broadcaster.broadcastReactionReceived(reactionJson) }
    }

    @Test
    fun `handleReactionReceivedEvent handles exception gracefully`() {
        val reactionJson = """{"reaction_to": "msg123", "emoji": "👍"}"""
        every { broadcaster.broadcastReactionReceived(any()) } throws RuntimeException("Broadcast error")

        // Act - should not throw
        eventHandler.handleReactionReceivedEvent(reactionJson)

        // Assert: Method was called
        verify { broadcaster.broadcastReactionReceived(reactionJson) }
    }

    // ========== startEventHandling() Tests ==========

    @Test
    fun `startEventHandling drains pending announces on startup`() =
        runTest {
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns emptyList()

            // Act
            eventHandler.startEventHandling()
            testScope.advanceUntilIdle()

            // Assert: wrapper was called to get pending announces
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) }
        }

    @Test
    fun `startEventHandling handles null pending announces gracefully`() =
        runTest {
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } returns null

            // Act - should not throw
            eventHandler.startEventHandling()
            testScope.advanceUntilIdle()

            // No crash
        }

    @Test
    fun `startEventHandling handles exception in drain gracefully`() =
        runTest {
            coEvery { wrapperManager.withWrapper<List<PyObject>?>(any()) } throws RuntimeException("Error")

            // Act - should not throw
            eventHandler.startEventHandling()
            testScope.advanceUntilIdle()

            // No crash
        }
}
