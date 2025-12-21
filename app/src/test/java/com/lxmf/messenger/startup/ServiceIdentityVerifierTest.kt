package com.lxmf.messenger.startup

import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.model.Identity
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceIdentityVerifier.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceIdentityVerifierTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var identityRepository: IdentityRepository
    private lateinit var verifier: ServiceIdentityVerifier

    // Test identity hash as hex string (32 bytes = 64 hex chars)
    private val testHashHex = "0123456789abcdef0123456789abcdef"
    private val testHashBytes = testHashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val differentHashHex = "fedcba9876543210fedcba9876543210"
    private val differentHashBytes = differentHashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val testDbIdentity =
        LocalIdentityEntity(
            identityHash = testHashHex,
            displayName = "Test User",
            destinationHash = "dest_hash",
            filePath = "/data/identity",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        identityRepository = mockk(relaxed = true)
        verifier = ServiceIdentityVerifier(identityRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Matching Identity Tests ==========

    @Test
    fun `verify returns isMatch true when identities match`() =
        runTest {
            // Arrange
            val serviceIdentity =
                Identity(
                    hash = testHashBytes,
                    publicKey = ByteArray(64),
                    privateKey = null,
                )
            coEvery { identityRepository.getActiveIdentitySync() } returns testDbIdentity

            // Act
            val result = verifier.verify(serviceIdentity)

            // Assert
            assertTrue(result.isMatch)
            assertEquals(testHashHex, result.serviceIdentityHash)
            assertEquals(testHashHex, result.dbIdentityHash)
        }

    // ========== Mismatched Identity Tests ==========

    @Test
    fun `verify returns isMatch false when identities do not match`() =
        runTest {
            // Arrange
            val serviceIdentity =
                Identity(
                    hash = differentHashBytes,
                    publicKey = ByteArray(64),
                    privateKey = null,
                )
            coEvery { identityRepository.getActiveIdentitySync() } returns testDbIdentity

            // Act
            val result = verifier.verify(serviceIdentity)

            // Assert
            assertFalse(result.isMatch)
            assertEquals(differentHashHex, result.serviceIdentityHash)
            assertEquals(testHashHex, result.dbIdentityHash)
        }

    // ========== Null Identity Tests ==========

    @Test
    fun `verify returns isMatch true when service identity is null`() =
        runTest {
            // Arrange
            coEvery { identityRepository.getActiveIdentitySync() } returns testDbIdentity

            // Act
            val result = verifier.verify(null)

            // Assert
            assertTrue(result.isMatch)
            assertNull(result.serviceIdentityHash)
            assertEquals(testHashHex, result.dbIdentityHash)
        }

    @Test
    fun `verify returns isMatch true when db identity is null`() =
        runTest {
            // Arrange
            val serviceIdentity =
                Identity(
                    hash = testHashBytes,
                    publicKey = ByteArray(64),
                    privateKey = null,
                )
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            // Act
            val result = verifier.verify(serviceIdentity)

            // Assert
            assertTrue(result.isMatch)
            assertEquals(testHashHex, result.serviceIdentityHash)
            assertNull(result.dbIdentityHash)
        }

    @Test
    fun `verify returns isMatch true when both identities are null`() =
        runTest {
            // Arrange
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            // Act
            val result = verifier.verify(null)

            // Assert
            assertTrue(result.isMatch)
            assertNull(result.serviceIdentityHash)
            assertNull(result.dbIdentityHash)
        }

    // ========== Hash Conversion Tests ==========

    @Test
    fun `verify converts service identity hash to lowercase hex`() =
        runTest {
            // Arrange - use bytes that would produce uppercase letters
            val hashWithUppercase = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
            val serviceIdentity =
                Identity(
                    hash = hashWithUppercase,
                    publicKey = ByteArray(64),
                    privateKey = null,
                )
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            // Act
            val result = verifier.verify(serviceIdentity)

            // Assert
            assertEquals("abcdef", result.serviceIdentityHash)
        }

    // ========== Result Properties Tests ==========

    @Test
    fun `VerificationResult contains correct hashes on match`() =
        runTest {
            // Arrange
            val serviceIdentity =
                Identity(
                    hash = testHashBytes,
                    publicKey = ByteArray(64),
                    privateKey = null,
                )
            coEvery { identityRepository.getActiveIdentitySync() } returns testDbIdentity

            // Act
            val result = verifier.verify(serviceIdentity)

            // Assert
            assertEquals(testHashHex, result.serviceIdentityHash)
            assertEquals(testHashHex, result.dbIdentityHash)
        }

    @Test
    fun `VerificationResult contains correct hashes on mismatch`() =
        runTest {
            // Arrange
            val serviceIdentity =
                Identity(
                    hash = differentHashBytes,
                    publicKey = ByteArray(64),
                    privateKey = null,
                )
            coEvery { identityRepository.getActiveIdentitySync() } returns testDbIdentity

            // Act
            val result = verifier.verify(serviceIdentity)

            // Assert
            assertEquals(differentHashHex, result.serviceIdentityHash)
            assertEquals(testHashHex, result.dbIdentityHash)
        }
}
