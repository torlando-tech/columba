package network.columba.app.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.rns.host.manager.CurrentTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceTransportObserverTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var transportAdmin: RnsTransportAdmin
    private lateinit var observer: InterfaceTransportObserver

    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        context = mockk()
        connectivityManager = mockk()
        interfaceRepository = mockk()
        transportAdmin = mockk()

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.getActiveNetwork() } returns null
        coEvery { transportAdmin.reloadInterfaces(any()) } just Runs
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs

        observer =
            InterfaceTransportObserver(
                context = context,
                interfaceRepository = interfaceRepository,
                transportAdmin = transportAdmin,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `start registers default network callback and seeds from snapshot`() =
        runTest {
            val activeNetwork = mockk<Network>()
            val caps = wifiCaps()
            every { connectivityManager.getActiveNetwork() } returns activeNetwork
            every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns caps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs

            observer.start(testScope.backgroundScope)

            verify(exactly = 1) { connectivityManager.registerDefaultNetworkCallback(any()) }
            assertEquals(CurrentTransport.WIFI_LIKE, observer.currentTransport.value)
            assertEquals(CurrentTransport.WIFI_LIKE, observer.snapshotTransport())
        }

    @Test
    fun `registration callback cannot be overwritten by an older startup snapshot`() =
        runTest {
            val oldNetwork = mockk<Network>()
            val newNetwork = mockk<Network>()
            val oldCaps = wifiCaps()
            val newCaps = cellularCaps()
            every { connectivityManager.getActiveNetwork() } returns oldNetwork
            every { connectivityManager.getNetworkCapabilities(oldNetwork) } returns oldCaps
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())
            every { connectivityManager.registerDefaultNetworkCallback(any()) } answers {
                firstArg<ConnectivityManager.NetworkCallback>().apply {
                    onAvailable(newNetwork)
                    onCapabilitiesChanged(newNetwork, newCaps)
                }
                Unit
            }

            observer.start(testScope.backgroundScope)

            assertEquals(CurrentTransport.CELLULAR, observer.currentTransport.value)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(any())
            }
        }

    @Test
    fun `first default capabilities trigger filtering and reload`() =
        runTest {
            val defaultNetwork = mockk<Network>()
            val caps = wifiCaps()
            every { connectivityManager.getActiveNetwork() } returns defaultNetwork
            every { connectivityManager.getNetworkCapabilities(defaultNetwork) } returns caps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            callbackSlot.captured.onAvailable(defaultNetwork)
            callbackSlot.captured.onCapabilitiesChanged(defaultNetwork, caps)
            advanceUntilIdle()

            assertEquals(CurrentTransport.WIFI_LIKE, observer.currentTransport.value)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(
                    match { configs ->
                        configs == listOf(sampleConfigs()[0], sampleConfigs()[1])
                    },
                )
            }
        }

    @Test
    fun `wifi to cellular handoff updates state and reloads once per meaningful transition`() =
        runTest {
            val wifiNetwork = mockk<Network>()
            val cellularNetwork = mockk<Network>()
            val wifiCaps = wifiCaps()
            val cellularCaps = cellularCaps()
            every { connectivityManager.getActiveNetwork() } returns wifiNetwork
            every { connectivityManager.getNetworkCapabilities(wifiNetwork) } returns wifiCaps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            callbackSlot.captured.onAvailable(wifiNetwork)
            callbackSlot.captured.onCapabilitiesChanged(wifiNetwork, wifiCaps)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(any())
            }

            callbackSlot.captured.onAvailable(cellularNetwork)
            callbackSlot.captured.onCapabilitiesChanged(cellularNetwork, cellularCaps)
            coVerify(timeout = 5_000, exactly = 2) {
                transportAdmin.reloadInterfaces(any())
            }

            assertEquals(CurrentTransport.CELLULAR, observer.currentTransport.value)
        }

    @Test
    fun `stale wifi onLost after cellular promotion does not emit none or reload`() =
        runTest {
            val wifiNetwork = mockk<Network>()
            val cellularNetwork = mockk<Network>()
            val wifiCaps = wifiCaps()
            val cellularCaps = cellularCaps()
            every { connectivityManager.getActiveNetwork() } returns wifiNetwork
            every { connectivityManager.getNetworkCapabilities(wifiNetwork) } returns wifiCaps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            callbackSlot.captured.onAvailable(wifiNetwork)
            callbackSlot.captured.onCapabilitiesChanged(wifiNetwork, wifiCaps)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(any())
            }
            callbackSlot.captured.onAvailable(cellularNetwork)
            callbackSlot.captured.onCapabilitiesChanged(cellularNetwork, cellularCaps)
            coVerify(timeout = 5_000, exactly = 2) {
                transportAdmin.reloadInterfaces(any())
            }

            callbackSlot.captured.onLost(wifiNetwork)
            advanceUntilIdle()

            assertEquals(CurrentTransport.CELLULAR, observer.currentTransport.value)
            coVerify(timeout = 5_000, exactly = 2) {
                transportAdmin.reloadInterfaces(any())
            }
        }

    @Test
    fun `final default loss emits none and reloads`() =
        runTest {
            val wifiNetwork = mockk<Network>()
            val wifiCaps = wifiCaps()
            every { connectivityManager.getActiveNetwork() } returns wifiNetwork
            every { connectivityManager.getNetworkCapabilities(wifiNetwork) } returns wifiCaps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            callbackSlot.captured.onAvailable(wifiNetwork)
            callbackSlot.captured.onCapabilitiesChanged(wifiNetwork, wifiCaps)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(any())
            }

            callbackSlot.captured.onLost(wifiNetwork)
            advanceUntilIdle()

            assertEquals(CurrentTransport.NONE, observer.currentTransport.value)
            coVerify(timeout = 5_000, exactly = 2) {
                transportAdmin.reloadInterfaces(any())
            }
        }

    @Test
    fun `vpn and unsupported capabilities emit unknown`() =
        runTest {
            val defaultNetwork = mockk<Network>()
            val caps = mockk<NetworkCapabilities>()
            every { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
            every { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
            every { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
            every { connectivityManager.getActiveNetwork() } returns defaultNetwork
            every { connectivityManager.getNetworkCapabilities(defaultNetwork) } returns caps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            callbackSlot.captured.onAvailable(defaultNetwork)
            callbackSlot.captured.onCapabilitiesChanged(defaultNetwork, caps)
            advanceUntilIdle()

            assertEquals(CurrentTransport.UNKNOWN, observer.currentTransport.value)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(
                    match { configs -> configs == listOf(sampleConfigs()[1]) },
                )
            }
        }

    @Test
    fun `duplicate same bucket capability updates are deduplicated`() =
        runTest {
            val defaultNetwork = mockk<Network>()
            val wifiCaps = wifiCaps()
            every { connectivityManager.getActiveNetwork() } returns defaultNetwork
            every { connectivityManager.getNetworkCapabilities(defaultNetwork) } returns wifiCaps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            callbackSlot.captured.onAvailable(defaultNetwork)
            callbackSlot.captured.onCapabilitiesChanged(defaultNetwork, wifiCaps)
            callbackSlot.captured.onCapabilitiesChanged(defaultNetwork, wifiCaps)
            advanceUntilIdle()

            assertEquals(CurrentTransport.WIFI_LIKE, observer.currentTransport.value)
            coVerify(timeout = 5_000, exactly = 1) {
                transportAdmin.reloadInterfaces(any())
            }
        }

    @Test
    fun `stop and restart clears callback identity and duplicate state`() =
        runTest {
            val firstNetwork = mockk<Network>()
            val secondNetwork = mockk<Network>()
            val wifiCaps = wifiCaps()
            every { connectivityManager.getActiveNetwork() } returns firstNetwork
            every { connectivityManager.getNetworkCapabilities(firstNetwork) } returns wifiCaps
            every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
            every { interfaceRepository.enabledInterfaces } returns flowOf(sampleConfigs())

            observer.start(testScope.backgroundScope)
            val firstCallback = callbackSlot.captured
            firstCallback.onAvailable(firstNetwork)
            firstCallback.onCapabilitiesChanged(firstNetwork, wifiCaps)
            advanceUntilIdle()

            observer.stop()
            verify(exactly = 1) { connectivityManager.unregisterNetworkCallback(firstCallback) }

            every { connectivityManager.getActiveNetwork() } returns secondNetwork
            every { connectivityManager.getNetworkCapabilities(secondNetwork) } returns wifiCaps
            observer.start(testScope.backgroundScope)
            val secondCallback = callbackSlot.captured
            assertTrue(firstCallback !== secondCallback)

            secondCallback.onAvailable(secondNetwork)
            secondCallback.onCapabilitiesChanged(secondNetwork, wifiCaps)
            advanceUntilIdle()

            coVerify(timeout = 5_000, atLeast = 1) {
                transportAdmin.reloadInterfaces(any())
            }
        }

    private fun wifiCaps(): NetworkCapabilities =
        mockk<NetworkCapabilities>().also {
            every { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
            every { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
            every { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        }

    private fun cellularCaps(): NetworkCapabilities =
        mockk<NetworkCapabilities>().also {
            every { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
            every { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
            every { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true
        }

    private fun sampleConfigs(): List<InterfaceConfig> =
        listOf(
            InterfaceConfig.AutoInterface(name = "wifi", networkRestriction = NetworkRestriction.WIFI_ONLY),
            InterfaceConfig.TCPClient(name = "any", targetHost = "10.0.0.1", targetPort = 4242),
            InterfaceConfig.TCPClient(
                name = "cell",
                targetHost = "10.0.0.2",
                targetPort = 4242,
                networkRestriction = NetworkRestriction.CELLULAR_ONLY,
            ),
        )
}
