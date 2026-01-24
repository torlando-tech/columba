package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.DiscoveredInterface
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for DiscoveredInterfacesViewModel.
 *
 * Tests cover:
 * - Initial state
 * - Loading discovered interfaces
 * - autoconnectedEndpoints state management
 * - isAutoconnected() helper function
 * - Discovery settings loading
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveredInterfacesViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var interfaceConfigManager: InterfaceConfigManager
    private lateinit var viewModel: DiscoveredInterfacesViewModel

    private val bootstrapNamesFlow = MutableStateFlow<List<String>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock Android Log to prevent "not mocked" errors
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        // Set test dispatcher for ViewModel
        DiscoveredInterfacesViewModel.ioDispatcher = testDispatcher

        reticulumProtocol = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        interfaceRepository = mockk(relaxed = true)
        interfaceConfigManager = mockk(relaxed = true)

        // Default mock responses
        coEvery { reticulumProtocol.isDiscoveryEnabled() } returns false
        coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns emptyList()
        coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns emptySet()
        coEvery { settingsRepository.getDiscoverInterfacesEnabled() } returns false
        coEvery { settingsRepository.getAutoconnectDiscoveredCount() } returns 0
        every { interfaceRepository.bootstrapInterfaceNames } returns bootstrapNamesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        clearAllMocks()
        // Reset dispatcher
        DiscoveredInterfacesViewModel.ioDispatcher = Dispatchers.IO
    }

    private fun createViewModel(): DiscoveredInterfacesViewModel {
        return DiscoveredInterfacesViewModel(
            reticulumProtocol,
            settingsRepository,
            interfaceRepository,
            interfaceConfigManager,
        )
    }

    // ========== Initial State Tests ==========

    @Test
    fun `state - initial state has empty interfaces list`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val initial = awaitItem()
                assertTrue(initial.interfaces.isEmpty())
                assertTrue(initial.isLoading)
            }
        }

    @Test
    fun `state - initial state has empty autoconnectedEndpoints`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val initial = awaitItem()
                assertTrue(initial.autoconnectedEndpoints.isEmpty())
            }
        }

    // ========== autoconnectedEndpoints State Tests ==========

    @Test
    fun `autoconnectedEndpoints - loads from protocol`() =
        runTest {
            // Given
            val endpoints = setOf("192.168.1.1:4242", "10.0.0.100:5353")
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns endpoints

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(endpoints, state.autoconnectedEndpoints)
            }
        }

    @Test
    fun `autoconnectedEndpoints - handles protocol error gracefully`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } throws RuntimeException("Protocol error")

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.autoconnectedEndpoints.isEmpty())
                assertTrue(state.errorMessage?.contains("Failed to load") == true)
            }
        }

    @Test
    fun `autoconnectedEndpoints - updates on reload`() =
        runTest {
            // Given: Initial empty endpoints
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns emptySet()
            viewModel = createViewModel()
            advanceUntilIdle()

            // When: Endpoints become available
            val newEndpoints = setOf("192.168.1.1:4242")
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns newEndpoints
            viewModel.loadDiscoveredInterfaces()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(newEndpoints, state.autoconnectedEndpoints)
            }
        }

    // ========== isAutoconnected() Helper Tests ==========

    @Test
    fun `isAutoconnected - returns false when endpoints empty`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns emptySet()
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            val iface =
                createTestDiscoveredInterface(
                    reachableOn = "192.168.1.1",
                    port = 4242,
                )

            // Then
            assertFalse(viewModel.isAutoconnected(iface))
        }

    @Test
    fun `isAutoconnected - returns false when interface has no host`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns setOf("192.168.1.1:4242")
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            val iface =
                createTestDiscoveredInterface(
                    reachableOn = null,
                    port = 4242,
                )

            // Then
            assertFalse(viewModel.isAutoconnected(iface))
        }

    @Test
    fun `isAutoconnected - returns false when interface has no port`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns setOf("192.168.1.1:4242")
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            val iface =
                createTestDiscoveredInterface(
                    reachableOn = "192.168.1.1",
                    port = null,
                )

            // Then
            assertFalse(viewModel.isAutoconnected(iface))
        }

    @Test
    fun `isAutoconnected - returns false when endpoint not in set`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns setOf("192.168.1.1:4242")
            viewModel = createViewModel()
            advanceUntilIdle()

            // When: Different IP address
            val iface =
                createTestDiscoveredInterface(
                    reachableOn = "192.168.2.2",
                    port = 4242,
                )

            // Then
            assertFalse(viewModel.isAutoconnected(iface))
        }

    @Test
    fun `isAutoconnected - returns true when endpoint matches`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns setOf("192.168.1.1:4242")
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            val iface =
                createTestDiscoveredInterface(
                    reachableOn = "192.168.1.1",
                    port = 4242,
                )

            // Then
            assertTrue(viewModel.isAutoconnected(iface))
        }

    @Test
    fun `isAutoconnected - matches multiple endpoints correctly`() =
        runTest {
            // Given
            val endpoints = setOf("192.168.1.1:4242", "10.0.0.100:5353", "200:abcd::1:4242")
            coEvery { reticulumProtocol.getAutoconnectedEndpoints() } returns endpoints
            viewModel = createViewModel()
            advanceUntilIdle()

            // When/Then: Match first endpoint
            val iface1 = createTestDiscoveredInterface(reachableOn = "192.168.1.1", port = 4242)
            assertTrue(viewModel.isAutoconnected(iface1))

            // When/Then: Match second endpoint
            val iface2 = createTestDiscoveredInterface(reachableOn = "10.0.0.100", port = 5353)
            assertTrue(viewModel.isAutoconnected(iface2))

            // When/Then: Match IPv6 endpoint
            val iface3 = createTestDiscoveredInterface(reachableOn = "200:abcd::1", port = 4242)
            assertTrue(viewModel.isAutoconnected(iface3))

            // When/Then: No match
            val iface4 = createTestDiscoveredInterface(reachableOn = "192.168.3.3", port = 4242)
            assertFalse(viewModel.isAutoconnected(iface4))
        }

    // ========== Discovery Settings Tests ==========

    @Test
    fun `loadDiscoverySettings - loads setting enabled state`() =
        runTest {
            // Given
            coEvery { settingsRepository.getDiscoverInterfacesEnabled() } returns true
            coEvery { settingsRepository.getAutoconnectDiscoveredCount() } returns 5

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.discoverInterfacesEnabled)
                assertEquals(5, state.autoconnectCount)
            }
        }

    @Test
    fun `loadDiscoverySettings - loads bootstrap interface names`() =
        runTest {
            // Given
            val bootstrapNames = listOf("TCPClient1", "TCPClient2")
            bootstrapNamesFlow.value = bootstrapNames

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(bootstrapNames, state.bootstrapInterfaceNames)
            }
        }

    @Test
    fun `isDiscoveryEnabled - loads from protocol`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.isDiscoveryEnabled() } returns true

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.isDiscoveryEnabled)
            }
        }

    // ========== loadDiscoveredInterfaces Tests ==========

    @Test
    fun `loadDiscoveredInterfaces - updates interfaces list`() =
        runTest {
            // Given
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "Interface1"),
                    createTestDiscoveredInterface(name = "Interface2"),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(2, state.interfaces.size)
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `loadDiscoveredInterfaces - calculates status counts`() =
        runTest {
            // Given
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "Available1", status = "available"),
                    createTestDiscoveredInterface(name = "Available2", status = "available"),
                    createTestDiscoveredInterface(name = "Unknown1", status = "unknown"),
                    createTestDiscoveredInterface(name = "Stale1", status = "stale"),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(2, state.availableCount)
                assertEquals(1, state.unknownCount)
                assertEquals(1, state.staleCount)
            }
        }

    @Test
    fun `loadDiscoveredInterfaces - sets error message on failure`() =
        runTest {
            // Given
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } throws RuntimeException("Network error")

            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.errorMessage?.contains("Failed to load discovered interfaces") == true)
                assertFalse(state.isLoading)
            }
        }

    // ========== setUserLocation Tests ==========

    @Test
    fun `setUserLocation - updates state with coordinates`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.setUserLocation(45.0, -122.0)

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(45.0, state.userLatitude)
                assertEquals(-122.0, state.userLongitude)
            }
        }

    // ========== calculateDistance Tests ==========

    @Test
    fun `calculateDistance - returns null when user location not set`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()

            val iface =
                createTestDiscoveredInterface(
                    latitude = 45.5,
                    longitude = -122.5,
                )

            // Then
            assertNull(viewModel.calculateDistance(iface))
        }

    @Test
    fun `calculateDistance - returns null when interface location not set`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(45.0, -122.0)

            val iface =
                createTestDiscoveredInterface(
                    latitude = null,
                    longitude = null,
                )

            // Then
            assertNull(viewModel.calculateDistance(iface))
        }

    @Test
    fun `calculateDistance - returns distance when both locations set`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(45.0, -122.0)

            val iface =
                createTestDiscoveredInterface(
                    latitude = 45.5,
                    longitude = -122.5,
                )

            // Then
            val distance = viewModel.calculateDistance(iface)
            assertTrue(distance != null && distance > 0)
        }

    // ========== clearError Tests ==========

    @Test
    fun `clearError - sets error message to null`() =
        runTest {
            // Given: Start with an error
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } throws RuntimeException("Error")
            viewModel = createViewModel()
            advanceUntilIdle()

            // When
            viewModel.clearError()

            // Then
            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.errorMessage)
            }
        }

    // ========== Helper Functions ==========

    private fun createTestDiscoveredInterface(
        name: String = "TestInterface",
        type: String = "TCPServerInterface",
        status: String = "available",
        reachableOn: String? = "192.168.1.1",
        port: Int? = 4242,
        latitude: Double? = null,
        longitude: Double? = null,
    ): DiscoveredInterface {
        return DiscoveredInterface(
            name = name,
            type = type,
            transportId = "test_transport_id",
            networkId = "test_network_id",
            status = status,
            statusCode =
                when (status) {
                    "available" -> 1000
                    "unknown" -> 100
                    else -> 0
                },
            lastHeard = System.currentTimeMillis() / 1000,
            heardCount = 1,
            hops = 1,
            stampValue = 0,
            reachableOn = reachableOn,
            port = port,
            frequency = null,
            bandwidth = null,
            spreadingFactor = null,
            codingRate = null,
            modulation = null,
            channel = null,
            latitude = latitude,
            longitude = longitude,
            height = null,
        )
    }
}
