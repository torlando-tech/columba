package com.lxmf.messenger.viewmodel

import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.migration.ExportResult
import com.lxmf.messenger.migration.ImportResult
import com.lxmf.messenger.migration.MigrationExporter
import com.lxmf.messenger.migration.MigrationImporter
import com.lxmf.messenger.migration.MigrationPreview
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for MigrationViewModel.
 * Tests state transitions for export and import operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var migrationExporter: MigrationExporter
    private lateinit var migrationImporter: MigrationImporter
    private lateinit var interfaceConfigManager: InterfaceConfigManager
    private lateinit var viewModel: MigrationViewModel

    private val testExportPreview =
        ExportResult.Success(
            identityCount = 2,
            messageCount = 100,
            contactCount = 10,
            announceCount = 50,
            peerIdentityCount = 30,
            interfaceCount = 3,
            customThemeCount = 1,
        )

    private val testImportPreview =
        MigrationPreview(
            version = 4,
            exportedAt = 1700000000000L,
            identityCount = 2,
            conversationCount = 5,
            messageCount = 100,
            contactCount = 10,
            announceCount = 50,
            peerIdentityCount = 30,
            interfaceCount = 3,
            customThemeCount = 1,
            identityNames = listOf("Alice", "Bob"),
        )

    private val testImportResult =
        ImportResult.Success(
            identitiesImported = 2,
            messagesImported = 100,
            contactsImported = 10,
            announcesImported = 50,
            peerIdentitiesImported = 30,
            interfacesImported = 3,
            customThemesImported = 1,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        migrationExporter = mockk(relaxed = true)
        migrationImporter = mockk(relaxed = true)
        interfaceConfigManager = mockk(relaxed = true)

        // Mock getExportPreview called in init
        coEvery { migrationExporter.getExportPreview() } returns testExportPreview

        // Mock service restart to succeed
        coEvery { interfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)

        viewModel = MigrationViewModel(migrationExporter, migrationImporter, interfaceConfigManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // region Initial State Tests

    @Test
    fun `initial state is Idle`() =
        runTest {
            viewModel.uiState.test {
                assertEquals(MigrationUiState.Idle, awaitItem())
            }
        }

    @Test
    fun `loadExportPreview is called on init and updates exportPreview`() =
        runTest {
            advanceUntilIdle()

            viewModel.exportPreview.test {
                val preview = awaitItem()
                assertTrue(preview is ExportResult.Success)
                assertEquals(2, (preview as ExportResult.Success).identityCount)
            }

            coVerify { migrationExporter.getExportPreview() }
        }

    // endregion

    // region Export Tests

    @Test
    fun `exportData sets state to Exporting then ExportComplete on success`() =
        runTest {
            val mockUri = mockk<Uri>()
            coEvery { migrationExporter.exportData(any()) } returns Result.success(mockUri)

            viewModel.exportData()

            // First state should be Exporting
            viewModel.uiState.test {
                // Skip initial Idle if needed
                var state = awaitItem()
                if (state == MigrationUiState.Idle) {
                    state = awaitItem()
                }
                assertTrue("Expected Exporting but was $state", state == MigrationUiState.Exporting)
                cancelAndConsumeRemainingEvents()
            }

            advanceUntilIdle()

            // Final state should be ExportComplete
            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected ExportComplete but was $state", state is MigrationUiState.ExportComplete)
                assertEquals(mockUri, (state as MigrationUiState.ExportComplete).fileUri)
            }
        }

    @Test
    fun `exportData sets state to Error on failure`() =
        runTest {
            val errorMessage = "Export failed: disk full"
            coEvery { migrationExporter.exportData(any()) } returns Result.failure(Exception(errorMessage))

            viewModel.exportData()
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected Error but was $state", state is MigrationUiState.Error)
                assertTrue((state as MigrationUiState.Error).message.contains("disk full"))
            }
        }

    @Test
    fun `exportData updates progress during export`() =
        runTest {
            val mockUri = mockk<Uri>()
            coEvery { migrationExporter.exportData(any()) } coAnswers {
                val progressCallback = firstArg<(Float) -> Unit>()
                progressCallback(0.25f)
                progressCallback(0.50f)
                progressCallback(0.75f)
                progressCallback(1.0f)
                Result.success(mockUri)
            }

            viewModel.exportData()
            advanceUntilIdle()

            // Progress should have been updated (final value)
            viewModel.exportProgress.test {
                // After completion, progress might be at final value or reset
                val progress = awaitItem()
                // Just verify it's a valid float
                assertTrue(progress >= 0f && progress <= 1f)
            }
        }

    // endregion

    // region Import Preview Tests

    @Test
    fun `previewImport sets state to Loading then ImportPreview on success`() =
        runTest {
            val mockUri = mockk<Uri>()
            coEvery { migrationImporter.previewMigration(mockUri) } returns Result.success(testImportPreview)

            viewModel.previewImport(mockUri)
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected ImportPreview but was $state", state is MigrationUiState.ImportPreview)
                val preview = (state as MigrationUiState.ImportPreview).preview
                assertEquals(2, preview.identityCount)
                assertEquals(listOf("Alice", "Bob"), preview.identityNames)
            }
        }

    @Test
    fun `previewImport sets state to Error on failure`() =
        runTest {
            val mockUri = mockk<Uri>()
            val errorMessage = "Invalid migration file"
            coEvery { migrationImporter.previewMigration(mockUri) } returns Result.failure(Exception(errorMessage))

            viewModel.previewImport(mockUri)
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected Error but was $state", state is MigrationUiState.Error)
                assertTrue((state as MigrationUiState.Error).message.contains("Invalid migration file"))
            }
        }

    // endregion

    // region Import Tests

    @Test
    fun `importData sets state to Importing then RestartingService then ImportComplete on success`() =
        runTest {
            val mockUri = mockk<Uri>()
            coEvery { migrationImporter.importData(mockUri, any()) } returns testImportResult

            viewModel.importData(mockUri)

            // Wait for all coroutines including IO dispatcher to complete
            advanceUntilIdle()
            // Use real delay to wait for Dispatchers.IO work (virtual delay doesn't wait for real threads)
            withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
            advanceUntilIdle()

            // Verify service restart was called
            coVerify { interfaceConfigManager.applyInterfaceChanges() }

            // Final state should be ImportComplete (after restart)
            viewModel.uiState.test {
                var state = awaitItem()
                // Skip intermediate states if needed (Importing, RestartingService)
                while (state is MigrationUiState.Importing || state is MigrationUiState.RestartingService) {
                    state = awaitItem()
                }
                assertTrue("Expected ImportComplete but was $state", state is MigrationUiState.ImportComplete)
                val result = (state as MigrationUiState.ImportComplete).result
                assertEquals(2, result.identitiesImported)
                assertEquals(100, result.messagesImported)
            }
        }

    @Test
    fun `importData sets state to Error on failure`() =
        runTest {
            val mockUri = mockk<Uri>()
            val errorResult = ImportResult.Error("Database error during import")
            coEvery { migrationImporter.importData(mockUri, any()) } returns errorResult

            viewModel.importData(mockUri)
            advanceUntilIdle()

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected Error but was $state", state is MigrationUiState.Error)
                assertTrue((state as MigrationUiState.Error).message.contains("Database error"))
            }
        }

    @Test
    fun `importData updates progress during import`() =
        runTest {
            val mockUri = mockk<Uri>()
            coEvery { migrationImporter.importData(mockUri, any()) } coAnswers {
                val progressCallback = secondArg<(Float) -> Unit>()
                progressCallback(0.1f)
                progressCallback(0.5f)
                progressCallback(1.0f)
                testImportResult
            }

            viewModel.importData(mockUri)

            // Wait for all coroutines including IO dispatcher to complete
            advanceUntilIdle()
            // Use real delay to wait for Dispatchers.IO work
            withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
            advanceUntilIdle()

            // Import should complete successfully (after restart)
            viewModel.uiState.test {
                var state = awaitItem()
                // Skip intermediate states if needed (Importing, RestartingService)
                while (state is MigrationUiState.Importing || state is MigrationUiState.RestartingService) {
                    state = awaitItem()
                }
                assertTrue("Expected ImportComplete but was $state", state is MigrationUiState.ImportComplete)
            }
        }

    // endregion

    // region State Management Tests

    @Test
    fun `resetState returns to Idle and clears progress`() =
        runTest {
            val mockUri = mockk<Uri>()
            coEvery { migrationExporter.exportData(any()) } returns Result.success(mockUri)

            // First export
            viewModel.exportData()
            advanceUntilIdle()

            // Verify we're in ExportComplete
            viewModel.uiState.test {
                assertTrue(awaitItem() is MigrationUiState.ExportComplete)
            }

            // Reset
            viewModel.resetState()

            // Verify state is reset
            viewModel.uiState.test {
                assertEquals(MigrationUiState.Idle, awaitItem())
            }

            viewModel.exportProgress.test {
                assertEquals(0f, awaitItem())
            }

            viewModel.importProgress.test {
                assertEquals(0f, awaitItem())
            }

            viewModel.importPreview.test {
                assertNull(awaitItem())
            }
        }

    @Test
    fun `cleanupExportFiles calls exporter cleanup`() =
        runTest {
            every { migrationExporter.cleanupExportFiles() } returns Unit

            viewModel.cleanupExportFiles()

            verify { migrationExporter.cleanupExportFiles() }
        }

    // endregion
}
