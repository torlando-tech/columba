// Abstract prevents JUnit from trying to instantiate this base class directly
@file:Suppress("UnnecessaryAbstractClass")

package com.lxmf.messenger.test

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.DraftDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for repository tests that need a real in-memory Room database.
 *
 * Why use real database tests instead of mocks?
 * ---------------------------------------------
 * Mock-based tests verify that "if the mock returns X, the code does Y" but don't
 * verify that the actual database queries work correctly. This leads to:
 *
 * 1. Tests that pass even when the actual logic breaks (false confidence)
 * 2. No coverage of Room query correctness (typos, SQL errors, missing indices)
 * 3. No coverage of foreign key constraints and cascade deletes
 * 4. No coverage of transaction atomicity
 *
 * Real database tests catch:
 * - Query bugs ("SELECT * FROM messages WHERE id = :id" with wrong column name)
 * - Transaction issues (partial writes, deadlocks)
 * - Constraint violations (foreign keys, unique constraints)
 * - Data transformation bugs (entity mapping)
 *
 * Usage:
 * ```kotlin
 * @RunWith(RobolectricTestRunner::class)
 * @Config(sdk = [34], application = Application::class)
 * class MyRepositoryTest : DatabaseTest() {
 *
 *     @Before
 *     fun setupTest() {
 *         // Additional setup if needed
 *         insertTestIdentity() // Required for FK constraints
 *     }
 *
 *     @Test
 *     fun myTest() = runTest {
 *         val dao = messageDao
 *         // Use real DAOs...
 *     }
 * }
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
abstract class DatabaseTest {
    protected lateinit var database: ColumbaDatabase
    protected lateinit var context: Context

    // Convenience accessors for common DAOs
    protected val announceDao: AnnounceDao get() = database.announceDao()
    protected val contactDao: ContactDao get() = database.contactDao()
    protected val messageDao: MessageDao get() = database.messageDao()
    protected val conversationDao: ConversationDao get() = database.conversationDao()
    protected val localIdentityDao: LocalIdentityDao get() = database.localIdentityDao()
    protected val peerIdentityDao: PeerIdentityDao get() = database.peerIdentityDao()
    protected val draftDao: DraftDao get() = database.draftDao()

    companion object {
        // Standard test values for identity-scoped operations
        const val TEST_IDENTITY_HASH = "test_identity_hash_12345678901234"
        const val TEST_DEST_HASH = "test_dest_hash_123456789012345678"
        const val TEST_PEER_HASH = "test_peer_hash_123456789012345678"
        const val TEST_PEER_HASH_2 = "test_peer_hash_2_1234567890123456"
    }

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDb() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    /**
     * Creates and inserts a test identity.
     * Call this before any operations that require an identity (FK constraints).
     *
     * @param identityHash The identity hash to use
     * @param displayName The display name
     * @param isActive Whether this identity should be marked as active
     * @return The inserted identity entity
     */
    protected suspend fun insertTestIdentity(
        identityHash: String = TEST_IDENTITY_HASH,
        displayName: String = "Test Identity",
        isActive: Boolean = true,
    ): LocalIdentityEntity {
        val identity =
            LocalIdentityEntity(
                identityHash = identityHash,
                displayName = displayName,
                destinationHash = TEST_DEST_HASH,
                filePath = "/data/identity_$identityHash",
                keyData = null,
                createdTimestamp = System.currentTimeMillis(),
                lastUsedTimestamp = System.currentTimeMillis(),
                isActive = isActive,
            )
        localIdentityDao.insert(identity)
        return identity
    }

    /**
     * Creates a test identity entity without inserting it.
     * Useful when you need the entity but don't want to insert yet.
     */
    protected fun createTestIdentity(
        identityHash: String = TEST_IDENTITY_HASH,
        displayName: String = "Test Identity",
        isActive: Boolean = true,
    ) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = displayName,
        destinationHash = TEST_DEST_HASH,
        filePath = "/data/identity_$identityHash",
        keyData = null,
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = isActive,
    )
}
