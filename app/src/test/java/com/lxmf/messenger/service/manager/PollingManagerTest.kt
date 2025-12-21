package com.lxmf.messenger.service.manager

import com.chaquo.python.PyObject
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
 * Unit tests for PollingManager.
 *
 * Tests the event-driven message delivery and startup drain functionality.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingManagerTest {
    private lateinit var state: ServiceState
    private lateinit var wrapperManager: PythonWrapperManager
    private lateinit var broadcaster: CallbackBroadcaster
    private lateinit var testScope: TestScope
    private lateinit var pollingManager: PollingManager

    @Before
    fun setup() {
        state = ServiceState()
        wrapperManager = mockk(relaxed = true)
        broadcaster = mockk(relaxed = true)
        testScope = TestScope(UnconfinedTestDispatcher())

        pollingManager =
            PollingManager(
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
            pollingManager.drainPendingMessages()
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
            pollingManager.drainPendingMessages()
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
            pollingManager.drainPendingMessages()
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
            pollingManager.handleMessageReceivedEvent("{\"event\": \"message\"}")
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
            pollingManager.handleMessageReceivedEvent("{\"event\": \"message\"}")
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
            pollingManager.handleMessageReceivedEvent("{\"event\": \"message\"}")
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
        pollingManager.setConversationActive(true)

        // Assert
        assertTrue(state.isConversationActive.get())
    }

    @Test
    fun `setConversationActive sets state to false`() {
        // Setup
        state.isConversationActive.set(true)
        assertTrue(state.isConversationActive.get())

        // Act
        pollingManager.setConversationActive(false)

        // Assert
        assertFalse(state.isConversationActive.get())
    }

    // ========== stopAll() Tests ==========

    @Test
    fun `stopAll clears polling job from state`() {
        // Setup: Manually set a mock job
        val mockJob = mockk<Job>(relaxed = true)
        state.pollingJob = mockJob

        // Act
        pollingManager.stopAll()

        // Assert
        assertNull(state.pollingJob)
        verify { mockJob.cancel() }
    }

    @Test
    fun `stopAll handles null job gracefully`() {
        // Setup: No job set
        assertNull(state.pollingJob)

        // Act - should not throw
        pollingManager.stopAll()

        // Assert
        assertNull(state.pollingJob)
    }

    // ========== handleDeliveryStatusEvent() Tests ==========

    @Test
    fun `handleDeliveryStatusEvent broadcasts status`() {
        val statusJson = "{\"status\": \"delivered\"}"

        // Act
        pollingManager.handleDeliveryStatusEvent(statusJson)

        // Assert
        verify { broadcaster.broadcastDeliveryStatus(statusJson) }
    }

    @Test
    fun `handleDeliveryStatusEvent handles exception in broadcaster gracefully`() {
        val statusJson = "{\"status\": \"delivered\"}"
        every { broadcaster.broadcastDeliveryStatus(any()) } throws RuntimeException("Broadcast error")

        // Act - should not throw (exception is caught internally)
        pollingManager.handleDeliveryStatusEvent(statusJson)

        // Assert: Method was called (exception handling is internal)
        verify { broadcaster.broadcastDeliveryStatus(statusJson) }
    }
}
