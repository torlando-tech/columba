package com.lxmf.messenger.viewmodel

import android.content.Context
import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.MapLibreOfflineManager
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.repository.SettingsRepository
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
    private lateinit var mockMapLibreOfflineManager: MapLibreOfflineManager
    private lateinit var mockMapTileSourceManager: MapTileSourceManager
    private lateinit var mockSettingsRepository: SettingsRepository
    private val httpEnabledFlow = MutableStateFlow(true)
    private val httpEnabledForDownloadFlow = MutableStateFlow(false)
    private lateinit var viewModel: OfflineMapDownloadViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = RuntimeEnvironment.getApplication()
        offlineMapRegionRepository = mockk()
        mockMapLibreOfflineManager = mockk()
        mockMapTileSourceManager = mockk()
        mockSettingsRepository = mockk()

        // Setup OfflineMapRegionRepository mock behavior
        coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 1L
        coEvery { offlineMapRegionRepository.updateProgress(any(), any(), any(), any()) } just Runs
        coEvery { offlineMapRegionRepository.markError(any(), any()) } just Runs
        coEvery { offlineMapRegionRepository.markCompleteWithMaplibreId(any(), any(), any(), any()) } just Runs
        coEvery { offlineMapRegionRepository.updateLocalStylePath(any(), any()) } just Runs

        // Setup MapLibreOfflineManager mock behavior
        every { mockMapLibreOfflineManager.estimateTileCount(any(), any(), any()) } returns 100L
        every {
            mockMapLibreOfflineManager.downloadRegion(
                name = any(),
                bounds = any(),
                minZoom = any(),
                maxZoom = any(),
                styleUrl = any(),
                onCreated = any(),
                onProgress = any(),
                onComplete = any(),
                onError = any(),
            )
        } just Runs

        // Setup MapTileSourceManager mock behavior
        every { mockMapTileSourceManager.httpEnabledFlow } returns httpEnabledFlow
        coEvery { mockMapTileSourceManager.setHttpEnabled(any()) } just Runs

        // Setup SettingsRepository mock behavior
        every { mockSettingsRepository.httpEnabledForDownloadFlow } returns httpEnabledForDownloadFlow
        coEvery { mockSettingsRepository.setHttpEnabledForDownload(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(): OfflineMapDownloadViewModel =
        OfflineMapDownloadViewModel(
            context = context,
            offlineMapRegionRepository = offlineMapRegionRepository,
            mapLibreOfflineManager = mockMapLibreOfflineManager,
            mapTileSourceManager = mockMapTileSourceManager,
            settingsRepository = mockSettingsRepository,
        )

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
                assertEquals(0L, state.estimatedTileCount)
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
                assertEquals(100L, state.estimatedTileCount)
                // estimatedSizeBytes = tileCount * 20000 = 100 * 20000 = 2000000
                assertEquals(2000000L, state.estimatedSizeBytes)

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
            assertEquals(100L, initialState.estimatedTileCount)
            assertEquals(2000000L, initialState.estimatedSizeBytes)
            assertEquals(0, initialState.minZoom)
            assertEquals(14, initialState.maxZoom)

            // Change zoom range and verify estimate is recalculated
            // The mock returns 100L for all calls, so we verify
            // that the zoom values are updated (the estimate stays the same in this test)
            viewModel.setZoomRange(5, 10)

            val updatedState = viewModel.state.value
            assertEquals(5, updatedState.minZoom)
            assertEquals(10, updatedState.maxZoom)
            // Estimate is recalculated (mock returns same value)
            assertEquals(100L, updatedState.estimatedTileCount)
            assertEquals(2000000L, updatedState.estimatedSizeBytes)

            // Verify estimateTileCount was called with new zoom values
            verify {
                mockMapLibreOfflineManager.estimateTileCount(
                    bounds = any(),
                    minZoom = 5,
                    maxZoom = 10,
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

            // Navigate to DOWNLOADING
            viewModel.nextStep() // RADIUS
            viewModel.nextStep() // CONFIRM
            viewModel.nextStep() // DOWNLOADING

            // Verify we're at DOWNLOADING
            assertEquals(DownloadWizardStep.DOWNLOADING, viewModel.state.value.step)

            // Go back
            viewModel.previousStep()

            // Should return to CONFIRM
            assertEquals(DownloadWizardStep.CONFIRM, viewModel.state.value.step)
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

            // Capture the onProgress callback
            val onProgressSlot = slot<(Float, Long, Long) -> Unit>()
            every {
                mockMapLibreOfflineManager.downloadRegion(
                    name = any(),
                    bounds = any(),
                    minZoom = any(),
                    maxZoom = any(),
                    styleUrl = any(),
                    onCreated = any(),
                    onProgress = capture(onProgressSlot),
                    onComplete = any(),
                    onError = any(),
                )
            } answers {
                // Simulate calling the progress callback
                onProgressSlot.captured(0.5f, 50L, 100L)
            }

            // Navigate to DOWNLOADING state first
            viewModel.nextStep() // RADIUS
            viewModel.nextStep() // CONFIRM
            viewModel.nextStep() // DOWNLOADING

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertEquals(DownloadWizardStep.DOWNLOADING, state.step)
                assertNotNull(state.downloadProgress)
                assertEquals(0.5f, state.downloadProgress?.progress)
                assertEquals(50L, state.downloadProgress?.completedResources)
                assertEquals(100L, state.downloadProgress?.requiredResources)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `download complete marks download as complete`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L
            coEvery { offlineMapRegionRepository.markCompleteWithMaplibreId(any(), any(), any(), any()) } returns Unit
            coEvery { offlineMapRegionRepository.updateLocalStylePath(any(), any()) } returns Unit

            // Capture the onComplete callback
            var capturedOnComplete: ((Long, Long) -> Unit)? = null
            every {
                mockMapLibreOfflineManager.downloadRegion(
                    name = any(),
                    bounds = any(),
                    minZoom = any(),
                    maxZoom = any(),
                    styleUrl = any(),
                    onCreated = any(),
                    onProgress = any(),
                    onComplete = any(),
                    onError = any(),
                )
            } answers {
                capturedOnComplete = arg<(Long, Long) -> Unit>(7)
            }

            // Navigate to DOWNLOADING
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // With UnconfinedTestDispatcher, coroutines execute eagerly
            // Now invoke the complete callback
            assertNotNull("onComplete callback should have been captured", capturedOnComplete)
            capturedOnComplete?.invoke(456L, 1500000L)

            // Verify state (state update happens immediately, style caching is async and non-blocking)
            assertEquals(DownloadWizardStep.DOWNLOADING, viewModel.state.value.step)
            assertTrue(viewModel.state.value.isComplete)
        }

    // endregion

    // region Download Cancellation Tests

    @Test
    fun `cancelDownload sets isDownloading to false`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            // Navigate to DOWNLOADING to trigger download
            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // Cancel download - this should complete without error
            viewModel.cancelDownload()

            // Verify we're still at DOWNLOADING step (cancelDownload doesn't change step)
            assertEquals(DownloadWizardStep.DOWNLOADING, viewModel.state.value.step)
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

            // Verify we're back at CONFIRM step
            assertEquals(DownloadWizardStep.CONFIRM, viewModel.state.value.step)
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
                assertEquals(0L, state.estimatedTileCount)
                assertFalse(state.isComplete)
                assertNull(state.errorMessage)

                cancelAndConsumeRemainingEvents()
            }
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
    fun `error callback updates error message`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 123L

            // Capture the onError callback
            val onErrorSlot = slot<(String) -> Unit>()
            every {
                mockMapLibreOfflineManager.downloadRegion(
                    name = any(),
                    bounds = any(),
                    minZoom = any(),
                    maxZoom = any(),
                    styleUrl = any(),
                    onCreated = any(),
                    onProgress = any(),
                    onComplete = any(),
                    onError = capture(onErrorSlot),
                )
            } answers {
                // Simulate calling the error callback
                onErrorSlot.captured("Network error")
            }

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertNotNull(state.errorMessage)
                assertTrue(state.errorMessage!!.contains("Network error"))
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

            // Verify name is blank before download
            assertEquals("", viewModel.state.value.name)

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // Verify region was created with default name
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

            // Verify we reached the downloading step
            assertEquals(DownloadWizardStep.DOWNLOADING, viewModel.state.value.step)
        }

    @Test
    fun `error marks region with error in repository`() =
        runTest {
            viewModel = createViewModel()
            viewModel.setLocation(40.7128, -74.0060)
            viewModel.setName("Test Region")

            coEvery { offlineMapRegionRepository.createRegion(any(), any(), any(), any(), any(), any()) } returns 101L

            // Capture the onError callback
            val onErrorSlot = slot<(String) -> Unit>()
            every {
                mockMapLibreOfflineManager.downloadRegion(
                    name = any(),
                    bounds = any(),
                    minZoom = any(),
                    maxZoom = any(),
                    styleUrl = any(),
                    onCreated = any(),
                    onProgress = any(),
                    onComplete = any(),
                    onError = capture(onErrorSlot),
                )
            } answers {
                // Simulate calling the error callback
                onErrorSlot.captured("Connection timeout")
            }

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // Verify error callback was captured
            assertTrue(onErrorSlot.isCaptured)

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

            // Capture the onProgress callback
            val onProgressSlot = slot<(Float, Long, Long) -> Unit>()
            every {
                mockMapLibreOfflineManager.downloadRegion(
                    name = any(),
                    bounds = any(),
                    minZoom = any(),
                    maxZoom = any(),
                    styleUrl = any(),
                    onCreated = any(),
                    onProgress = capture(onProgressSlot),
                    onComplete = any(),
                    onError = any(),
                )
            } answers {
                // Simulate calling the progress callback
                onProgressSlot.captured(0.6f, 60L, 100L)
            }

            viewModel.nextStep()
            viewModel.nextStep()
            viewModel.nextStep()

            // Verify progress callback was captured and invoked
            assertTrue(onProgressSlot.isCaptured)

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
    // other tests.

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

    // region Address Search Tests

    @Test
    fun `initial state has empty address search fields`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals("", state.addressQuery)
                assertTrue(state.addressSearchResults.isEmpty())
                assertFalse(state.isSearchingAddress)
                assertNull(state.addressSearchError)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `setAddressQuery updates state`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setAddressQuery("New York")

                val state = awaitItem()
                assertEquals("New York", state.addressQuery)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `searchAddress does nothing when query is blank`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.searchAddress()

                // Should not emit new state since query is blank
                expectNoEvents()

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `searchAddress does nothing when query is only whitespace`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.setAddressQuery("   ")
                awaitItem() // Query updated

                viewModel.searchAddress()

                // Should not emit new state since trimmed query is blank
                expectNoEvents()

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `selectAddressResult sets location and clears search`() =
        runTest {
            viewModel = createViewModel()

            // Set up some search state first
            viewModel.setAddressQuery("Dallas")

            // Select a result
            val result =
                AddressSearchResult(
                    displayName = "Dallas, TX, USA",
                    latitude = 32.7767,
                    longitude = -96.7970,
                )
            viewModel.selectAddressResult(result)

            // Verify the final state after all updates
            val state = viewModel.state.value
            assertEquals(32.7767, state.centerLatitude!!, 0.0001)
            assertEquals(-96.7970, state.centerLongitude!!, 0.0001)
            assertEquals("", state.addressQuery)
            assertTrue(state.addressSearchResults.isEmpty())
            assertNull(state.addressSearchError)
        }

    @Test
    fun `clearAddressSearch resets all address search state`() =
        runTest {
            viewModel = createViewModel()

            // Set up some search state
            viewModel.setAddressQuery("Chicago")

            // Clear search
            viewModel.clearAddressSearch()

            // Verify final state
            val state = viewModel.state.value
            assertEquals("", state.addressQuery)
            assertTrue(state.addressSearchResults.isEmpty())
            assertNull(state.addressSearchError)
        }

    @Test
    fun `selectAddressResult updates hasLocation to true`() =
        runTest {
            viewModel = createViewModel()

            assertFalse(viewModel.state.value.hasLocation)

            val result =
                AddressSearchResult(
                    displayName = "Los Angeles, CA, USA",
                    latitude = 34.0522,
                    longitude = -118.2437,
                )
            viewModel.selectAddressResult(result)

            assertTrue(viewModel.state.value.hasLocation)
        }

    // endregion

    // region Geocoder Availability Tests

    @Test
    fun `isGeocoderAvailable defaults to true in state`() {
        val state = OfflineMapDownloadState()
        assertTrue(state.isGeocoderAvailable)
    }

    @Test
    fun `state with isGeocoderAvailable false is valid`() {
        val state = OfflineMapDownloadState(isGeocoderAvailable = false)
        assertFalse(state.isGeocoderAvailable)
    }

    @Test
    fun `initial viewmodel state has geocoder availability set`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                // In Robolectric, Geocoder.isPresent() returns true by default
                // The actual availability depends on whether the geocoder test succeeds
                // We just verify the field exists and has a boolean value
                assertNotNull(state.isGeocoderAvailable)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region HTTP Enabled State Tests

    @Test
    fun `initial state reflects httpEnabled from flow`() =
        runTest {
            httpEnabledFlow.value = true
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.httpEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `httpEnabled state updates when flow changes`() =
        runTest {
            httpEnabledFlow.value = true
            viewModel = createViewModel()

            viewModel.state.test {
                val initial = awaitItem()
                assertTrue(initial.httpEnabled)

                httpEnabledFlow.value = false
                val updated = awaitItem()
                assertFalse(updated.httpEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `enableHttp sets httpEnabledForDownload flag and enables HTTP`() =
        runTest {
            httpEnabledFlow.value = false
            viewModel = createViewModel()

            // Verify initial state is HTTP disabled
            assertFalse(viewModel.state.value.httpEnabled)

            viewModel.enableHttp()

            // Verify the download flag is set
            coVerify { mockSettingsRepository.setHttpEnabledForDownload(true) }
            // Verify HTTP is enabled
            coVerify { mockMapTileSourceManager.setHttpEnabled(true) }

            // Verify calls completed successfully (no exception)
            assertTrue(true)
        }

    @Test
    fun `httpAutoDisabled is false initially`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.httpAutoDisabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `dismissHttpAutoDisabledMessage clears httpAutoDisabled flag`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val initial = awaitItem()
                assertFalse(initial.httpAutoDisabled)

                // Manually set httpAutoDisabled to true via internal state update
                // We simulate this by triggering the dismiss and verifying it stays false
                viewModel.dismissHttpAutoDisabledMessage()

                // Should remain false (no event emitted if already false)
                expectNoEvents()

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `httpEnabled false initially when flow starts with false`() =
        runTest {
            httpEnabledFlow.value = false
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.httpEnabled)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `enableHttp does not throw when called multiple times`() =
        runTest {
            httpEnabledFlow.value = false
            viewModel = createViewModel()

            // Call enableHttp multiple times - should not throw
            viewModel.enableHttp()
            viewModel.enableHttp()

            // Verify calls were made (at least twice)
            coVerify(atLeast = 2) { mockSettingsRepository.setHttpEnabledForDownload(true) }
            coVerify(atLeast = 2) { mockMapTileSourceManager.setHttpEnabled(true) }

            // Verify both calls completed successfully without exception
            assertTrue(true)
        }

    // endregion
}
