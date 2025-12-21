package com.lxmf.messenger.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Instrumented tests for LocalIdentityDao.
 * Tests all CRUD operations, transactions, and Flow emissions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalIdentityDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var dao: LocalIdentityDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use in-memory database for testing
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.localIdentityDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(
        hash: String = "test_hash_${System.currentTimeMillis()}",
        displayName: String = "Test Identity",
        destinationHash: String = "dest_hash_${System.currentTimeMillis()}",
        isActive: Boolean = false,
        lastUsedTimestamp: Long = System.currentTimeMillis(),
    ) = LocalIdentityEntity(
        identityHash = hash,
        displayName = displayName,
        destinationHash = destinationHash,
        filePath = "/data/identity_$hash",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = lastUsedTimestamp,
        isActive = isActive,
    )

    // ========== Insert and Retrieve Tests ==========

    @Test
    fun insertIdentity_andRetrieve_returnsCorrectIdentity() =
        runTest {
            // Given
            val identity = createTestIdentity(hash = "abc123")

            // When
            dao.insert(identity)
            val retrieved = dao.getIdentity("abc123")

            // Then
            assertNotNull(retrieved)
            assertEquals("abc123", retrieved?.identityHash)
            assertEquals("Test Identity", retrieved?.displayName)
            assertFalse(retrieved?.isActive ?: true)
        }

    @Test
    fun insertIdentity_withConflict_replacesExisting() =
        runTest {
            // Given
            val identity1 = createTestIdentity(hash = "abc123", displayName = "Original")
            val identity2 = createTestIdentity(hash = "abc123", displayName = "Updated")

            // When
            dao.insert(identity1)
            dao.insert(identity2)
            val retrieved = dao.getIdentity("abc123")

            // Then
            assertEquals("Updated", retrieved?.displayName)
        }

    @Test
    fun getIdentity_whenNotExists_returnsNull() =
        runTest {
            // When
            val retrieved = dao.getIdentity("nonexistent")

            // Then
            assertNull(retrieved)
        }

    // ========== Get All Identities Tests ==========

    @Test
    fun getAllIdentities_emitsEmptyList_whenNoIdentities() =
        runTest {
            // When/Then
            dao.getAllIdentities().test {
                val identities = awaitItem()
                assertTrue(identities.isEmpty())
            }
        }

    @Test
    fun getAllIdentities_emitsAllIdentities_orderedByLastUsed() =
        runTest {
            // Given
            val identity1 = createTestIdentity(hash = "id1", lastUsedTimestamp = 1000L)
            val identity2 = createTestIdentity(hash = "id2", lastUsedTimestamp = 3000L)
            val identity3 = createTestIdentity(hash = "id3", lastUsedTimestamp = 2000L)

            dao.insert(identity1)
            dao.insert(identity2)
            dao.insert(identity3)

            // When/Then
            dao.getAllIdentities().test {
                val identities = awaitItem()
                assertEquals(3, identities.size)
                // Should be ordered by lastUsedTimestamp DESC
                assertEquals("id2", identities[0].identityHash) // 3000L
                assertEquals("id3", identities[1].identityHash) // 2000L
                assertEquals("id1", identities[2].identityHash) // 1000L
            }
        }

    @Test
    fun getAllIdentities_emitsUpdates_whenIdentityInserted() =
        runTest {
            // Given
            dao.getAllIdentities().test {
                // Initially empty
                assertEquals(0, awaitItem().size)

                // When
                dao.insert(createTestIdentity(hash = "new_id"))

                // Then
                val updated = awaitItem()
                assertEquals(1, updated.size)
                assertEquals("new_id", updated[0].identityHash)
            }
        }

    // ========== Active Identity Tests ==========

    @Test
    fun getActiveIdentity_returnsNull_whenNoActiveIdentity() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = false))
            dao.insert(createTestIdentity(hash = "id2", isActive = false))

            // When/Then
            dao.getActiveIdentity().test {
                assertNull(awaitItem())
            }
        }

    @Test
    fun getActiveIdentity_returnsActiveIdentity() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = false))
            dao.insert(createTestIdentity(hash = "id2", isActive = true))
            dao.insert(createTestIdentity(hash = "id3", isActive = false))

            // When/Then
            dao.getActiveIdentity().test {
                val active = awaitItem()
                assertNotNull(active)
                assertEquals("id2", active?.identityHash)
                assertTrue(active?.isActive ?: false)
            }
        }

    @Test
    fun getActiveIdentitySync_returnsActiveIdentity() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = false))
            dao.insert(createTestIdentity(hash = "id2", isActive = true))

            // When
            val active = dao.getActiveIdentitySync()

            // Then
            assertNotNull(active)
            assertEquals("id2", active?.identityHash)
        }

    @Test
    fun getActiveIdentitySync_returnsNull_whenNoActiveIdentity() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = false))

            // When
            val active = dao.getActiveIdentitySync()

            // Then
            assertNull(active)
        }

    // ========== Set Active Tests (Transaction) ==========

    @Test
    fun setActive_deactivatesAllOthers_andActivatesSpecified() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = true))
            dao.insert(createTestIdentity(hash = "id2", isActive = false))
            dao.insert(createTestIdentity(hash = "id3", isActive = false))

            // When
            dao.setActive("id2")

            // Then
            val identities = dao.getAllIdentities().test { awaitItem() }
            val id1 = dao.getIdentity("id1")
            val id2 = dao.getIdentity("id2")
            val id3 = dao.getIdentity("id3")

            assertFalse(id1?.isActive ?: true)
            assertTrue(id2?.isActive ?: false)
            assertFalse(id3?.isActive ?: true)
        }

    @Test
    fun setActive_updatesLastUsedTimestamp() =
        runTest {
            // Given
            val identity = createTestIdentity(hash = "id1", lastUsedTimestamp = 1000L)
            dao.insert(identity)

            // When
            val beforeTimestamp = System.currentTimeMillis()
            dao.setActive("id1")
            val afterTimestamp = System.currentTimeMillis()

            // Then
            val updated = dao.getIdentity("id1")
            assertNotNull(updated)
            assertTrue(updated!!.lastUsedTimestamp >= beforeTimestamp)
            assertTrue(updated.lastUsedTimestamp <= afterTimestamp)
        }

    @Test
    fun setActive_emitsUpdate_toActiveIdentityFlow() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = true))
            dao.insert(createTestIdentity(hash = "id2", isActive = false))

            // When/Then
            dao.getActiveIdentity().test {
                // Initially id1
                assertEquals("id1", awaitItem()?.identityHash)

                // Switch to id2
                dao.setActive("id2")
                assertEquals("id2", awaitItem()?.identityHash)
            }
        }

    // ========== Delete Tests ==========

    @Test
    fun delete_removesIdentity() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1"))

            // When
            dao.delete("id1")

            // Then
            assertNull(dao.getIdentity("id1"))
        }

    @Test
    fun delete_emitsUpdate_toAllIdentitiesFlow() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1"))

            // When/Then
            dao.getAllIdentities().test {
                assertEquals(1, awaitItem().size)

                dao.delete("id1")

                assertEquals(0, awaitItem().size)
            }
        }

    @Test
    fun delete_nonexistentIdentity_doesNotThrow() =
        runTest {
            // When/Then - should not throw
            dao.delete("nonexistent")
        }

    // ========== Update Tests ==========

    @Test
    fun updateDisplayName_changesName() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", displayName = "Original"))

            // When
            dao.updateDisplayName("id1", "Updated")

            // Then
            assertEquals("Updated", dao.getIdentity("id1")?.displayName)
        }

    @Test
    fun updateLastUsedTimestamp_changesTimestamp() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", lastUsedTimestamp = 1000L))

            // When
            dao.updateLastUsedTimestamp("id1", 5000L)

            // Then
            assertEquals(5000L, dao.getIdentity("id1")?.lastUsedTimestamp)
        }

    // ========== Count and Existence Tests ==========

    @Test
    fun getIdentityCount_returnsCorrectCount() =
        runTest {
            // Given - empty
            assertEquals(0, dao.getIdentityCount())

            // When
            dao.insert(createTestIdentity(hash = "id1"))
            dao.insert(createTestIdentity(hash = "id2"))
            dao.insert(createTestIdentity(hash = "id3"))

            // Then
            assertEquals(3, dao.getIdentityCount())
        }

    @Test
    fun identityExists_returnsTrueWhenExists() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1"))

            // When/Then
            assertTrue(dao.identityExists("id1"))
        }

    @Test
    fun identityExists_returnsFalseWhenNotExists() =
        runTest {
            // When/Then
            assertFalse(dao.identityExists("nonexistent"))
        }

    // ========== Edge Cases ==========

    @Test
    fun deactivateAll_setsAllIdentitiesToInactive() =
        runTest {
            // Given
            dao.insert(createTestIdentity(hash = "id1", isActive = true))
            dao.insert(createTestIdentity(hash = "id2", isActive = true))
            dao.insert(createTestIdentity(hash = "id3", isActive = false))

            // When
            dao.deactivateAll()

            // Then
            dao.getAllIdentities().test {
                val identities = awaitItem()
                assertTrue(identities.all { !it.isActive })
            }
        }

    @Test
    fun multipleActiveIdentities_setActiveEnsuresOnlyOne() =
        runTest {
            // Given - manually create inconsistent state
            dao.insert(createTestIdentity(hash = "id1", isActive = true))
            dao.insert(createTestIdentity(hash = "id2", isActive = true))
            dao.insert(createTestIdentity(hash = "id3", isActive = true))

            // When
            dao.setActive("id2")

            // Then
            dao.getAllIdentities().test {
                val identities = awaitItem()
                val activeCount = identities.count { it.isActive }
                assertEquals(1, activeCount)
            }

            val active = dao.getActiveIdentitySync()
            assertEquals("id2", active?.identityHash)
        }
}
