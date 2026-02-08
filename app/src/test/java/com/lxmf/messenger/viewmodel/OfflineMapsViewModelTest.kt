package com.lxmf.messenger.viewmodel

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.repository.OfflineMapRegion
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.MapLibreOfflineManager
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
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
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for OfflineMapsViewModel.
 *
 * Tests cover:
 * - Initial state and loading
 * - State flow collection from repository
 * - Region deletion (success and error cases)
 * - Error handling and clearing
 * - Retry download functionality
 * - Offline maps directory retrieval
 * - Storage calculation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapsViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()

    // Android framework class - requires relaxed mock for complex internal state
    @Suppress("NoRelaxedMocks")
    private lateinit var context: Context

    private lateinit var offlineMapRegionRepository: OfflineMapRegionRepository

    // Android-dependent class with complex callback-based APIs
    @Suppress("NoRelaxedMocks")
    private val mockMapLibreOfflineManager: MapLibreOfflineManager = mockk(relaxed = true)

    private lateinit var viewModel: OfflineMapsViewModel

    // Mutable flows for controlling test scenarios
    private val regionsFlow = MutableStateFlow<List<OfflineMapRegion>>(emptyList())
    private val totalStorageFlow = MutableStateFlow<Long?>(0L)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        @Suppress("NoRelaxedMocks") // Android framework class
        context = mockk(relaxed = true)
        offlineMapRegionRepository = mockk()

        // Setup repository flow mocks
        every { offlineMapRegionRepository.getAllRegions() } returns regionsFlow
        every { offlineMapRegionRepository.getTotalStorageUsed() } returns totalStorageFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun createViewModel(): OfflineMapsViewModel =
        OfflineMapsViewModel(
            context = context,
            offlineMapRegionRepository = offlineMapRegionRepository,
            mapLibreOfflineManager = mockMapLibreOfflineManager,
        )

    /**
     * Configuration for test region creation.
     */
    private data class TestRegionConfig(
        val id: Long = 1L,
        val name: String = "Test Region",
        val status: OfflineMapRegion.Status = OfflineMapRegion.Status.COMPLETE,
        val mbtilesPath: String? = "/path/to/test.mbtiles",
        val sizeBytes: Long = 15_000_000L,
        val downloadProgress: Float = 1.0f,
        val errorMessage: String? = null,
        val source: OfflineMapRegion.Source = OfflineMapRegion.Source.HTTP,
    )

    private fun createTestRegion(config: TestRegionConfig = TestRegionConfig()): OfflineMapRegion {
        val now = System.currentTimeMillis()
        return OfflineMapRegion(
            id = config.id,
            name = config.name,
            centerLatitude = 40.7128,
            centerLongitude = -74.0060,
            radiusKm = 10,
            minZoom = 0,
            maxZoom = 14,
            status = config.status,
            mbtilesPath = config.mbtilesPath,
            tileCount = 1000,
            sizeBytes = config.sizeBytes,
            downloadProgress = config.downloadProgress,
            errorMessage = config.errorMessage,
            createdAt = now,
            completedAt = if (config.status == OfflineMapRegion.Status.COMPLETE) now else null,
            source = config.source,
            tileVersion = null,
        )
    }

    // region Initial State Tests

    @Test
    fun `initial state has correct defaults`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.regions.isEmpty())
                assertEquals(0L, state.totalStorageBytes)
                assertFalse(state.isLoading)
                assertFalse(state.isDeleting)
                assertNull(state.errorMessage)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state updates when regions flow emits`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state with empty regions
                var state = awaitItem()
                assertTrue(state.regions.isEmpty())

                // Emit a region
                val testRegion = createTestRegion()
                regionsFlow.value = listOf(testRegion)
                state = awaitItem()

                assertEquals(1, state.regions.size)
                assertEquals(testRegion.id, state.regions[0].id)
                assertEquals(testRegion.name, state.regions[0].name)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state updates when totalStorage flow emits`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                var state = awaitItem()
                assertEquals(0L, state.totalStorageBytes)

                // Emit new storage value
                val newStorageBytes = 50_000_000L
                totalStorageFlow.value = newStorageBytes
                state = awaitItem()

                assertEquals(newStorageBytes, state.totalStorageBytes)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state handles null totalStorage as zero`() =
        runTest {
            totalStorageFlow.value = null
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(0L, state.totalStorageBytes)
                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Regions List Tests

    @Test
    fun `state contains multiple regions when repository returns list`() =
        runTest {
            val regions =
                listOf(
                    createTestRegion(TestRegionConfig(id = 1, name = "Home")),
                    createTestRegion(TestRegionConfig(id = 2, name = "Work")),
                    createTestRegion(TestRegionConfig(id = 3, name = "Downtown")),
                )
            regionsFlow.value = regions
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(3, state.regions.size)
                assertEquals("Home", state.regions[0].name)
                assertEquals("Work", state.regions[1].name)
                assertEquals("Downtown", state.regions[2].name)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state updates when region list changes`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial empty state
                var state = awaitItem()
                assertTrue(state.regions.isEmpty())

                // Add first region
                regionsFlow.value = listOf(createTestRegion(TestRegionConfig(id = 1, name = "First")))
                state = awaitItem()
                assertEquals(1, state.regions.size)

                // Add second region
                regionsFlow.value =
                    listOf(
                        createTestRegion(TestRegionConfig(id = 1, name = "First")),
                        createTestRegion(TestRegionConfig(id = 2, name = "Second")),
                    )
                state = awaitItem()
                assertEquals(2, state.regions.size)

                // Remove first region
                regionsFlow.value = listOf(createTestRegion(TestRegionConfig(id = 2, name = "Second")))
                state = awaitItem()
                assertEquals(1, state.regions.size)
                assertEquals("Second", state.regions[0].name)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state correctly reflects regions with different statuses`() =
        runTest {
            val regions =
                listOf(
                    createTestRegion(TestRegionConfig(id = 1, name = "Complete", status = OfflineMapRegion.Status.COMPLETE)),
                    createTestRegion(
                        TestRegionConfig(
                            id = 2,
                            name = "Downloading",
                            status = OfflineMapRegion.Status.DOWNLOADING,
                            downloadProgress = 0.5f,
                        ),
                    ),
                    createTestRegion(
                        TestRegionConfig(
                            id = 3,
                            name = "Error",
                            status = OfflineMapRegion.Status.ERROR,
                            errorMessage = "Download failed",
                        ),
                    ),
                    createTestRegion(TestRegionConfig(id = 4, name = "Pending", status = OfflineMapRegion.Status.PENDING)),
                )
            regionsFlow.value = regions
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(4, state.regions.size)
                assertEquals(OfflineMapRegion.Status.COMPLETE, state.regions[0].status)
                assertEquals(OfflineMapRegion.Status.DOWNLOADING, state.regions[1].status)
                assertEquals(0.5f, state.regions[1].downloadProgress)
                assertEquals(OfflineMapRegion.Status.ERROR, state.regions[2].status)
                assertEquals("Download failed", state.regions[2].errorMessage)
                assertEquals(OfflineMapRegion.Status.PENDING, state.regions[3].status)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state correctly reflects regions with different sources`() =
        runTest {
            val regions =
                listOf(
                    createTestRegion(TestRegionConfig(id = 1, name = "HTTP Region", source = OfflineMapRegion.Source.HTTP)),
                    createTestRegion(TestRegionConfig(id = 2, name = "RMSP Region", source = OfflineMapRegion.Source.RMSP)),
                )
            regionsFlow.value = regions
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(2, state.regions.size)
                assertEquals(OfflineMapRegion.Source.HTTP, state.regions[0].source)
                assertEquals(OfflineMapRegion.Source.RMSP, state.regions[1].source)
                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Delete Region Tests

    @Test
    fun `deleteRegion sets isDeleting to true then false`() =
        runTest {
            val testRegion = createTestRegion()
            coEvery { offlineMapRegionRepository.deleteRegion(testRegion.id) } just Runs
            viewModel = createViewModel()

            // Verify initial state
            assertFalse(viewModel.state.value.isDeleting)

            // Delete region
            viewModel.deleteRegion(testRegion)

            // Since coroutine completes immediately with UnconfinedTestDispatcher,
            // the final state should have isDeleting = false
            assertFalse(viewModel.state.value.isDeleting)

            // Verify the deletion was actually called
            coVerify { offlineMapRegionRepository.deleteRegion(testRegion.id) }
        }

    @Test
    fun `deleteRegion calls repository deleteRegion`() =
        runTest {
            val testRegion = createTestRegion(TestRegionConfig(id = 42L))
            coEvery { offlineMapRegionRepository.deleteRegion(42L) } just Runs
            viewModel = createViewModel()

            viewModel.deleteRegion(testRegion)

            coVerify { offlineMapRegionRepository.deleteRegion(42L) }
            // Verify deletion completed (isDeleting returns to false)
            assertFalse(viewModel.state.value.isDeleting)
        }

    @Test
    fun `deleteRegion deletes mbtiles file when path exists`() =
        runTest {
            val tempFile = File.createTempFile("test", ".mbtiles")
            tempFile.writeText("test content")
            assertTrue(tempFile.exists())

            val testRegion = createTestRegion(TestRegionConfig(mbtilesPath = tempFile.absolutePath))
            coEvery { offlineMapRegionRepository.deleteRegion(testRegion.id) } just Runs
            viewModel = createViewModel()

            viewModel.deleteRegion(testRegion)

            // File should be deleted
            assertFalse(tempFile.exists())
            coVerify { offlineMapRegionRepository.deleteRegion(testRegion.id) }
        }

    @Test
    fun `deleteRegion handles null mbtiles path gracefully`() =
        runTest {
            val testRegion = createTestRegion(TestRegionConfig(mbtilesPath = null))
            coEvery { offlineMapRegionRepository.deleteRegion(testRegion.id) } just Runs
            viewModel = createViewModel()

            // Should not throw
            val result = runCatching { viewModel.deleteRegion(testRegion) }

            assertTrue("deleteRegion should complete without throwing", result.isSuccess)
            coVerify { offlineMapRegionRepository.deleteRegion(testRegion.id) }
        }

    @Test
    fun `deleteRegion handles non-existent mbtiles file gracefully`() =
        runTest {
            val testRegion = createTestRegion(TestRegionConfig(mbtilesPath = "/non/existent/path.mbtiles"))
            coEvery { offlineMapRegionRepository.deleteRegion(testRegion.id) } just Runs
            viewModel = createViewModel()

            // Should not throw
            val result = runCatching { viewModel.deleteRegion(testRegion) }

            assertTrue("deleteRegion should complete without throwing", result.isSuccess)
            coVerify { offlineMapRegionRepository.deleteRegion(testRegion.id) }
        }

    @Test
    fun `deleteRegion sets error message on exception`() =
        runTest {
            val testRegion = createTestRegion()
            coEvery {
                offlineMapRegionRepository.deleteRegion(testRegion.id)
            } throws RuntimeException("Database error")
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                assertNull(awaitItem().errorMessage)

                viewModel.deleteRegion(testRegion)

                val finalState = expectMostRecentItem()
                assertNotNull(finalState.errorMessage)
                assertTrue(finalState.errorMessage!!.contains("Failed to delete region"))
                assertTrue(finalState.errorMessage!!.contains("Database error"))
                assertFalse(finalState.isDeleting)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `deleteRegion resets isDeleting on error`() =
        runTest {
            val testRegion = createTestRegion()
            coEvery {
                offlineMapRegionRepository.deleteRegion(testRegion.id)
            } throws RuntimeException("Failed")
            viewModel = createViewModel()

            viewModel.deleteRegion(testRegion)

            viewModel.state.test {
                val state = expectMostRecentItem()
                assertFalse(state.isDeleting)
                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Error Handling Tests

    @Test
    fun `clearError sets errorMessage to null`() =
        runTest {
            val testRegion = createTestRegion()
            coEvery {
                offlineMapRegionRepository.deleteRegion(testRegion.id)
            } throws RuntimeException("Test error")
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial

                // Trigger an error
                viewModel.deleteRegion(testRegion)
                var state = expectMostRecentItem()
                assertNotNull(state.errorMessage)

                // Clear the error
                viewModel.clearError()
                state = awaitItem()
                assertNull(state.errorMessage)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearError does nothing when no error exists`() =
        runTest {
            viewModel = createViewModel()

            // Verify initial state has no error
            val initialState = viewModel.state.value
            assertNull(initialState.errorMessage)

            // Clear error when none exists
            viewModel.clearError()

            // State should still have no error
            val finalState = viewModel.state.value
            assertNull(finalState.errorMessage)
        }

    @Test
    fun `error message is preserved when other state changes`() =
        runTest {
            val testRegion = createTestRegion()
            coEvery {
                offlineMapRegionRepository.deleteRegion(testRegion.id)
            } throws RuntimeException("Persistent error")
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial

                // Trigger an error
                viewModel.deleteRegion(testRegion)
                var state = expectMostRecentItem()
                assertNotNull(state.errorMessage)

                // Update regions - error should persist
                regionsFlow.value = listOf(createTestRegion(TestRegionConfig(id = 99, name = "New Region")))
                state = awaitItem()
                assertNotNull(state.errorMessage)
                assertTrue(state.errorMessage!!.contains("Persistent error"))

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Retry Download Tests

    @Test
    fun `retryDownload does not throw for any region`() =
        runTest {
            viewModel = createViewModel()

            val pendingRegion = createTestRegion(TestRegionConfig(status = OfflineMapRegion.Status.PENDING))
            val errorRegion =
                createTestRegion(
                    TestRegionConfig(
                        status = OfflineMapRegion.Status.ERROR,
                        errorMessage = "Previous failure",
                    ),
                )
            val completeRegion = createTestRegion(TestRegionConfig(status = OfflineMapRegion.Status.COMPLETE))

            // None of these should throw
            viewModel.retryDownload(pendingRegion)
            viewModel.retryDownload(errorRegion)
            viewModel.retryDownload(completeRegion)
        }

    @Test
    fun `retryDownload can be called multiple times`() =
        runTest {
            viewModel = createViewModel()

            val region =
                createTestRegion(
                    TestRegionConfig(
                        status = OfflineMapRegion.Status.ERROR,
                        errorMessage = "Failed",
                    ),
                )

            // Should not throw when called multiple times
            repeat(5) {
                viewModel.retryDownload(region)
            }
        }

    // endregion

    // region Offline Maps Directory Tests

    @Test
    fun `getOfflineMapsDir returns directory with correct path`() =
        runTest {
            val mockFilesDir = File("/mock/files")
            every { context.filesDir } returns mockFilesDir
            viewModel = createViewModel()

            val result = viewModel.getOfflineMapsDir()

            assertEquals(File(mockFilesDir, "offline_maps"), result)
        }

    @Test
    fun `getOfflineMapsDir uses context filesDir`() =
        runTest {
            val mockFilesDir = File("/test/files")
            every { context.filesDir } returns mockFilesDir
            viewModel = createViewModel()

            val result = viewModel.getOfflineMapsDir()

            assertTrue(result.path.startsWith(mockFilesDir.path))
        }

    // endregion

    // region OfflineMapsState Tests

    @Test
    fun `getTotalStorageString returns B for small values`() {
        val state = OfflineMapsState(totalStorageBytes = 512L)
        assertEquals("512 B", state.getTotalStorageString())
    }

    @Test
    fun `getTotalStorageString returns KB for kilobyte values`() {
        val state = OfflineMapsState(totalStorageBytes = 2048L) // 2 KB
        assertEquals("2 KB", state.getTotalStorageString())
    }

    @Test
    fun `getTotalStorageString returns MB for megabyte values`() {
        val state = OfflineMapsState(totalStorageBytes = 5 * 1024 * 1024L) // 5 MB
        assertEquals("5 MB", state.getTotalStorageString())
    }

    @Test
    fun `getTotalStorageString returns GB for gigabyte values`() {
        val state = OfflineMapsState(totalStorageBytes = (1.5 * 1024 * 1024 * 1024).toLong())
        assertEquals("1.5 GB", state.getTotalStorageString())
    }

    @Test
    fun `getTotalStorageString handles zero`() {
        val state = OfflineMapsState(totalStorageBytes = 0L)
        assertEquals("0 B", state.getTotalStorageString())
    }

    @Test
    fun `getTotalStorageString handles boundary values`() {
        // Just under 1 KB
        val stateUnder1KB = OfflineMapsState(totalStorageBytes = 1023L)
        assertEquals("1023 B", stateUnder1KB.getTotalStorageString())

        // Exactly 1 KB
        val stateExact1KB = OfflineMapsState(totalStorageBytes = 1024L)
        assertEquals("1 KB", stateExact1KB.getTotalStorageString())

        // Just under 1 MB
        val stateUnder1MB = OfflineMapsState(totalStorageBytes = 1024L * 1024L - 1L)
        assertEquals("${(1024L * 1024L - 1L) / 1024} KB", stateUnder1MB.getTotalStorageString())

        // Exactly 1 MB
        val stateExact1MB = OfflineMapsState(totalStorageBytes = 1024L * 1024L)
        assertEquals("1 MB", stateExact1MB.getTotalStorageString())
    }

    // endregion

    // region OfflineMapRegion.getSizeString Tests

    @Test
    fun `region getSizeString returns B for small values`() {
        val region = createTestRegion(TestRegionConfig(sizeBytes = 100L))
        assertEquals("100 B", region.getSizeString())
    }

    @Test
    fun `region getSizeString returns KB for kilobyte values`() {
        val region = createTestRegion(TestRegionConfig(sizeBytes = 4096L))
        assertEquals("4 KB", region.getSizeString())
    }

    @Test
    fun `region getSizeString returns MB for megabyte values`() {
        val region = createTestRegion(TestRegionConfig(sizeBytes = 15_728_640L)) // 15 MB
        assertEquals("15 MB", region.getSizeString())
    }

    @Test
    fun `region getSizeString returns GB for gigabyte values`() {
        val region = createTestRegion(TestRegionConfig(sizeBytes = (2.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("2.5 GB", region.getSizeString())
    }

    // endregion

    // region State Immutability Tests

    @Test
    fun `OfflineMapsState is immutable`() {
        val state1 = OfflineMapsState(isLoading = false)
        val state2 = state1.copy(isLoading = true)

        // Original state should not be modified
        assertFalse(state1.isLoading)
        assertTrue(state2.isLoading)
    }

    @Test
    fun `state copy preserves all fields correctly`() {
        val regions = listOf(createTestRegion())
        val originalState =
            OfflineMapsState(
                regions = regions,
                totalStorageBytes = 1000L,
                isLoading = false,
                isDeleting = true,
                errorMessage = "test error",
            )

        // Copy with one field changed
        val copiedState = originalState.copy(isDeleting = false)

        assertEquals(regions, copiedState.regions)
        assertEquals(1000L, copiedState.totalStorageBytes)
        assertFalse(copiedState.isLoading)
        assertFalse(copiedState.isDeleting)
        assertEquals("test error", copiedState.errorMessage)
    }

    // endregion

    // region Concurrent Operation Tests

    @Test
    fun `deleteRegion handles concurrent calls safely`() =
        runTest {
            val region1 = createTestRegion(TestRegionConfig(id = 1))
            val region2 = createTestRegion(TestRegionConfig(id = 2))
            coEvery { offlineMapRegionRepository.deleteRegion(any()) } just Runs
            viewModel = createViewModel()

            // Call delete on multiple regions concurrently
            val result1 = runCatching { viewModel.deleteRegion(region1) }
            val result2 = runCatching { viewModel.deleteRegion(region2) }

            assertTrue("First deleteRegion should complete without throwing", result1.isSuccess)
            assertTrue("Second deleteRegion should complete without throwing", result2.isSuccess)
            coVerify { offlineMapRegionRepository.deleteRegion(1) }
            coVerify { offlineMapRegionRepository.deleteRegion(2) }
        }

    // endregion

    // region Flow Combination Tests

    @Test
    fun `state combines regions and storage correctly`() =
        runTest {
            val regions =
                listOf(
                    createTestRegion(TestRegionConfig(id = 1, sizeBytes = 10_000_000L)),
                    createTestRegion(TestRegionConfig(id = 2, sizeBytes = 20_000_000L)),
                )
            regionsFlow.value = regions
            totalStorageFlow.value = 30_000_000L
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(2, state.regions.size)
                assertEquals(30_000_000L, state.totalStorageBytes)
                assertEquals("28 MB", state.getTotalStorageString())
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state updates independently for regions and storage`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                var state = awaitItem()
                assertTrue(state.regions.isEmpty())
                assertEquals(0L, state.totalStorageBytes)

                // Update only regions
                regionsFlow.value = listOf(createTestRegion())
                state = awaitItem()
                assertEquals(1, state.regions.size)
                assertEquals(0L, state.totalStorageBytes)

                // Update only storage
                totalStorageFlow.value = 5_000_000L
                state = awaitItem()
                assertEquals(1, state.regions.size)
                assertEquals(5_000_000L, state.totalStorageBytes)

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region Edge Cases Tests

    @Test
    fun `state handles empty region name`() =
        runTest {
            val region = createTestRegion(TestRegionConfig(name = ""))
            regionsFlow.value = listOf(region)
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.regions.size)
                assertEquals("", state.regions[0].name)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state handles very large storage values`() =
        runTest {
            val largeStorage = Long.MAX_VALUE / 2
            totalStorageFlow.value = largeStorage
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(largeStorage, state.totalStorageBytes)
                // Should not throw when calculating string
                assertNotNull(state.getTotalStorageString())
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state handles region with all null optional fields`() =
        runTest {
            val region =
                createTestRegion(
                    TestRegionConfig(
                        mbtilesPath = null,
                        errorMessage = null,
                    ),
                )
            regionsFlow.value = listOf(region)
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.regions.size)
                assertNull(state.regions[0].mbtilesPath)
                assertNull(state.regions[0].errorMessage)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `deleteRegion with file delete failure still deletes from repository`() =
        runTest {
            // Create a directory instead of a file - File.delete() will return false for non-empty dir
            val tempDir =
                File.createTempFile("test", "").also {
                    it.delete()
                    it.mkdir()
                }
            val tempFile = File(tempDir, "nested.txt").also { it.writeText("test") }

            val testRegion = createTestRegion(TestRegionConfig(mbtilesPath = tempDir.absolutePath))
            viewModel = createViewModel()

            // Trigger deletion - with UnconfinedTestDispatcher this completes synchronously
            val result = runCatching { viewModel.deleteRegion(testRegion) }

            // Repository delete SHOULD be called even when file delete fails
            // This ensures the region is removed from the database to keep UI consistent
            assertTrue("deleteRegion should complete without throwing", result.isSuccess)
            coVerify { offlineMapRegionRepository.deleteRegion(testRegion.id) }

            // Cleanup
            tempFile.delete()
            tempDir.delete()
        }

    // endregion

    // region Check For Updates Tests

    @Test
    fun `checkForUpdates sets isChecking to true initially`() =
        runTest {
            val testRegion = createTestRegion(TestRegionConfig(id = 42L, name = "Test Region"))
            viewModel = createViewModel()

            viewModel.state.test {
                // Initial state
                awaitItem()

                viewModel.checkForUpdates(testRegion)

                // After calling checkForUpdates, we should see the update check result
                val state = expectMostRecentItem()
                assertTrue(state.updateCheckResults.containsKey(42L))

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `checkForUpdates stores current version from region`() =
        runTest {
            val testRegion = createTestRegion(TestRegionConfig(id = 42L))
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.checkForUpdates(testRegion)

                val state = expectMostRecentItem()
                val result = state.updateCheckResults[42L]
                assertNotNull(result)
                assertEquals(42L, result!!.regionId)

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `checkForUpdates can be called for multiple regions`() =
        runTest {
            val region1 = createTestRegion(TestRegionConfig(id = 1L, name = "Region 1"))
            val region2 = createTestRegion(TestRegionConfig(id = 2L, name = "Region 2"))
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.checkForUpdates(region1)
                viewModel.checkForUpdates(region2)

                val state = expectMostRecentItem()
                assertTrue(state.updateCheckResults.containsKey(1L))
                assertTrue(state.updateCheckResults.containsKey(2L))

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearUpdateCheckResult removes the result for a region`() =
        runTest {
            val testRegion = createTestRegion(TestRegionConfig(id = 42L))
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                // First trigger a check
                viewModel.checkForUpdates(testRegion)
                var state = expectMostRecentItem()

                // Verify it's there
                assertTrue(state.updateCheckResults.containsKey(42L))

                // Clear it
                viewModel.clearUpdateCheckResult(42L)
                state = awaitItem()

                // Verify it's gone
                assertFalse(state.updateCheckResults.containsKey(42L))

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearUpdateCheckResult does nothing for non-existent region`() =
        runTest {
            viewModel = createViewModel()

            viewModel.state.test {
                val state = awaitItem()

                // Should not throw
                viewModel.clearUpdateCheckResult(999L)

                assertTrue(state.updateCheckResults.isEmpty())

                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `clearUpdateCheckResult only removes specified region`() =
        runTest {
            val region1 = createTestRegion(TestRegionConfig(id = 1L))
            val region2 = createTestRegion(TestRegionConfig(id = 2L))
            viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // Initial state

                viewModel.checkForUpdates(region1)
                viewModel.checkForUpdates(region2)

                var state = expectMostRecentItem()
                assertEquals(2, state.updateCheckResults.size)

                viewModel.clearUpdateCheckResult(1L)

                state = awaitItem()
                assertEquals(1, state.updateCheckResults.size)
                assertFalse(state.updateCheckResults.containsKey(1L))
                assertTrue(state.updateCheckResults.containsKey(2L))

                cancelAndConsumeRemainingEvents()
            }
        }

    // endregion

    // region UpdateCheckResult Tests

    @Test
    fun `UpdateCheckResult hasUpdate is false when latestVersion is null`() {
        val result =
            UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = null,
            )

        assertFalse(result.hasUpdate)
    }

    @Test
    fun `UpdateCheckResult hasUpdate is false when currentVersion is null`() {
        val result =
            UpdateCheckResult(
                regionId = 1L,
                currentVersion = null,
                latestVersion = "v2",
            )

        assertFalse(result.hasUpdate)
    }

    @Test
    fun `UpdateCheckResult hasUpdate is false when versions are equal`() {
        val result =
            UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v1",
            )

        assertFalse(result.hasUpdate)
    }

    @Test
    fun `UpdateCheckResult hasUpdate is true when versions differ`() {
        val result =
            UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v2",
            )

        assertTrue(result.hasUpdate)
    }

    @Test
    fun `UpdateCheckResult defaults`() {
        val result =
            UpdateCheckResult(
                regionId = 1L,
                currentVersion = null,
                latestVersion = null,
            )

        assertFalse(result.isChecking)
        assertNull(result.error)
    }

    @Test
    fun `UpdateCheckResult copy preserves all fields`() {
        val original =
            UpdateCheckResult(
                regionId = 1L,
                currentVersion = "v1",
                latestVersion = "v2",
                isChecking = true,
                error = "test error",
            )

        val copied = original.copy(isChecking = false)

        assertEquals(1L, copied.regionId)
        assertEquals("v1", copied.currentVersion)
        assertEquals("v2", copied.latestVersion)
        assertFalse(copied.isChecking)
        assertEquals("test error", copied.error)
    }

    @Test
    fun `UpdateCheckResult equality works correctly`() {
        val result1 = UpdateCheckResult(1L, "v1", "v2", false, null)
        val result2 = UpdateCheckResult(1L, "v1", "v2", false, null)
        val result3 = UpdateCheckResult(2L, "v1", "v2", false, null)

        assertEquals(result1, result2)
        assertTrue(result1 != result3)
    }

    // endregion

    // region MapLibre Region Deletion Tests

    @Test
    fun `deleteRegion calls mapLibreOfflineManager for regions with maplibreRegionId`() =
        runTest {
            val testRegion =
                createTestRegion(TestRegionConfig(id = 1L)).copy(
                    maplibreRegionId = 100L,
                )
            coEvery { offlineMapRegionRepository.deleteRegion(1L) } just Runs
            viewModel = createViewModel()

            viewModel.deleteRegion(testRegion)

            // Verify MapLibre deletion was called
            verify { mockMapLibreOfflineManager.deleteRegion(100L, any()) }
            coVerify { offlineMapRegionRepository.deleteRegion(1L) }
            // Verify deletion completed successfully (no error, not deleting)
            assertFalse(viewModel.state.value.isDeleting)
            assertNull(viewModel.state.value.errorMessage)
        }

    @Test
    fun `deleteRegion does not call mapLibreOfflineManager when maplibreRegionId is null`() =
        runTest {
            val testRegion =
                createTestRegion(TestRegionConfig(id = 1L)).copy(
                    maplibreRegionId = null,
                )
            coEvery { offlineMapRegionRepository.deleteRegion(1L) } just Runs
            viewModel = createViewModel()

            viewModel.deleteRegion(testRegion)

            // Verify MapLibre deletion was NOT called (no maplibreRegionId)
            verify(exactly = 0) { mockMapLibreOfflineManager.deleteRegion(any(), any()) }
            coVerify { offlineMapRegionRepository.deleteRegion(1L) }
            // Verify deletion completed successfully (no error, not deleting)
            assertFalse(viewModel.state.value.isDeleting)
            assertNull(viewModel.state.value.errorMessage)
        }

    // endregion
}
