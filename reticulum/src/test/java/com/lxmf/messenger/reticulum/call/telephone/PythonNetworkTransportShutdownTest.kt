package com.lxmf.messenger.reticulum.call.telephone

import com.chaquo.python.PyObject
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import tech.torlando.lxst.core.PacketRouter

/**
 * Tests that PythonNetworkTransport's shuttingDown flag prevents
 * Python JNI calls during shutdown (SIGSEGV prevention).
 *
 * Note: PyObject.toString() is native JNI, so MockK's verify() can't be used
 * on PyObject mocks (UnsatisfiedLinkError during recording). Instead we verify
 * observable behavior: return values and absence of exceptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PythonNetworkTransportShutdownTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var mockBridge: PacketRouter
    private lateinit var mockCallManager: PyObject
    private lateinit var transport: PythonNetworkTransport

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        mockBridge = mockk()
        mockCallManager = mockk()
        transport = PythonNetworkTransport(mockBridge, mockCallManager, testScope)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shuttingDown is initially false`() {
        assertFalse(transport.shuttingDown)
    }

    @Test
    fun `establishLink returns false when shuttingDown`() =
        testScope.runTest {
            transport.shuttingDown = true

            val result = transport.establishLink(ByteArray(16))

            // Guard returns false without calling into Python
            assertFalse(result)
        }

    @Test
    fun `teardownLink does not crash when shuttingDown`() =
        testScope.runTest {
            transport.shuttingDown = true

            // Should return immediately without launching coroutine to call Python
            transport.teardownLink()
            advanceUntilIdle()

            // If we reach here without UnsatisfiedLinkError, the guard worked
            assertFalse(transport.isLinkActive)
        }
}
