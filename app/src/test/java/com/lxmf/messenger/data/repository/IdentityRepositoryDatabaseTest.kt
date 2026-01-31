package com.lxmf.messenger.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.cash.turbine.test
import com.lxmf.messenger.test.DatabaseTest
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Database-backed tests for IdentityRepository.
 *
 * Tests database operations against real in-memory Room database while mocking
 * only file system operations (Context, FileProvider).
 *
 * Key behaviors tested with real database:
 * - Identity CRUD operations
 * - Active identity switching (setActive deactivates others)
 * - Identity existence checks
 * - Flow emissions on data changes
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRepositoryDatabaseTest : DatabaseTest() {
    private lateinit var repository: IdentityRepository
    private lateinit var mockContext: Context
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File

    @Before
    fun setupRepository() {
        Dispatchers.setMain(testDispatcher)

        // Create temp directory for file operations
        tempDir = createTempDirectory("identity_test").toFile()

        // Mock context for file operations
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempDir
        every { mockContext.cacheDir } returns tempDir
        every { mockContext.packageName } returns "com.lxmf.messenger"

        repository =
            IdentityRepository(
                identityDao = localIdentityDao,
                database = database,
                context = mockContext,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun teardownRepository() {
        Dispatchers.resetMain()
        clearAllMocks()
        tempDir.deleteRecursively()
    }

    // ========== Create Identity Tests ==========

    @Test
    fun `createIdentity inserts identity into database`() =
        runTest {
            val result =
                repository.createIdentity(
                    identityHash = "new_identity_hash",
                    displayName = "New Identity",
                    destinationHash = "new_dest_hash",
                    filePath = "/data/identity_new",
                )
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue("Create should succeed", result.isSuccess)

            // Verify identity was actually inserted
            val saved = localIdentityDao.getIdentity("new_identity_hash")
            assertNotNull("Identity should exist in database", saved)
            assertEquals("New Identity", saved?.displayName)
            assertEquals("new_dest_hash", saved?.destinationHash)
            assertFalse("New identity should not be active", saved?.isActive ?: true)
        }

    @Test
    fun `createIdentity with keyData stores key data`() =
        runTest {
            val keyData = ByteArray(64) { it.toByte() }

            val result =
                repository.createIdentity(
                    identityHash = "key_identity",
                    displayName = "Key Identity",
                    destinationHash = "key_dest",
                    filePath = "/data/identity_key",
                    keyData = keyData,
                )
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isSuccess)

            val saved = localIdentityDao.getIdentity("key_identity")
            assertNotNull(saved?.keyData)
            assertEquals(64, saved?.keyData?.size)
        }

    // ========== Get Identity Tests ==========

    @Test
    fun `getIdentity returns existing identity`() =
        runTest {
            // Setup: Insert identity directly
            localIdentityDao.insert(createTestIdentity())

            // When
            val identity = repository.getIdentity(TEST_IDENTITY_HASH)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertNotNull(identity)
            assertEquals(TEST_IDENTITY_HASH, identity?.identityHash)
        }

    @Test
    fun `getIdentity returns null for nonexistent identity`() =
        runTest {
            val identity = repository.getIdentity("nonexistent_hash")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(identity)
        }

    // ========== Active Identity Tests ==========

    @Test
    fun `switchActiveIdentity activates specified identity`() =
        runTest {
            // Setup: Create two identities
            localIdentityDao.insert(createTestIdentity(identityHash = "id1", isActive = false))
            localIdentityDao.insert(createTestIdentity(identityHash = "id2", isActive = false))

            // When: Activate id1
            val result = repository.switchActiveIdentity("id1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)

            val id1 = localIdentityDao.getIdentity("id1")
            val id2 = localIdentityDao.getIdentity("id2")

            assertTrue("id1 should be active", id1?.isActive ?: false)
            assertFalse("id2 should not be active", id2?.isActive ?: true)
        }

    @Test
    fun `switchActiveIdentity deactivates previous active identity`() =
        runTest {
            // Setup: Create identity and make it active
            localIdentityDao.insert(createTestIdentity(identityHash = "old_active", isActive = true))
            localIdentityDao.insert(createTestIdentity(identityHash = "new_active", isActive = false))

            // Verify precondition
            assertTrue(localIdentityDao.getIdentity("old_active")?.isActive ?: false)

            // When: Switch to new identity
            val result = repository.switchActiveIdentity("new_active")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Old should be deactivated, new should be active
            assertTrue(result.isSuccess)
            assertFalse(
                "Old identity should be deactivated",
                localIdentityDao.getIdentity("old_active")?.isActive ?: true,
            )
            assertTrue(
                "New identity should be active",
                localIdentityDao.getIdentity("new_active")?.isActive ?: false,
            )
        }

    @Test
    fun `getActiveIdentitySync returns active identity`() =
        runTest {
            localIdentityDao.insert(createTestIdentity(identityHash = "inactive", isActive = false))
            localIdentityDao.insert(createTestIdentity(identityHash = "active", isActive = true))

            val active = repository.getActiveIdentitySync()
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(active)
            assertEquals("active", active?.identityHash)
        }

    @Test
    fun `getActiveIdentitySync returns null when no active identity`() =
        runTest {
            localIdentityDao.insert(createTestIdentity(identityHash = "inactive", isActive = false))

            val active = repository.getActiveIdentitySync()
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(active)
        }

    // ========== Update Tests ==========

    @Test
    fun `updateDisplayName changes identity display name`() =
        runTest {
            localIdentityDao.insert(createTestIdentity(displayName = "Original Name"))

            val result = repository.updateDisplayName(TEST_IDENTITY_HASH, "Updated Name")
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isSuccess)

            val updated = localIdentityDao.getIdentity(TEST_IDENTITY_HASH)
            assertEquals("Updated Name", updated?.displayName)
        }

    @Test
    fun `updateLastUsedTimestamp updates timestamp`() =
        runTest {
            val originalTimestamp = 1000L
            val identity = createTestIdentity().copy(lastUsedTimestamp = originalTimestamp)
            localIdentityDao.insert(identity)

            val result = repository.updateLastUsedTimestamp(TEST_IDENTITY_HASH)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isSuccess)

            val updated = localIdentityDao.getIdentity(TEST_IDENTITY_HASH)
            assertTrue(
                "Timestamp should be updated to recent time",
                (updated?.lastUsedTimestamp ?: 0) > originalTimestamp,
            )
        }

    // ========== Delete Tests ==========

    @Test
    fun `deleteIdentity removes identity from database`() =
        runTest {
            localIdentityDao.insert(createTestIdentity())
            assertTrue(localIdentityDao.identityExists(TEST_IDENTITY_HASH))

            val result = repository.deleteIdentity(TEST_IDENTITY_HASH)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isSuccess)
            assertFalse(localIdentityDao.identityExists(TEST_IDENTITY_HASH))
        }

    // ========== Count and Existence Tests ==========

    @Test
    fun `getIdentityCount returns correct count`() =
        runTest {
            assertEquals(0, repository.getIdentityCount())

            localIdentityDao.insert(createTestIdentity(identityHash = "id1"))
            localIdentityDao.insert(createTestIdentity(identityHash = "id2"))
            localIdentityDao.insert(createTestIdentity(identityHash = "id3"))

            val count = repository.getIdentityCount()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(3, count)
        }

    @Test
    fun `identityExists returns correct boolean`() =
        runTest {
            assertFalse(repository.identityExists("nonexistent"))

            localIdentityDao.insert(createTestIdentity())

            val exists = repository.identityExists(TEST_IDENTITY_HASH)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(exists)
        }

    // ========== Flow Tests ==========

    @Test
    fun `allIdentities flow emits on insert`() =
        runTest {
            repository.allIdentities.test {
                // Initial emission should be empty
                assertEquals(0, awaitItem().size)

                // Insert identity
                localIdentityDao.insert(createTestIdentity(identityHash = "flow_test"))
                testDispatcher.scheduler.advanceUntilIdle()

                // Should emit with new identity
                val list = awaitItem()
                assertEquals(1, list.size)
                assertEquals("flow_test", list[0].identityHash)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `activeIdentity flow emits on activation change`() =
        runTest {
            repository.activeIdentity.test {
                // Initial: no active identity
                assertNull(awaitItem())

                // Insert inactive identity
                localIdentityDao.insert(createTestIdentity(identityHash = "to_activate", isActive = false))
                testDispatcher.scheduler.advanceUntilIdle()

                // Room may or may not emit here depending on change detection
                // Skip any intermediate null emissions

                // Activate the identity
                localIdentityDao.setActive("to_activate")
                testDispatcher.scheduler.advanceUntilIdle()

                // Should eventually emit the active identity
                // Skip intermediate nulls until we get the active identity
                var active: com.lxmf.messenger.data.db.entity.LocalIdentityEntity? = null
                while (active == null) {
                    active = awaitItem()
                }
                assertEquals("to_activate", active.identityHash)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Import Identity Tests ==========

    @Test
    fun `importIdentity creates identity in database`() =
        runTest {
            val result =
                repository.importIdentity(
                    identityHash = "imported_hash",
                    displayName = "Imported Identity",
                    destinationHash = "imported_dest",
                    filePath = "/data/imported",
                )
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isSuccess)

            val imported = localIdentityDao.getIdentity("imported_hash")
            assertNotNull(imported)
            assertEquals("Imported Identity", imported?.displayName)
            assertFalse("Imported identity should not be active by default", imported?.isActive ?: true)
        }

    // ========== Export Identity Tests ==========

    @Test
    fun `exportIdentity creates file and returns URI`() =
        runTest {
            val fileData = "identity_data".toByteArray()
            val mockUri = mockk<Uri>()

            mockkStatic(FileProvider::class)
            every {
                FileProvider.getUriForFile(any(), any(), any())
            } returns mockUri

            val result = repository.exportIdentity("export_test", fileData)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isSuccess)
            assertEquals(mockUri, result.getOrNull())

            // Verify file was created
            val exportedFile = File(tempDir, "identity_export_test.rnsidentity")
            assertTrue("Export file should exist", exportedFile.exists())

            unmockkStatic(FileProvider::class)
        }

    // ========== File Recovery Tests ==========

    @Test
    fun `ensureIdentityFileExists recovers file from keyData when missing`() =
        runTest {
            // Setup: Create reticulum directory
            val reticulumDir = File(tempDir, "reticulum").apply { mkdirs() }

            val keyData = ByteArray(64) { it.toByte() }
            val identity =
                createTestIdentity().copy(
                    keyData = keyData,
                    filePath = "/old/path/that/doesnt/exist",
                )
            localIdentityDao.insert(identity)

            // When: Ensure file exists (should recover from keyData)
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Should succeed and file should be created
            assertTrue("Recovery should succeed", result.isSuccess)

            val recoveredFile = File(reticulumDir, "identity_${TEST_IDENTITY_HASH}")
            assertTrue("Recovered file should exist", recoveredFile.exists())
            assertEquals("Recovered file should be 64 bytes", 64L, recoveredFile.length())
        }

    @Test
    fun `ensureIdentityFileExists fails when no keyData available`() =
        runTest {
            // Setup: Create reticulum directory but no file
            File(tempDir, "reticulum").mkdirs()

            val identity =
                createTestIdentity().copy(
                    keyData = null, // No backup!
                    filePath = "/missing/path",
                )
            localIdentityDao.insert(identity)

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Should fail
            assertTrue("Should fail when no keyData", result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message?.contains("no valid keyData") == true,
            )
        }

    @Test
    fun `ensureIdentityFileExists updates database path when file exists at canonical location`() =
        runTest {
            // Setup: Create file at canonical path
            val reticulumDir = File(tempDir, "reticulum").apply { mkdirs() }
            val canonicalFile = File(reticulumDir, "identity_${TEST_IDENTITY_HASH}")
            val keyData = ByteArray(64) { it.toByte() }
            canonicalFile.writeBytes(keyData)

            // Insert identity with wrong path
            val identity =
                createTestIdentity().copy(
                    keyData = keyData,
                    filePath = "/old/default_identity", // Old path
                )
            localIdentityDao.insert(identity)

            // When
            val result = repository.ensureIdentityFileExists(identity)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Should succeed and DB path should be updated
            assertTrue(result.isSuccess)
            assertEquals(canonicalFile.absolutePath, result.getOrNull())

            // Verify DB was updated
            val updated = localIdentityDao.getIdentity(TEST_IDENTITY_HASH)
            assertEquals(canonicalFile.absolutePath, updated?.filePath)
        }
}
