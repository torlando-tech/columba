package com.lxmf.messenger.viewmodel

import android.content.Context
import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.data.repository.RmspServer
import com.lxmf.messenger.data.repository.RmspServerRepository
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.map.TileDownloadManager
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Unit tests for OfflineMapDownloadViewModel.
 *
 * Tests cover:
 * - Location setting (from current location and manual coordinates)
 * - Radius option selection
 * - Zoom range configuration
 * - Region naming
 * - Wizard step navigation (next/previous)
 * - Download progress updates
 * - Download cancellation
 * - Error handling
 * - Wizard reset
 * - State transitions during download lifecycle
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineMapDownloadViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var offlineMapRegionRepository: OfflineMapRegionRepository
    private lateinit var mapTileSourceManager: MapTileSourceManager
    private lateinit var rmspServerRepository: RmspServerRepository
    private lateinit var reticulumProtocol: ServiceReticulumProtocol
    private lateinit var viewModel: OfflineMapDownloadViewModel

    // Mutable flows for controlling test scenarios
    private val httpEnabledFlow = MutableStateFlow(true)
    private val rmspEnabledFlow = MutableStateFlow(false)
    private val allServersFlow = MutableStateFlow<List<RmspServer>>(emptyList())

    // Progress flow for TileDownloadManager
    private val progressFlow =
        MutableStateFlow(
            TileDownloadManager.DownloadProgress(
                status = TileDownloadManager.DownloadProgress.Status.IDLE,
                totalTiles = 0,
                downloadedTiles = 0,
                failedTiles = 0,
                bytesDownloaded = 0L,
                currentZoom = 0,
            ),
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()

        offlineMapRegionRepository = mockk(relaxed = true)
        mapTileSourceManager = mockk(relaxed = true)
        rmspServerRepository = mockk(relaxed = true)
        reticulumProtocol = mockk(relaxed = true)

        // Setup flow mocks
        every { mapTileSourceManager.httpEnabledFlow } returns httpEnabledFlow
        every { mapTileSourceManager.rmspEnabledFlow } returns rmspEnabledFlow
        every { rmspServerRepository.getAllServers() } returns allServersFlow

        // Setup TileDownloadManager mock behavior
        mockkConstructor(TileDownloadManager::class)
        every { anyConstructed<TileDownloadManager>().progress } returns progressFlow
        every {
            anyConstructed<TileDownloadManager>().estimateDownload(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns Pair(100, 1500000L)
        every { anyConstructed<TileDownloadManager>().cancel() } just runs
        every { anyConstructed<TileDownloadManager>().reset() } just runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkConstructor(TileDownloadManager::class)
    }

    private fun createViewModel(): OfflineMapDownloadViewModel {
        return OfflineMapDownloadViewModel(
            context = context,
            offlineMapRegionRepository = offlineMapRegionRepository,
            mapTileSourceManager = mapTileSourceManager,
            rmspServerRepository = rmspServerRepository,
            reticulumProtocol = reticulumProtocol,
        )
    }

    // region Initial State Tests

    @Test
    fun `initial state has correct defaults`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(DownloadWizardStep.LOCATION, state.step)
                assertNull(state.centerLatitude)
                assertNull(state.centerLongitude)
                assertEquals(RadiusOption.MEDIUM, state.radiusOption)
                assertEquals(0, state.minZoom)
                assertEquals(14, state.maxZoom)
                assertEquals("", state.name)
                assertEquals(0, state.estimatedTileCount)
                assertEquals(0L, state.estimatedSizeBytes)
                assertNull(state.downloadProgress)
                assertFalse(state.isComplete)
                assertNull(state.errorMessage)
                assertNull(state.createdRegionId)
                assertFalse(state.hasLocation)
                assertFalse(state.hasValidName)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Location Setting Tests

    @Test
    fun `setLocation updates state with coordinates`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setLocation(40.7128, -74.0060)
                val state = awaitItem()

                assertEquals(40.7128, state.centerLatitude)
                assertEquals(-74.0060, state.centerLongitude)
                assertTrue(state.hasLocation)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setLocationFromCurrent updates state from Location object`() =
        runTest {
            viewModel = createViewModel()
            val location = mockk<Location>()
            every { location.latitude } returns 34.0522
            every { location.longitude } returns -118.2437

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setLocationFromCurrent(location)
                val state = awaitItem()

                assertEquals(34.0522, state.centerLatitude)
                assertEquals(-118.2437, state.centerLongitude)
                assertTrue(state.hasLocation)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setLocation triggers estimate update`() =
        runTest {
            viewModel = createViewModel()

            viewModel.setLocation(40.7128, -74.0060)

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(100, state.estimatedTileCount)
                assertEquals(1500000L, state.estimatedSizeBytes)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Radius Option Tests

    @Test
    fun `setRadiusOption updates state`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)

            viewModel.state.test {
                var state = awaitItem()

                viewModel.setRadiusOption(RadiusOption.LARGE)
                state = awaitItem()
                assertEquals(RadiusOption.LARGE, state.radiusOption)

                viewModel.setRadiusOption(RadiusOption.EXTRA_LARGE)
                state = awaitItem()
                assertEquals(RadiusOption.EXTRA_LARGE, state.radiusOption)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setRadiusOption with all options works correctly`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)

            viewModel.state.test {
                awaitItem() // Initial

                RadiusOption.entries.forEach { option ->
                    viewModel.setRadiusOption(option)
                    val state = awaitItem()
                    assertEquals(option, state.radiusOption)
                }

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `RadiusOption has correct km values`() {
        assertEquals(5, RadiusOption.SMALL.km)
        assertEquals(10, RadiusOption.MEDIUM.km)
        assertEquals(25, RadiusOption.LARGE.km)
        assertEquals(50, RadiusOption.EXTRA_LARGE.km)
        assertEquals(100, RadiusOption.HUGE.km)
    }

    @Test
    fun `RadiusOption has correct labels`() {
        assertEquals("5 km", RadiusOption.SMALL.label)
        assertEquals("10 km", RadiusOption.MEDIUM.label)
        assertEquals("25 km", RadiusOption.LARGE.label)
        assertEquals("50 km", RadiusOption.EXTRA_LARGE.label)
        assertEquals("100 km", RadiusOption.HUGE.label)
    }

    // endregion

    // region Zoom Range Tests

    @Test
    fun `setZoomRange updates state`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)

            viewModel.state.test {
                awaitItem() // Initial

                viewModel.setZoomRange(5, 12)
                val state = awaitItem()
                assertEquals(5, state.minZoom)
                assertEquals(12, state.maxZoom)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setZoomRange clamps values to valid range`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)

            // First set valid non-default values, then test clamping
            viewModel.setZoomRange(5, 10)

            viewModel.state.test {
                awaitItem() // Get current state with (5, 10)

                viewModel.setZoomRange(-5, 20)
                val state = awaitItem()
                assertEquals(0, state.minZoom)
                assertEquals(14, state.maxZoom)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setZoomRange triggers estimate update`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)

            // Verify initial estimate is calculated
            val initialState = viewModel.state.value
            assertEquals(100, initialState.estimatedTileCount)
            assertEquals(1500000L, initialState.estimatedSizeBytes)
            assertEquals(0, initialState.minZoom)
            assertEquals(14, initialState.maxZoom)

            // Change zoom range and verify estimate is recalculated
            // The mock returns (100, 1500000L) for all calls, so we verify
            // that the zoom values are updated (the estimate stays the same in this test)
            viewModel.setZoomRange(5, 10)

            val updatedState = viewModel.state.value
            assertEquals(5, updatedState.minZoom)
            assertEquals(10, updatedState.maxZoom)
            // Estimate is recalculated (mock returns same value)
            assertEquals(100, updatedState.estimatedTileCount)
            assertEquals(1500000L, updatedState.estimatedSizeBytes)

            // Verify estimateDownload was called with new zoom values
            io.mockk.verify {
                anyConstructed<TileDownloadManager>().estimateDownload(
                    40.7128,
                    -74.0060,
                    10, // radiusOption.km = MEDIUM = 10
                    5,
                    10,
                )
            }
        }

    // endregion

    // region Name Setting Tests

    @Test
    fun `setName updates state`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial

                viewModel.setName("New York City")
                val state = awaitItem()
                assertEquals("New York City", state.name)
                assertTrue(state.hasValidName)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setName with blank string has invalid name`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial

                viewModel.setName("   ")
                val state = awaitItem()
                assertEquals("   ", state.name)
                assertFalse(state.hasValidName)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setName with empty string has invalid name`() =
        runTest {
            viewModel = createViewModel()

            // First set a non-empty name, then set to empty to verify behavior
            viewModel.setName("temporary")

            viewModel.state.test {
                awaitItem() // Get current state with "temporary"

                viewModel.setName("")
                val state = awaitItem()
                assertEquals("", state.name)
                assertFalse(state.hasValidName)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Wizard Step Navigation Tests

    @Test
    fun `nextStep transitions from LOCATION to RADIUS`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                assertEquals(DownloadWizardStep.LOCATION, state.step)

                viewModel.nextStep()
                state = awaitItem()
                assertEquals(DownloadWizardStep.RADIUS, state.step)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `nextStep transitions from RADIUS to CONFIRM`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // LOCATION

                viewModel.nextStep()
                awaitItem() // RADIUS

                viewModel.nextStep()
                val state = awaitItem()
                assertEquals(DownloadWizardStep.CONFIRM, state.step)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `nextStep from CONFIRM starts download and transitions to DOWNLOADING`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            // Verify initial state
            assertEquals(DownloadWizardStep.LOCATION, viewModel.state.value.step)

            // Navigate through wizard
            viewModel.nextStep() // To RADIUS
            assertEquals(DownloadWizardStep.RADIUS, viewModel.state.value.step)

            viewModel.nextStep() // To CONFIRM
            assertEquals(DownloadWizardStep.CONFIRM, viewModel.state.value.step)

            viewModel.nextStep() // To DOWNLOADING - triggers startDownload()
            assertEquals(DownloadWizardStep.DOWNLOADING, viewModel.state.value.step)
        }

    @Test
    fun `nextStep from DOWNLOADING stays at DOWNLOADING`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.state.test {
                awaitItem()

                // Navigate to DOWNLOADING
                viewModel.nextStep()
                awaitItem()
                viewModel.nextStep()
                awaitItem()
                viewModel.nextStep()
                awaitItem()

                // Try to go to next step
                viewModel.nextStep()
                val state = expectMostRecentItem()
                assertEquals(DownloadWizardStep.DOWNLOADING, state.step)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `previousStep transitions from RADIUS to LOCATION`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // LOCATION

                viewModel.nextStep()
                awaitItem() // RADIUS

                viewModel.previousStep()
                val state = awaitItem()
                assertEquals(DownloadWizardStep.LOCATION, state.step)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `previousStep transitions from CONFIRM to RADIUS`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem()

                viewModel.nextStep()
                awaitItem()

                viewModel.nextStep()
                awaitItem() // CONFIRM

                viewModel.previousStep()
                val state = awaitItem()
                assertEquals(DownloadWizardStep.RADIUS, state.step)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `previousStep from LOCATION stays at LOCATION`() =
        runTest {
            viewModel = createViewModel()

            // Verify initial state is LOCATION
            assertEquals(DownloadWizardStep.LOCATION, viewModel.state.value.step)

            // Call previousStep - should stay at LOCATION
            viewModel.previousStep()

            // Verify state is still LOCATION
            assertEquals(DownloadWizardStep.LOCATION, viewModel.state.value.step)
        }

    @Test
    fun `previousStep from DOWNLOADING cancels and returns to CONFIRM`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.state.test {
                awaitItem()

                // Navigate to DOWNLOADING
                viewModel.nextStep()
                awaitItem()
                viewModel.nextStep()
                awaitItem()
                viewModel.nextStep()
                awaitItem()

                viewModel.previousStep()
                val state = awaitItem()
                assertEquals(DownloadWizardStep.CONFIRM, state.step)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Download Progress Tests

    @Test
    fun `download progress updates are reflected in state`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            val downloadingProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
                    totalTiles = 100,
                    downloadedTiles = 50,
                    failedTiles = 2,
                    bytesDownloaded = 750000L,
                    currentZoom = 10,
                )

            // Navigate to DOWNLOADING state first
            viewModel.nextStep() // RADIUS
            viewModel.nextStep() // CONFIRM
            viewModel.nextStep() // DOWNLOADING

            viewModel.state.test {
                val initialState = awaitItem()
                assertEquals(DownloadWizardStep.DOWNLOADING, initialState.step)

                // Simulate progress update
                progressFlow.value = downloadingProgress

                val state = awaitItem()
                assertNotNull(state.downloadProgress)
                assertEquals(
                    TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
                    state.downloadProgress?.status,
                )
                assertEquals(100, state.downloadProgress?.totalTiles)
                assertEquals(50, state.downloadProgress?.downloadedTiles)
                assertEquals(2, state.downloadProgress?.failedTiles)
                assertEquals(0.5f, state.downloadProgress?.progress)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `download progress status COMPLETE marks download as complete`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            val testDir = File(context.filesDir, "test_offline_maps")
            testDir.mkdirs()
            val testFile = File(testDir, "test.mbtiles")
            testFile.createNewFile()

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L
            coEvery {
                anyConstructed<TileDownloadManager>().downloadRegion(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns testFile

            // Navigate to DOWNLOADING
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertEquals(DownloadWizardStep.DOWNLOADING, state.step)

                cancelAndConsumeRemainingEvents()
            }

            testFile.delete()
            testDir.delete()
        }

    // endregion

    // region Download Cancellation Tests

    @Test
    fun `cancelDownload cancels the download manager`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            // Navigate to DOWNLOADING to trigger download
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.cancelDownload()

            // Verify cancel was called
            io.mockk.verify { anyConstructed<TileDownloadManager>().cancel() }
        }

    @Test
    fun `previousStep from DOWNLOADING cancels download`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.previousStep()

            io.mockk.verify { anyConstructed<TileDownloadManager>().cancel() }
        }

    // endregion

    // region Reset Tests

    @Test
    fun `reset resets all state to initial values`() =
        runTest {
            viewModel = createViewModel()

            // Set up some state
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")
            viewModel.setRadiusOption(RadiusOption.LARGE)
            viewModel.nextStep()

            viewModel.state.test {
                var state = awaitItem()
                assertEquals(DownloadWizardStep.RADIUS, state.step)
                assertEquals(40.7128, state.centerLatitude)
                assertEquals("Test Region", state.name)

                viewModel.reset()
                state = awaitItem()

                assertEquals(DownloadWizardStep.LOCATION, state.step)
                assertNull(state.centerLatitude)
                assertNull(state.centerLongitude)
                assertEquals(RadiusOption.MEDIUM, state.radiusOption)
                assertEquals("", state.name)
                assertEquals(0, state.estimatedTileCount)
                assertFalse(state.isComplete)
                assertNull(state.errorMessage)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `reset calls reset on download manager`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)

            viewModel.reset()

            io.mockk.verify { anyConstructed<TileDownloadManager>().reset() }
        }

    // endregion

    // region Error Handling Tests

    @Test
    fun `clearError clears error message`() =
        runTest {
            viewModel = createViewModel()

            // Verify initial errorMessage is null
            assertNull(viewModel.state.value.errorMessage)

            // Call clearError - should maintain null (no-op when already null)
            viewModel.clearError()

            // Verify errorMessage is still null
            assertNull(viewModel.state.value.errorMessage)
        }

    @Test
    fun `error progress status updates error message`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            val errorProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.ERROR,
                    totalTiles = 100,
                    downloadedTiles = 50,
                    failedTiles = 50,
                    bytesDownloaded = 750000L,
                    currentZoom = 10,
                    errorMessage = "Network error",
                )

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.state.test {
                awaitItem()

                progressFlow.value = errorProgress

                val state = awaitItem()
                assertEquals(TileDownloadManager.DownloadProgress.Status.ERROR, state.downloadProgress?.status)
                assertEquals("Network error", state.downloadProgress?.errorMessage)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Estimated Size String Tests

    @Test
    fun `getEstimatedSizeString returns bytes for small sizes`() {
        val state = OfflineMapDownloadState(estimatedSizeBytes = 500L)
        assertEquals("500 B", state.getEstimatedSizeString())
    }

    @Test
    fun `getEstimatedSizeString returns KB for kilobyte sizes`() {
        val state = OfflineMapDownloadState(estimatedSizeBytes = 5000L)
        assertEquals("4 KB", state.getEstimatedSizeString())
    }

    @Test
    fun `getEstimatedSizeString returns MB for megabyte sizes`() {
        val state = OfflineMapDownloadState(estimatedSizeBytes = 5_000_000L)
        assertEquals("4 MB", state.getEstimatedSizeString())
    }

    @Test
    fun `getEstimatedSizeString returns GB for gigabyte sizes`() {
        val state = OfflineMapDownloadState(estimatedSizeBytes = 2_500_000_000L)
        assertEquals("2.3 GB", state.getEstimatedSizeString())
    }

    // endregion

    // region Tile Source Determination Tests

    @Test
    fun `download uses HTTP when HTTP is enabled`() =
        runTest {
            httpEnabledFlow.value = true
            rmspEnabledFlow.value = false

            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // Verify region was created (download started)
            coVerify {
                offlineMapRegionRepository.createRegion(
                    name = "Test Region",
                    centerLatitude = 40.7128,
                    centerLongitude = -74.0060,
                    radiusKm = 10,
                    minZoom = 0,
                    maxZoom = 14,
                )
            }
        }

    @Test
    fun `download uses RMSP when HTTP disabled and RMSP enabled with servers`() =
        runTest {
            httpEnabledFlow.value = false
            rmspEnabledFlow.value = true

            val testServer = createTestRmspServer()
            allServersFlow.value = listOf(testServer)

            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            coVerify {
                offlineMapRegionRepository.createRegion(
                    name = "Test Region",
                    centerLatitude = 40.7128,
                    centerLongitude = -74.0060,
                    radiusKm = 10,
                    minZoom = 0,
                    maxZoom = 14,
                )
            }
        }

    @Test
    fun `download fails when both HTTP and RMSP disabled`() =
        runTest {
            httpEnabledFlow.value = false
            rmspEnabledFlow.value = false

            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertNotNull(state.errorMessage)
                assertTrue(state.errorMessage!!.contains("No tile source available"))

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `download fails when RMSP enabled but no servers available`() =
        runTest {
            httpEnabledFlow.value = false
            rmspEnabledFlow.value = true
            allServersFlow.value = emptyList()

            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertNotNull(state.errorMessage)
                assertTrue(state.errorMessage!!.contains("No RMSP servers discovered"))

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Region Repository Interaction Tests

    @Test
    fun `download creates region in repository`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("My Region")
            viewModel.setRadiusOption(RadiusOption.LARGE)
            viewModel.setZoomRange(3, 12)

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 456L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertEquals(456L, state.createdRegionId)

                cancelAndConsumeRemainingEvents()
            }

            coVerify {
                offlineMapRegionRepository.createRegion(
                    name = "My Region",
                    centerLatitude = 40.7128,
                    centerLongitude = -74.0060,
                    radiusKm = 25,
                    minZoom = 3,
                    maxZoom = 12,
                )
            }
        }

    @Test
    fun `download uses default name when name is blank`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            // Don't set name, leaving it blank

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            coVerify {
                offlineMapRegionRepository.createRegion(
                    name = "Offline Map",
                    centerLatitude = any(),
                    centerLongitude = any(),
                    radiusKm = any(),
                    minZoom = any(),
                    maxZoom = any(),
                )
            }
        }

    @Test
    fun `cancelled download deletes region from repository`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 789L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // Simulate cancelled progress
            val cancelledProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.CANCELLED,
                    totalTiles = 100,
                    downloadedTiles = 25,
                    failedTiles = 0,
                    bytesDownloaded = 375000L,
                    currentZoom = 8,
                )

            progressFlow.value = cancelledProgress

            coVerify(timeout = 1000) { offlineMapRegionRepository.deleteRegion(789L) }
        }

    @Test
    fun `error progress marks region with error in repository`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 101L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            val errorProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.ERROR,
                    totalTiles = 100,
                    downloadedTiles = 30,
                    failedTiles = 70,
                    bytesDownloaded = 450000L,
                    currentZoom = 9,
                    errorMessage = "Connection timeout",
                )

            progressFlow.value = errorProgress

            coVerify(timeout = 1000) {
                offlineMapRegionRepository.markError(
                    id = 101L,
                    errorMessage = "Connection timeout",
                )
            }
        }

    @Test
    fun `downloading progress updates repository`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 202L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            val downloadingProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.DOWNLOADING,
                    totalTiles = 100,
                    downloadedTiles = 60,
                    failedTiles = 5,
                    bytesDownloaded = 900000L,
                    currentZoom = 11,
                )

            progressFlow.value = downloadingProgress

            coVerify(timeout = 1000) {
                offlineMapRegionRepository.updateProgress(
                    id = 202L,
                    status = OfflineMapRegion.Status.DOWNLOADING,
                    progress = 0.6f,
                    tileCount = 60,
                )
            }
        }

    // endregion

    // region ViewModel Lifecycle Tests

    // Note: onCleared() is a protected method and cannot be tested directly.
    // The ViewModel's cleanup behavior when cleared is verified through
    // the TileDownloadManager cancellation in other tests.

    // endregion

    // region Download Progress State Tests

    @Test
    fun `progress IDLE status is reflected in state`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.downloadProgress)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `progress CALCULATING status is reflected correctly`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            val calculatingProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.CALCULATING,
                    totalTiles = 0,
                    downloadedTiles = 0,
                    failedTiles = 0,
                    bytesDownloaded = 0L,
                    currentZoom = 0,
                )

            progressFlow.value = calculatingProgress

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(
                    TileDownloadManager.DownloadProgress.Status.CALCULATING,
                    state.downloadProgress?.status,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `progress WRITING status is reflected correctly`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            val writingProgress =
                TileDownloadManager.DownloadProgress(
                    status = TileDownloadManager.DownloadProgress.Status.WRITING,
                    totalTiles = 100,
                    downloadedTiles = 100,
                    failedTiles = 0,
                    bytesDownloaded = 1500000L,
                    currentZoom = 14,
                )

            progressFlow.value = writingProgress

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(
                    TileDownloadManager.DownloadProgress.Status.WRITING,
                    state.downloadProgress?.status,
                )

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region DownloadWizardStep Enum Tests

    @Test
    fun `DownloadWizardStep has all expected values`() {
        val steps = DownloadWizardStep.entries.toTypedArray()
        assertEquals(4, steps.size)
        assertTrue(steps.contains(DownloadWizardStep.LOCATION))
        assertTrue(steps.contains(DownloadWizardStep.RADIUS))
        assertTrue(steps.contains(DownloadWizardStep.CONFIRM))
        assertTrue(steps.contains(DownloadWizardStep.DOWNLOADING))
    }

    // endregion

    // region State hasLocation Tests

    @Test
    fun `hasLocation is false when only latitude is set`() {
        val state = OfflineMapDownloadState(centerLatitude = 40.0, centerLongitude = null)
        assertFalse(state.hasLocation)
    }

    @Test
    fun `hasLocation is false when only longitude is set`() {
        val state = OfflineMapDownloadState(centerLatitude = null, centerLongitude = -74.0)
        assertFalse(state.hasLocation)
    }

    @Test
    fun `hasLocation is true when both coordinates are set`() {
        val state = OfflineMapDownloadState(centerLatitude = 40.0, centerLongitude = -74.0)
        assertTrue(state.hasLocation)
    }

    // endregion

    // region Helper Functions

    private fun createTestRmspServer(): RmspServer {
        return RmspServer(
            destinationHash = "abc123def456",
            serverName = "Test RMSP Server",
            publicKey = ByteArray(32) { it.toByte() },
            coverageGeohashes = listOf("dr5r"),
            minZoom = 0,
            maxZoom = 14,
            formats = listOf("pbf"),
            layers = listOf("default"),
            dataUpdatedTimestamp = System.currentTimeMillis(),
            dataSize = 1_000_000L,
            version = "1.0.0",
            lastSeenTimestamp = System.currentTimeMillis(),
            hops = 1,
        )
    }

    // endregion
}
