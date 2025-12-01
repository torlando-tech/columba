package com.lxmf.messenger.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for IdentityManagerViewModel.
 * Tests state management, Python integration, and all identity operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityManagerViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: IdentityManagerViewModel
    private lateinit var mockContext: Context
    private lateinit var mockRepository: IdentityRepository
    private lateinit var mockProtocol: ReticulumProtocol
    private lateinit var mockInterfaceConfigManager: InterfaceConfigManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true)
        mockRepository = mockk(relaxed = true)
        mockProtocol = mockk(relaxed = true)
        mockInterfaceConfigManager = mockk(relaxed = true)

        // Default mocks
        every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
        every { mockRepository.activeIdentity } returns MutableStateFlow(null)

        viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(
        hash: String = "test_hash",
        displayName: String = "Test",
        isActive: Boolean = false,
    ) = LocalIdentityEntity(
        identityHash = hash,
        displayName = displayName,
        destinationHash = "dest_$hash",
        filePath = "/data/identity_$hash",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = isActive,
    )

    private fun mockPythonCreateSuccess(
        identityHash: String = "new_hash",
        destinationHash: String = "new_dest",
    ): Map<String, Any> =
        mapOf(
            "identity_hash" to identityHash,
            "destination_hash" to destinationHash,
            "file_path" to "/data/identity_$identityHash",
            "display_name" to "Test",
        )

    private fun mockPythonError(): Map<String, Any> =
        mapOf(
            "error" to "Python error occurred",
        )

    // ========== Initial State Tests ==========

    @Test
    fun initialState_isIdle() =
        runTest {
            // When/Then
            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)
            }
        }

    @Test
    fun identities_exposesRepositoryFlow() =
        runTest {
            // Given
            val testIdentities = listOf(createTestIdentity(hash = "id1"))
            every { mockRepository.allIdentities } returns MutableStateFlow(testIdentities)
            every { mockRepository.activeIdentity } returns MutableStateFlow(null)

            // Re-create ViewModel with new mock
            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            // When/Then
            viewModel.identities.test {
                // First item is the initialValue (emptyList) from stateIn
                val initial = awaitItem()
                assertEquals(0, initial.size)

                // Second item is the actual value from the repository flow
                val identities = awaitItem()
                assertEquals(1, identities.size)
                assertEquals("id1", identities[0].identityHash)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun activeIdentity_exposesRepositoryFlow() =
        runTest {
            // Given
            val activeIdentity = createTestIdentity(hash = "active", isActive = true)
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(activeIdentity)

            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            // When/Then
            viewModel.activeIdentity.test {
                // First item is the initialValue (null) from stateIn
                val initial = awaitItem()
                assertNull(initial)

                // Second item is the actual value from the repository flow
                assertEquals("active", awaitItem()?.identityHash)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Create Identity Tests ==========

    @Test
    fun createNewIdentity_success_transitionsToSuccessState() =
        runTest {
            // Given
            val pythonResult = mockPythonCreateSuccess()
            coEvery { mockProtocol.createIdentityWithName("Work") } returns pythonResult
            coEvery { mockRepository.createIdentity(any(), any(), any(), any()) } returns
                Result.success(
                    createTestIdentity(),
                )

            viewModel.uiState.test {
                // Initial state
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.createNewIdentity("Work")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val successState = awaitItem()
                assertTrue(successState is IdentityManagerUiState.Success)
                assertEquals("Identity created successfully", (successState as IdentityManagerUiState.Success).message)
            }
        }

    @Test
    fun createNewIdentity_pythonError_transitionsToErrorState() =
        runTest {
            // Given
            coEvery { mockProtocol.createIdentityWithName(any()) } returns mockPythonError()

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.createNewIdentity("Work")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Python error occurred", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    @Test
    fun createNewIdentity_missingIdentityHash_transitionsToErrorState() =
        runTest {
            // Given - Python returns success but missing identity_hash
            coEvery { mockProtocol.createIdentityWithName(any()) } returns
                mapOf(
                    "destination_hash" to "dest",
                    "file_path" to "/path",
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.createNewIdentity("Work")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("identity_hash"))
            }
        }

    @Test
    fun createNewIdentity_databaseError_transitionsToErrorState() =
        runTest {
            // Given
            coEvery { mockProtocol.createIdentityWithName(any()) } returns mockPythonCreateSuccess()
            coEvery { mockRepository.createIdentity(any(), any(), any(), any()) } returns
                Result.failure(
                    RuntimeException("DB error"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.createNewIdentity("Work")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("DB error", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Switch Identity Tests ==========

    @Test
    fun switchToIdentity_success_transitionsToSuccess() =
        runTest {
            // Given
            coEvery { mockRepository.getIdentity("id2") } returns null
            coEvery { mockRepository.switchActiveIdentity("id2") } returns Result.success(Unit)
            coEvery { mockInterfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.switchToIdentity("id2")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then - The ViewModel now calls applyInterfaceChanges() instead of RequiresRestart
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                // Second loading state: "Restarting service..."
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                assertTrue(awaitItem() is IdentityManagerUiState.Success)
            }
        }

    @Test
    fun switchToIdentity_failure_transitionsToErrorState() =
        runTest {
            // Given
            coEvery { mockRepository.getIdentity(any()) } returns null
            coEvery { mockRepository.switchActiveIdentity(any()) } returns
                Result.failure(
                    RuntimeException("Switch failed"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.switchToIdentity("id2")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Switch failed", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Delete Identity Tests ==========

    @Test
    fun deleteIdentity_success_transitionsToSuccessState() =
        runTest {
            // Given
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "active", isActive = true))
            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            coEvery { mockProtocol.deleteIdentityFile("id1") } returns mapOf("success" to true)
            coEvery { mockRepository.deleteIdentity("id1") } returns Result.success(Unit)

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.deleteIdentity("id1")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val successState = awaitItem()
                assertTrue(successState is IdentityManagerUiState.Success)
                assertEquals("Identity deleted successfully", (successState as IdentityManagerUiState.Success).message)
            }
        }

    @Test
    fun deleteIdentity_activeIdentity_transitionsToErrorState() =
        runTest {
            // Given - trying to delete the active identity
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "id1", isActive = true))
            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.deleteIdentity("id1")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("active identity"))
            }
        }

    @Test
    fun deleteIdentity_pythonError_transitionsToErrorState() =
        runTest {
            // Given
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "active", isActive = true))
            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            coEvery { mockProtocol.deleteIdentityFile("id1") } returns
                mapOf(
                    "success" to false,
                    "error" to "File not found",
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.deleteIdentity("id1")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("File not found"))
            }
        }

    @Test
    fun deleteIdentity_databaseError_transitionsToErrorState() =
        runTest {
            // Given
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "active", isActive = true))
            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            coEvery { mockProtocol.deleteIdentityFile("id1") } returns mapOf("success" to true)
            coEvery { mockRepository.deleteIdentity("id1") } returns
                Result.failure(
                    RuntimeException("DB error"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.deleteIdentity("id1")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("DB error", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Rename Identity Tests ==========

    @Test
    fun renameIdentity_success_transitionsToSuccessState() =
        runTest {
            // Given
            coEvery { mockRepository.updateDisplayName("id1", "New Name") } returns Result.success(Unit)

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.renameIdentity("id1", "New Name")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val successState = awaitItem()
                assertTrue(successState is IdentityManagerUiState.Success)
                assertEquals("Identity renamed successfully", (successState as IdentityManagerUiState.Success).message)
            }
        }

    @Test
    fun renameIdentity_failure_transitionsToErrorState() =
        runTest {
            // Given
            coEvery { mockRepository.updateDisplayName(any(), any()) } returns
                Result.failure(
                    RuntimeException("Rename failed"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.renameIdentity("id1", "New Name")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Rename failed", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Import Identity Tests ==========

    @Test
    fun importIdentity_success_transitionsToSuccessState() =
        runTest {
            // Given
            val mockUri = mockk<Uri>()
            val mockContentResolver = mockk<ContentResolver>()
            val fileData = "identity_file_data".toByteArray()
            val inputStream = ByteArrayInputStream(fileData)

            every { mockContext.contentResolver } returns mockContentResolver
            every { mockContentResolver.openInputStream(mockUri) } returns inputStream

            coEvery { mockProtocol.importIdentityFile(fileData, "Imported") } returns
                mapOf(
                    "identity_hash" to "imported_hash",
                    "destination_hash" to "imported_dest",
                    "file_path" to "/data/identity_imported_hash",
                    "display_name" to "Imported",
                )
            coEvery { mockRepository.importIdentity(any(), any(), any(), any()) } returns
                Result.success(
                    createTestIdentity(hash = "imported_hash"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentity(mockUri, "Imported")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val successState = awaitItem()
                assertTrue(successState is IdentityManagerUiState.Success)
                assertEquals("Identity imported successfully", (successState as IdentityManagerUiState.Success).message)
            }
        }

    @Test
    fun importIdentity_fileReadError_transitionsToErrorState() =
        runTest {
            // Given
            val mockUri = mockk<Uri>()
            val mockContentResolver = mockk<ContentResolver>()

            every { mockContext.contentResolver } returns mockContentResolver
            every { mockContentResolver.openInputStream(mockUri) } returns null

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentity(mockUri, "Imported")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("Failed to read file"))
            }
        }

    @Test
    fun importIdentity_pythonError_transitionsToErrorState() =
        runTest {
            // Given
            val mockUri = mockk<Uri>()
            val mockContentResolver = mockk<ContentResolver>()
            val fileData = "identity_file_data".toByteArray()
            val inputStream = ByteArrayInputStream(fileData)

            every { mockContext.contentResolver } returns mockContentResolver
            every { mockContentResolver.openInputStream(mockUri) } returns inputStream
            coEvery { mockProtocol.importIdentityFile(any(), any()) } returns mockPythonError()

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentity(mockUri, "Imported")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Python error occurred", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Export Identity Tests ==========

    @Test
    fun exportIdentity_success_transitionsToExportReadyState() =
        runTest {
            // Given
            val fileData = "identity_data".toByteArray()
            val mockUri = mockk<Uri>()

            coEvery { mockProtocol.exportIdentityFile("id1", any()) } returns fileData
            coEvery { mockRepository.exportIdentity("id1", fileData) } returns Result.success(mockUri)

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.exportIdentity("id1", "/test/path")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val exportState = awaitItem()
                assertTrue(exportState is IdentityManagerUiState.ExportReady)
                assertEquals(mockUri, (exportState as IdentityManagerUiState.ExportReady).uri)
            }
        }

    @Test
    fun exportIdentity_emptyFileData_transitionsToErrorState() =
        runTest {
            // Given
            coEvery { mockProtocol.exportIdentityFile("id1", any()) } returns byteArrayOf()

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.exportIdentity("id1", "/test/path")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Failed to read identity file", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    @Test
    fun exportIdentity_repositoryError_transitionsToErrorState() =
        runTest {
            // Given
            val fileData = "identity_data".toByteArray()
            coEvery { mockProtocol.exportIdentityFile("id1", any()) } returns fileData
            coEvery { mockRepository.exportIdentity("id1", fileData) } returns
                Result.failure(
                    RuntimeException("FileProvider error"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.exportIdentity("id1", "/test/path")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("FileProvider error", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Reset UI State Tests ==========

    @Test
    fun resetUiState_transitionsToIdle() =
        runTest {
            // Given - set state to Error
            coEvery { mockRepository.updateDisplayName(any(), any()) } returns
                Result.failure(
                    RuntimeException("Error"),
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                viewModel.renameIdentity("id1", "Name")
                testDispatcher.scheduler.advanceUntilIdle()

                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                assertTrue(awaitItem() is IdentityManagerUiState.Error)

                // When
                viewModel.resetUiState()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)
            }
        }

    // ========== Python Integration Verification ==========

    @Test
    fun createNewIdentity_callsPythonWithCorrectDisplayName() =
        runTest {
            // Given
            coEvery { mockProtocol.createIdentityWithName("My Work Identity") } returns mockPythonCreateSuccess()
            coEvery { mockRepository.createIdentity(any(), any(), any(), any()) } returns
                Result.success(
                    createTestIdentity(),
                )

            // When
            viewModel.createNewIdentity("My Work Identity")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            coVerify { mockProtocol.createIdentityWithName("My Work Identity") }
        }

    @Test
    fun deleteIdentity_callsPythonWithCorrectHash() =
        runTest {
            // Given
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "active", isActive = true))
            viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            coEvery { mockProtocol.deleteIdentityFile("id_to_delete") } returns mapOf("success" to true)
            coEvery { mockRepository.deleteIdentity(any()) } returns Result.success(Unit)

            // When
            viewModel.deleteIdentity("id_to_delete")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            coVerify { mockProtocol.deleteIdentityFile("id_to_delete") }
            coVerify { mockRepository.deleteIdentity("id_to_delete") }
        }
}
