package network.columba.app.service.manager

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
import org.junit.After
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
class NetworkChangeManagerTest {
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var lockManager: LockManager
    private lateinit var networkChangeManager: NetworkChangeManager
    private var networkChangedCallCount = 0
    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()

    @Suppress("NoRelaxedMocks") // Android Context and system services require relaxed mocks
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        lockManager = mockk()
        networkChangedCallCount = 0

        // Explicit stubs for LockManager
        every { lockManager.acquireAll() } returns Unit
        every { lockManager.releaseAll() } returns Unit

        // Mock Android framework classes
        mockkConstructor(NetworkRequest.Builder::class)
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } answers {
            self as NetworkRequest.Builder
        }
        @Suppress("NoRelaxedMocks") // Android NetworkRequest.Builder
        val networkRequest = mockk<NetworkRequest>(relaxed = true)
        every { anyConstructed<NetworkRequest.Builder>().build() } returns networkRequest

        every { context.getSystemService(any<String>()) } returns connectivityManager
        every {
            connectivityManager.registerNetworkCallback(any(), capture(callbackSlot))
        } just runs

        networkChangeManager =
            NetworkChangeManager(
                context = context,
                lockManager = lockManager,
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

        val isMonitoring = networkChangeManager.isMonitoring()
        assertTrue("Should be monitoring after start", isMonitoring)
        verify(exactly = 1) {
            connectivityManager.registerNetworkCallback(any(), any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `stop unregisters network callback`() {
        networkChangeManager.start()
        networkChangeManager.stop()

        val isMonitoring = networkChangeManager.isMonitoring()
        assertFalse("Should not be monitoring after stop", isMonitoring)
        verify(exactly = 1) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
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

        val isMonitoring = networkChangeManager.isMonitoring()
        assertTrue("Should still be monitoring after restart", isMonitoring)
        // Should have unregistered previous callback
        verify(exactly = 1) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Suppress("NoRelaxedMocks") // Android Network framework class
    @Test
    fun `first network available triggers callback for hot-add`() {
        networkChangeManager.start()

        // Simulate first network connection (e.g., WiFi connects after starting without it)
        val network = mockk<android.net.Network>(relaxed = true)
        every { network.toString() } returns "network1"

        callbackSlot.captured.onAvailable(network)

        // First network SHOULD trigger callback so AutoInterface can hot-add the new interface
        assertTrue("Callback should trigger on first network", networkChangedCallCount == 1)
        verify(exactly = 1) { lockManager.acquireAll() }
    }

    @Suppress("NoRelaxedMocks") // Android Network framework class
    @Test
    fun `network change triggers callback and reacquires locks`() {
        networkChangeManager.start()

        // Simulate first network (triggers once for initial connection)
        val network1 = mockk<android.net.Network>(relaxed = true)
        every { network1.toString() } returns "network1"
        callbackSlot.captured.onAvailable(network1)

        // Simulate network change (triggers again for the switch)
        val network2 = mockk<android.net.Network>(relaxed = true)
        every { network2.toString() } returns "network2"
        callbackSlot.captured.onAvailable(network2)

        // Should trigger callback for both first connection and network switch
        assertTrue("Callback should trigger on both connections", networkChangedCallCount == 2)
        verify(exactly = 2) { lockManager.acquireAll() }
    }

    @Suppress("NoRelaxedMocks") // Android Network framework class
    @Test
    fun `same network reconnecting does not trigger callback again`() {
        networkChangeManager.start()

        // Simulate network
        val network = mockk<android.net.Network>(relaxed = true)
        every { network.toString() } returns "network1"

        // Connect twice with same network
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onAvailable(network)

        // Should trigger once for first connection, but not for same-network reconnect
        assertTrue("Callback should only trigger once for same network", networkChangedCallCount == 1)
        verify(exactly = 1) { lockManager.acquireAll() }
    }

    @Suppress("NoRelaxedMocks") // Android Network framework class
    @Test
    fun `exception in lock acquisition does not crash`() {
        every { lockManager.acquireAll() } throws RuntimeException("Test error")

        networkChangeManager.start()

        // First connection triggers callback despite lock error
        val network1 = mockk<android.net.Network>(relaxed = true)
        every { network1.toString() } returns "network1"
        callbackSlot.captured.onAvailable(network1)

        // Network switch also triggers callback despite lock error
        val network2 = mockk<android.net.Network>(relaxed = true)
        every { network2.toString() } returns "network2"

        // Should not throw despite lock acquisition failure
        callbackSlot.captured.onAvailable(network2)

        // Callback should be invoked for both connections
        assertTrue("Callback should be invoked for both connections after lock error", networkChangedCallCount == 2)
    }

    @Suppress("NoRelaxedMocks") // Android Network framework class
    @Test
    fun `exception in callback does not crash`() {
        val crashingManager =
            NetworkChangeManager(
                context = context,
                lockManager = lockManager,
                onNetworkChanged = { throw IllegalStateException("Test error") },
            )

        crashingManager.start()

        val network1 = mockk<android.net.Network>(relaxed = true)
        every { network1.toString() } returns "network1"
        callbackSlot.captured.onAvailable(network1)

        val network2 = mockk<android.net.Network>(relaxed = true)
        every { network2.toString() } returns "network2"

        // Should not throw despite callback failure
        val result = runCatching { callbackSlot.captured.onAvailable(network2) }

        assertTrue("Exception in callback should not crash", result.isSuccess)
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
}
