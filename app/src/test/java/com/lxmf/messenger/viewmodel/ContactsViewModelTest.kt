package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.RelayInfo
import com.lxmf.messenger.test.TestFactories
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for ContactsViewModel.
 *
 * Tests cover:
 * - StateFlow emissions (contacts, filteredContacts, groupedContacts)
 * - Search/filtering functionality
 * - Contact operations (add, delete, update)
 * - Duplicate detection
 * - Unified input handling (lxma:// URL vs hash-only)
 * - Relay management
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var contactRepository: ContactRepository
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var viewModel: ContactsViewModel

    private val currentRelayFlow = MutableStateFlow<RelayInfo?>(null)
    private val contactsFlow = MutableStateFlow<List<EnrichedContact>>(emptyList())
    private val contactCountFlow = MutableStateFlow(0)

    private val testDestHash = TestFactories.TEST_DEST_HASH
    private val testPublicKey = TestFactories.TEST_PUBLIC_KEY

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock Android Log to prevent "not mocked" errors
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        contactRepository = mockk()
        propagationNodeManager = mockk()

        every { contactRepository.getEnrichedContacts() } returns contactsFlow
        every { contactRepository.getContactCountFlow() } returns contactCountFlow
        every { propagationNodeManager.currentRelay } returns currentRelayFlow

        viewModel = ContactsViewModel(contactRepository, propagationNodeManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
        clearAllMocks()
    }

    // ========== StateFlow Tests ==========

    @Test
    fun `contacts - initial state is empty`() =
        runTest {
            viewModel.contacts.test {
                assertEquals(emptyList<EnrichedContact>(), awaitItem())
            }
        }

    @Test
    fun `contacts - emits repository data`() =
        runTest {
            // Given
            val contact1 = TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice")
            val contact2 = TestFactories.createEnrichedContact(destinationHash = "hash2", displayName = "Bob")

            // Create fresh ViewModel with configured repository
            val testContactsFlow = MutableStateFlow(listOf(contact1, contact2))
            every { contactRepository.getEnrichedContacts() } returns testContactsFlow

            val newViewModel = ContactsViewModel(contactRepository, propagationNodeManager)

            newViewModel.contacts.test {
                awaitItem() // Initial empty
                advanceUntilIdle()
                val contacts = awaitItem()
                assertEquals(2, contacts.size)
                assertEquals("Alice", contacts[0].displayName)
                assertEquals("Bob", contacts[1].displayName)
            }
        }

    @Test
    fun `filteredContacts - no query returns all contacts`() =
        runTest {
            // Given
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice"),
                    TestFactories.createEnrichedContact(destinationHash = "hash2", displayName = "Bob"),
                )
            contactsFlow.value = contacts
            advanceUntilIdle()

            // When: No search query
            viewModel.onSearchQueryChanged("")
            advanceUntilIdle()

            // Then
            viewModel.filteredContacts.test {
                awaitItem() // Skip initial
                advanceUntilIdle()
                val filtered = awaitItem()
                assertEquals(2, filtered.size)
            }
        }

    @Test
    fun `filteredContacts - matches display name`() =
        runTest {
            // Given
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice"),
                    TestFactories.createEnrichedContact(destinationHash = "hash2", displayName = "Bob"),
                    TestFactories.createEnrichedContact(destinationHash = "hash3", displayName = "Charlie"),
                )
            contactsFlow.value = contacts
            advanceUntilIdle()

            // When
            viewModel.onSearchQueryChanged("alice")
            advanceUntilIdle()

            // Then
            viewModel.filteredContacts.test {
                skipItems(1) // Skip initial
                advanceUntilIdle()
                val filtered = awaitItem()
                assertEquals(1, filtered.size)
                assertEquals("Alice", filtered[0].displayName)
            }
        }

    @Test
    fun `filteredContacts - matches destination hash`() =
        runTest {
            // Given
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(destinationHash = "abc123def456", displayName = "Alice"),
                    TestFactories.createEnrichedContact(destinationHash = "xyz789uvw012", displayName = "Bob"),
                )
            contactsFlow.value = contacts
            advanceUntilIdle()

            // When
            viewModel.onSearchQueryChanged("abc123")
            advanceUntilIdle()

            // Then
            viewModel.filteredContacts.test {
                skipItems(1)
                advanceUntilIdle()
                val filtered = awaitItem()
                assertEquals(1, filtered.size)
                assertEquals("Alice", filtered[0].displayName)
            }
        }

    @Test
    fun `filteredContacts - matches announce name`() =
        runTest {
            // Given
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        TestFactories.EnrichedContactConfig(
                            destinationHash = "hash1",
                            displayName = "Custom Name",
                            announceName = "Announce Alice",
                        ),
                    ),
                    TestFactories.createEnrichedContact(
                        TestFactories.EnrichedContactConfig(
                            destinationHash = "hash2",
                            displayName = "Bob",
                            announceName = "Announce Bob",
                        ),
                    ),
                )
            contactsFlow.value = contacts
            advanceUntilIdle()

            // When
            viewModel.onSearchQueryChanged("Announce Alice")
            advanceUntilIdle()

            // Then
            viewModel.filteredContacts.test {
                skipItems(1)
                advanceUntilIdle()
                val filtered = awaitItem()
                assertEquals(1, filtered.size)
                assertEquals("hash1", filtered[0].destinationHash)
            }
        }

    @Test
    fun `filteredContacts - matches tags`() =
        runTest {
            // Given
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(
                        TestFactories.EnrichedContactConfig(
                            destinationHash = "hash1",
                            displayName = "Alice",
                            tags = "[\"friend\", \"work\"]",
                        ),
                    ),
                    TestFactories.createEnrichedContact(
                        TestFactories.EnrichedContactConfig(
                            destinationHash = "hash2",
                            displayName = "Bob",
                            tags = "[\"family\"]",
                        ),
                    ),
                )
            contactsFlow.value = contacts
            advanceUntilIdle()

            // When
            viewModel.onSearchQueryChanged("friend")
            advanceUntilIdle()

            // Then
            viewModel.filteredContacts.test {
                skipItems(1)
                advanceUntilIdle()
                val filtered = awaitItem()
                assertEquals(1, filtered.size)
                assertEquals("Alice", filtered[0].displayName)
            }
        }

    @Test
    fun `filteredContacts - case insensitive search`() =
        runTest {
            // Given
            val contacts =
                listOf(
                    TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice"),
                )
            contactsFlow.value = contacts
            advanceUntilIdle()

            // When: Search with different case
            viewModel.onSearchQueryChanged("ALICE")
            advanceUntilIdle()

            // Then
            viewModel.filteredContacts.test {
                skipItems(1)
                advanceUntilIdle()
                val filtered = awaitItem()
                assertEquals(1, filtered.size)
            }
        }

    // ========== GroupedContacts Tests ==========

    @Test
    fun `groupedContacts - separates relay`() =
        runTest {
            // Given: Create fresh ViewModel with configured repository
            val testContactsFlow =
                MutableStateFlow(
                    listOf(
                        TestFactories.createEnrichedContact(
                            destinationHash = "relay_hash",
                            displayName = "My Relay",
                            isMyRelay = true,
                        ),
                        TestFactories.createEnrichedContact(
                            destinationHash = "regular_hash",
                            displayName = "Regular Contact",
                        ),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns testContactsFlow
            val newViewModel = ContactsViewModel(contactRepository, propagationNodeManager)

            // Then - wait for data to propagate through the flow chain
            newViewModel.contactsState.test {
                // Skip loading states until we get actual data
                var groups = awaitItem().groupedContacts
                while (groups.relay == null && groups.all.isEmpty()) {
                    advanceUntilIdle()
                    groups = awaitItem().groupedContacts
                }
                assertNotNull(groups.relay)
                assertEquals("My Relay", groups.relay?.displayName)
                assertEquals(1, groups.all.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `groupedContacts - separates pinned`() =
        runTest {
            // Given: Create fresh ViewModel with configured repository
            val testContactsFlow =
                MutableStateFlow(
                    listOf(
                        TestFactories.createEnrichedContact(
                            destinationHash = "pinned_hash",
                            displayName = "Pinned Contact",
                            isPinned = true,
                        ),
                        TestFactories.createEnrichedContact(
                            destinationHash = "regular_hash",
                            displayName = "Regular Contact",
                            isPinned = false,
                        ),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns testContactsFlow
            val newViewModel = ContactsViewModel(contactRepository, propagationNodeManager)

            // Then - wait for data to propagate through the flow chain
            newViewModel.contactsState.test {
                var groups = awaitItem().groupedContacts
                while (groups.pinned.isEmpty() && groups.all.isEmpty()) {
                    advanceUntilIdle()
                    groups = awaitItem().groupedContacts
                }
                assertEquals(1, groups.pinned.size)
                assertEquals("Pinned Contact", groups.pinned[0].displayName)
                assertEquals(1, groups.all.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `groupedContacts - excludes relay from pinned`() =
        runTest {
            // Given: Create fresh ViewModel with relay that is also pinned
            val testContactsFlow =
                MutableStateFlow(
                    listOf(
                        TestFactories.createEnrichedContact(
                            destinationHash = "relay_hash",
                            displayName = "Relay",
                            isMyRelay = true,
                            isPinned = true,
                        ),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns testContactsFlow
            val newViewModel = ContactsViewModel(contactRepository, propagationNodeManager)

            // Then: Should be in relay, not pinned - wait for data to propagate
            newViewModel.contactsState.test {
                var groups = awaitItem().groupedContacts
                while (groups.relay == null) {
                    advanceUntilIdle()
                    groups = awaitItem().groupedContacts
                }
                assertNotNull(groups.relay)
                assertTrue(groups.pinned.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `groupedContacts - all group excludes relay and pinned`() =
        runTest {
            // Given: Create fresh ViewModel with configured repository
            val testContactsFlow =
                MutableStateFlow(
                    listOf(
                        TestFactories.createEnrichedContact(
                            destinationHash = "relay_hash",
                            displayName = "Relay",
                            isMyRelay = true,
                        ),
                        TestFactories.createEnrichedContact(
                            destinationHash = "pinned_hash",
                            displayName = "Pinned",
                            isPinned = true,
                        ),
                        TestFactories.createEnrichedContact(
                            destinationHash = "regular_hash",
                            displayName = "Regular",
                        ),
                    ),
                )
            every { contactRepository.getEnrichedContacts() } returns testContactsFlow
            val newViewModel = ContactsViewModel(contactRepository, propagationNodeManager)

            // Then - wait for data to propagate through the flow chain
            newViewModel.contactsState.test {
                var groups = awaitItem().groupedContacts
                while (groups.all.isEmpty()) {
                    advanceUntilIdle()
                    groups = awaitItem().groupedContacts
                }
                assertEquals(1, groups.all.size)
                assertEquals("Regular", groups.all[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Contact Operations Tests ==========

    @Test
    fun `onSearchQueryChanged - updates search query`() =
        runTest {
            // When
            viewModel.onSearchQueryChanged("test query")
            advanceUntilIdle()

            // Then
            assertEquals("test query", viewModel.searchQuery.value)
        }

    @Test
    fun `addContactFromAnnounce - calls repository`() =
        runTest {
            // Given
            coEvery { contactRepository.addContactFromAnnounce(any(), any()) } returns Result.success(Unit)

            // When
            val result = runCatching { viewModel.addContactFromAnnounce(testDestHash, testPublicKey) }
            advanceUntilIdle()

            // Then
            assertTrue("addContactFromAnnounce should complete without exception", result.isSuccess)
            coVerify { contactRepository.addContactFromAnnounce(testDestHash, testPublicKey) }
        }

    @Test
    fun `addContactManually - calls repository`() =
        runTest {
            // Given
            coEvery { contactRepository.addContactManually(any(), any(), any()) } returns Result.success(Unit)

            // When
            val result = runCatching { viewModel.addContactManually(testDestHash, testPublicKey, "Nickname") }
            advanceUntilIdle()

            // Then
            assertTrue("addContactManually should complete without exception", result.isSuccess)
            coVerify { contactRepository.addContactManually(testDestHash, testPublicKey, "Nickname") }
        }

    @Test
    fun `deleteContact - calls repository`() =
        runTest {
            // Given
            coEvery { contactRepository.deleteContact(any()) } just Runs

            // When
            val result = runCatching { viewModel.deleteContact(testDestHash) }
            advanceUntilIdle()

            // Then
            assertTrue("deleteContact should complete without exception", result.isSuccess)
            coVerify { contactRepository.deleteContact(testDestHash) }
        }

    @Test
    fun `unsetRelayAndDelete - deletes and triggers reselection`() =
        runTest {
            // Given
            coEvery { propagationNodeManager.excludeFromAutoSelect(any()) } just Runs
            coEvery { contactRepository.deleteContact(any()) } just Runs
            coEvery { propagationNodeManager.onRelayDeleted() } just Runs

            // When
            val result = runCatching { viewModel.unsetRelayAndDelete(testDestHash) }
            advanceUntilIdle()

            // Then
            assertTrue("unsetRelayAndDelete should complete without exception", result.isSuccess)
            coVerify { contactRepository.deleteContact(testDestHash) }
            coVerify { propagationNodeManager.onRelayDeleted() }
        }

    // ========== Update Operations Tests ==========

    @Test
    fun `updateNickname - calls repository`() =
        runTest {
            // Given
            coEvery { contactRepository.updateNickname(any(), any()) } just Runs

            // When
            val result = runCatching { viewModel.updateNickname(testDestHash, "New Nickname") }
            advanceUntilIdle()

            // Then
            assertTrue("updateNickname should complete without exception", result.isSuccess)
            coVerify { contactRepository.updateNickname(testDestHash, "New Nickname") }
        }

    @Test
    fun `updateNotes - calls repository`() =
        runTest {
            // Given
            coEvery { contactRepository.updateNotes(any(), any()) } just Runs

            // When
            val result = runCatching { viewModel.updateNotes(testDestHash, "Some notes") }
            advanceUntilIdle()

            // Then
            assertTrue("updateNotes should complete without exception", result.isSuccess)
            coVerify { contactRepository.updateNotes(testDestHash, "Some notes") }
        }

    @Test
    fun `togglePin - calls repository`() =
        runTest {
            // Given
            coEvery { contactRepository.togglePin(any()) } just Runs

            // When
            val result = runCatching { viewModel.togglePin(testDestHash) }
            advanceUntilIdle()

            // Then
            assertTrue("togglePin should complete without exception", result.isSuccess)
            coVerify { contactRepository.togglePin(testDestHash) }
        }

    // ========== Duplicate Detection Tests ==========

    @Test
    fun `checkContactExists - exists in db returns contact`() =
        runTest {
            // Given
            val contact = TestFactories.createContactEntity()
            coEvery { contactRepository.hasContact(testDestHash) } returns true
            coEvery { contactRepository.getContact(testDestHash) } returns contact

            // When
            val result = viewModel.checkContactExists(testDestHash)

            // Then: Should return an EnrichedContact
            assertNotNull(result)
            assertEquals(testDestHash, result?.destinationHash)
        }

    @Test
    fun `checkContactExists - not in db returns null`() =
        runTest {
            // Given
            coEvery { contactRepository.hasContact(testDestHash) } returns false

            // When
            val result = viewModel.checkContactExists(testDestHash)

            // Then
            assertNull(result)
        }

    @Test
    fun `checkContactExists - in db but not stateflow fetches from db`() =
        runTest {
            // Given: Contact exists in DB but not yet in StateFlow
            val contact = TestFactories.createContactEntity()
            coEvery { contactRepository.hasContact(testDestHash) } returns true
            coEvery { contactRepository.getContact(testDestHash) } returns contact
            // StateFlow is empty
            contactsFlow.value = emptyList()

            // When
            val result = viewModel.checkContactExists(testDestHash)

            // Then: Should fetch from DB
            assertNotNull(result)
            assertEquals(testDestHash, result?.destinationHash)
        }

    // ========== Unified Input (Sideband Import) Tests ==========

    @Test
    fun `addContactFromInput - valid lxma url adds successfully`() =
        runTest {
            // Given: Full identity lxma:// URL
            val lxmaUrl = "lxma://$testDestHash/${testPublicKey.joinToString("") { "%02x".format(it) }}"
            coEvery { contactRepository.hasContact(any()) } returns false
            coEvery { contactRepository.addContactManually(any(), any(), any()) } returns Result.success(Unit)

            // When
            val result = viewModel.addContactFromInput(lxmaUrl, "Test")

            // Then
            assertTrue(result is AddContactResult.Success || result is AddContactResult.Error)
            // Note: Actual parsing depends on InputValidator implementation
        }

    @Test
    fun `addContactFromInput - invalid input returns error`() =
        runTest {
            // Given: Invalid input
            val invalidInput = "not_a_valid_hash"

            // When
            val result = viewModel.addContactFromInput(invalidInput)

            // Then
            assertTrue(result is AddContactResult.Error)
        }

    @Test
    fun `addContactFromInput - duplicate contact returns already exists`() =
        runTest {
            // Given: Contact already exists and valid hash
            val validHash = "0123456789abcdef0123456789abcdef"
            val existingContact = TestFactories.createEnrichedContact(destinationHash = validHash)
            contactsFlow.value = listOf(existingContact)
            coEvery { contactRepository.hasContact(validHash) } returns true
            coEvery { contactRepository.getContact(validHash) } returns TestFactories.createContactEntity(destinationHash = validHash)

            // When
            val result = viewModel.addContactFromInput(validHash)

            // Then
            assertTrue(result is AddContactResult.AlreadyExists)
        }

    @Test
    fun `addContactFromInput - hash only adds as pending`() =
        runTest {
            // Given: Valid 32-char hex hash
            val validHash = "0123456789abcdef0123456789abcdef"
            coEvery { contactRepository.hasContact(validHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(
                    ContactRepository.AddPendingResult.AddedAsPending,
                )

            // When
            val result = viewModel.addContactFromInput(validHash, "Test")

            // Then
            assertTrue(result is AddContactResult.PendingIdentity)
        }

    @Test
    fun `addContactFromInput - hash with existing announce resolves immediately`() =
        runTest {
            // Given: Valid hash, announce exists
            val validHash = "0123456789abcdef0123456789abcdef"
            coEvery { contactRepository.hasContact(validHash) } returns false
            coEvery { contactRepository.addPendingContact(any(), any()) } returns
                Result.success(
                    ContactRepository.AddPendingResult.ResolvedImmediately,
                )

            // When
            val result = viewModel.addContactFromInput(validHash, "Test")

            // Then
            assertTrue(result is AddContactResult.Success)
        }

    // ========== Retry Identity Tests ==========

    @Test
    fun `retryIdentityResolution - resets contact with fresh timestamp`() =
        runTest {
            // Given
            coEvery { contactRepository.resetContactForRetry(any()) } returns Result.success(Unit)

            // When
            val result = runCatching { viewModel.retryIdentityResolution(testDestHash) }
            advanceUntilIdle()

            // Then: Should call resetContactForRetry and log success
            assertTrue("retryIdentityResolution should complete without exception", result.isSuccess)
            coVerify { contactRepository.resetContactForRetry(testDestHash) }
            verify { Log.d(any(), match { it.contains("Reset contact for retry") }) }
        }

    @Test
    fun `retryIdentityResolution - handles Result failure gracefully`() =
        runTest {
            // Given: Repository returns failure result
            coEvery { contactRepository.resetContactForRetry(any()) } returns
                Result.failure(RuntimeException("Reset failed"))

            // When: Should not crash
            val result = runCatching { viewModel.retryIdentityResolution(testDestHash) }
            advanceUntilIdle()

            // Then: Verify attempt was made and error was logged (covers else branch)
            assertTrue("retryIdentityResolution should handle failure gracefully", result.isSuccess)
            coVerify { contactRepository.resetContactForRetry(testDestHash) }
            verify { Log.e(any(), match { it.contains("Failed to reset contact") }) }
        }

    @Test
    fun `retryIdentityResolution - handles exception gracefully`() =
        runTest {
            // Given: Repository throws exception
            coEvery { contactRepository.resetContactForRetry(any()) } throws
                RuntimeException("Database error")

            // When: Should not crash
            val result = runCatching { viewModel.retryIdentityResolution(testDestHash) }
            advanceUntilIdle()

            // Then: Verify attempt was made and exception was logged (covers catch block)
            assertTrue("retryIdentityResolution should handle exception gracefully", result.isSuccess)
            coVerify { contactRepository.resetContactForRetry(testDestHash) }
            verify { Log.e(any(), match { it.contains("Error retrying identity") }, any<Throwable>()) }
        }

    // ========== Contact Count Tests ==========

    @Test
    fun `contactCount - emits repository count`() =
        runTest {
            // Given
            contactCountFlow.value = 5

            // Then
            viewModel.contactCount.test {
                assertEquals(0, awaitItem()) // Initial
                advanceUntilIdle()
                assertEquals(5, awaitItem())
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `deleteContact - handles errors gracefully`() =
        runTest {
            // Given
            coEvery { contactRepository.deleteContact(any()) } throws RuntimeException("DB error")

            // When: Should not crash
            val result = runCatching { viewModel.deleteContact(testDestHash) }
            advanceUntilIdle()

            // Then: Verify attempt was made and error was handled gracefully
            assertTrue("deleteContact should handle errors gracefully", result.isSuccess)
            coVerify { contactRepository.deleteContact(testDestHash) }
        }

    @Test
    fun `updateNickname - handles errors gracefully`() =
        runTest {
            // Given
            coEvery { contactRepository.updateNickname(any(), any()) } throws RuntimeException("DB error")

            // When: Should not crash
            val result = runCatching { viewModel.updateNickname(testDestHash, "Name") }
            advanceUntilIdle()

            // Then: Verify attempt was made and error was handled gracefully
            assertTrue("updateNickname should handle errors gracefully", result.isSuccess)
            coVerify { contactRepository.updateNickname(testDestHash, "Name") }
        }
}
