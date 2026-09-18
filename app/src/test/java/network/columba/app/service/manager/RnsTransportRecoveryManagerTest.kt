package network.columba.app.service.manager

import android.os.SystemClock
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.startup.ConfigApplyFlagManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RnsTransportRecoveryManager] (columba#1127 auto-recovery).
 *
 * Covers the [RnsTransportRecoveryManager.maybeRecover] decision table and the
 * [RnsTransportRecoveryManager.isIntactNow] invariant directly, without the
 * coroutine/timer plumbing of the transport-driven scheduling.
 */
class RnsTransportRecoveryManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var rnsCore: RnsCore
    private lateinit var rnsTransportAdmin: RnsTransportAdmin
    private lateinit var rnsBackend: RnsBackend
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var interfaceConfigManager: InterfaceConfigManager
    private lateinit var configApplyFlagManager: ConfigApplyFlagManager
    private lateinit var transportObserver: InterfaceTransportObserver

    private lateinit var networkStatus: MutableStateFlow<NetworkStatus>
    private lateinit var capabilities: MutableStateFlow<BackendCapabilities>
    private lateinit var currentTransport: MutableStateFlow<CurrentTransport>

    private var applyingConfig = false
    private var debugInfo: Map<String, Any> = mapOf("interfaces" to emptyList<Any>())
    private var enabledInterfaces: List<InterfaceConfig> = emptyList()
    private var clockMs = 0L

    private lateinit var manager: RnsTransportRecoveryManager

    private fun caps(hotReload: Boolean): BackendCapabilities =
        BackendCapabilities(
            backendId = BackendCapabilities.BackendId.PYTHON_CHAQUOPY,
            versions = BackendCapabilities.Versions(null, null, null, null),
            interfaces = BackendCapabilities.InterfaceCaps(hotReloadInterfaces = hotReload),
            telemetry =
                BackendCapabilities.TelemetryCaps(
                    collectorHostMode = BackendCapabilities.Support.UNSUPPORTED,
                    storeOwnTelemetry = BackendCapabilities.Support.UNSUPPORTED,
                    allowedRequestersFilter = BackendCapabilities.Support.UNSUPPORTED,
                ),
            performance =
                BackendCapabilities.PerformanceCaps(
                    batteryProfileTuning = BackendCapabilities.Support.UNSUPPORTED,
                    sharedInstanceAvailabilityChecks = false,
                ),
        )

    private fun autoInterface(name: String = "Local WiFi"): InterfaceConfig.AutoInterface =
        InterfaceConfig.AutoInterface(name = name, enabled = true)

    private fun bleInterface(name: String = "Bluetooth LE"): InterfaceConfig.AndroidBLE =
        InterfaceConfig.AndroidBLE(name = name, enabled = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        rnsCore = mockk()
        rnsTransportAdmin = mockk()
        rnsBackend = mockk()
        interfaceRepository = mockk()
        interfaceConfigManager = mockk()
        configApplyFlagManager = mockk()
        transportObserver = mockk()

        networkStatus = MutableStateFlow(NetworkStatus.READY)
        capabilities = MutableStateFlow(caps(hotReload = false))
        currentTransport = MutableStateFlow(CurrentTransport.WIFI_LIKE)
        applyingConfig = false
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        enabledInterfaces = listOf(autoInterface())
        // Baseline well past the initial lastRecoveryStartMs=0 sentinel so the first
        // recovery is never (falsely) inside the cooldown window.
        clockMs = 1_000_000L

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { clockMs }

        every { rnsCore.networkStatus } returns networkStatus
        every { rnsBackend.capabilities } returns capabilities
        every { transportObserver.currentTransport } returns currentTransport
        every { configApplyFlagManager.isApplyingConfig() } answers { applyingConfig }
        every { interfaceRepository.enabledInterfaces } answers { flowOf(enabledInterfaces) }
        coEvery { rnsTransportAdmin.getDebugInfo() } answers { debugInfo }
        coEvery { interfaceConfigManager.applyInterfaceChanges(any()) } returns Result.success(Unit)

        manager =
            RnsTransportRecoveryManager(
                rnsCore = rnsCore,
                rnsTransportAdmin = rnsTransportAdmin,
                rnsBackend = rnsBackend,
                interfaceRepository = interfaceRepository,
                interfaceConfigManager = interfaceConfigManager,
                configApplyFlagManager = configApplyFlagManager,
                transportObserver = transportObserver,
                applicationScope = testScope,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
        clearAllMocks()
    }

    // --- maybeRecover: capability gate ---

    @Test
    fun `hot-reload backend never triggers a restart`() = runTest {
        capabilities.value = caps(hotReload = true)
        debugInfo = mapOf("interfaces" to emptyList<Any>()) // dead state
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- maybeRecover: RNS status gate ---

    @Test
    fun `does not restart while RNS is initializing`() = runTest {
        networkStatus.value = NetworkStatus.INITIALIZING
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    @Test
    fun `does not restart while RNS is shut down`() = runTest {
        networkStatus.value = NetworkStatus.SHUTDOWN
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- maybeRecover: in-apply flag gate ---

    @Test
    fun `does not double-restart while an apply is already in flight`() = runTest {
        applyingConfig = true
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- maybeRecover: transport gate ---

    @Test
    fun `does not restart while transport is NONE`() = runTest {
        currentTransport.value = CurrentTransport.NONE
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- maybeRecover: healthy state ---

    @Test
    fun `does not restart when the live set already contains every enabled IP interface`() =
        runTest {
            debugInfo =
                mapOf(
                    "interfaces" to
                        listOf(
                            mapOf("name" to "Local WiFi", "online" to true),
                        ),
                )
            val recovered = manager.maybeRecover()
            assertFalse(recovered)
            coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
        }

    @Test
    fun `does not restart when only non-IP (BLE) interfaces are enabled and live`() = runTest {
        // A BLE-only config is intact when the BLE interface is live; the applied set
        // (filterByTransport keeps non-IP interfaces) is fully present, so no restart.
        enabledInterfaces = listOf(bleInterface())
        debugInfo =
            mapOf("interfaces" to listOf(mapOf("name" to "Bluetooth LE", "online" to true)))
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    @Test
    fun `does not restart when no interfaces are enabled at all`() = runTest {
        enabledInterfaces = emptyList()
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- maybeRecover: the #1127 dead state (the fix path) ---

    @Test
    fun `restarts when RNS is READY but enabled IP interfaces are missing (issue 1127 dead state)`() =
        runTest {
            // The dead state: RNS READY, 0 live interfaces, 1 enabled AutoInterface.
            debugInfo = mapOf("interfaces" to emptyList<Any>())
            val recovered = manager.maybeRecover()
            assertTrue(recovered)
            coVerify(exactly = 1) { interfaceConfigManager.applyInterfaceChanges(any()) }
        }

    @Test
    fun `restarts when only some enabled IP interfaces are live`() = runTest {
        enabledInterfaces = listOf(autoInterface("Local WiFi"), autoInterface("Guest WiFi"))
        debugInfo =
            mapOf(
                "interfaces" to
                    listOf(
                        mapOf("name" to "Local WiFi", "online" to true),
                    ),
            )
        val recovered = manager.maybeRecover()
        assertTrue(recovered)
        coVerify(exactly = 1) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    @Test
    fun `does not restart when live set is a superset (AutoDiscovery peers present)`() = runTest {
        debugInfo =
            mapOf(
                "interfaces" to
                    listOf(
                        mapOf("name" to "Local WiFi", "online" to true),
                        mapOf("name" to "Local WiFi Peer", "online" to true),
                    ),
            )
        val recovered = manager.maybeRecover()
        assertFalse(recovered)
        coVerify(exactly = 0) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- maybeRecover: cooldown ---

    @Test
    fun `does not restart again within the cooldown after a recovery`() = runTest {
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        assertTrue(manager.maybeRecover())
        // Immediately after (same clock tick): cooldown active, live set still empty.
        val second = manager.maybeRecover()
        assertFalse(second)
        coVerify(exactly = 1) { interfaceConfigManager.applyInterfaceChanges(any()) }
        // Once the cooldown elapses the manager may recover again (live set still empty).
        clockMs += RnsTransportRecoveryManager.RECOVERY_COOLDOWN_MS + 1
        val third = manager.maybeRecover()
        assertTrue(third)
        coVerify(exactly = 2) { interfaceConfigManager.applyInterfaceChanges(any()) }
    }

    // --- isIntactNow invariant ---

    @Test
    fun `isIntactNow is true when every enabled IP interface is live`() = runTest {
        debugInfo =
            mapOf("interfaces" to listOf(mapOf("name" to "Local WiFi", "online" to true)))
        assertTrue(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is false when an enabled IP interface is missing`() = runTest {
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        assertFalse(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is false when RNS is not READY`() = runTest {
        networkStatus.value = NetworkStatus.INITIALIZING
        debugInfo =
            mapOf("interfaces" to listOf(mapOf("name" to "Local WiFi", "online" to true)))
        assertFalse(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is false when the live set cannot be read`() = runTest {
        coEvery { rnsTransportAdmin.getDebugInfo() } throws RuntimeException("python down")
        assertFalse(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is vacuously true when no interface is enabled at all`() = runTest {
        enabledInterfaces = emptyList()
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        assertTrue(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is true when a non-IP (BLE) interface is live`() = runTest {
        // filterByTransport keeps non-IP interfaces (BLE never rides the IP carrier),
        // so an enabled BLE interface IS expected to be live.
        enabledInterfaces = listOf(bleInterface())
        debugInfo =
            mapOf("interfaces" to listOf(mapOf("name" to "Bluetooth LE", "online" to true)))
        assertTrue(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is false when a non-IP (BLE) interface is missing`() = runTest {
        enabledInterfaces = listOf(bleInterface())
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        assertFalse(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow ignores an interface filtered out by the current transport`() = runTest {
        // A WIFI_ONLY interface is deliberately NOT live while the device is on
        // cellular: applyInterfaceChanges filters it out. isIntactNow must treat the
        // healthy filtered state as intact (not restart in a loop).
        currentTransport.value = CurrentTransport.CELLULAR
        debugInfo = mapOf("interfaces" to emptyList<Any>()) // no IP interface live
        assertTrue(manager.isIntactNow())
    }

    @Test
    fun `isIntactNow is false when a transport-eligible interface is missing`() = runTest {
        // On WiFi a WIFI_ONLY interface IS expected live; if it is absent the set is
        // damaged and recovery should fire.
        currentTransport.value = CurrentTransport.WIFI_LIKE
        debugInfo = mapOf("interfaces" to emptyList<Any>())
        assertFalse(manager.isIntactNow())
    }

    // --- getDebugInfo shape tolerance ---

    @Test
    fun `treats a missing interfaces key as an unknown (not intact) live set`() = runTest {
        debugInfo = mapOf("initialized" to true) // no "interfaces" key
        assertFalse(manager.isIntactNow())
    }

    @Test
    fun `skips blank and placeholder interface names when reading the live set`() = runTest {
        debugInfo =
            mapOf(
                "interfaces" to
                    listOf(
                        mapOf("name" to "Local WiFi", "online" to true),
                        mapOf("name" to "", "online" to true),
                        mapOf("name" to "?", "online" to true),
                    ),
            )
        assertTrue(manager.isIntactNow())
    }
}
