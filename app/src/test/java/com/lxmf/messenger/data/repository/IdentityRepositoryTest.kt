package com.lxmf.messenger.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.match
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Unit tests for IdentityRepository.
 * Tests repository logic with mocked DAO and Context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRepositoryTest {
    private lateinit var repository: IdentityRepository
    private lateinit var mockDao: LocalIdentityDao
    private lateinit var mockDatabase: ColumbaDatabase
    private lateinit var mockContext: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockDao = mockk(relaxed = true)
        mockDatabase = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        // Set up default flows BEFORE constructing repository
        // This is critical because repository initializes flows at construction time
        every { mockDao.getAllIdentities() } returns flowOf(emptyList())
        every { mockDao.getActiveIdentity() } returns flowOf(null)

        repository =
            IdentityRepository(
                identityDao = mockDao,
                database = mockDatabase,
                context = mockContext,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(
        hash: String = "test_hash_123",
        displayName: String = "Test Identity",
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

    // ========== Flow Exposure Tests ==========

    @Test
    fun allIdentities_exposesFlowFromDao() =
        runTest {
            // Given
            val testIdentities =
                listOf(
                    createTestIdentity(hash = "id1"),
                    createTestIdentity(hash = "id2"),
                )
            every { mockDao.getAllIdentities() } returns flowOf(testIdentities)
            every { mockDao.getActiveIdentity() } returns flowOf(null)

            // Recreate repository with new mock
            repository =
                IdentityRepository(
                    identityDao = mockDao,
                    database = mockDatabase,
                    context = mockContext,
                    ioDispatcher = testDispatcher,
                )

            // When/Then
            repository.allIdentities.test {
                val identities = awaitItem()
                assertEquals(2, identities.size)
                assertEquals("id1", identities[0].identityHash)
                awaitComplete()
            }
        }

    @Test
    fun activeIdentity_exposesFlowFromDao() =
        runTest {
            // Given
            val activeIdentity = createTestIdentity(hash = "active", isActive = true)
            every { mockDao.getAllIdentities() } returns flowOf(emptyList())
            every { mockDao.getActiveIdentity() } returns flowOf(activeIdentity)

            // Recreate repository with new mock
            repository =
                IdentityRepository(
                    identityDao = mockDao,
                    database = mockDatabase,
                    context = mockContext,
                    ioDispatcher = testDispatcher,
                )

            // When/Then
            repository.activeIdentity.test {
                val identity = awaitItem()
                assertEquals("active", identity?.identityHash)
                assertTrue(identity?.isActive ?: false)
                awaitComplete()
            }
        }

    // ========== Get Active Identity Tests ==========

    @Test
    fun getActiveIdentitySync_callsDaoOnIoDispatcher() =
        runTest {
            // Given
            val activeIdentity = createTestIdentity(hash = "active", isActive = true)
            coEvery { mockDao.getActiveIdentitySync() } returns activeIdentity

            // When
            val result = repository.getActiveIdentitySync()
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertEquals(activeIdentity, result)
            coVerify { mockDao.getActiveIdentitySync() }
        }

    @Test
    fun getIdentity_callsDao() =
        runTest {
            // Given
            val identity = createTestIdentity(hash = "id1")
            coEvery { mockDao.getIdentity("id1") } returns identity

            // When
            val result = repository.getIdentity("id1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertEquals(identity, result)
            coVerify { mockDao.getIdentity("id1") }
        }

    // ========== Create Identity Tests ==========

    @Test
    fun createIdentity_insertsToDao_returnsSuccess() =
        runTest {
            // Given
            coEvery { mockDao.insert(any()) } just Runs

            // When
            val result =
                repository.createIdentity(
                    identityHash = "new_hash",
                    displayName = "New Identity",
                    destinationHash = "new_dest",
                    filePath = "/data/identity_new_hash",
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            val entity = result.getOrNull()
            assertNotNull(entity)
            assertEquals("new_hash", entity?.identityHash)
            assertEquals("New Identity", entity?.displayName)
            assertFalse(entity?.isActive ?: true)

            coVerify { mockDao.insert(match { it.identityHash == "new_hash" }) }
        }

    @Test
    fun createIdentity_whenDaoThrows_returnsFailure() =
        runTest {
            // Given
            val exception = RuntimeException("Database error")
            coEvery { mockDao.insert(any()) } throws exception

            // When
            val result =
                repository.createIdentity(
                    identityHash = "new_hash",
                    displayName = "New Identity",
                    destinationHash = "new_dest",
                    filePath = "/data/identity_new_hash",
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertEquals(exception, result.exceptionOrNull())
        }

    // ========== Switch Active Identity Tests ==========

    @Test
    fun switchActiveIdentity_callsSetActive_returnsSuccess() =
        runTest {
            // Given
            coEvery { mockDao.setActive("id2") } just Runs

            // When
            val result = repository.switchActiveIdentity("id2")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify { mockDao.setActive("id2") }
        }

    @Test
    fun switchActiveIdentity_whenDaoThrows_returnsFailure() =
        runTest {
            // Given
            val exception = RuntimeException("Cannot switch")
            coEvery { mockDao.setActive(any()) } throws exception

            // When
            val result = repository.switchActiveIdentity("id2")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertEquals(exception, result.exceptionOrNull())
        }

    // ========== Delete Identity Tests ==========

    @Test
    fun deleteIdentity_callsDao_returnsSuccess() =
        runTest {
            // Given
            coEvery { mockDao.delete("id1") } just Runs

            // When
            val result = repository.deleteIdentity("id1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify { mockDao.delete("id1") }
        }

    @Test
    fun deleteIdentity_whenDaoThrows_returnsFailure() =
        runTest {
            // Given
            val exception = RuntimeException("Cannot delete")
            coEvery { mockDao.delete(any()) } throws exception

            // When
            val result = repository.deleteIdentity("id1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertEquals(exception, result.exceptionOrNull())
        }

    // ========== Update Display Name Tests ==========

    @Test
    fun updateDisplayName_callsDao_returnsSuccess() =
        runTest {
            // Given
            coEvery { mockDao.updateDisplayName("id1", "New Name") } just Runs

            // When
            val result = repository.updateDisplayName("id1", "New Name")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify { mockDao.updateDisplayName("id1", "New Name") }
        }

    @Test
    fun updateDisplayName_whenDaoThrows_returnsFailure() =
        runTest {
            // Given
            val exception = RuntimeException("Update failed")
            coEvery { mockDao.updateDisplayName(any(), any()) } throws exception

            // When
            val result = repository.updateDisplayName("id1", "New Name")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
        }

    // ========== Import Identity Tests ==========

    @Test
    fun importIdentity_insertsToDao_returnsSuccess() =
        runTest {
            // Given
            coEvery { mockDao.insert(any()) } just Runs

            // When
            val result =
                repository.importIdentity(
                    identityHash = "imported_hash",
                    displayName = "Imported",
                    destinationHash = "imported_dest",
                    filePath = "/data/identity_imported_hash",
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            val entity = result.getOrNull()
            assertEquals("imported_hash", entity?.identityHash)
            assertEquals("Imported", entity?.displayName)
            assertFalse(entity?.isActive ?: true)

            coVerify { mockDao.insert(match { it.identityHash == "imported_hash" }) }
        }

    @Test
    fun importIdentity_whenDaoThrows_returnsFailure() =
        runTest {
            // Given
            val exception = RuntimeException("Import failed")
            coEvery { mockDao.insert(any()) } throws exception

            // When
            val result =
                repository.importIdentity(
                    identityHash = "imported_hash",
                    displayName = "Imported",
                    destinationHash = "imported_dest",
                    filePath = "/data/identity_imported_hash",
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
        }

    // ========== Export Identity Tests ==========

    @Test
    fun exportIdentity_createsFileAndReturnsUri() =
        runTest {
            // Given
            val fileData = "identity_data".toByteArray()
            val mockUri = mockk<Uri>()

            // Create a real temporary directory for this test
            val tempCacheDir = createTempDirectory("test_cache").toFile()

            every { mockContext.cacheDir } returns tempCacheDir
            every { mockContext.packageName } returns "com.lxmf.messenger"

            mockkStatic(FileProvider::class)
            every {
                FileProvider.getUriForFile(
                    mockContext,
                    "com.lxmf.messenger.fileprovider",
                    any(),
                )
            } returns mockUri

            // When
            val result = repository.exportIdentity("test_hash", fileData)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(mockUri, result.getOrNull())

            // Verify the file was actually created with correct data
            val expectedFile = File(tempCacheDir, "identity_test_hash.rnsidentity")
            assertTrue(expectedFile.exists())
            assertArrayEquals(fileData, expectedFile.readBytes())

            verify {
                FileProvider.getUriForFile(
                    mockContext,
                    "com.lxmf.messenger.fileprovider",
                    any(),
                )
            }

            // Cleanup
            unmockkStatic(FileProvider::class)
            tempCacheDir.deleteRecursively()
        }

    @Test
    fun exportIdentity_whenFileProviderThrows_returnsFailure() =
        runTest {
            // Given
            val fileData = "identity_data".toByteArray()
            val exception = RuntimeException("FileProvider error")

            every { mockContext.cacheDir } throws exception

            // When
            val result = repository.exportIdentity("test_hash", fileData)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
        }

    // ========== Count and Existence Tests ==========

    @Test
    fun getIdentityCount_returnsDaoCount() =
        runTest {
            // Given
            coEvery { mockDao.getIdentityCount() } returns 5

            // When
            val count = repository.getIdentityCount()
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertEquals(5, count)
            coVerify { mockDao.getIdentityCount() }
        }

    @Test
    fun identityExists_returnsDaoResult() =
        runTest {
            // Given
            coEvery { mockDao.identityExists("id1") } returns true
            coEvery { mockDao.identityExists("id2") } returns false

            // When
            val exists1 = repository.identityExists("id1")
            val exists2 = repository.identityExists("id2")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(exists1)
            assertFalse(exists2)
        }

    // ========== Update Last Used Timestamp Tests ==========

    @Test
    fun updateLastUsedTimestamp_callsDao_returnsSuccess() =
        runTest {
            // Given
            coEvery { mockDao.updateLastUsedTimestamp(any(), any()) } just Runs

            // When
            val result = repository.updateLastUsedTimestamp("id1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify { mockDao.updateLastUsedTimestamp(eq("id1"), any()) }
        }

    @Test
    fun updateLastUsedTimestamp_whenDaoThrows_returnsFailure() =
        runTest {
            // Given
            val exception = RuntimeException("Update failed")
            coEvery { mockDao.updateLastUsedTimestamp(any(), any()) } throws exception

            // When
            val result = repository.updateLastUsedTimestamp("id1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
        }

    // ========== Ensure Identity File Exists Tests ==========

    @Test
    fun ensureIdentityFileExists_whenFileExists_returnsPath() =
        runTest {
            // Given - create real temp directory structure
            val tempFilesDir = createTempDirectory("test_files").toFile()
            val reticulumDir = File(tempFilesDir, "reticulum").apply { mkdirs() }
            val identityHash = "abc123def456"
            val canonicalFile = File(reticulumDir, "identity_$identityHash")

            // Create a 64-byte identity file (valid size)
            val keyData = ByteArray(64) { it.toByte() }
            canonicalFile.writeBytes(keyData)

            every { mockContext.filesDir } returns tempFilesDir

            val identity =
                LocalIdentityEntity(
                    identityHash = identityHash,
                    displayName = "Test",
                    destinationHash = "dest_hash",
                    filePath = canonicalFile.absolutePath,
                    keyData = keyData,
                    createdTimestamp = 0L,
                    lastUsedTimestamp = 0L,
                    isActive = true,
                )

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(canonicalFile.absolutePath, result.getOrNull())

            // Cleanup
            tempFilesDir.deleteRecursively()
        }

    @Test
    fun ensureIdentityFileExists_whenFileExistsButDbHasDifferentPath_updatesDb() =
        runTest {
            // Given - file exists at canonical path but DB has old path
            val tempFilesDir = createTempDirectory("test_files").toFile()
            val reticulumDir = File(tempFilesDir, "reticulum").apply { mkdirs() }
            val identityHash = "abc123def456"
            val canonicalFile = File(reticulumDir, "identity_$identityHash")

            val keyData = ByteArray(64) { it.toByte() }
            canonicalFile.writeBytes(keyData)

            every { mockContext.filesDir } returns tempFilesDir
            coEvery { mockDao.updateFilePath(any(), any()) } just Runs

            // DB shows old default_identity path
            val identity =
                LocalIdentityEntity(
                    identityHash = identityHash,
                    displayName = "Test",
                    destinationHash = "dest_hash",
                    filePath = "/old/path/default_identity",
                    keyData = keyData,
                    createdTimestamp = 0L,
                    lastUsedTimestamp = 0L,
                    isActive = true,
                )

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(canonicalFile.absolutePath, result.getOrNull())
            coVerify { mockDao.updateFilePath(identityHash, canonicalFile.absolutePath) }

            // Cleanup
            tempFilesDir.deleteRecursively()
        }

    @Test
    fun ensureIdentityFileExists_whenFileMissing_recoversFromKeyData() =
        runTest {
            // Given - no file exists but keyData is available
            val tempFilesDir = createTempDirectory("test_files").toFile()
            val reticulumDir = File(tempFilesDir, "reticulum").apply { mkdirs() }
            val identityHash = "abc123def456"
            val canonicalFile = File(reticulumDir, "identity_$identityHash")

            // File does NOT exist
            assertFalse(canonicalFile.exists())

            val keyData = ByteArray(64) { it.toByte() }

            every { mockContext.filesDir } returns tempFilesDir
            coEvery { mockDao.updateFilePath(any(), any()) } just Runs

            val identity =
                LocalIdentityEntity(
                    identityHash = identityHash,
                    displayName = "Test",
                    destinationHash = "dest_hash",
                    filePath = "/old/path/identity_file",
                    keyData = keyData,
                    createdTimestamp = 0L,
                    lastUsedTimestamp = 0L,
                    isActive = true,
                )

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(canonicalFile.absolutePath, result.getOrNull())

            // Verify file was created with correct data
            assertTrue(canonicalFile.exists())
            assertArrayEquals(keyData, canonicalFile.readBytes())

            // Verify DB was updated
            coVerify { mockDao.updateFilePath(identityHash, canonicalFile.absolutePath) }

            // Cleanup
            tempFilesDir.deleteRecursively()
        }

    @Test
    fun ensureIdentityFileExists_whenFileMissingAndNoKeyData_returnsFailure() =
        runTest {
            // Given - no file and no keyData backup
            val tempFilesDir = createTempDirectory("test_files").toFile()
            val reticulumDir = File(tempFilesDir, "reticulum").apply { mkdirs() }

            every { mockContext.filesDir } returns tempFilesDir

            val identity =
                LocalIdentityEntity(
                    identityHash = "abc123",
                    displayName = "Test",
                    destinationHash = "dest_hash",
                    filePath = "/missing/path",
                    // No backup!
                    keyData = null,
                    createdTimestamp = 0L,
                    lastUsedTimestamp = 0L,
                    isActive = true,
                )

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("no valid keyData") == true,
            )

            // Cleanup
            tempFilesDir.deleteRecursively()
        }

    @Test
    fun ensureIdentityFileExists_whenFileMissingAndKeyDataWrongSize_returnsFailure() =
        runTest {
            // Given - keyData exists but is wrong size (not 64 bytes)
            val tempFilesDir = createTempDirectory("test_files").toFile()
            val reticulumDir = File(tempFilesDir, "reticulum").apply { mkdirs() }

            every { mockContext.filesDir } returns tempFilesDir

            val identity =
                LocalIdentityEntity(
                    identityHash = "abc123",
                    displayName = "Test",
                    destinationHash = "dest_hash",
                    filePath = "/missing/path",
                    // Wrong size - should be 64
                    keyData = ByteArray(32),
                    createdTimestamp = 0L,
                    lastUsedTimestamp = 0L,
                    isActive = true,
                )

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("no valid keyData") == true,
            )

            // Cleanup
            tempFilesDir.deleteRecursively()
        }

    @Test
    fun ensureIdentityFileExists_whenFileExistsButWrongSize_recoversFromKeyData() =
        runTest {
            // Given - file exists but is corrupted (wrong size)
            val tempFilesDir = createTempDirectory("test_files").toFile()
            val reticulumDir = File(tempFilesDir, "reticulum").apply { mkdirs() }
            val identityHash = "abc123def456"
            val canonicalFile = File(reticulumDir, "identity_$identityHash")

            // Create corrupted file (wrong size)
            canonicalFile.writeBytes(ByteArray(32))

            val keyData = ByteArray(64) { it.toByte() }

            every { mockContext.filesDir } returns tempFilesDir
            coEvery { mockDao.updateFilePath(any(), any()) } just Runs

            val identity =
                LocalIdentityEntity(
                    identityHash = identityHash,
                    displayName = "Test",
                    destinationHash = "dest_hash",
                    filePath = canonicalFile.absolutePath,
                    keyData = keyData,
                    createdTimestamp = 0L,
                    lastUsedTimestamp = 0L,
                    isActive = true,
                )

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)

            // Verify file was recovered with correct data
            assertTrue(canonicalFile.exists())
            assertEquals(64L, canonicalFile.length())
            assertArrayEquals(keyData, canonicalFile.readBytes())

            // Cleanup
            tempFilesDir.deleteRecursively()
        }
}
