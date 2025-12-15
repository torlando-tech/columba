package com.lxmf.messenger.reticulum.protocol

import android.content.Context
import android.content.ServiceConnection
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.IReticulumServiceCallback
import com.lxmf.messenger.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceReticulumProtocol callback emissions.
 *
 * Tests that callbacks from the service correctly emit to their respective flows:
 * - onBleConnectionChanged -> bleConnectionsFlow
 * - onDebugInfoChanged -> debugInfoFlow
 * - onInterfaceStatusChanged -> interfaceStatusFlow
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceReticulumProtocolCallbackTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mockService: IReticulumService
    private lateinit var protocol: ServiceReticulumProtocol

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock static AIDL method
        mockkStatic(IReticulumService.Stub::class)

        // Create mocks
        context = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        mockService = mockk(relaxed = true)

        // Default settings repository behavior
        coEvery { settingsRepository.lastServiceStatusFlow } returns flowOf("SHUTDOWN")
        coEvery { settingsRepository.saveServiceStatus(any()) } just Runs
        coEvery { settingsRepository.saveIsSharedInstance(any()) } just Runs

        // Capture service connection for lifecycle simulation
        val connectionSlot = slot<ServiceConnection>()
        every {
            context.bindService(
                any<android.content.Intent>(),
                capture(connectionSlot),
                any<Int>(),
            )
        } returns true
        every { context.unbindService(any()) } just Runs
        every { context.startService(any()) } returns mockk()
        every { context.startForegroundService(any()) } returns mockk()

        // Default service mock behaviors
        every { mockService.getStatus() } returns "SHUTDOWN"
        every { mockService.isInitialized() } returns false
        every { mockService.registerCallback(any()) } just Runs
        every { mockService.unregisterCallback(any()) } just Runs
        every { mockService.registerReadinessCallback(any()) } just Runs

        // Mock the static AIDL asInterface method
        every { IReticulumService.Stub.asInterface(any()) } returns mockService

        // Create protocol instance
        protocol = ServiceReticulumProtocol(context, settingsRepository)
    }

    @After
    fun tearDown() {
        if (::protocol.isInitialized) {
            protocol.cleanup()
        }
        Dispatchers.resetMain()
        unmockkStatic(IReticulumService.Stub::class)
        clearAllMocks()
    }

    @Test
    fun `onBleConnectionChanged emits to bleConnectionsFlow`() =
        runTest {
            // Given - access the internal callback via reflection
            val callbackField =
                ServiceReticulumProtocol::class.java
                    .getDeclaredField("serviceCallback")
            callbackField.isAccessible = true
            val callback = callbackField.get(protocol) as IReticulumServiceCallback

            val testJson = """[{"identityHash":"abc123","address":"AA:BB:CC:DD:EE:FF"}]"""

            // When
            callback.onBleConnectionChanged(testJson)

            // Then
            val emitted = withTimeout(1000) { protocol.bleConnectionsFlow.first() }
            assertEquals(testJson, emitted)
        }

    @Test
    fun `onDebugInfoChanged emits to debugInfoFlow`() =
        runTest {
            // Given
            val callbackField =
                ServiceReticulumProtocol::class.java
                    .getDeclaredField("serviceCallback")
            callbackField.isAccessible = true
            val callback = callbackField.get(protocol) as IReticulumServiceCallback

            val testJson = """{"initialized":true,"storage_path":"/test"}"""

            // When
            callback.onDebugInfoChanged(testJson)

            // Then
            val emitted = withTimeout(1000) { protocol.debugInfoFlow.first() }
            assertEquals(testJson, emitted)
        }

    @Test
    fun `onInterfaceStatusChanged emits to interfaceStatusFlow`() =
        runTest {
            // Given
            val callbackField =
                ServiceReticulumProtocol::class.java
                    .getDeclaredField("serviceCallback")
            callbackField.isAccessible = true
            val callback = callbackField.get(protocol) as IReticulumServiceCallback

            val testJson = """{"RNode":true,"BLE":false}"""

            // When
            callback.onInterfaceStatusChanged(testJson)

            // Then
            val emitted = withTimeout(1000) { protocol.interfaceStatusFlow.first() }
            assertEquals(testJson, emitted)
        }

    @Test
    fun `callback handles empty strings without crashing`() =
        runTest {
            // Given
            val callbackField =
                ServiceReticulumProtocol::class.java
                    .getDeclaredField("serviceCallback")
            callbackField.isAccessible = true
            val callback = callbackField.get(protocol) as IReticulumServiceCallback

            // When - call with empty strings (edge case)
            callback.onBleConnectionChanged("")
            callback.onDebugInfoChanged("")
            callback.onInterfaceStatusChanged("")

            // Then - no exception thrown, flows should have emitted the empty strings
            assertTrue("Should not crash on empty strings", true)
        }

    @Test
    fun `flows have replay of 1 for late subscribers`() =
        runTest {
            // Given - emit before subscribing
            val callbackField =
                ServiceReticulumProtocol::class.java
                    .getDeclaredField("serviceCallback")
            callbackField.isAccessible = true
            val callback = callbackField.get(protocol) as IReticulumServiceCallback

            val testJson = """{"test":"replay"}"""
            callback.onDebugInfoChanged(testJson)

            // When - subscribe after emission
            val emitted = withTimeout(1000) { protocol.debugInfoFlow.first() }

            // Then - should receive the replayed value
            assertEquals(testJson, emitted)
        }

    @Test
    fun `multiple emissions replace previous values in replay buffer`() =
        runTest {
            // Given
            val callbackField =
                ServiceReticulumProtocol::class.java
                    .getDeclaredField("serviceCallback")
            callbackField.isAccessible = true
            val callback = callbackField.get(protocol) as IReticulumServiceCallback

            // When - emit multiple values
            callback.onDebugInfoChanged("""{"version":1}""")
            callback.onDebugInfoChanged("""{"version":2}""")
            callback.onDebugInfoChanged("""{"version":3}""")

            // Then - new subscriber should get latest value
            val emitted = withTimeout(1000) { protocol.debugInfoFlow.first() }
            assertEquals("""{"version":3}""", emitted)
        }
}
