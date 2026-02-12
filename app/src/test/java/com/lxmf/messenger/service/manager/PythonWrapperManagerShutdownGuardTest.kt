package com.lxmf.messenger.service.manager

import com.chaquo.python.PyObject
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests that PythonWrapperManager.withWrapper() guards against Python calls
 * after shutdown has started (SIGSEGV prevention).
 *
 * withCallManager() has identical guard logic but is private inline,
 * so it's tested transitively via PythonNetworkTransportShutdownTest.
 */
class PythonWrapperManagerShutdownGuardTest {
    private lateinit var state: ServiceState
    private lateinit var manager: PythonWrapperManager
    private lateinit var mockWrapper: PyObject

    @Before
    fun setup() {
        state = ServiceState()
        mockWrapper = mockk()
        manager =
            PythonWrapperManager(
                state = state,
                context = mockk(),
                scope = TestScope(),
            )
    }

    @Test
    fun `withWrapper returns value when wrapper set and shutdown not started`() {
        state.wrapper = mockWrapper

        val result = manager.withWrapper { "hello" }

        assertEquals("hello", result)
    }

    @Test
    fun `withWrapper returns null when shutdown started`() {
        state.wrapper = mockWrapper
        state.isPythonShutdownStarted.set(true)

        val result = manager.withWrapper { "hello" }

        assertNull(result)
    }

    @Test
    fun `withWrapper returns null when wrapper is null`() {
        // wrapper is null by default
        val result = manager.withWrapper { "hello" }

        assertNull(result)
    }

    @Test
    fun `withWrapper returns null when both shutdown and wrapper null`() {
        state.isPythonShutdownStarted.set(true)

        val result = manager.withWrapper { "hello" }

        assertNull(result)
    }

    @Test
    fun `withWrapper block is not executed when shutdown started`() {
        state.wrapper = mockWrapper
        state.isPythonShutdownStarted.set(true)

        var blockExecuted = false
        manager.withWrapper { blockExecuted = true }

        assertEquals(false, blockExecuted)
    }

    @Test
    fun `withWrapper resumes after clearShutdownFlag`() {
        state.wrapper = mockWrapper

        // Simulate shutdown
        state.isPythonShutdownStarted.set(true)
        assertNull(manager.withWrapper { "blocked" })

        // Simulate re-initialization clearing the flag
        state.clearShutdownFlag()
        assertEquals("unblocked", manager.withWrapper { "unblocked" })
    }
}
