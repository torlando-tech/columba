package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ContactRepository, focusing on pending identity resolution methods.
 *
 * Tests cover:
 * - addPendingContact: hash-only contact creation with announce lookup
 * - updateContactWithIdentity: resolving pending contacts
 * - updateContactStatus: status transitions
 * - getContactsByStatus: querying by status
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactRepositoryTest {
    private lateinit var repository: ContactRepository
    private lateinit var mockContactDao: ContactDao
    private lateinit var mockLocalIdentityDao: LocalIdentityDao
    private lateinit var mockAnnounceDao: AnnounceDao
    private val testDispatcher = StandardTestDispatcher()

    private val testIdentityHash = "test_identity_hash_123"
    private val testDestHash = "0123456789abcdef0123456789abcdef"
    private val testPublicKey = ByteArray(64) { it.toByte() }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockContactDao = mockk(relaxed = true)
        mockLocalIdentityDao = mockk(relaxed = true)
        mockAnnounceDao = mockk(relaxed = true)

        // Default: active identity exists
        every { mockLocalIdentityDao.getActiveIdentity() } returns flowOf(createTestIdentity())
        coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns createTestIdentity()

        repository =
            ContactRepository(
                contactDao = mockContactDao,
                localIdentityDao = mockLocalIdentityDao,
                announceDao = mockAnnounceDao,
            )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(
        hash: String = testIdentityHash,
        isActive: Boolean = true,
    ) = LocalIdentityEntity(
        identityHash = hash,
        displayName = "Test Identity",
        destinationHash = "dest_$hash",
        filePath = "/data/identity_$hash",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = isActive,
    )

    private fun createTestAnnounce(
        destinationHash: String = testDestHash,
        publicKey: ByteArray = testPublicKey,
        peerName: String = "Test Peer",
    ) = AnnounceEntity(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = publicKey,
        appData = null,
        hops = 1,
        lastSeenTimestamp = System.currentTimeMillis(),
        nodeType = "PEER",
        receivingInterface = null,
    )

    private fun createTestContact(
        destinationHash: String = testDestHash,
        identityHash: String = testIdentityHash,
        publicKey: ByteArray? = testPublicKey,
        status: ContactStatus = ContactStatus.ACTIVE,
    ) = ContactEntity(
        destinationHash = destinationHash,
        identityHash = identityHash,
        publicKey = publicKey,
        customNickname = null,
        notes = null,
        tags = null,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "MANUAL",
        lastInteractionTimestamp = 0,
        isPinned = false,
        status = status,
    )

    // ========== addPendingContact Tests ==========

    @Test
    fun `addPendingContact - no active identity returns failure`() =
        runTest {
            // Given: No active identity
            coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns null

            // When
            val result = repository.addPendingContact(testDestHash, "Test")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun `addPendingContact - existing announce resolves immediately`() =
        runTest {
            // Given: Announce exists with public key
            val announce = createTestAnnounce()
            coEvery { mockAnnounceDao.getAnnounce(testDestHash) } returns announce
            coEvery { mockContactDao.insertContact(any()) } just Runs

            // When
            val result = repository.addPendingContact(testDestHash, "Test Nickname")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(ContactRepository.AddPendingResult.ResolvedImmediately, result.getOrNull())

            // Verify contact was inserted with ACTIVE status and public key
            val contactSlot = slot<ContactEntity>()
            coVerify { mockContactDao.insertContact(capture(contactSlot)) }

            val insertedContact = contactSlot.captured
            assertEquals(testDestHash, insertedContact.destinationHash)
            assertEquals(testIdentityHash, insertedContact.identityHash)
            assertEquals(testPublicKey, insertedContact.publicKey)
            assertEquals(ContactStatus.ACTIVE, insertedContact.status)
            assertEquals("Test Nickname", insertedContact.customNickname)
            assertEquals("MANUAL", insertedContact.addedVia)
        }

    @Test
    fun `addPendingContact - announce with empty public key adds as pending`() =
        runTest {
            // Given: Announce exists but with empty public key
            val announce = createTestAnnounce(publicKey = ByteArray(0))
            coEvery { mockAnnounceDao.getAnnounce(testDestHash) } returns announce
            coEvery { mockContactDao.insertContact(any()) } just Runs

            // When
            val result = repository.addPendingContact(testDestHash, "Test")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(ContactRepository.AddPendingResult.AddedAsPending, result.getOrNull())

            // Verify contact was inserted with PENDING_IDENTITY status and null public key
            val contactSlot = slot<ContactEntity>()
            coVerify { mockContactDao.insertContact(capture(contactSlot)) }

            val insertedContact = contactSlot.captured
            assertNull(insertedContact.publicKey)
            assertEquals(ContactStatus.PENDING_IDENTITY, insertedContact.status)
            assertEquals("MANUAL_PENDING", insertedContact.addedVia)
        }

    @Test
    fun `addPendingContact - no announce adds as pending`() =
        runTest {
            // Given: No existing announce
            coEvery { mockAnnounceDao.getAnnounce(testDestHash) } returns null
            coEvery { mockContactDao.insertContact(any()) } just Runs

            // When
            val result = repository.addPendingContact(testDestHash, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            assertEquals(ContactRepository.AddPendingResult.AddedAsPending, result.getOrNull())

            // Verify contact was inserted correctly
            val contactSlot = slot<ContactEntity>()
            coVerify { mockContactDao.insertContact(capture(contactSlot)) }

            val insertedContact = contactSlot.captured
            assertEquals(testDestHash, insertedContact.destinationHash)
            assertNull(insertedContact.publicKey)
            assertNull(insertedContact.customNickname)
            assertEquals(ContactStatus.PENDING_IDENTITY, insertedContact.status)
        }

    @Test
    fun `addPendingContact - database error returns failure`() =
        runTest {
            // Given: Database throws exception
            coEvery { mockAnnounceDao.getAnnounce(testDestHash) } returns null
            coEvery { mockContactDao.insertContact(any()) } throws RuntimeException("DB error")

            // When
            val result = repository.addPendingContact(testDestHash, "Test")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertEquals("DB error", result.exceptionOrNull()?.message)
        }

    // ========== updateContactWithIdentity Tests ==========

    @Test
    fun `updateContactWithIdentity - no active identity returns failure`() =
        runTest {
            // Given: No active identity
            coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns null

            // When
            val result = repository.updateContactWithIdentity(testDestHash, testPublicKey)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun `updateContactWithIdentity - success updates dao`() =
        runTest {
            // Given
            coEvery {
                mockContactDao.updateContactIdentity(any(), any(), any(), any())
            } just Runs

            // When
            val result = repository.updateContactWithIdentity(testDestHash, testPublicKey)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify {
                mockContactDao.updateContactIdentity(
                    destinationHash = testDestHash,
                    identityHash = testIdentityHash,
                    publicKey = testPublicKey,
                    status = ContactStatus.ACTIVE.name,
                )
            }
        }

    @Test
    fun `updateContactWithIdentity - database error returns failure`() =
        runTest {
            // Given
            coEvery {
                mockContactDao.updateContactIdentity(any(), any(), any(), any())
            } throws RuntimeException("Update failed")

            // When
            val result = repository.updateContactWithIdentity(testDestHash, testPublicKey)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertEquals("Update failed", result.exceptionOrNull()?.message)
        }

    // ========== updateContactStatus Tests ==========

    @Test
    fun `updateContactStatus - no active identity returns failure`() =
        runTest {
            // Given: No active identity
            coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns null

            // When
            val result = repository.updateContactStatus(testDestHash, ContactStatus.UNRESOLVED)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun `updateContactStatus - success updates to UNRESOLVED`() =
        runTest {
            // Given
            coEvery { mockContactDao.updateContactStatus(any(), any(), any()) } just Runs

            // When
            val result = repository.updateContactStatus(testDestHash, ContactStatus.UNRESOLVED)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify {
                mockContactDao.updateContactStatus(
                    destinationHash = testDestHash,
                    identityHash = testIdentityHash,
                    status = ContactStatus.UNRESOLVED.name,
                )
            }
        }

    @Test
    fun `updateContactStatus - success updates to PENDING_IDENTITY`() =
        runTest {
            // Given
            coEvery { mockContactDao.updateContactStatus(any(), any(), any()) } just Runs

            // When
            val result = repository.updateContactStatus(testDestHash, ContactStatus.PENDING_IDENTITY)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isSuccess)
            coVerify {
                mockContactDao.updateContactStatus(
                    destinationHash = testDestHash,
                    identityHash = testIdentityHash,
                    status = ContactStatus.PENDING_IDENTITY.name,
                )
            }
        }

    @Test
    fun `updateContactStatus - database error returns failure`() =
        runTest {
            // Given
            coEvery {
                mockContactDao.updateContactStatus(any(), any(), any())
            } throws RuntimeException("Status update failed")

            // When
            val result = repository.updateContactStatus(testDestHash, ContactStatus.UNRESOLVED)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isFailure)
            assertEquals("Status update failed", result.exceptionOrNull()?.message)
        }

    // ========== getContactsByStatus Tests ==========

    @Test
    fun `getContactsByStatus - returns contacts matching statuses`() =
        runTest {
            // Given
            val pendingContact =
                createTestContact(
                    destinationHash = "pending_hash",
                    status = ContactStatus.PENDING_IDENTITY,
                )
            val unresolvedContact =
                createTestContact(
                    destinationHash = "unresolved_hash",
                    status = ContactStatus.UNRESOLVED,
                )
            coEvery {
                mockContactDao.getContactsByStatus(listOf("PENDING_IDENTITY", "UNRESOLVED"))
            } returns listOf(pendingContact, unresolvedContact)

            // When
            val result =
                repository.getContactsByStatus(
                    listOf(ContactStatus.PENDING_IDENTITY, ContactStatus.UNRESOLVED),
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertEquals(2, result.size)
            assertEquals("pending_hash", result[0].destinationHash)
            assertEquals("unresolved_hash", result[1].destinationHash)
        }

    @Test
    fun `getContactsByStatus - returns empty list when no matches`() =
        runTest {
            // Given
            coEvery { mockContactDao.getContactsByStatus(any()) } returns emptyList()

            // When
            val result = repository.getContactsByStatus(listOf(ContactStatus.PENDING_IDENTITY))
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isEmpty())
        }

    // ========== getContactsByStatusForActiveIdentity Tests ==========

    @Test
    fun `getContactsByStatusForActiveIdentity - no active identity returns empty`() =
        runTest {
            // Given
            coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns null

            // When
            val result =
                repository.getContactsByStatusForActiveIdentity(
                    listOf(ContactStatus.PENDING_IDENTITY),
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getContactsByStatusForActiveIdentity - returns contacts for active identity`() =
        runTest {
            // Given
            val pendingContact = createTestContact(status = ContactStatus.PENDING_IDENTITY)
            coEvery {
                mockContactDao.getContactsByStatusForIdentity(testIdentityHash, listOf("PENDING_IDENTITY"))
            } returns listOf(pendingContact)

            // When
            val result =
                repository.getContactsByStatusForActiveIdentity(
                    listOf(ContactStatus.PENDING_IDENTITY),
                )
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertEquals(1, result.size)
            assertEquals(ContactStatus.PENDING_IDENTITY, result[0].status)
        }

    // ========== getContact Tests ==========

    @Test
    fun `getContact - no active identity returns null`() =
        runTest {
            // Given
            coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns null

            // When
            val result = repository.getContact(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertNull(result)
        }

    @Test
    fun `getContact - returns contact from dao`() =
        runTest {
            // Given
            val contact = createTestContact()
            coEvery {
                mockContactDao.getContact(testDestHash, testIdentityHash)
            } returns contact

            // When
            val result = repository.getContact(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertEquals(contact, result)
        }

    @Test
    fun `getContact - returns null when not found`() =
        runTest {
            // Given
            coEvery {
                mockContactDao.getContact(testDestHash, testIdentityHash)
            } returns null

            // When
            val result = repository.getContact(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then
            assertNull(result)
        }
}
