package com.lxmf.messenger.service.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceState.
 *
 * Tests atomic state management and generation tracking for race condition prevention.
 */
class ServiceStateTest {
    private lateinit var state: ServiceState

    @Before
    fun setup() {
        state = ServiceState()
    }

    @Test
    fun `initial state is SHUTDOWN`() {
        assertEquals("SHUTDOWN", state.networkStatus.get())
    }

    @Test
    fun `initial generation is 0`() {
        assertEquals(0, state.initializationGeneration.get())
    }

    @Test
    fun `initial wrapper is null`() {
        assertNull(state.wrapper)
    }

    @Test
    fun `initial conversation active is false`() {
        assertFalse(state.isConversationActive.get())
    }

    @Test
    fun `isInitialized returns false when wrapper is null`() {
        state.networkStatus.set("READY")
        assertFalse(state.isInitialized())
    }

    @Test
    fun `isInitialized returns false when status is not READY`() {
        // Cannot set a real PyObject in unit tests, but we can test the status check
        state.networkStatus.set("INITIALIZING")
        assertFalse(state.isInitialized())
    }

    @Test
    fun `nextGeneration increments counter`() {
        assertEquals(0, state.initializationGeneration.get())

        val gen1 = state.nextGeneration()
        assertEquals(1, gen1)
        assertEquals(1, state.initializationGeneration.get())

        val gen2 = state.nextGeneration()
        assertEquals(2, gen2)
        assertEquals(2, state.initializationGeneration.get())
    }

    @Test
    fun `isCurrentGeneration returns true for matching generation`() {
        val gen = state.nextGeneration()
        assertTrue(state.isCurrentGeneration(gen))
    }

    @Test
    fun `isCurrentGeneration returns false for stale generation`() {
        val staleGen = state.nextGeneration()
        state.nextGeneration() // Increment again

        assertFalse(state.isCurrentGeneration(staleGen))
    }

    @Test
    fun `reset clears state but preserves generation`() {
        // Set up some state
        state.networkStatus.set("READY")
        state.isConversationActive.set(true)
        val gen = state.nextGeneration()

        // Reset
        state.reset()

        // Verify state is cleared
        assertEquals("SHUTDOWN", state.networkStatus.get())
        assertFalse(state.isConversationActive.get())
        assertNull(state.wrapper)
        assertNull(state.pollingJob)
        assertNull(state.messagePollingJob)
        assertNull(state.shutdownJob)

        // Verify generation is preserved (important for race condition safety)
        assertEquals(gen, state.initializationGeneration.get())
    }

    @Test
    fun `networkStatus atomic operations work correctly`() {
        state.networkStatus.set("INITIALIZING")
        assertEquals("INITIALIZING", state.networkStatus.get())

        // Compare and set
        val wasInitializing = state.networkStatus.compareAndSet("INITIALIZING", "READY")
        assertTrue(wasInitializing)
        assertEquals("READY", state.networkStatus.get())

        // Compare and set with wrong expected value
        val wasReady = state.networkStatus.compareAndSet("INITIALIZING", "ERROR")
        assertFalse(wasReady)
        assertEquals("READY", state.networkStatus.get())
    }

    @Test
    fun `isConversationActive atomic operations work correctly`() {
        assertFalse(state.isConversationActive.get())

        state.isConversationActive.set(true)
        assertTrue(state.isConversationActive.get())

        // Get and set
        val wasActive = state.isConversationActive.getAndSet(false)
        assertTrue(wasActive)
        assertFalse(state.isConversationActive.get())
    }
}
