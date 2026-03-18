// NoVerifyOnlyTests: Repository is a thin delegation layer; verifying correct DAO calls IS the behavior
// NoRelaxedMocks: DAO interfaces have many methods; tests explicitly stub what they need
@file:Suppress("NoVerifyOnlyTests", "NoRelaxedMocks")

package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.model.EnrichedContact
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ContactRepository SOS emergency contact methods and EnrichedContact.isSosContact.
 *
 * Tests cover:
 * - getSosContacts: filtering enriched contacts by SOS tag
 * - getSosContactsFlow: reactive stream of SOS contacts
 * - toggleSosTag: adding/removing the "sos" tag via DAO interactions
 * - isSosContact: pure data class tag parsing
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactRepositorySosTest {
    private lateinit var contactDao: ContactDao
    private lateinit var localIdentityDao: LocalIdentityDao
    private lateinit var announceDao: AnnounceDao
    private val testDispatcher = StandardTestDispatcher()

    private val testIdentityHash = "identity123"
    private val testDestHash = "abc123"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        contactDao = mockk(relaxed = true)
        localIdentityDao = mockk(relaxed = true)
        announceDao = mockk(relaxed = true)

        // Default: active identity exists
        val identity = createTestIdentity()
        every { localIdentityDao.getActiveIdentity() } returns flowOf(identity)
        coEvery { localIdentityDao.getActiveIdentitySync() } returns identity
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(hash: String = testIdentityHash) =
        LocalIdentityEntity(
            identityHash = hash,
            displayName = "Test Identity",
            destinationHash = "dest_$hash",
            filePath = "/data/identity_$hash",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    private fun createContact(
        destinationHash: String = testDestHash,
        tags: String? = null,
        displayName: String = "Test",
    ): EnrichedContact =
        EnrichedContact(
            destinationHash = destinationHash,
            publicKey = ByteArray(64) { it.toByte() },
            displayName = displayName,
            customNickname = null,
            announceName = null,
            lastSeenTimestamp = null,
            hops = null,
            isOnline = false,
            hasConversation = false,
            unreadCount = 0,
            lastMessageTimestamp = null,
            notes = null,
            tags = tags,
            addedTimestamp = System.currentTimeMillis(),
            addedVia = "MANUAL",
            isPinned = false,
        )

    private fun createContactEntity(
        destinationHash: String = testDestHash,
        tags: String? = null,
    ) = ContactEntity(
        destinationHash = destinationHash,
        identityHash = testIdentityHash,
        publicKey = ByteArray(64) { it.toByte() },
        customNickname = null,
        notes = null,
        tags = tags,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "MANUAL",
        lastInteractionTimestamp = 0,
        isPinned = false,
        status = ContactStatus.ACTIVE,
    )

    // ========== isSosContact Tests (pure data class, no mocking) ==========

    @Test
    fun `isSosContact returns true when tags contain sos`() {
        val contact = createContact(tags = """["sos"]""")
        assertTrue(contact.isSosContact)
    }

    @Test
    fun `isSosContact returns false when tags empty`() {
        val contact = createContact(tags = "")
        assertFalse(contact.isSosContact)
    }

    @Test
    fun `isSosContact returns false when tags null`() {
        val contact = createContact(tags = null)
        assertFalse(contact.isSosContact)
    }

    @Test
    fun `isSosContact returns false when tags contain other tags but not sos`() {
        val contact = createContact(tags = """["friend","family"]""")
        assertFalse(contact.isSosContact)
    }

    // ========== getSosContacts Tests ==========

    @Test
    fun `getSosContacts returns only contacts with sos tag`() =
        runTest {
            val repo = spyk(ContactRepository(contactDao, localIdentityDao, announceDao))
            every { repo.getEnrichedContacts() } returns
                flowOf(
                    listOf(
                        createContact("hash1", tags = """["sos"]""", displayName = "Alice"),
                        createContact("hash2", tags = null, displayName = "Bob"),
                        createContact("hash3", tags = """["sos","friend"]""", displayName = "Carol"),
                    ),
                )

            val result = repo.getSosContacts()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, result.size)
            assertEquals("hash1", result[0].destinationHash)
            assertEquals("hash3", result[1].destinationHash)
        }

    @Test
    fun `getSosContacts returns empty when no sos contacts`() =
        runTest {
            val repo = spyk(ContactRepository(contactDao, localIdentityDao, announceDao))
            every { repo.getEnrichedContacts() } returns
                flowOf(
                    listOf(
                        createContact("hash1", tags = null, displayName = "Alice"),
                        createContact("hash2", tags = """["friend"]""", displayName = "Bob"),
                    ),
                )

            val result = repo.getSosContacts()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(result.isEmpty())
        }

    // ========== getSosContactsFlow Tests ==========

    @Test
    fun `getSosContactsFlow emits filtered contacts`() =
        runTest {
            val repo = spyk(ContactRepository(contactDao, localIdentityDao, announceDao))
            every { repo.getEnrichedContacts() } returns
                flowOf(
                    listOf(
                        createContact("hash1", tags = """["sos"]""", displayName = "Alice"),
                        createContact("hash2", tags = null, displayName = "Bob"),
                        createContact("hash3", tags = """["sos","family"]""", displayName = "Carol"),
                    ),
                )

            val result = repo.getSosContactsFlow().first()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, result.size)
            assertEquals("hash1", result[0].destinationHash)
            assertEquals("hash3", result[1].destinationHash)
        }

    // ========== toggleSosTag Tests ==========

    @Test
    fun `toggleSosTag adds sos tag to contact with no tags`() =
        runTest {
            val contactEntity = createContactEntity(tags = null)
            coEvery { contactDao.getContact(testDestHash, testIdentityHash) } returns contactEntity
            coEvery { contactDao.updateTags(any(), any(), any()) } just Runs

            val repo = ContactRepository(contactDao, localIdentityDao, announceDao)
            repo.toggleSosTag(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                contactDao.updateTags(
                    testDestHash,
                    testIdentityHash,
                    match { it == """["sos"]""" },
                )
            }
        }

    @Test
    fun `toggleSosTag adds sos tag alongside existing tags`() =
        runTest {
            val contactEntity = createContactEntity(tags = """["friend"]""")
            coEvery { contactDao.getContact(testDestHash, testIdentityHash) } returns contactEntity
            coEvery { contactDao.updateTags(any(), any(), any()) } just Runs

            val repo = ContactRepository(contactDao, localIdentityDao, announceDao)
            repo.toggleSosTag(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                contactDao.updateTags(
                    testDestHash,
                    testIdentityHash,
                    match { it != null && it.contains("\"friend\"") && it.contains("\"sos\"") },
                )
            }
        }

    @Test
    fun `toggleSosTag removes sos tag when already present`() =
        runTest {
            val contactEntity = createContactEntity(tags = """["sos","friend"]""")
            coEvery { contactDao.getContact(testDestHash, testIdentityHash) } returns contactEntity
            coEvery { contactDao.updateTags(any(), any(), any()) } just Runs

            val repo = ContactRepository(contactDao, localIdentityDao, announceDao)
            repo.toggleSosTag(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                contactDao.updateTags(
                    testDestHash,
                    testIdentityHash,
                    match { it != null && it.contains("\"friend\"") && !it.contains("\"sos\"") },
                )
            }
        }

    @Test
    fun `toggleSosTag clears tags to null when sos was only tag`() =
        runTest {
            val contactEntity = createContactEntity(tags = """["sos"]""")
            coEvery { contactDao.getContact(testDestHash, testIdentityHash) } returns contactEntity
            coEvery { contactDao.updateTags(any(), any(), any()) } just Runs
            coEvery { contactDao.updateTags(any(), any(), isNull()) } just Runs

            val repo = ContactRepository(contactDao, localIdentityDao, announceDao)
            repo.toggleSosTag(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                contactDao.updateTags(testDestHash, testIdentityHash, isNull())
            }
        }

    @Test
    fun `toggleSosTag does nothing when no active identity`() =
        runTest {
            coEvery { localIdentityDao.getActiveIdentitySync() } returns null

            val repo = ContactRepository(contactDao, localIdentityDao, announceDao)
            repo.toggleSosTag(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { contactDao.getContact(any(), any()) }
            coVerify(exactly = 0) { contactDao.updateTags(any(), any(), any()) }
        }

    @Test
    fun `toggleSosTag does nothing when contact not found`() =
        runTest {
            coEvery { contactDao.getContact(testDestHash, testIdentityHash) } returns null

            val repo = ContactRepository(contactDao, localIdentityDao, announceDao)
            repo.toggleSosTag(testDestHash)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { contactDao.updateTags(any(), any(), any()) }
        }
}
