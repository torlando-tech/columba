package com.lxmf.messenger.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for IdentityKeyMigrator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class IdentityKeyMigratorTest {
    private lateinit var context: Context
    private lateinit var identityDao: LocalIdentityDao
    private lateinit var encryptor: IdentityKeyEncryptor
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor
    private lateinit var migrator: IdentityKeyMigrator

    companion object {
        private val TEST_KEY_DATA = ByteArray(64) { it.toByte() }
        private val ENCRYPTED_KEY_DATA = ByteArray(100) { (it + 50).toByte() }
        private const val TEST_IDENTITY_HASH = "abc123def456"
    }

    @Before
    fun setUp() {
        @Suppress("NoRelaxedMocks") // Android Context
        context = mockk(relaxed = true)
        identityDao = mockk()
        encryptor = mockk()
        @Suppress("NoRelaxedMocks") // Android SharedPreferences
        sharedPreferences = mockk(relaxed = true)
        @Suppress("NoRelaxedMocks") // Android SharedPreferences.Editor
        prefsEditor = mockk(relaxed = true)

        // Setup SharedPreferences mock
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns prefsEditor
        every { prefsEditor.putBoolean(any(), any()) } returns prefsEditor
        every { prefsEditor.putLong(any(), any()) } returns prefsEditor
        every { prefsEditor.putInt(any(), any()) } returns prefsEditor
        every { prefsEditor.clear() } returns prefsEditor

        // Setup Context mock
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"), "test_files")

        // Setup Encryptor mock
        every { encryptor.encryptWithDeviceKey(any()) } returns ENCRYPTED_KEY_DATA
        every { encryptor.secureWipe(any()) } returns Unit

        // Setup DAO mutation stubs (non-relaxed mock requires explicit stubs)
        coEvery { identityDao.updateEncryptedKeyData(any(), any(), any()) } returns Unit
        coEvery { identityDao.clearUnencryptedKeyData(any()) } returns Unit

        migrator = IdentityKeyMigrator(context, identityDao, encryptor)
    }

    // ==================== Migration Completion Tests ====================

    @Test
    fun `isMigrationCompleted returns false when not completed`() {
        every { sharedPreferences.getBoolean("migration_completed", false) } returns false

        assertFalse(migrator.isMigrationCompleted())
    }

    @Test
    fun `isMigrationCompleted returns true when completed`() {
        every { sharedPreferences.getBoolean("migration_completed", false) } returns true

        assertTrue(migrator.isMigrationCompleted())
    }

    // ==================== Migration Process Tests ====================

    @Test
    fun `migrateUnencryptedIdentities returns success with zero count when no identities need migration`() =
        runTest {
            coEvery { identityDao.getIdentitiesNeedingEncryption() } returns emptyList()
            coEvery { identityDao.hasUnencryptedIdentities() } returns false

            val result = migrator.migrateUnencryptedIdentities()

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrThrow().successCount)
            assertEquals(0, result.getOrThrow().failureCount)
        }

    @Test
    fun `migrateUnencryptedIdentities encrypts identity with keyData`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = TEST_KEY_DATA,
                    keyEncryptionVersion = 0,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentitiesNeedingEncryption() } returns listOf(identity)
            coEvery { identityDao.hasUnencryptedIdentities() } returns false

            val result = migrator.migrateUnencryptedIdentities()

            assertTrue(result.isSuccess)
            assertEquals(1, result.getOrThrow().successCount)
            assertEquals(0, result.getOrThrow().failureCount)

            // Verify encryption was called
            verify { encryptor.encryptWithDeviceKey(TEST_KEY_DATA) }

            // Verify database was updated
            coVerify {
                identityDao.updateEncryptedKeyData(
                    identityHash = TEST_IDENTITY_HASH,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    version = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                )
            }

            // Verify unencrypted data was cleared
            coVerify { identityDao.clearUnencryptedKeyData(TEST_IDENTITY_HASH) }
        }

    @Test
    fun `migrateUnencryptedIdentities skips identity with invalid key size`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = ByteArray(32), // Wrong size
                    keyEncryptionVersion = 0,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentitiesNeedingEncryption() } returns listOf(identity)
            coEvery { identityDao.hasUnencryptedIdentities() } returns true // Still has unencrypted

            val result = migrator.migrateUnencryptedIdentities()

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrThrow().successCount)
            assertEquals(1, result.getOrThrow().failureCount)
            assertTrue(result.getOrThrow().failedIdentityHashes.contains(TEST_IDENTITY_HASH))

            // Encryption should not have been called with wrong-sized key
            verify(exactly = 0) { encryptor.encryptWithDeviceKey(any()) }
        }

    @Test
    fun `migrateUnencryptedIdentities skips identity with no key data`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/nonexistent/path",
                    keyData = null,
                    keyEncryptionVersion = 0,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentitiesNeedingEncryption() } returns listOf(identity)
            coEvery { identityDao.hasUnencryptedIdentities() } returns true

            val result = migrator.migrateUnencryptedIdentities()

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrThrow().successCount)
            assertEquals(1, result.getOrThrow().failureCount)
        }

    @Test
    fun `migrateUnencryptedIdentities handles multiple identities`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity1 =
                LocalIdentityEntity(
                    identityHash = "hash1",
                    displayName = "Identity 1",
                    destinationHash = "dest1",
                    filePath = "/fake/path1",
                    keyData = TEST_KEY_DATA,
                    keyEncryptionVersion = 0,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            @Suppress("DEPRECATION")
            val identity2 =
                LocalIdentityEntity(
                    identityHash = "hash2",
                    displayName = "Identity 2",
                    destinationHash = "dest2",
                    filePath = "/fake/path2",
                    keyData = TEST_KEY_DATA,
                    keyEncryptionVersion = 0,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = false,
                )

            coEvery { identityDao.getIdentitiesNeedingEncryption() } returns listOf(identity1, identity2)
            coEvery { identityDao.hasUnencryptedIdentities() } returns false

            val result = migrator.migrateUnencryptedIdentities()

            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrThrow().successCount)
            assertEquals(0, result.getOrThrow().failureCount)
            assertTrue(result.getOrThrow().isFullySuccessful)
        }

    // ==================== Migration Stats Tests ====================

    @Test
    fun `getMigrationStats returns correct values`() {
        every { sharedPreferences.getBoolean("migration_completed", false) } returns true
        every { sharedPreferences.getLong("last_migration_attempt", 0) } returns 1234567890L
        every { sharedPreferences.getInt("migrated_count", 0) } returns 5

        val stats = migrator.getMigrationStats()

        assertTrue(stats.isCompleted)
        assertEquals(1234567890L, stats.lastAttempt)
        assertEquals(5, stats.totalMigrated)
    }

    // ==================== Reset Migration State Tests ====================

    @Test
    fun `resetMigrationState clears preferences`() {
        every { sharedPreferences.getBoolean("migration_completed", false) } returns false

        migrator.resetMigrationState()

        verify { prefsEditor.clear() }
        verify { prefsEditor.apply() }
        // After reset, migration should not be marked as completed
        assertFalse(migrator.isMigrationCompleted())
    }
}
