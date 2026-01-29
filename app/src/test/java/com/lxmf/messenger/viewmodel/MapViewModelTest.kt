package com.lxmf.messenger.viewmodel

import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.db.entity.ReceivedLocationEntity
import com.lxmf.messenger.data.model.EnrichedAnnounce
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.map.MapStyleResult
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.test.TestFactories
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var contactRepository: ContactRepository
    private lateinit var receivedLocationDao: ReceivedLocationDao
    private lateinit var locationSharingManager: LocationSharingManager
    private lateinit var announceDao: AnnounceDao
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mapTileSourceManager: MapTileSourceManager
    private lateinit var viewModel: MapViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Disable periodic refresh to prevent infinite loops in tests
        MapViewModel.enablePeriodicRefresh = false

        savedStateHandle = SavedStateHandle()
        contactRepository = mockk(relaxed = true)
        receivedLocationDao = mockk(relaxed = true)
        locationSharingManager = mockk(relaxed = true)
        announceDao = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        mapTileSourceManager = mockk(relaxed = true)

        every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
        every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(emptyList())
        every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(emptyList())
        every { announceDao.getEnrichedAnnounces() } returns flowOf(emptyList())
        every { locationSharingManager.isSharing } returns MutableStateFlow(false)
        every { locationSharingManager.activeSessions } returns MutableStateFlow(emptyList())
        every { settingsRepository.hasDismissedLocationPermissionSheetFlow } returns flowOf(false)
        coEvery { mapTileSourceManager.getMapStyle(any(), any()) } returns MapStyleResult.Online(MapTileSourceManager.DEFAULT_STYLE_URL)
        every { mapTileSourceManager.httpEnabledFlow } returns flowOf(true)
        every { mapTileSourceManager.hasOfflineMaps() } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Re-enable periodic refresh for other tests
        MapViewModel.enablePeriodicRefresh = true
        clearAllMocks()
    }

    @Test
    fun `initial state has no user location`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.userLocation)
            }
        }

    @Test
    fun `initial state has no location permission`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.hasLocationPermission)
            }
        }

    @Test
    fun `initial state has no error message`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.errorMessage)
            }
        }

    @Test
    fun `onPermissionResult updates hasLocationPermission to true`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                // Consume initial state
                awaitItem()

                viewModel.onPermissionResult(granted = true)

                val updatedState = awaitItem()
                assertTrue(updatedState.hasLocationPermission)
            }
        }

    @Test
    fun `onPermissionResult updates hasLocationPermission to false`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

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
    fun `updateUserLocation updates state with new location`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
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
    fun `clearError removes error message`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            // Verify initial state has no error message
            assertNull(viewModel.state.value.errorMessage)

            // Call clearError - should work without causing issues even when no error
            viewModel.clearError()

            // Verify state still has no error message
            assertNull(viewModel.state.value.errorMessage)
        }

    // ===== dismissPermissionCard Tests =====

    @Test
    fun `initial state has permission card not dismissed`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isPermissionCardDismissed)
            }
        }

    @Test
    fun `dismissPermissionCard updates state to dismissed`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                // Consume initial state
                awaitItem()

                viewModel.dismissPermissionCard()

                val updatedState = awaitItem()
                assertTrue(updatedState.isPermissionCardDismissed)
            }
        }

    @Test
    fun `dismissed state persists after permission granted`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                awaitItem() // initial state

                // Dismiss the card
                viewModel.dismissPermissionCard()
                val dismissedState = awaitItem()
                assertTrue(dismissedState.isPermissionCardDismissed)

                // Grant permission
                viewModel.onPermissionResult(true)
                val afterPermission = awaitItem()

                // Dismissed state should persist even when permission is granted
                assertTrue(afterPermission.isPermissionCardDismissed)
                assertTrue(afterPermission.hasLocationPermission)
            }
        }

    @Test
    fun `contact markers come from received locations`() =
        runTest {
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash1",
                        displayName = "Contact 1",
                    ),
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash2",
                        displayName = "Contact 2",
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                    ReceivedLocationEntity(
                        id = "loc2",
                        senderHash = "hash2",
                        latitude = 40.7128,
                        longitude = -74.0060,
                        accuracy = 15f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(2, state.contactMarkers.size)
                assertEquals("hash1", state.contactMarkers[0].destinationHash)
                assertEquals("Contact 1", state.contactMarkers[0].displayName)
                assertEquals(37.7749, state.contactMarkers[0].latitude, 0.0001)
            }
        }

    @Test
    fun `contact markers use display name from contacts`() =
        runTest {
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash1",
                        displayName = "My Friend",
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                assertEquals("My Friend", state.contactMarkers[0].displayName)
            }
        }

    @Test
    fun `isLoading is false after contacts loaded`() =
        runTest {
            every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `empty contacts results in empty markers`() =
        runTest {
            every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.contactMarkers.isEmpty())
            }
        }

    @Test
    fun `multiple location updates replace previous location`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
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
    fun `markers are static from received locations - user location does not affect them`() =
        runTest {
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash1",
                        displayName = "Contact 1",
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val newLocation = createMockLocation(40.7128, -74.0060) // New York

            viewModel.state.test {
                val initialState = awaitItem()
                // Marker is at the received location
                assertEquals(37.7749, initialState.contactMarkers[0].latitude, 0.0001)

                viewModel.updateUserLocation(newLocation)

                // User location changed but marker should stay the same (it comes from database)
                val state = expectMostRecentItem()
                assertEquals(37.7749, state.contactMarkers[0].latitude, 0.0001)
            }
        }

    @Test
    fun `large number of received locations generates correct number of markers`() =
        runTest {
            val contacts =
                (1..50).map { i ->
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash$i",
                        displayName = "Contact $i",
                    )
                }
            val receivedLocations =
                (1..50).map { i ->
                    ReceivedLocationEntity(
                        id = "loc$i",
                        senderHash = "hash$i",
                        latitude = 37.0 + i * 0.01,
                        longitude = -122.0 + i * 0.01,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    )
                }
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(50, state.contactMarkers.size)
            }
        }

    @Test
    fun `markers have positions from received locations`() =
        runTest {
            val contacts =
                (1..5).map { i ->
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash$i",
                        displayName = "Contact $i",
                    )
                }
            val receivedLocations =
                (1..5).map { i ->
                    ReceivedLocationEntity(
                        id = "loc$i",
                        senderHash = "hash$i",
                        latitude = 37.0 + i,
                        longitude = -122.0 + i,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    )
                }
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                val positions = state.contactMarkers.map { "${it.latitude},${it.longitude}" }.toSet()
                // Each marker should have a unique position
                assertEquals(5, positions.size)
            }
        }

    @Test
    fun `permission and location can be set independently`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
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
    fun `state is immutable - modifications dont affect original`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            val originalState = viewModel.state.value
            val originalPermission = originalState.hasLocationPermission

            viewModel.onPermissionResult(true)

            // Original state reference should be unchanged
            assertEquals(originalPermission, originalState.hasLocationPermission)
            // New state should be different
            assertTrue(viewModel.state.value.hasLocationPermission)
        }

    // ===== calculateMarkerState Tests =====

    @Test
    fun `calculateMarkerState - fresh location returns FRESH`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - 1_000L, // 1 second ago
                    expiresAt = now + 3600_000L, // Expires in 1 hour
                    currentTime = now,
                )

            assertEquals(MarkerState.FRESH, state)
        }

    @Test
    fun `calculateMarkerState - location older than 5 minutes returns STALE`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - (6 * 60_000L), // 6 minutes ago
                    expiresAt = now + 3600_000L, // Not expired
                    currentTime = now,
                )

            assertEquals(MarkerState.STALE, state)
        }

    @Test
    fun `calculateMarkerState - expired within grace period returns EXPIRED_GRACE_PERIOD`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - (10 * 60_000L), // 10 minutes ago
                    expiresAt = now - (5 * 60_000L), // Expired 5 minutes ago (within 1 hour grace)
                    currentTime = now,
                )

            assertEquals(MarkerState.EXPIRED_GRACE_PERIOD, state)
        }

    @Test
    fun `calculateMarkerState - expired past grace period returns null`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - (3 * 3600_000L), // 3 hours ago
                    expiresAt = now - (2 * 3600_000L), // Expired 2 hours ago (past 1 hour grace)
                    currentTime = now,
                )

            assertNull(state)
        }

    @Test
    fun `calculateMarkerState - indefinite sharing never expires`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - (6 * 60_000L), // 6 minutes ago (stale)
                    expiresAt = null, // Indefinite
                    currentTime = now,
                )

            assertEquals(MarkerState.STALE, state) // Stale but never expired
        }

    @Test
    fun `calculateMarkerState - exactly at stale threshold returns FRESH`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - (5 * 60_000L), // Exactly 5 minutes ago
                    expiresAt = null,
                    currentTime = now,
                )

            // At exactly 5 minutes, age > threshold is false (5 is not > 5)
            assertEquals(MarkerState.FRESH, state)
        }

    @Test
    fun `calculateMarkerState - just past stale threshold returns STALE`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            val now = System.currentTimeMillis()

            val state =
                viewModel.calculateMarkerState(
                    timestamp = now - (5 * 60_000L + 1L), // 5 minutes + 1ms ago
                    expiresAt = null,
                    currentTime = now,
                )

            assertEquals(MarkerState.STALE, state)
        }

    // ===== startSharing Tests =====

    @Test
    fun `startSharing calls locationSharingManager with correct parameters`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            val selectedContacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash1",
                        displayName = "Alice",
                    ),
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash2",
                        displayName = "Bob",
                    ),
                )
            val duration = com.lxmf.messenger.ui.model.SharingDuration.ONE_HOUR

            viewModel.startSharing(selectedContacts, duration)

            verify {
                locationSharingManager.startSharing(
                    listOf("hash1", "hash2"),
                    mapOf("hash1" to "Alice", "hash2" to "Bob"),
                    duration,
                )
            }
        }

    @Test
    fun `startSharing with empty list calls locationSharingManager with empty list`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.startSharing(emptyList(), com.lxmf.messenger.ui.model.SharingDuration.FIFTEEN_MINUTES)

            verify {
                locationSharingManager.startSharing(emptyList(), emptyMap(), any())
            }
        }

    @Test
    fun `startSharing with single contact`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            val selectedContacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "single_hash",
                        displayName = "Single Contact",
                    ),
                )

            viewModel.startSharing(selectedContacts, com.lxmf.messenger.ui.model.SharingDuration.INDEFINITE)

            verify {
                locationSharingManager.startSharing(
                    listOf("single_hash"),
                    mapOf("single_hash" to "Single Contact"),
                    com.lxmf.messenger.ui.model.SharingDuration.INDEFINITE,
                )
            }
        }

    // ===== stopSharing Tests =====

    @Test
    fun `stopSharing without parameter stops all sharing`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.stopSharing()

            verify {
                locationSharingManager.stopSharing(null)
            }
        }

    @Test
    fun `stopSharing with destinationHash stops specific session`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.stopSharing("specific_hash")

            verify {
                locationSharingManager.stopSharing("specific_hash")
            }
        }

    @Test
    fun `stopSharing called multiple times calls manager each time`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.stopSharing("hash1")
            viewModel.stopSharing("hash2")
            viewModel.stopSharing()

            verify(exactly = 1) { locationSharingManager.stopSharing("hash1") }
            verify(exactly = 1) { locationSharingManager.stopSharing("hash2") }
            verify(exactly = 1) { locationSharingManager.stopSharing(null) }
        }

    // ===== sharing state updates Tests =====

    @Test
    fun `isSharing state updates from locationSharingManager`() =
        runTest {
            val isSharingFlow = MutableStateFlow(false)
            every { locationSharingManager.isSharing } returns isSharingFlow

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val initial = awaitItem()
                assertFalse(initial.isSharing)

                isSharingFlow.value = true

                val updated = awaitItem()
                assertTrue(updated.isSharing)
            }
        }

    @Test
    fun `activeSessions state updates from locationSharingManager`() =
        runTest {
            val sessionsFlow = MutableStateFlow<List<com.lxmf.messenger.service.SharingSession>>(emptyList())
            every { locationSharingManager.activeSessions } returns sessionsFlow

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val initial = awaitItem()
                assertTrue(initial.activeSessions.isEmpty())

                val newSessions =
                    listOf(
                        com.lxmf.messenger.service.SharingSession(
                            destinationHash = "hash1",
                            displayName = "Alice",
                            startTime = System.currentTimeMillis(),
                            endTime = System.currentTimeMillis() + 3600_000L,
                        ),
                    )
                sessionsFlow.value = newSessions

                val updated = awaitItem()
                assertEquals(1, updated.activeSessions.size)
                assertEquals("hash1", updated.activeSessions[0].destinationHash)
            }
        }

    // ===== Display name fallback Tests =====

    @Test
    fun `markers use announce peerName when not in contacts`() =
        runTest {
            val announces =
                listOf(
                    EnrichedAnnounce(
                        destinationHash = "hash1",
                        peerName = "Announce Name",
                        publicKey = ByteArray(64),
                        appData = null,
                        hops = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "peer",
                        receivingInterface = null,
                        receivingInterfaceType = null,
                        aspect = "lxmf.delivery",
                        isFavorite = false,
                        favoritedTimestamp = null,
                        stampCost = null,
                        stampCostFlexibility = null,
                        peeringCost = null,
                        propagationTransferLimitKb = null,
                        iconName = null,
                        iconForegroundColor = null,
                        iconBackgroundColor = null,
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            // Empty contacts - no match
            every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)
            every { announceDao.getEnrichedAnnounces() } returns flowOf(announces)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                assertEquals("Announce Name", state.contactMarkers[0].displayName)
            }
        }

    @Test
    fun `markers fall back to truncated hash when no name found`() =
        runTest {
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "abcdefgh12345678",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            // No contacts or announces
            every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)
            every { announceDao.getEnrichedAnnounces() } returns flowOf(emptyList())

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                // Falls back to first 8 characters of hash
                assertEquals("abcdefgh", state.contactMarkers[0].displayName)
            }
        }

    @Test
    fun `markers prefer contacts over announces for display name`() =
        runTest {
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash1",
                        displayName = "Contact Name",
                    ),
                )
            val announces =
                listOf(
                    EnrichedAnnounce(
                        destinationHash = "hash1",
                        peerName = "Announce Name",
                        publicKey = ByteArray(64),
                        appData = null,
                        hops = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "peer",
                        receivingInterface = null,
                        receivingInterfaceType = null,
                        aspect = "lxmf.delivery",
                        isFavorite = false,
                        favoritedTimestamp = null,
                        stampCost = null,
                        stampCostFlexibility = null,
                        peeringCost = null,
                        propagationTransferLimitKb = null,
                        iconName = null,
                        iconForegroundColor = null,
                        iconBackgroundColor = null,
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)
            every { announceDao.getEnrichedAnnounces() } returns flowOf(announces)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                // Contact name takes priority
                assertEquals("Contact Name", state.contactMarkers[0].displayName)
            }
        }

    // ===== Icon appearance in ContactMarker Tests =====

    @Test
    fun `markers include icon appearance from announces`() =
        runTest {
            val announces =
                listOf(
                    EnrichedAnnounce(
                        destinationHash = "hash1",
                        peerName = "Test User",
                        publicKey = ByteArray(64) { it.toByte() },
                        appData = null,
                        hops = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "peer",
                        receivingInterface = null,
                        receivingInterfaceType = null,
                        aspect = "lxmf.delivery",
                        isFavorite = false,
                        favoritedTimestamp = null,
                        stampCost = null,
                        stampCostFlexibility = null,
                        peeringCost = null,
                        propagationTransferLimitKb = null,
                        iconName = "account",
                        iconForegroundColor = "FFFFFF",
                        iconBackgroundColor = "1E88E5",
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)
            every { announceDao.getEnrichedAnnounces() } returns flowOf(announces)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                val marker = state.contactMarkers[0]
                assertEquals("account", marker.iconName)
                assertEquals("FFFFFF", marker.iconForegroundColor)
                assertEquals("1E88E5", marker.iconBackgroundColor)
                assertNotNull(marker.publicKey)
            }
        }

    @Test
    fun `markers have null icon fields when announce has no icon`() =
        runTest {
            val announces =
                listOf(
                    EnrichedAnnounce(
                        destinationHash = "hash1",
                        peerName = "Test User",
                        publicKey = ByteArray(64),
                        appData = null,
                        hops = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "peer",
                        receivingInterface = null,
                        receivingInterfaceType = null,
                        aspect = "lxmf.delivery",
                        isFavorite = false,
                        favoritedTimestamp = null,
                        stampCost = null,
                        stampCostFlexibility = null,
                        peeringCost = null,
                        propagationTransferLimitKb = null,
                        // No icon set - peer_icons table has no entry for this peer
                        iconName = null,
                        iconForegroundColor = null,
                        iconBackgroundColor = null,
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns flowOf(emptyList())
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)
            every { announceDao.getEnrichedAnnounces() } returns flowOf(announces)

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                val marker = state.contactMarkers[0]
                assertNull(marker.iconName)
                assertNull(marker.iconForegroundColor)
                assertNull(marker.iconBackgroundColor)
            }
        }

    @Test
    fun `markers have null icon fields when no announce found`() =
        runTest {
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        destinationHash = "hash1",
                        displayName = "Contact Name",
                    ),
                )
            val receivedLocations =
                listOf(
                    ReceivedLocationEntity(
                        id = "loc1",
                        senderHash = "hash1",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        accuracy = 10f,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = null,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
            // Contact exists but no announce with icon (peer_icons table has no entry)
            every { contactRepository.getEnrichedContacts() } returns flowOf(contacts)
            every { receivedLocationDao.getLatestLocationsPerSenderUnfiltered() } returns flowOf(receivedLocations)
            every { announceDao.getEnrichedAnnounces() } returns flowOf(emptyList())

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.contactMarkers.size)
                val marker = state.contactMarkers[0]
                assertNull(marker.iconName)
                assertNull(marker.iconForegroundColor)
                assertNull(marker.iconBackgroundColor)
                assertNull(marker.publicKey)
            }
        }

    // ========== Location Permission Sheet Dismissal Tests ==========

    @Test
    fun `initial state has permission sheet not dismissed`() =
        runTest {
            every { settingsRepository.hasDismissedLocationPermissionSheetFlow } returns flowOf(false)
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.hasUserDismissedPermissionSheet)
            }
        }

    @Test
    fun `state reflects dismissed permission sheet from SavedStateHandle`() =
        runTest {
            // Given: SavedStateHandle has sheet dismissed state set to true
            savedStateHandle["hasUserDismissedPermissionSheet"] = true

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.hasUserDismissedPermissionSheet)
            }
        }

    @Test
    fun `dismissLocationPermissionSheet calls settingsRepository and updates SavedStateHandle`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.dismissLocationPermissionSheet()

            // Verify state is updated immediately
            assertTrue(viewModel.state.value.hasUserDismissedPermissionSheet)
            // Verify SavedStateHandle is updated
            assertTrue(savedStateHandle.get<Boolean>("hasUserDismissedPermissionSheet") == true)
            // Verify DataStore is also updated (for MainActivity reset logic)
            coVerify { settingsRepository.markLocationPermissionSheetDismissed() }
        }

    @Test
    fun `permission sheet dismissed state survives ViewModel recreation via SavedStateHandle`() =
        runTest {
            // Given: A ViewModel where the user dismissed the permission sheet
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            viewModel.dismissLocationPermissionSheet()

            // Verify sheet is dismissed
            assertTrue(viewModel.state.value.hasUserDismissedPermissionSheet)

            // When: ViewModel is recreated with the same SavedStateHandle (simulates tab switch)
            val viewModel2 =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            // Then: Permission sheet should still be dismissed
            viewModel2.state.test {
                val state = awaitItem()
                assertTrue(
                    "Permission sheet dismissed state should survive ViewModel recreation",
                    state.hasUserDismissedPermissionSheet,
                )
            }
        }

    // ========== enableHttp Tests ==========

    @Test
    fun `enableHttp clears download flag and enables HTTP`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.enableHttp()

            // Verify it clears the download flag and enables HTTP
            coVerify { settingsRepository.setHttpEnabledForDownload(false) }
            coVerify { mapTileSourceManager.setHttpEnabled(true) }
        }

    @Test
    fun `enableHttp triggers map style refresh`() =
        runTest {
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            viewModel.enableHttp()

            // Verify getMapStyle is called (initial + after enableHttp)
            coVerify(atLeast = 2) { mapTileSourceManager.getMapStyle(any(), any()) }
        }

    // ========== httpEnabledFlow observer Tests ==========

    @Test
    fun `httpEnabledFlow changes trigger map style refresh`() =
        runTest {
            val httpEnabledFlow = MutableStateFlow(true)
            every { mapTileSourceManager.httpEnabledFlow } returns httpEnabledFlow

            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            // Change HTTP enabled state
            httpEnabledFlow.value = false

            // Verify getMapStyle was called multiple times (initial + after flow change)
            coVerify(atLeast = 2) { mapTileSourceManager.getMapStyle(any(), any()) }
        }

    // ========== Permission Card Dismissed State Persistence Tests (Issue #342) ==========

    @Test
    fun `permission card dismissed state survives ViewModel recreation via SavedStateHandle`() =
        runTest {
            // Given: A ViewModel where the user dismissed the permission card
            viewModel =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )
            viewModel.dismissPermissionCard()

            // Verify card is dismissed
            assertTrue(viewModel.state.value.isPermissionCardDismissed)

            // When: ViewModel is recreated with the same SavedStateHandle (simulates tab switch)
            val viewModel2 =
                MapViewModel(
                    savedStateHandle,
                    contactRepository,
                    receivedLocationDao,
                    locationSharingManager,
                    announceDao,
                    settingsRepository,
                    mapTileSourceManager,
                )

            // Then: Permission card should still be dismissed
            viewModel2.state.test {
                val state = awaitItem()
                assertTrue(
                    "Permission card dismissed state should survive ViewModel recreation (issue #342)",
                    state.isPermissionCardDismissed,
                )
            }
        }

    @Test
    fun `permission card dismissed state is false with fresh SavedStateHandle`() =
        runTest {
            // Given: A fresh SavedStateHandle (simulates fresh app launch)
            val freshHandle = SavedStateHandle()

            // When: ViewModel is created
            viewModel =
                MapViewModel(freshHandle, contactRepository, receivedLocationDao, locationSharingManager, announceDao, settingsRepository, mapTileSourceManager)

            // Then: Permission card should NOT be dismissed
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isPermissionCardDismissed)
            }
        }

    @Test
    fun `permission card dismissed state does not leak between different SavedStateHandles`() =
        runTest {
            // Given: First ViewModel dismisses the card
            val handle1 = SavedStateHandle()
            val vm1 =
                MapViewModel(handle1, contactRepository, receivedLocationDao, locationSharingManager, announceDao, settingsRepository, mapTileSourceManager)
            vm1.dismissPermissionCard()
            assertTrue(vm1.state.value.isPermissionCardDismissed)

            // When: A completely separate ViewModel is created with a different handle
            val handle2 = SavedStateHandle()
            val vm2 =
                MapViewModel(handle2, contactRepository, receivedLocationDao, locationSharingManager, announceDao, settingsRepository, mapTileSourceManager)

            // Then: The second ViewModel should NOT have the card dismissed
            assertFalse(vm2.state.value.isPermissionCardDismissed)
        }

    // Helper function to create mock Location
    private fun createMockLocation(
        lat: Double,
        lng: Double,
    ): Location {
        val location = mockk<Location>(relaxed = true)
        every { location.latitude } returns lat
        every { location.longitude } returns lng
        return location
    }
}
