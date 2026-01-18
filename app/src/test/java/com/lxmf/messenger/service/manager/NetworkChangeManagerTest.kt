package com.lxmf.messenger.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NetworkChangeManager.
 *
 * Tests the network connectivity monitoring that reacquires locks and
 * triggers LXMF announce when network changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkChangeManagerTest {
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var lockManager: LockManager
    private lateinit var networkChangeManager: NetworkChangeManager
    private var networkChangedCallCount = 0
    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        lockManager = mockk(relaxed = true)
        networkChangedCallCount = 0

        // Mock Android framework classes
        mockkConstructor(NetworkRequest.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } answers {
            self as NetworkRequest.Builder
        }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk(relaxed = true)

        every { context.getSystemService(any<String>()) } returns connectivityManager
        every {
            connectivityManager.registerNetworkCallback(any(), capture(callbackSlot))
        } just runs

        networkChangeManager =
            NetworkChangeManager(
                context = context,
                lockManager = lockManager,
                scope = testScope,
                onNetworkChanged = { networkChangedCallCount++ },
            )
    }

    @After
    fun tearDown() {
        networkChangeManager.stop()
        unmockkConstructor(NetworkRequest.Builder::class)
        clearAllMocks()
    }

    @Test
    fun `start registers network callback`() {
        networkChangeManager.start()

        verify(exactly = 1) {
            connectivityManager.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>())
        }
        assertTrue("Should be monitoring after start", networkChangeManager.isMonitoring())
    }

    @Test
    fun `stop unregisters network callback`() {
        networkChangeManager.start()
        networkChangeManager.stop()

        verify(exactly = 1) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
        assertFalse("Should not be monitoring after stop", networkChangeManager.isMonitoring())
    }

    @Test
    fun `isMonitoring returns false when not started`() {
        assertFalse("Should not be monitoring initially", networkChangeManager.isMonitoring())
    }

    @Test
    fun `stop is safe to call when not monitoring`() {
        assertFalse(networkChangeManager.isMonitoring())

        // Should not throw
        networkChangeManager.stop()

        assertFalse(networkChangeManager.isMonitoring())
    }

    @Test
    fun `stop is safe to call multiple times`() {
        networkChangeManager.start()

        networkChangeManager.stop()
        networkChangeManager.stop()
        networkChangeManager.stop()

        assertFalse(networkChangeManager.isMonitoring())
    }

    @Test
    fun `start when already monitoring stops previous monitoring`() {
        networkChangeManager.start()
        assertTrue(networkChangeManager.isMonitoring())

        // Start again should work without error
        networkChangeManager.start()

        assertTrue(networkChangeManager.isMonitoring())
        // Should have unregistered previous callback
        verify(exactly = 1) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `first network available does not trigger callback`() {
        networkChangeManager.start()

        // Simulate first network connection
        val network = mockk<android.net.Network>(relaxed = true)
        every { network.toString() } returns "network1"

        callbackSlot.captured.onAvailable(network)

        // First network should not trigger callback (no previous network)
        assertTrue("Callback should not trigger on first network", networkChangedCallCount == 0)
    }

    @Test
    fun `network change triggers callback and reacquires locks`() =
        testScope.runTest {
            networkChangeManager.start()

            // Simulate first network
            val network1 = mockk<android.net.Network>(relaxed = true)
            every { network1.toString() } returns "network1"
            callbackSlot.captured.onAvailable(network1)

            // Simulate network change
            val network2 = mockk<android.net.Network>(relaxed = true)
            every { network2.toString() } returns "network2"
            callbackSlot.captured.onAvailable(network2)

            // Locks should be reacquired immediately
            verify(exactly = 1) { lockManager.acquireAll() }

            // Callback is debounced - advance time to trigger it
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS + 100)

            // Should trigger callback after debounce
            assertEquals("Callback should trigger on network change", 1, networkChangedCallCount)
        }

    @Test
    fun `same network reconnecting does not trigger callback`() {
        networkChangeManager.start()

        // Simulate network
        val network = mockk<android.net.Network>(relaxed = true)
        every { network.toString() } returns "network1"

        // Connect twice with same network
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onAvailable(network)

        // Should not trigger callback for same network
        assertTrue("Callback should not trigger for same network", networkChangedCallCount == 0)
    }

    @Test
    fun `exception in lock acquisition does not crash`() =
        testScope.runTest {
            every { lockManager.acquireAll() } throws RuntimeException("Test error")

            networkChangeManager.start()

            val network1 = mockk<android.net.Network>(relaxed = true)
            every { network1.toString() } returns "network1"
            callbackSlot.captured.onAvailable(network1)

            val network2 = mockk<android.net.Network>(relaxed = true)
            every { network2.toString() } returns "network2"

            // Should not throw despite lock acquisition failure
            callbackSlot.captured.onAvailable(network2)

            // Advance time to trigger debounced callback
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS + 100)

            // Callback should still be invoked
            assertEquals("Callback should still be invoked after lock error", 1, networkChangedCallCount)
        }

    @Test
    fun `exception in callback does not crash`() =
        testScope.runTest {
            val crashingManager =
                NetworkChangeManager(
                    context = context,
                    lockManager = lockManager,
                    scope = testScope,
                    onNetworkChanged = { throw IllegalStateException("Test error") },
                )

            crashingManager.start()

            val network1 = mockk<android.net.Network>(relaxed = true)
            every { network1.toString() } returns "network1"
            callbackSlot.captured.onAvailable(network1)

            val network2 = mockk<android.net.Network>(relaxed = true)
            every { network2.toString() } returns "network2"

            // Should not throw despite callback failure
            callbackSlot.captured.onAvailable(network2)

            // Advance time to trigger debounced callback (which will throw but be caught)
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS + 100)

            crashingManager.stop()
        }

    @Test
    fun `registration failure is handled gracefully`() {
        every {
            connectivityManager.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>())
        } throws RuntimeException("Registration failed")

        // Should not throw
        networkChangeManager.start()

        // Should not be monitoring since registration failed
        assertFalse(networkChangeManager.isMonitoring())
    }

    @Test
    fun `unregistration failure is handled gracefully`() {
        networkChangeManager.start()

        every {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        } throws RuntimeException("Unregistration failed")

        // Should not throw
        networkChangeManager.stop()

        assertFalse(networkChangeManager.isMonitoring())
    }

    @Test
    fun `rapid network changes are debounced to single callback`() =
        testScope.runTest {
            networkChangeManager.start()

            // Simulate first network (sets lastNetworkId)
            val network1 = mockk<android.net.Network>(relaxed = true)
            every { network1.toString() } returns "network1"
            callbackSlot.captured.onAvailable(network1)

            // Simulate rapid network changes (like leaving WiFi range)
            repeat(5) { i ->
                val network = mockk<android.net.Network>(relaxed = true)
                every { network.toString() } returns "network${i + 2}"
                callbackSlot.captured.onAvailable(network)
            }

            // Advance past debounce window
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS + 100)

            // Should only call callback once (debouncing coalesces rapid changes)
            assertEquals("Should only trigger callback once due to debouncing", 1, networkChangedCallCount)
        }

    @Test
    fun `stop cancels pending debounced callback`() =
        testScope.runTest {
            networkChangeManager.start()

            // Simulate first network
            val network1 = mockk<android.net.Network>(relaxed = true)
            every { network1.toString() } returns "network1"
            callbackSlot.captured.onAvailable(network1)

            // Simulate network change (starts debounce timer)
            val network2 = mockk<android.net.Network>(relaxed = true)
            every { network2.toString() } returns "network2"
            callbackSlot.captured.onAvailable(network2)

            // Stop before debounce completes
            networkChangeManager.stop()
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS + 100)

            // Callback should not have been called since we stopped
            assertEquals("Callback should not trigger after stop", 0, networkChangedCallCount)
        }

    @Test
    fun `debounce resets on each network change`() =
        testScope.runTest {
            networkChangeManager.start()

            // Simulate first network
            val network1 = mockk<android.net.Network>(relaxed = true)
            every { network1.toString() } returns "network1"
            callbackSlot.captured.onAvailable(network1)

            // Simulate network change
            val network2 = mockk<android.net.Network>(relaxed = true)
            every { network2.toString() } returns "network2"
            callbackSlot.captured.onAvailable(network2)

            // Advance time but not past debounce
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS / 2)

            // Another network change - should reset debounce timer
            val network3 = mockk<android.net.Network>(relaxed = true)
            every { network3.toString() } returns "network3"
            callbackSlot.captured.onAvailable(network3)

            // Advance halfway again - still not past debounce from last change
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS / 2)

            // Callback should not have triggered yet
            assertEquals("Callback should not trigger before debounce completes", 0, networkChangedCallCount)

            // Advance past debounce from last change
            advanceTimeBy(NetworkChangeManager.DEBOUNCE_DELAY_MS / 2 + 100)

            // Now callback should have triggered once
            assertEquals("Callback should trigger after debounce", 1, networkChangedCallCount)
        }
}
