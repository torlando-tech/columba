package network.columba.app.di

import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import network.columba.app.reticulum.protocol.NativeReticulumProtocol
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.rns.backend.kt.NativeRnsBackend
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ReticulumModule.
 *
 * Verifies that the Hilt DI module provides the correct implementation
 * of ReticulumProtocol and that the facade shares the singleton
 * [NativeRnsBackend] (vs constructing a parallel instance, the bug fixed
 * in A.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReticulumModuleTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockBackend: NativeRnsBackend

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockBackend = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `provideReticulumProtocol - returns ReticulumProtocol instance`() {
        val protocol: ReticulumProtocol =
            ReticulumModule.provideReticulumProtocol(mockBackend)

        assertNotNull(protocol)
        assertTrue("Protocol should implement ReticulumProtocol", protocol is ReticulumProtocol)
    }

    @Test
    fun `provideReticulumProtocol - facade reuses injected backend singleton`() {
        val protocol = ReticulumModule.provideReticulumProtocol(mockBackend) as NativeReticulumProtocol

        // The facade must not construct its own NativeRnsBackend — A.10 callers and
        // the ReticulumProtocol-injecting UI callers need to observe the same RNS
        // Transport / LXMF Router / call state.
        assertSame(mockBackend, protocol.backend)
    }
}
