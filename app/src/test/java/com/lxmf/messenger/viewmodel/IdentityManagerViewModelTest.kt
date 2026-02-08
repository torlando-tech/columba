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
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

    private lateinit var mockContext: Context
    private lateinit var mockRepository: IdentityRepository
    private lateinit var mockProtocol: ReticulumProtocol
    private lateinit var mockInterfaceConfigManager: InterfaceConfigManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    @Suppress("NoRelaxedMocks") // Context is an Android framework class
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContext = mockk(relaxed = true) // Android framework class
        mockRepository = mockk()
        mockProtocol = mockk()
        mockInterfaceConfigManager = mockk()

        // Default stubs for repository flows
        every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
        every { mockRepository.activeIdentity } returns MutableStateFlow(null)

        // Default stub for InterfaceConfigManager
        coEvery { mockInterfaceConfigManager.applyInterfaceChanges() } returns Result.success(Unit)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    /**
     * Creates a new ViewModel instance inside a test's runTest scope.
     * This ensures coroutines are properly tracked by the test infrastructure.
     */
    private fun createTestViewModel(): IdentityManagerViewModel =
        IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

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
            val viewModel = createTestViewModel()
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

            // Create ViewModel with configured mocks
            val viewModel = createTestViewModel()

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

            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

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
            val viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            // WhileSubscribed requires active collector - subscribe to activeIdentity to start the flow
            val identityJob =
                launch {
                    viewModel.activeIdentity.collect {}
                }
            advanceUntilIdle()

            viewModel.uiState.test {
                // Skip initial value if any, wait for Idle state
                val firstState = awaitItem()
                if (firstState !is IdentityManagerUiState.Idle) {
                    // If not Idle, wait for it
                    val idleState = awaitItem()
                    assertTrue(idleState is IdentityManagerUiState.Idle)
                } else {
                    assertTrue(firstState is IdentityManagerUiState.Idle)
                }

                // When
                viewModel.deleteIdentity("id1")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("active identity"))
            }

            identityJob.cancel()
        }

    @Test
    fun deleteIdentity_pythonError_transitionsToErrorState() =
        runTest {
            // Given
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "active", isActive = true))
            val viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

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
            val viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

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

    // ========== Import Identity from Base32 Tests ==========

    /** A valid 64-byte identity key encoded as Base32. */
    private val testIdentityBytes = ByteArray(64) { (it * 3 + 17).toByte() }
    private val testBase32Key =
        com.lxmf.messenger.util.Base32
            .encode(testIdentityBytes)

    @Test
    fun importIdentityFromBase32_success_transitionsToSuccessState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            coEvery { mockProtocol.importIdentityFile(testIdentityBytes, "Shared Key") } returns
                mapOf(
                    "identity_hash" to "b32_hash",
                    "destination_hash" to "b32_dest",
                    "file_path" to "/data/identity_b32_hash",
                )
            coEvery { mockRepository.importIdentity(any(), any(), any(), any()) } returns
                Result.success(createTestIdentity(hash = "b32_hash"))

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBase32(testBase32Key, "Shared Key")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val successState = awaitItem()
                assertTrue(successState is IdentityManagerUiState.Success)
                assertEquals(
                    "Identity imported successfully",
                    (successState as IdentityManagerUiState.Success).message,
                )
            }
        }

    @Test
    fun importIdentityFromBase32_invalidBase32_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When — garbage that isn't valid Base32
                viewModel.importIdentityFromBase32("not-valid-base32!@#", "Test")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("Invalid Base32"))
            }
        }

    @Test
    fun importIdentityFromBase32_wrongSize_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given — valid Base32 but only 32 bytes (not 64)
            val shortKey =
                com.lxmf.messenger.util.Base32
                    .encode(ByteArray(32) { it.toByte() })

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBase32(shortKey, "Test")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("expected 64 bytes"))
            }
        }

    @Test
    fun importIdentityFromBase32_pythonError_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            coEvery { mockProtocol.importIdentityFile(any(), any()) } returns mockPythonError()

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBase32(testBase32Key, "Test")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Python error occurred", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    @Test
    fun importIdentityFromBase32_duplicateIdentity_transitionsToErrorState() =
        runTest {
            // Given — identity already exists in the repository
            val existingIdentity = createTestIdentity(hash = "existing_hash", displayName = "My Key")
            every { mockRepository.allIdentities } returns MutableStateFlow(listOf(existingIdentity))
            val viewModel = createTestViewModel()

            // WhileSubscribed requires an active collector for identities to populate
            val identitiesJob = launch { viewModel.identities.collect {} }
            advanceUntilIdle()

            coEvery { mockProtocol.importIdentityFile(any(), any()) } returns
                mapOf(
                    "identity_hash" to "existing_hash",
                    "destination_hash" to "existing_dest",
                    "file_path" to "/data/identity_existing",
                )

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBase32(testBase32Key, "Duplicate")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("already exists"))
                assertTrue(errorState.message.contains("My Key"))
            }

            identitiesJob.cancel()
        }

    @Test
    fun importIdentityFromBase32_databaseError_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            coEvery { mockProtocol.importIdentityFile(any(), any()) } returns
                mapOf(
                    "identity_hash" to "new_hash",
                    "destination_hash" to "new_dest",
                    "file_path" to "/data/identity_new",
                )
            coEvery { mockRepository.importIdentity(any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("DB write failed"))

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBase32(testBase32Key, "Test")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("DB write failed", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Export Identity as Text Tests ==========

    @Test
    fun exportIdentityAsText_success_transitionsToExportTextReadyState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            val fileData = ByteArray(64) { it.toByte() }
            coEvery { mockProtocol.exportIdentityFile("id1", "/test/path") } returns fileData

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.exportIdentityAsText("id1", "/test/path")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val exportState = awaitItem()
                assertTrue(exportState is IdentityManagerUiState.ExportTextReady)

                // Verify round-trip: decode the Base32 string back to bytes
                val decoded =
                    com.lxmf.messenger.util.Base32.decode(
                        (exportState as IdentityManagerUiState.ExportTextReady).base32String,
                    )
                assertArrayEquals(fileData, decoded)
            }
        }

    @Test
    fun exportIdentityAsText_emptyFileData_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            coEvery { mockProtocol.exportIdentityFile("id1", any()) } returns byteArrayOf()

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.exportIdentityAsText("id1", "/test/path")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Failed to read identity file", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    @Test
    fun exportIdentityAsText_protocolException_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            coEvery { mockProtocol.exportIdentityFile(any(), any()) } throws
                RuntimeException("Service not connected")

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.exportIdentityAsText("id1", "/test/path")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertEquals("Service not connected", (errorState as IdentityManagerUiState.Error).message)
            }
        }

    // ========== Import Identity from Backup Tests ==========

    @Test
    fun importIdentityFromBackup_success_transitionsToSuccessState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given — build a gzipped tar containing a 64-byte identity
            val identityData = ByteArray(64) { (it * 5).toByte() }
            val tarBytes = buildTestTar("Sideband Backup/primary_identity", identityData)
            val gzipBytes = gzipCompress(tarBytes)

            val mockUri = mockk<Uri>()
            val mockContentResolver = mockk<ContentResolver>()
            every { mockContext.contentResolver } returns mockContentResolver
            every { mockContentResolver.openInputStream(mockUri) } returns ByteArrayInputStream(gzipBytes)

            coEvery { mockProtocol.importIdentityFile(identityData, "From Backup") } returns
                mapOf(
                    "identity_hash" to "backup_hash",
                    "destination_hash" to "backup_dest",
                    "file_path" to "/data/identity_backup",
                )
            coEvery { mockRepository.importIdentity(any(), any(), any(), any()) } returns
                Result.success(createTestIdentity(hash = "backup_hash"))

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBackup(mockUri, "From Backup")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                // Second loading state: "Importing identity..."
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val successState = awaitItem()
                assertTrue(successState is IdentityManagerUiState.Success)
                assertEquals(
                    "Identity imported from backup successfully",
                    (successState as IdentityManagerUiState.Success).message,
                )
            }
        }

    @Test
    fun importIdentityFromBackup_wrongIdentitySize_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given — tar contains a 32-byte file (not 64)
            val shortData = ByteArray(32) { it.toByte() }
            val tarBytes = buildTestTar("Sideband Backup/primary_identity", shortData)
            val gzipBytes = gzipCompress(tarBytes)

            val mockUri = mockk<Uri>()
            val mockContentResolver = mockk<ContentResolver>()
            every { mockContext.contentResolver } returns mockContentResolver
            every { mockContentResolver.openInputStream(mockUri) } returns ByteArrayInputStream(gzipBytes)

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBackup(mockUri, "Bad Backup")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("expected 64 bytes"))
            }
        }

    @Test
    fun importIdentityFromBackup_nullInputStream_transitionsToErrorState() =
        runTest {
            val viewModel = createTestViewModel()

            // Given
            val mockUri = mockk<Uri>()
            val mockContentResolver = mockk<ContentResolver>()
            every { mockContext.contentResolver } returns mockContentResolver
            every { mockContentResolver.openInputStream(mockUri) } returns null

            viewModel.uiState.test {
                assertTrue(awaitItem() is IdentityManagerUiState.Idle)

                // When
                viewModel.importIdentityFromBackup(mockUri, "Test")
                testDispatcher.scheduler.advanceUntilIdle()

                // Then
                assertTrue(awaitItem() is IdentityManagerUiState.Loading)
                val errorState = awaitItem()
                assertTrue(errorState is IdentityManagerUiState.Error)
                assertTrue((errorState as IdentityManagerUiState.Error).message.contains("Failed"))
            }
        }

    // ========== Backup Test Helpers ==========

    /** Build a minimal tar archive with one entry. */
    private fun buildTestTar(
        name: String,
        data: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val header = ByteArray(512)
        // Name field (0-99)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0, 0, minOf(name.length, 100))
        // Size field (124-135), octal
        data.size
            .toString(8)
            .padStart(11, '0')
            .toByteArray(Charsets.US_ASCII)
            .copyInto(header, 124)
        header[156] = '0'.code.toByte()
        out.write(header)
        out.write(data)
        val remainder = data.size % 512
        if (remainder > 0) out.write(ByteArray(512 - remainder))
        out.write(ByteArray(1024)) // end-of-archive
        return out.toByteArray()
    }

    /** Gzip-compress a byte array. */
    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip
            .GZIPOutputStream(bos)
            .use { it.write(data) }
        return bos.toByteArray()
    }

    // ========== Reset UI State Tests ==========

    @Test
    fun resetUiState_transitionsToIdle() =
        runTest {
            val viewModel = createTestViewModel()

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
            val viewModel = createTestViewModel()

            // Given
            coEvery { mockProtocol.createIdentityWithName("My Work Identity") } returns mockPythonCreateSuccess()
            coEvery { mockRepository.createIdentity(any(), any(), any(), any()) } returns
                Result.success(
                    createTestIdentity(),
                )

            // When
            viewModel.createNewIdentity("My Work Identity")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then - verify the call was made and assert final state
            coVerify { mockProtocol.createIdentityWithName("My Work Identity") }
            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected Success state after identity creation", state is IdentityManagerUiState.Success)
            }
        }

    @Test
    fun deleteIdentity_callsPythonWithCorrectHash() =
        runTest {
            // Given
            every { mockRepository.allIdentities } returns MutableStateFlow(emptyList())
            every { mockRepository.activeIdentity } returns MutableStateFlow(createTestIdentity(hash = "active", isActive = true))
            val viewModel = IdentityManagerViewModel(mockContext, mockRepository, mockProtocol, mockInterfaceConfigManager)

            coEvery { mockProtocol.deleteIdentityFile("id_to_delete") } returns mapOf("success" to true)
            coEvery { mockRepository.deleteIdentity(any()) } returns Result.success(Unit)

            // When
            viewModel.deleteIdentity("id_to_delete")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then - verify the calls were made and assert final state
            coVerify { mockProtocol.deleteIdentityFile("id_to_delete") }
            coVerify { mockRepository.deleteIdentity("id_to_delete") }
            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue("Expected Success state after identity deletion", state is IdentityManagerUiState.Success)
            }
        }
}
