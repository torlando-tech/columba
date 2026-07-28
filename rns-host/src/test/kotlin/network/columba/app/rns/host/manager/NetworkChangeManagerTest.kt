package network.columba.app.rns.host.manager

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.runs
import io.mockk.verify
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

        every { lockManager.acquireAll() } returns Unit
        every { lockManager.releaseAll() } returns Unit

        every { context.getSystemService(any<String>()) } returns connectivityManager
        every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just runs
        every {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
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
        clearAllMocks()
    }

    @Test
    fun `start registers default network callback`() {
        networkChangeManager.start()

        assertTrue("Should be monitoring after start", networkChangeManager.isMonitoring())
        verify(exactly = 1) {
            connectivityManager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `stop unregisters network callback`() {
        networkChangeManager.start()
        networkChangeManager.stop()

        assertFalse("Should not be monitoring after stop", networkChangeManager.isMonitoring())
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

        networkChangeManager.start()

        assertTrue("Should still be monitoring after restart", networkChangeManager.isMonitoring())
        verify(exactly = 1) {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `first default network available reacquires locks`() {
        networkChangeManager.start()

        val network = mockk<android.net.Network>(relaxed = true)
        callbackSlot.captured.onAvailable(network)

        assertEquals(1, networkChangedCallCount)
        verify(exactly = 1) { lockManager.acquireAll() }
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `network change triggers callback and reacquires locks`() {
        networkChangeManager.start()

        val network1 = mockk<android.net.Network>(relaxed = true)
        val network2 = mockk<android.net.Network>(relaxed = true)

        callbackSlot.captured.onAvailable(network1)
        callbackSlot.captured.onAvailable(network2)

        assertEquals(2, networkChangedCallCount)
        verify(exactly = 2) { lockManager.acquireAll() }
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `same network reconnecting does not trigger callback again`() {
        networkChangeManager.start()

        val network = mockk<android.net.Network>(relaxed = true)

        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onAvailable(network)

        assertEquals(1, networkChangedCallCount)
        verify(exactly = 1) { lockManager.acquireAll() }
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `exception in lock acquisition does not crash`() {
        every { lockManager.acquireAll() } throws RuntimeException("Test error")

        networkChangeManager.start()

        val network1 = mockk<android.net.Network>(relaxed = true)
        val network2 = mockk<android.net.Network>(relaxed = true)

        val result1 = runCatching { callbackSlot.captured.onAvailable(network1) }
        val result2 = runCatching { callbackSlot.captured.onAvailable(network2) }

        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        assertEquals(2, networkChangedCallCount)
    }

    @Suppress("NoRelaxedMocks")
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
        val network2 = mockk<android.net.Network>(relaxed = true)

        callbackSlot.captured.onAvailable(network1)

        val result = runCatching { callbackSlot.captured.onAvailable(network2) }

        assertTrue("Exception in callback should not crash", result.isSuccess)
        crashingManager.stop()
    }

    @Test
    fun `registration failure is handled gracefully`() {
        every {
            connectivityManager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        } throws RuntimeException("Registration failed")

        networkChangeManager.start()

        assertFalse(networkChangeManager.isMonitoring())
    }

    @Test
    fun `unregistration failure is handled gracefully`() {
        networkChangeManager.start()

        every {
            connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>())
        } throws RuntimeException("Unregistration failed")

        networkChangeManager.stop()

        assertFalse(networkChangeManager.isMonitoring())
    }

    // ========== onTransportChanged callback tests ==========

    @Suppress("NoRelaxedMocks")
    @Test
    fun `broad backup capabilities do not drive transport when not default`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val defaultNetwork = mockk<android.net.Network>(relaxed = true)
        val backupNetwork = mockk<android.net.Network>(relaxed = true)
        val wifiCaps = mockNetworkCapabilities(wifi = true)
        val cellularCaps = mockNetworkCapabilities(cellular = true)

        mgr.start()
        callbackSlot.captured.onAvailable(defaultNetwork)
        callbackSlot.captured.onCapabilitiesChanged(defaultNetwork, wifiCaps)
        callbackSlot.captured.onCapabilitiesChanged(backupNetwork, cellularCaps)

        assertEquals(listOf(CurrentTransport.WIFI_LIKE), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `wifi to cellular handoff emits both transports in order`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val wifiNetwork = mockk<android.net.Network>(relaxed = true)
        val cellularNetwork = mockk<android.net.Network>(relaxed = true)

        mgr.start()
        callbackSlot.captured.onAvailable(wifiNetwork)
        callbackSlot.captured.onCapabilitiesChanged(wifiNetwork, mockNetworkCapabilities(wifi = true))
        callbackSlot.captured.onAvailable(cellularNetwork)
        callbackSlot.captured.onCapabilitiesChanged(
            cellularNetwork,
            mockNetworkCapabilities(cellular = true),
        )

        assertEquals(listOf(CurrentTransport.WIFI_LIKE, CurrentTransport.CELLULAR), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `old default onLost after replacement does not emit NONE`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val wifiNetwork = mockk<android.net.Network>(relaxed = true)
        val cellularNetwork = mockk<android.net.Network>(relaxed = true)

        mgr.start()
        callbackSlot.captured.onAvailable(wifiNetwork)
        callbackSlot.captured.onCapabilitiesChanged(wifiNetwork, mockNetworkCapabilities(wifi = true))
        callbackSlot.captured.onAvailable(cellularNetwork)
        callbackSlot.captured.onCapabilitiesChanged(
            cellularNetwork,
            mockNetworkCapabilities(cellular = true),
        )
        callbackSlot.captured.onLost(wifiNetwork)

        assertEquals(listOf(CurrentTransport.WIFI_LIKE, CurrentTransport.CELLULAR), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `true final default loss emits NONE`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val network = mockk<android.net.Network>(relaxed = true)

        mgr.start()
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onCapabilitiesChanged(network, mockNetworkCapabilities(wifi = true))
        callbackSlot.captured.onLost(network)

        assertEquals(listOf(CurrentTransport.WIFI_LIKE, CurrentTransport.NONE), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `vpn only keeps any active but does not satisfy wifi or cellular restrictions`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val vpnNetwork = mockk<android.net.Network>(relaxed = true)

        mgr.start()
        callbackSlot.captured.onAvailable(vpnNetwork)
        callbackSlot.captured.onCapabilitiesChanged(vpnNetwork, mockNetworkCapabilities(vpn = true))

        assertEquals(listOf(CurrentTransport.UNKNOWN), transports)
        assertEquals(
            listOf("any"),
            filterByTransport(listOf(tcp("any", network.columba.app.rns.api.model.NetworkRestriction.ANY)), CurrentTransport.UNKNOWN)
                .map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            filterByTransport(
                listOf(tcp("wifi", network.columba.app.rns.api.model.NetworkRestriction.WIFI_ONLY)),
                CurrentTransport.UNKNOWN,
            ).map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            filterByTransport(
                listOf(tcp("cell", network.columba.app.rns.api.model.NetworkRestriction.CELLULAR_ONLY)),
                CurrentTransport.UNKNOWN,
            ).map { it.name },
        )
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `unsupported live default transport emits UNKNOWN rather than NONE`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val network = mockk<android.net.Network>(relaxed = true)

        mgr.start()
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onCapabilitiesChanged(network, mockNetworkCapabilities())

        assertEquals(listOf(CurrentTransport.UNKNOWN), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `vpn with deterministic wifi underlay classifies as wifi like`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val vpnNetwork = mockk<android.net.Network>(relaxed = true)

        mgr.start()
        callbackSlot.captured.onAvailable(vpnNetwork)
        callbackSlot.captured.onCapabilitiesChanged(
            vpnNetwork,
            mockNetworkCapabilities(vpn = true, wifi = true),
        )

        assertEquals(listOf(CurrentTransport.WIFI_LIKE), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `duplicate capability updates remain deduplicated`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val network = mockk<android.net.Network>(relaxed = true)
        val wifiCaps = mockNetworkCapabilities(wifi = true)
        val wifiCapsMetered = mockNetworkCapabilities(wifi = true, metered = true)

        mgr.start()
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onCapabilitiesChanged(network, wifiCaps)
        callbackSlot.captured.onCapabilitiesChanged(network, wifiCapsMetered)
        callbackSlot.captured.onCapabilitiesChanged(network, wifiCapsMetered)

        assertEquals(listOf(CurrentTransport.WIFI_LIKE), transports)
        mgr.stop()
    }

    @Suppress("NoRelaxedMocks")
    @Test
    fun `ethernet active network buckets as wifi like`() {
        val transports = mutableListOf<CurrentTransport>()
        val mgr = transportManager(transports)
        val network = mockk<android.net.Network>(relaxed = true)
        val ethernetCaps = mockNetworkCapabilities(ethernet = true)

        mgr.start()
        callbackSlot.captured.onAvailable(network)
        callbackSlot.captured.onCapabilitiesChanged(network, ethernetCaps)

        assertEquals(listOf(CurrentTransport.WIFI_LIKE), transports)
        mgr.stop()
    }

    private fun transportManager(transports: MutableList<CurrentTransport>) =
        NetworkChangeManager(
            context = context,
            lockManager = lockManager,
            onTransportChanged = { transports.add(it) },
        )

    @Suppress("NoRelaxedMocks")
    private fun mockNetworkCapabilities(
        wifi: Boolean = false,
        ethernet: Boolean = false,
        cellular: Boolean = false,
        vpn: Boolean = false,
        metered: Boolean = false,
    ): android.net.NetworkCapabilities {
        val caps = mockk<android.net.NetworkCapabilities>(relaxed = true)
        every { caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) } returns wifi
        every {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        } returns ethernet
        every {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        } returns cellular
        every { caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) } returns vpn
        every {
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } returns !metered
        return caps
    }

    private fun tcp(
        name: String,
        restriction: network.columba.app.rns.api.model.NetworkRestriction,
    ): network.columba.app.rns.api.model.InterfaceConfig.TCPClient =
        network.columba.app.rns.api.model.InterfaceConfig.TCPClient(
            name = name,
            targetHost = "10.0.0.1",
            targetPort = 4242,
            networkRestriction = restriction,
        )
}
