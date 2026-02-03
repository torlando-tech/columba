@file:Suppress("InjectDispatcher")

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

        // Relaxed mocks for protocol/infrastructure classes with many methods
        @Suppress("NoRelaxedMocks") // Protocol classes have many methods; explicit stubs provided for tested methods
        reticulumProtocol = mockk(relaxed = true)
        @Suppress("NoRelaxedMocks") // Repository has many settings methods; explicit stubs provided for tested methods
        settingsRepository = mockk(relaxed = true)
        @Suppress("NoRelaxedMocks") // Repository has many interface methods; explicit stubs provided for tested methods
        interfaceRepository = mockk(relaxed = true)
        @Suppress("NoRelaxedMocks") // Manager has many config methods; explicit stubs provided for tested methods
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

    private fun createViewModel(): DiscoveredInterfacesViewModel =
        DiscoveredInterfacesViewModel(
            reticulumProtocol,
            settingsRepository,
            interfaceRepository,
            interfaceConfigManager,
        )

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

    // ========== Sort Mode Tests ==========

    @Test
    fun `sortMode - initial state is AVAILABILITY_AND_QUALITY`() =
        runTest {
            // When
            viewModel = createViewModel()
            advanceUntilIdle()

            // Then - use .value for synchronous state checks
            assertEquals(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY, viewModel.state.value.sortMode)
        }

    @Test
    fun `setSortMode - changes sort mode to PROXIMITY when user location available`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(45.0, -122.0) // Required for PROXIMITY mode

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Then - use .value for synchronous state checks
            assertEquals(DiscoveredInterfacesSortMode.PROXIMITY, viewModel.state.value.sortMode)
        }

    @Test
    fun `setSortMode - changes sort mode back to AVAILABILITY_AND_QUALITY`() =
        runTest {
            // Given
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(45.0, -122.0) // Required for PROXIMITY mode
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY)

            // Then - use .value for synchronous state checks
            assertEquals(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY, viewModel.state.value.sortMode)
        }

    @Test
    fun `setSortMode PROXIMITY - sorts interfaces by distance when user location available`() =
        runTest {
            // Given: User at origin (0,0), interfaces at various distances
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "Far", latitude = 10.0, longitude = 10.0), // ~1570 km
                    createTestDiscoveredInterface(name = "Near", latitude = 1.0, longitude = 1.0), // ~157 km
                    createTestDiscoveredInterface(name = "Medium", latitude = 5.0, longitude = 5.0), // ~786 km
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(0.0, 0.0)

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Then: Should be sorted nearest first - use .value for synchronous state checks
            val state = viewModel.state.value
            assertEquals(3, state.interfaces.size)
            assertEquals("Near", state.interfaces[0].name)
            assertEquals("Medium", state.interfaces[1].name)
            assertEquals("Far", state.interfaces[2].name)
        }

    @Test
    fun `setSortMode PROXIMITY - ignored when user location not available`() =
        runTest {
            // Given: Interfaces with location but NO user location
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "First", latitude = 10.0, longitude = 10.0),
                    createTestDiscoveredInterface(name = "Second", latitude = 1.0, longitude = 1.0),
                    createTestDiscoveredInterface(name = "Third", latitude = 5.0, longitude = 5.0),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()
            // Note: NOT setting user location

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Then: Sort mode should remain AVAILABILITY_AND_QUALITY (request ignored)
            val state = viewModel.state.value
            assertEquals(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY, state.sortMode)
            assertEquals("First", state.interfaces[0].name)
            assertEquals("Second", state.interfaces[1].name)
            assertEquals("Third", state.interfaces[2].name)
        }

    @Test
    fun `setSortMode PROXIMITY - places interfaces without location at end`() =
        runTest {
            // Given: Mix of interfaces with and without location
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "NoLocation1", latitude = null, longitude = null),
                    createTestDiscoveredInterface(name = "HasLocation", latitude = 1.0, longitude = 1.0),
                    createTestDiscoveredInterface(name = "NoLocation2", latitude = null, longitude = null),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(0.0, 0.0)

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Then: Interface with location first, then those without (in original order)
            val state = viewModel.state.value
            assertEquals(3, state.interfaces.size)
            assertEquals("HasLocation", state.interfaces[0].name)
            assertEquals("NoLocation1", state.interfaces[1].name)
            assertEquals("NoLocation2", state.interfaces[2].name)
        }

    @Test
    fun `setSortMode AVAILABILITY_AND_QUALITY - preserves original order from Python`() =
        runTest {
            // Given: Interfaces in Python's sorted order (status_code desc, stamp_value desc)
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "First", latitude = 10.0, longitude = 10.0),
                    createTestDiscoveredInterface(name = "Second", latitude = 1.0, longitude = 1.0),
                    createTestDiscoveredInterface(name = "Third", latitude = 5.0, longitude = 5.0),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(0.0, 0.0)

            // When: Explicitly set to AVAILABILITY_AND_QUALITY (the default)
            viewModel.setSortMode(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY)

            // Then: Order should match original from Python - use .value for synchronous state checks
            val state = viewModel.state.value
            assertEquals(3, state.interfaces.size)
            assertEquals("First", state.interfaces[0].name)
            assertEquals("Second", state.interfaces[1].name)
            assertEquals("Third", state.interfaces[2].name)
        }

    @Test
    fun `setUserLocation - re-sorts interfaces when in PROXIMITY mode`() =
        runTest {
            // Given: Interfaces loaded, PROXIMITY mode set, then location updated
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "Far", latitude = 10.0, longitude = 10.0),
                    createTestDiscoveredInterface(name = "Near", latitude = 1.0, longitude = 1.0),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()

            // Set location first, then change to proximity mode
            viewModel.setUserLocation(0.0, 0.0)
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Verify sorted by distance - use .value for synchronous state checks
            var state = viewModel.state.value
            assertEquals("Near", state.interfaces[0].name)
            assertEquals("Far", state.interfaces[1].name)

            // When: User moves to new location (closer to "Far" interface)
            viewModel.setUserLocation(9.0, 9.0)

            // Then: Should re-sort with new distances
            state = viewModel.state.value
            assertEquals("Far", state.interfaces[0].name) // Now closer
            assertEquals("Near", state.interfaces[1].name) // Now farther
        }

    @Test
    fun `setUserLocation - does NOT re-sort when in AVAILABILITY_AND_QUALITY mode`() =
        runTest {
            // Given: Interfaces in specific order
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "First", latitude = 10.0, longitude = 10.0),
                    createTestDiscoveredInterface(name = "Second", latitude = 1.0, longitude = 1.0),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()

            // When: Set user location while in AVAILABILITY_AND_QUALITY mode (default)
            viewModel.setUserLocation(0.0, 0.0)

            // Then: Order should remain unchanged - use .value for synchronous state checks
            val state = viewModel.state.value
            assertEquals(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY, state.sortMode)
            assertEquals("First", state.interfaces[0].name)
            assertEquals("Second", state.interfaces[1].name)
        }

    @Test
    fun `loadDiscoveredInterfaces - applies current sort mode`() =
        runTest {
            // Given: Initial load with default sort mode
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "Far", latitude = 10.0, longitude = 10.0),
                    createTestDiscoveredInterface(name = "Near", latitude = 1.0, longitude = 1.0),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()

            // Set user location and switch to PROXIMITY mode
            viewModel.setUserLocation(0.0, 0.0)
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Verify initial sort - use .value for synchronous state checks
            assertEquals(
                "Near",
                viewModel.state.value.interfaces[0]
                    .name,
            )

            // When: Reload interfaces (simulating refresh)
            viewModel.loadDiscoveredInterfaces()
            advanceUntilIdle()

            // Then: New data should still be sorted by proximity
            val state = viewModel.state.value
            assertEquals(DiscoveredInterfacesSortMode.PROXIMITY, state.sortMode)
            assertEquals("Near", state.interfaces[0].name)
            assertEquals("Far", state.interfaces[1].name)
        }

    @Test
    fun `setSortMode PROXIMITY - handles empty interfaces list`() =
        runTest {
            // Given: No interfaces
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns emptyList()
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(0.0, 0.0)

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Then: Should not crash, empty list should remain empty
            val state = viewModel.state.value
            assertTrue(state.interfaces.isEmpty())
            assertEquals(DiscoveredInterfacesSortMode.PROXIMITY, state.sortMode)
        }

    @Test
    fun `setSortMode PROXIMITY - handles all interfaces without location`() =
        runTest {
            // Given: All interfaces missing location data
            val interfaces =
                listOf(
                    createTestDiscoveredInterface(name = "NoLoc1", latitude = null, longitude = null),
                    createTestDiscoveredInterface(name = "NoLoc2", latitude = null, longitude = null),
                )
            coEvery { reticulumProtocol.getDiscoveredInterfaces() } returns interfaces
            viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setUserLocation(0.0, 0.0)

            // When
            viewModel.setSortMode(DiscoveredInterfacesSortMode.PROXIMITY)

            // Then: Order should be preserved (all go to "without location" group)
            val state = viewModel.state.value
            assertEquals(2, state.interfaces.size)
            assertEquals("NoLoc1", state.interfaces[0].name)
            assertEquals("NoLoc2", state.interfaces[1].name)
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
    ): DiscoveredInterface =
        DiscoveredInterface(
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
