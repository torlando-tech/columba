package com.lxmf.messenger.data.crypto

import android.content.Context
import android.os.Build
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for IdentityKeyProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class IdentityKeyProviderTest {
    private lateinit var context: Context
    private lateinit var identityDao: LocalIdentityDao
    private lateinit var encryptor: IdentityKeyEncryptor
    private lateinit var keyProvider: IdentityKeyProvider

    companion object {
        private val TEST_KEY_DATA = ByteArray(64) { it.toByte() }
        private val ENCRYPTED_KEY_DATA = ByteArray(100) { (it + 50).toByte() }
        private val PASSWORD_ENCRYPTED_DATA = ByteArray(150) { (it + 100).toByte() }
        private const val TEST_IDENTITY_HASH = "abc123def456"
        private val TEST_PASSWORD = "testPassword123!".toCharArray()
    }

    @Before
    fun setUp() {
        @Suppress("NoRelaxedMocks") // Android Context
        context = mockk(relaxed = true)
        identityDao = mockk()
        encryptor = mockk()

        // Setup Context mock
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "test_cache")
        cacheDir.mkdirs()
        every { context.cacheDir } returns cacheDir

        keyProvider = IdentityKeyProvider(context, identityDao, encryptor)
    }

    // ==================== Get Decrypted Key Data Tests ====================

    @Test
    fun `getDecryptedKeyData returns decrypted key for device-only encryption`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every { encryptor.decryptWithDeviceKey(ENCRYPTED_KEY_DATA) } returns TEST_KEY_DATA

            val result = keyProvider.getDecryptedKeyData(TEST_IDENTITY_HASH)

            assertTrue(result.isSuccess)
            assertArrayEquals(TEST_KEY_DATA, result.getOrThrow())
        }

    @Test
    fun `getDecryptedKeyData returns decrypted key for password encryption`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    passwordSalt = ByteArray(32),
                    passwordVerificationHash = ByteArray(32),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every { encryptor.decryptWithPassword(PASSWORD_ENCRYPTED_DATA, TEST_PASSWORD) } returns TEST_KEY_DATA

            val result = keyProvider.getDecryptedKeyData(TEST_IDENTITY_HASH, TEST_PASSWORD)

            assertTrue(result.isSuccess)
            assertArrayEquals(TEST_KEY_DATA, result.getOrThrow())
        }

    @Test
    fun `getDecryptedKeyData returns failure for unknown identity`() =
        runTest {
            coEvery { identityDao.getIdentity(any()) } returns null

            val result = keyProvider.getDecryptedKeyData("unknown_hash")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is KeyNotFoundException)
        }

    @Test
    fun `getDecryptedKeyData returns failure when password required but not provided`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            // No password provided
            val result = keyProvider.getDecryptedKeyData(TEST_IDENTITY_HASH, null)

            assertTrue(result.isFailure)
        }

    @Test
    fun `getDecryptedKeyData returns legacy unencrypted key`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = TEST_KEY_DATA,
                    encryptedKeyData = null,
                    keyEncryptionVersion = 0, // Unencrypted
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            val result = keyProvider.getDecryptedKeyData(TEST_IDENTITY_HASH)

            assertTrue(result.isSuccess)
            assertArrayEquals(TEST_KEY_DATA, result.getOrThrow())
        }

    // ==================== Requires Password Tests ====================

    @Test
    fun `requiresPassword returns true for password-protected identity`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            assertTrue(keyProvider.requiresPassword(TEST_IDENTITY_HASH))
        }

    @Test
    fun `requiresPassword returns false for device-only identity`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            assertFalse(keyProvider.requiresPassword(TEST_IDENTITY_HASH))
        }

    @Test
    fun `requiresPassword returns false for unknown identity`() =
        runTest {
            coEvery { identityDao.getIdentity(any()) } returns null

            assertFalse(keyProvider.requiresPassword("unknown_hash"))
        }

    // ==================== Verify Password Tests ====================

    @Test
    fun `verifyPassword returns true for correct password`() =
        runTest {
            val salt = ByteArray(32)
            val verificationHash = ByteArray(32)

            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    passwordSalt = salt,
                    passwordVerificationHash = verificationHash,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every { encryptor.verifyPassword(TEST_PASSWORD, salt, verificationHash) } returns true

            assertTrue(keyProvider.verifyPassword(TEST_IDENTITY_HASH, TEST_PASSWORD))
        }

    @Test
    fun `verifyPassword returns false for wrong password`() =
        runTest {
            val salt = ByteArray(32)
            val verificationHash = ByteArray(32)
            val wrongPassword = "wrongPassword!".toCharArray()

            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    passwordSalt = salt,
                    passwordVerificationHash = verificationHash,
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every { encryptor.verifyPassword(wrongPassword, salt, verificationHash) } returns false

            assertFalse(keyProvider.verifyPassword(TEST_IDENTITY_HASH, wrongPassword))
        }

    @Test
    fun `verifyPassword returns true for non-password-protected identity`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            // Non-password-protected identities should return true for any password
            assertTrue(keyProvider.verifyPassword(TEST_IDENTITY_HASH, TEST_PASSWORD))
        }

    // ==================== Enable Password Protection Tests ====================

    @Test
    fun `enablePasswordProtection updates database correctly`() =
        runTest {
            val newPasswordEncrypted = ByteArray(160)
            val newVerificationHash = ByteArray(32)

            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every { encryptor.addPasswordProtection(ENCRYPTED_KEY_DATA, TEST_PASSWORD) } returns newPasswordEncrypted
            every { encryptor.createPasswordVerificationHash(any(), any()) } returns newVerificationHash
            coEvery {
                identityDao.updatePasswordProtection(
                    identityHash = any(),
                    encryptedKeyData = any(),
                    version = any(),
                    passwordSalt = any(),
                    passwordVerificationHash = any(),
                )
            } returns Unit

            val result = keyProvider.enablePasswordProtection(TEST_IDENTITY_HASH, TEST_PASSWORD)

            assertTrue(result.isSuccess)
            coVerify {
                identityDao.updatePasswordProtection(
                    identityHash = TEST_IDENTITY_HASH,
                    encryptedKeyData = newPasswordEncrypted,
                    version = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    passwordSalt = any(),
                    passwordVerificationHash = newVerificationHash,
                )
            }
        }

    @Test
    fun `enablePasswordProtection fails for unknown identity`() =
        runTest {
            coEvery { identityDao.getIdentity(any()) } returns null

            val result = keyProvider.enablePasswordProtection("unknown_hash", TEST_PASSWORD)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is KeyNotFoundException)
        }

    @Test
    fun `enablePasswordProtection fails for already protected identity`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            val result = keyProvider.enablePasswordProtection(TEST_IDENTITY_HASH, TEST_PASSWORD)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    // ==================== Disable Password Protection Tests ====================

    @Test
    fun `disablePasswordProtection updates database correctly`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every { encryptor.removePasswordProtection(PASSWORD_ENCRYPTED_DATA, TEST_PASSWORD) } returns ENCRYPTED_KEY_DATA
            coEvery {
                identityDao.clearPasswordProtection(
                    identityHash = any(),
                    encryptedKeyData = any(),
                    version = any(),
                )
            } returns Unit

            val result = keyProvider.disablePasswordProtection(TEST_IDENTITY_HASH, TEST_PASSWORD)

            assertTrue(result.isSuccess)
            coVerify {
                identityDao.clearPasswordProtection(
                    identityHash = TEST_IDENTITY_HASH,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    version = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                )
            }
        }

    @Test
    fun `disablePasswordProtection fails for non-password-protected identity`() =
        runTest {
            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = ENCRYPTED_KEY_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity

            val result = keyProvider.disablePasswordProtection(TEST_IDENTITY_HASH, TEST_PASSWORD)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun `disablePasswordProtection fails with wrong password`() =
        runTest {
            val wrongPassword = "wrongPassword!".toCharArray()

            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every {
                encryptor.removePasswordProtection(PASSWORD_ENCRYPTED_DATA, wrongPassword)
            } throws WrongPasswordException("Wrong password")

            val result = keyProvider.disablePasswordProtection(TEST_IDENTITY_HASH, wrongPassword)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is WrongPasswordException)
        }

    // ==================== Change Password Tests ====================

    @Test
    fun `changePassword updates database correctly`() =
        runTest {
            val newPassword = "newPassword456!".toCharArray()
            val newEncryptedData = ByteArray(170)

            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every {
                encryptor.changePassword(PASSWORD_ENCRYPTED_DATA, TEST_PASSWORD, newPassword)
            } returns newEncryptedData
            every { encryptor.createPasswordVerificationHash(any(), any()) } returns ByteArray(32)
            coEvery {
                identityDao.updatePasswordProtection(
                    identityHash = any(),
                    encryptedKeyData = any(),
                    version = any(),
                    passwordSalt = any(),
                    passwordVerificationHash = any(),
                )
            } returns Unit

            val result = keyProvider.changePassword(TEST_IDENTITY_HASH, TEST_PASSWORD, newPassword)

            assertTrue(result.isSuccess)
            coVerify {
                identityDao.updatePasswordProtection(
                    identityHash = TEST_IDENTITY_HASH,
                    encryptedKeyData = newEncryptedData,
                    version = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    passwordSalt = any(),
                    passwordVerificationHash = any(),
                )
            }
        }

    @Test
    fun `changePassword fails with wrong old password`() =
        runTest {
            val wrongOldPassword = "wrongOld!".toCharArray()
            val newPassword = "newPassword456!".toCharArray()

            @Suppress("DEPRECATION")
            val identity =
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Test Identity",
                    destinationHash = "dest123",
                    filePath = "/fake/path",
                    keyData = null,
                    encryptedKeyData = PASSWORD_ENCRYPTED_DATA,
                    keyEncryptionVersion = IdentityKeyEncryptor.VERSION_DEVICE_AND_PASSWORD.toInt(),
                    createdTimestamp = System.currentTimeMillis(),
                    lastUsedTimestamp = System.currentTimeMillis(),
                    isActive = true,
                )

            coEvery { identityDao.getIdentity(TEST_IDENTITY_HASH) } returns identity
            every {
                encryptor.changePassword(PASSWORD_ENCRYPTED_DATA, wrongOldPassword, newPassword)
            } throws WrongPasswordException("Wrong password")

            val result = keyProvider.changePassword(TEST_IDENTITY_HASH, wrongOldPassword, newPassword)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is WrongPasswordException)
        }
}
