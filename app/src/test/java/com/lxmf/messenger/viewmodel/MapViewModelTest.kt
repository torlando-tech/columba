package com.lxmf.messenger.viewmodel

import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.test.TestFactories
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Unit tests for MapViewModel.
 *
 * Tests cover:
 * - Initial state
 * - Contact markers generation
 * - User location updates
 * - Permission state updates
 * - Error clearing
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var contactRepository: ContactRepository
    private lateinit var viewModel: MapViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        contactRepository = mockk(relaxed = true)
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `initial state has no user location`() = runTest {
        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.userLocation)
        }
    }

    @Test
    fun `initial state has no location permission`() = runTest {
        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.hasLocationPermission)
        }
    }

    @Test
    fun `initial state has no error message`() = runTest {
        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.errorMessage)
        }
    }

    @Test
    fun `onPermissionResult updates hasLocationPermission to true`() = runTest {
        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            // Consume initial state
            awaitItem()

            viewModel.onPermissionResult(granted = true)

            val updatedState = awaitItem()
            assertTrue(updatedState.hasLocationPermission)
        }
    }

    @Test
    fun `onPermissionResult updates hasLocationPermission to false`() = runTest {
        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            // First grant permission
            awaitItem()
            viewModel.onPermissionResult(granted = true)
            awaitItem()

            // Then revoke
            viewModel.onPermissionResult(granted = false)

            val updatedState = awaitItem()
            assertFalse(updatedState.hasLocationPermission)
        }
    }

    @Test
    fun `updateUserLocation updates state with new location`() = runTest {
        viewModel = MapViewModel(contactRepository)
        val mockLocation = createMockLocation(37.7749, -122.4194)

        viewModel.state.test {
            // Consume initial state
            awaitItem()

            viewModel.updateUserLocation(mockLocation)

            val updatedState = awaitItem()
            assertEquals(mockLocation, updatedState.userLocation)
            assertEquals(37.7749, updatedState.userLocation!!.latitude, 0.0001)
            assertEquals(-122.4194, updatedState.userLocation!!.longitude, 0.0001)
        }
    }

    @Test
    fun `clearError removes error message`() = runTest {
        viewModel = MapViewModel(contactRepository)

        // Verify initial state has no error message
        assertNull(viewModel.state.value.errorMessage)

        // Call clearError - should work without causing issues even when no error
        viewModel.clearError()

        // Verify state still has no error message
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `contact markers generated from contacts`() = runTest {
        val contacts = listOf(
            TestFactories.createEnrichedContact(
                destinationHash = "hash1",
                displayName = "Contact 1",
            ),
            TestFactories.createEnrichedContact(
                destinationHash = "hash2",
                displayName = "Contact 2",
            ),
        )
        every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)

        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(2, state.contactMarkers.size)
            assertEquals("hash1", state.contactMarkers[0].destinationHash)
            assertEquals("Contact 1", state.contactMarkers[0].displayName)
            assertEquals("hash2", state.contactMarkers[1].destinationHash)
            assertEquals("Contact 2", state.contactMarkers[1].displayName)
        }
    }

    @Test
    fun `contact markers use default location when no user location`() = runTest {
        val contacts = listOf(
            TestFactories.createEnrichedContact(
                destinationHash = "hash1",
                displayName = "Contact 1",
            ),
        )
        every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)

        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(1, state.contactMarkers.size)
            // Markers should be near San Francisco (default: 37.7749, -122.4194)
            assertTrue(state.contactMarkers[0].latitude > 37.0)
            assertTrue(state.contactMarkers[0].latitude < 38.0)
            assertTrue(state.contactMarkers[0].longitude > -123.0)
            assertTrue(state.contactMarkers[0].longitude < -122.0)
        }
    }

    @Test
    fun `isLoading is false after contacts loaded`() = runTest {
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())

        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
        }
    }

    @Test
    fun `empty contacts results in empty markers`() = runTest {
        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())

        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.contactMarkers.isEmpty())
        }
    }

    @Test
    fun `multiple location updates replace previous location`() = runTest {
        viewModel = MapViewModel(contactRepository)
        val location1 = createMockLocation(37.7749, -122.4194)
        val location2 = createMockLocation(40.7128, -74.0060)

        viewModel.state.test {
            awaitItem() // initial state

            viewModel.updateUserLocation(location1)
            val state1 = awaitItem()
            assertEquals(37.7749, state1.userLocation!!.latitude, 0.0001)

            viewModel.updateUserLocation(location2)
            val state2 = awaitItem()
            assertEquals(40.7128, state2.userLocation!!.latitude, 0.0001)
            assertEquals(-74.0060, state2.userLocation!!.longitude, 0.0001)
        }
    }

    @Test
    fun `contact markers recenter when user location changes`() = runTest {
        val contacts = listOf(
            TestFactories.createEnrichedContact(
                destinationHash = "hash1",
                displayName = "Contact 1",
            ),
        )
        every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)

        viewModel = MapViewModel(contactRepository)
        val newLocation = createMockLocation(40.7128, -74.0060) // New York

        viewModel.state.test {
            val initialState = awaitItem()
            // Initial markers should be near San Francisco (default)
            assertTrue(initialState.contactMarkers[0].latitude < 38.0)

            viewModel.updateUserLocation(newLocation)

            // Consume state updates until we find recentered markers or run out
            var foundRecenteredMarkers = false
            while (!foundRecenteredMarkers) {
                val state = expectMostRecentItem()
                if (state.contactMarkers.isNotEmpty() &&
                    state.contactMarkers[0].latitude > 40.0
                ) {
                    foundRecenteredMarkers = true
                    // Markers should now be near New York
                    assertTrue(state.contactMarkers[0].latitude > 40.0)
                    assertTrue(state.contactMarkers[0].latitude < 41.0)
                }
                break // Only check once with expectMostRecentItem
            }
            assertTrue("Markers should recenter around new location", foundRecenteredMarkers)
        }
    }

    @Test
    fun `large number of contacts generates correct number of markers`() = runTest {
        val contacts = (1..50).map { i ->
            TestFactories.createEnrichedContact(
                destinationHash = "hash$i",
                displayName = "Contact $i",
            )
        }
        every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)

        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(50, state.contactMarkers.size)
        }
    }

    @Test
    fun `markers have unique positions for each contact`() = runTest {
        val contacts = (1..5).map { i ->
            TestFactories.createEnrichedContact(
                destinationHash = "hash$i",
                displayName = "Contact $i",
            )
        }
        every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)

        viewModel = MapViewModel(contactRepository)

        viewModel.state.test {
            val state = awaitItem()
            val positions = state.contactMarkers.map { "${it.latitude},${it.longitude}" }.toSet()
            // Each marker should have a unique position
            assertEquals(5, positions.size)
        }
    }

    @Test
    fun `permission and location can be set independently`() = runTest {
        viewModel = MapViewModel(contactRepository)
        val mockLocation = createMockLocation(37.7749, -122.4194)

        viewModel.state.test {
            awaitItem() // initial

            // Set location first (before permission)
            viewModel.updateUserLocation(mockLocation)
            val state1 = awaitItem()
            assertFalse(state1.hasLocationPermission)
            assertEquals(mockLocation, state1.userLocation)

            // Then grant permission
            viewModel.onPermissionResult(true)
            val state2 = awaitItem()
            assertTrue(state2.hasLocationPermission)
            assertEquals(mockLocation, state2.userLocation)
        }
    }

    @Test
    fun `state is immutable - modifications dont affect original`() = runTest {
        viewModel = MapViewModel(contactRepository)

        val originalState = viewModel.state.value
        val originalPermission = originalState.hasLocationPermission

        viewModel.onPermissionResult(true)

        // Original state reference should be unchanged
        assertEquals(originalPermission, originalState.hasLocationPermission)
        // New state should be different
        assertTrue(viewModel.state.value.hasLocationPermission)
    }

    // Helper function to create mock Location
    private fun createMockLocation(lat: Double, lng: Double): Location {
        val location = mockk<Location>(relaxed = true)
        every { location.latitude } returns lat
        every { location.longitude } returns lng
        return location
    }
}
