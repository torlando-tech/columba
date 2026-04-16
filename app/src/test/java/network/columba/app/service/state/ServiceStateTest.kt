package network.columba.app.service.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceState.
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
    fun `isInitialized returns true when status is READY`() {
        state.networkStatus.set("READY")
        assertTrue(state.isInitialized())
    }

    @Test
    fun `isInitialized returns false when status is not READY`() {
        state.networkStatus.set("INITIALIZING")
        assertFalse(state.isInitialized())
    }

    @Test
    fun `networkStatus atomic compareAndSet succeeds on match`() {
        state.networkStatus.set("INITIALIZING")
        assertTrue(state.networkStatus.compareAndSet("INITIALIZING", "READY"))
        assertEquals("READY", state.networkStatus.get())
    }

    @Test
    fun `networkStatus atomic compareAndSet fails on mismatch`() {
        state.networkStatus.set("READY")
        assertFalse(state.networkStatus.compareAndSet("INITIALIZING", "ERROR"))
        assertEquals("READY", state.networkStatus.get())
    }
}
