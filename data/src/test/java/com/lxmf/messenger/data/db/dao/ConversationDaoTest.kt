package com.lxmf.messenger.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ConversationDao operations.
 * Validates CRUD operations with composite primary keys, unread count management,
 * search functionality, and Flow emissions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var identityDao: LocalIdentityDao
    private lateinit var contactDao: ContactDao
    private lateinit var announceDao: AnnounceDao

    companion object {
        private const val IDENTITY_HASH = "identity_hash_12345678901234567"
        private const val DEST_HASH = "dest_hash_123456789012345678901"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        conversationDao = database.conversationDao()
        identityDao = database.localIdentityDao()
        contactDao = database.contactDao()
        announceDao = database.announceDao()

        // Setup required parent entity (FK constraint)
        runTest {
            identityDao.insert(createTestIdentity())
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(identityHash: String = IDENTITY_HASH) =
        LocalIdentityEntity(
            identityHash = identityHash,
            displayName = "Test Identity",
            destinationHash = DEST_HASH,
            filePath = "/test/identity.key",
            keyData = null,
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    private fun createTestConversation(
        peerHash: String = "peer_${System.nanoTime()}",
        peerName: String = "Test Peer",
        lastMessage: String = "Hello",
        lastMessageTimestamp: Long = System.currentTimeMillis(),
        unreadCount: Int = 0,
    ) = ConversationEntity(
        peerHash = peerHash,
        identityHash = IDENTITY_HASH,
        peerName = peerName,
        lastMessage = lastMessage,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount,
    )

    private fun createTestContact(
        destinationHash: String,
        customNickname: String? = null,
    ) = ContactEntity(
        destinationHash = destinationHash,
        identityHash = IDENTITY_HASH,
        publicKey = ByteArray(32) { 0x42 }, // Dummy public key
        customNickname = customNickname,
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "CONVERSATION",
        status = ContactStatus.ACTIVE,
    )

    private fun createTestAnnounce(
        destinationHash: String,
        peerName: String,
    ) = AnnounceEntity(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = ByteArray(32) { 0x42 }, // Dummy public key
        appData = null,
        hops = 1,
        lastSeenTimestamp = System.currentTimeMillis(),
        nodeType = "messenger",
        receivingInterface = "UDP Interface",
    )

    // ========== Insert Tests ==========

    @Test
    fun insertConversation_createsNewConversation() =
        runTest {
            val conversation = createTestConversation(peerHash = "peer1", peerName = "Alice")
            conversationDao.insertConversation(conversation)

            val retrieved = conversationDao.getConversation("peer1", IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals("peer1", retrieved?.peerHash)
            assertEquals("Alice", retrieved?.peerName)
        }

    @Test
    fun insertConversation_replacesExisting() =
        runTest {
            val original = createTestConversation(peerHash = "peer1", peerName = "Original")
            conversationDao.insertConversation(original)

            val updated = original.copy(peerName = "Updated")
            conversationDao.insertConversation(updated)

            val retrieved = conversationDao.getConversation("peer1", IDENTITY_HASH)
            assertEquals("Updated", retrieved?.peerName)
        }

    @Test
    fun insertConversations_bulkInserts() =
        runTest {
            val conversations =
                (1..5).map { i ->
                    createTestConversation(peerHash = "peer$i", peerName = "Peer $i")
                }
            conversationDao.insertConversations(conversations)

            val all = conversationDao.getAllConversationsList(IDENTITY_HASH)
            assertEquals(5, all.size)
        }

    // ========== Update Tests ==========

    @Test
    fun updateConversation_modifiesFields() =
        runTest {
            val conversation = createTestConversation(peerHash = "peer1")
            conversationDao.insertConversation(conversation)

            val updated = conversation.copy(lastMessage = "New message", unreadCount = 5)
            conversationDao.updateConversation(updated)

            val retrieved = conversationDao.getConversation("peer1", IDENTITY_HASH)
            assertEquals("New message", retrieved?.lastMessage)
            assertEquals(5, retrieved?.unreadCount)
        }

    @Test
    fun updatePeerName_changesName() =
        runTest {
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "Old Name"),
            )

            conversationDao.updatePeerName("peer1", IDENTITY_HASH, "New Name")

            val retrieved = conversationDao.getConversation("peer1", IDENTITY_HASH)
            assertEquals("New Name", retrieved?.peerName)
        }

    // ========== Delete Tests ==========

    @Test
    fun deleteConversation_removesConversation() =
        runTest {
            val conversation = createTestConversation(peerHash = "peer1")
            conversationDao.insertConversation(conversation)
            assertNotNull(conversationDao.getConversation("peer1", IDENTITY_HASH))

            conversationDao.deleteConversation(conversation)

            assertNull(conversationDao.getConversation("peer1", IDENTITY_HASH))
        }

    @Test
    fun deleteConversationByKey_removesConversation() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1"))

            conversationDao.deleteConversationByKey("peer1", IDENTITY_HASH)

            assertNull(conversationDao.getConversation("peer1", IDENTITY_HASH))
        }

    // ========== Unread Count Tests ==========

    @Test
    fun incrementUnreadCount_increasesCount() =
        runTest {
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", unreadCount = 0),
            )

            conversationDao.incrementUnreadCount("peer1", IDENTITY_HASH)
            assertEquals(1, conversationDao.getConversation("peer1", IDENTITY_HASH)?.unreadCount)

            conversationDao.incrementUnreadCount("peer1", IDENTITY_HASH)
            assertEquals(2, conversationDao.getConversation("peer1", IDENTITY_HASH)?.unreadCount)

            conversationDao.incrementUnreadCount("peer1", IDENTITY_HASH)
            assertEquals(3, conversationDao.getConversation("peer1", IDENTITY_HASH)?.unreadCount)
        }

    @Test
    fun markAsRead_resetsCountAndUpdatesTimestamp() =
        runTest {
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", unreadCount = 10),
            )

            val beforeMark = System.currentTimeMillis()
            conversationDao.markAsRead("peer1", IDENTITY_HASH)

            val retrieved = conversationDao.getConversation("peer1", IDENTITY_HASH)
            assertEquals(0, retrieved?.unreadCount)
            assertTrue(retrieved?.lastSeenTimestamp ?: 0 >= beforeMark)
        }

    // ========== Query Tests ==========

    @Test
    fun getConversation_returnsNullForNonexistent() =
        runTest {
            val result = conversationDao.getConversation("nonexistent", IDENTITY_HASH)
            assertNull(result)
        }

    @Test
    fun getAllConversationsList_returnsAllForIdentity() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer2"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer3"))

            val all = conversationDao.getAllConversationsList(IDENTITY_HASH)
            assertEquals(3, all.size)
        }

    // ========== Flow Tests (validates Room 2.8.x race condition fix) ==========

    @Test
    fun getAllConversations_flowEmitsOnInsert() =
        runTest {
            conversationDao.getAllConversations(IDENTITY_HASH).test {
                assertEquals(0, awaitItem().size)

                conversationDao.insertConversation(createTestConversation(peerHash = "peer1"))
                assertEquals(1, awaitItem().size)

                conversationDao.insertConversation(createTestConversation(peerHash = "peer2"))
                assertEquals(2, awaitItem().size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getAllConversations_flowEmitsOnUpdate() =
        runTest {
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "Original"),
            )

            conversationDao.getAllConversations(IDENTITY_HASH).test {
                val initial = awaitItem()
                assertEquals("Original", initial[0].peerName)

                conversationDao.updatePeerName("peer1", IDENTITY_HASH, "Updated")

                val updated = awaitItem()
                assertEquals("Updated", updated[0].peerName)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getAllConversations_flowEmitsOnDelete() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer2"))

            conversationDao.getAllConversations(IDENTITY_HASH).test {
                assertEquals(2, awaitItem().size)

                conversationDao.deleteConversationByKey("peer1", IDENTITY_HASH)

                assertEquals(1, awaitItem().size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getAllConversations_orderedByLastMessageTimestamp() =
        runTest {
            val baseTime = System.currentTimeMillis()
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", lastMessageTimestamp = baseTime - 2000),
            )
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer2", lastMessageTimestamp = baseTime),
            )
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer3", lastMessageTimestamp = baseTime - 1000),
            )

            conversationDao.getAllConversations(IDENTITY_HASH).test {
                val conversations = awaitItem()
                assertEquals(3, conversations.size)
                assertEquals("peer2", conversations[0].peerHash) // Most recent first
                assertEquals("peer3", conversations[1].peerHash)
                assertEquals("peer1", conversations[2].peerHash) // Oldest last
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Search Tests ==========

    @Test
    fun searchConversations_findsByPeerName() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1", peerName = "Alice Smith"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer2", peerName = "Bob Johnson"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer3", peerName = "Charlie Smith"))

            conversationDao.searchConversations(IDENTITY_HASH, "Smith").test {
                val results = awaitItem()
                assertEquals(2, results.size)
                assertTrue(results.all { it.peerName.contains("Smith") })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun searchConversations_caseInsensitiveSearch() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1", peerName = "Alice"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer2", peerName = "ALICE"))
            conversationDao.insertConversation(createTestConversation(peerHash = "peer3", peerName = "alice"))

            conversationDao.searchConversations(IDENTITY_HASH, "alice").test {
                val results = awaitItem()
                assertEquals(3, results.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun searchConversations_partialMatch() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1", peerName = "Christopher"))

            conversationDao.searchConversations(IDENTITY_HASH, "Chris").test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("Christopher", results[0].peerName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun searchConversations_emptyResultsForNoMatch() =
        runTest {
            conversationDao.insertConversation(createTestConversation(peerHash = "peer1", peerName = "Alice"))

            conversationDao.searchConversations(IDENTITY_HASH, "xyz").test {
                val results = awaitItem()
                assertTrue(results.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Composite Primary Key Tests ==========

    @Test
    fun compositePrimaryKey_allowsSamePeerForDifferentIdentities() =
        runTest {
            // Create second identity
            val secondIdentity = createTestIdentity(identityHash = "second_identity_hash")
            identityDao.insert(secondIdentity)

            // Insert same peer hash for both identities
            conversationDao.insertConversation(
                createTestConversation(peerHash = "shared_peer").copy(identityHash = IDENTITY_HASH),
            )
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = "shared_peer",
                    identityHash = "second_identity_hash",
                    peerName = "Different Context",
                    lastMessage = "Hello",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    unreadCount = 0,
                ),
            )

            // Both should exist independently
            val first = conversationDao.getConversation("shared_peer", IDENTITY_HASH)
            val second = conversationDao.getConversation("shared_peer", "second_identity_hash")

            assertNotNull(first)
            assertNotNull(second)
            assertEquals(IDENTITY_HASH, first?.identityHash)
            assertEquals("second_identity_hash", second?.identityHash)
        }

    // ========== DisplayName Priority Tests ==========

    @Test
    fun getEnrichedConversations_displayNameShowsNicknameWhenSet() =
        runTest {
            // Create conversation with generic peerName
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "peer 12345678"),
            )
            // Create contact with custom nickname
            contactDao.insertContact(createTestContact("peer1", customNickname = "Alice"))

            conversationDao.getEnrichedConversations(IDENTITY_HASH).test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("Alice", results[0].displayName)
                assertEquals("peer 12345678", results[0].peerName) // Original peerName preserved
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getEnrichedConversations_displayNameShowsAnnounceNameWhenNoNickname() =
        runTest {
            // Create conversation with generic peerName
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "peer 12345678"),
            )
            // Create announce with network name (no contact/nickname)
            announceDao.upsertAnnounce(createTestAnnounce("peer1", peerName = "Bob's Node"))

            conversationDao.getEnrichedConversations(IDENTITY_HASH).test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("Bob's Node", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getEnrichedConversations_displayNameShowsPeerNameWhenNoNicknameOrAnnounce() =
        runTest {
            // Create conversation with only peerName set (no contact, no announce)
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "Snapshot Name"),
            )

            conversationDao.getEnrichedConversations(IDENTITY_HASH).test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("Snapshot Name", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getEnrichedConversations_displayNameFallsToPeerHash() =
        runTest {
            // Create conversation where peerName equals peerHash (no other name sources)
            conversationDao.insertConversation(
                createTestConversation(peerHash = "abc123def456", peerName = "abc123def456"),
            )

            conversationDao.getEnrichedConversations(IDENTITY_HASH).test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("abc123def456", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getEnrichedConversations_nicknameOverridesAnnounceName() =
        runTest {
            // Create conversation
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "peer 12345678"),
            )
            // Create BOTH announce AND contact with nickname
            announceDao.upsertAnnounce(createTestAnnounce("peer1", peerName = "Network Name"))
            contactDao.insertContact(createTestContact("peer1", customNickname = "My Friend"))

            conversationDao.getEnrichedConversations(IDENTITY_HASH).test {
                val results = awaitItem()
                assertEquals(1, results.size)
                // Nickname takes priority over announce name
                assertEquals("My Friend", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getEnrichedConversations_contactWithoutNicknameUsesAnnounceName() =
        runTest {
            // Create conversation
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "peer 12345678"),
            )
            // Create announce with network name
            announceDao.upsertAnnounce(createTestAnnounce("peer1", peerName = "Network Name"))
            // Create contact WITHOUT nickname (null)
            contactDao.insertContact(createTestContact("peer1", customNickname = null))

            conversationDao.getEnrichedConversations(IDENTITY_HASH).test {
                val results = awaitItem()
                assertEquals(1, results.size)
                // Should fall through to announce name
                assertEquals("Network Name", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Enriched Search Tests ==========

    @Test
    fun searchEnrichedConversations_findsByNickname() =
        runTest {
            // Create conversation with non-matching peerName
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "peer 12345678"),
            )
            // Create contact with searchable nickname
            contactDao.insertContact(createTestContact("peer1", customNickname = "Alice Smith"))

            conversationDao.searchEnrichedConversations(IDENTITY_HASH, "Alice").test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("Alice Smith", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun searchEnrichedConversations_findsByAnnounceName() =
        runTest {
            // Create conversation with non-matching peerName
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "peer 12345678"),
            )
            // Create announce with searchable name
            announceDao.upsertAnnounce(createTestAnnounce("peer1", peerName = "Bob's Radio"))

            conversationDao.searchEnrichedConversations(IDENTITY_HASH, "Radio").test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("Bob's Radio", results[0].displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun searchEnrichedConversations_findsByPeerHash() =
        runTest {
            // Create conversation
            conversationDao.insertConversation(
                createTestConversation(peerHash = "abc123def456", peerName = "Some Name"),
            )

            conversationDao.searchEnrichedConversations(IDENTITY_HASH, "abc123").test {
                val results = awaitItem()
                assertEquals(1, results.size)
                assertEquals("abc123def456", results[0].peerHash)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun searchEnrichedConversations_noResultsWhenNoMatch() =
        runTest {
            conversationDao.insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "Alice"),
            )

            conversationDao.searchEnrichedConversations(IDENTITY_HASH, "xyz").test {
                val results = awaitItem()
                assertTrue(results.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
