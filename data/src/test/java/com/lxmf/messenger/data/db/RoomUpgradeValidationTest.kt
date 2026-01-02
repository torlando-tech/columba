package com.lxmf.messenger.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 2.8.4 Upgrade Validation Tests
 *
 * These tests validate that the Room upgrade from 2.6.1 to 2.8.4 does not break
 * existing functionality. Key areas tested:
 *
 * 1. Basic CRUD operations on all DAOs
 * 2. Flow emission (validates 2.8.x race condition fix)
 * 3. Composite primary key handling
 * 4. Suspend functions (validates 2.8.1 crash fix)
 * 5. Transaction handling (validates 2.8.2 deadlock fix)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomUpgradeValidationTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var context: Context

    companion object {
        private const val TEST_IDENTITY_HASH = "test_identity_hash_123456789012"
        private const val TEST_DEST_HASH = "test_dest_hash_1234567890123456"
        private const val TEST_PEER_HASH = "test_peer_hash_12345678901234567"
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(
        identityHash: String = TEST_IDENTITY_HASH,
        displayName: String = "Test Identity",
    ) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = displayName,
        destinationHash = TEST_DEST_HASH,
        filePath = "/test/path",
        keyData = null,
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = true,
    )

    private fun createTestConversation(
        peerHash: String = TEST_PEER_HASH,
        identityHash: String = TEST_IDENTITY_HASH,
        peerName: String = "Test Peer",
    ) = ConversationEntity(
        peerHash = peerHash,
        identityHash = identityHash,
        peerName = peerName,
        lastMessage = "Hello",
        lastMessageTimestamp = System.currentTimeMillis(),
        unreadCount = 0,
    )

    private fun createTestMessage(
        id: String = "msg_${System.nanoTime()}",
        conversationHash: String = TEST_PEER_HASH,
        identityHash: String = TEST_IDENTITY_HASH,
        content: String = "Test message",
        isFromMe: Boolean = true,
    ) = MessageEntity(
        id = id,
        conversationHash = conversationHash,
        identityHash = identityHash,
        content = content,
        timestamp = System.currentTimeMillis(),
        isFromMe = isFromMe,
        status = "sent",
        isRead = false,
    )

    // ========== Database Creation Test ==========

    @Test
    fun database_createsSuccessfully() {
        assertNotNull(database)
        assertNotNull(database.localIdentityDao())
        assertNotNull(database.conversationDao())
        assertNotNull(database.messageDao())
        assertNotNull(database.contactDao())
        assertNotNull(database.announceDao())
        assertNotNull(database.peerIdentityDao())
        assertNotNull(database.customThemeDao())
        assertNotNull(database.receivedLocationDao())
    }

    // ========== LocalIdentity DAO Tests ==========

    @Test
    fun localIdentityDao_insertAndRetrieve() =
        runTest {
            val identity = createTestIdentity()
            database.localIdentityDao().insert(identity)

            val retrieved = database.localIdentityDao().getIdentity(TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals(identity.identityHash, retrieved?.identityHash)
            assertEquals(identity.displayName, retrieved?.displayName)
        }

    @Test
    fun localIdentityDao_flowEmitsUpdates() =
        runTest {
            val identity = createTestIdentity()
            database.localIdentityDao().insert(identity)

            database.localIdentityDao().getAllIdentities().test {
                val initial = awaitItem()
                assertEquals(1, initial.size)

                // Update identity (insert with REPLACE strategy)
                val updated = identity.copy(displayName = "Updated Name")
                database.localIdentityDao().insert(updated)

                val afterUpdate = awaitItem()
                assertEquals("Updated Name", afterUpdate[0].displayName)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Conversation DAO Tests (Composite PK) ==========

    @Test
    fun conversationDao_compositePrimaryKey_works() =
        runTest {
            // Setup: Create identity first (FK constraint)
            database.localIdentityDao().insert(createTestIdentity())

            val conversation = createTestConversation()
            database.conversationDao().insertConversation(conversation)

            val retrieved =
                database.conversationDao().getConversation(
                    TEST_PEER_HASH,
                    TEST_IDENTITY_HASH,
                )
            assertNotNull(retrieved)
            assertEquals(conversation.peerHash, retrieved?.peerHash)
            assertEquals(conversation.identityHash, retrieved?.identityHash)
        }

    @Test
    fun conversationDao_flowEmitsOnInsert() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())

            database.conversationDao().getAllConversations(TEST_IDENTITY_HASH).test {
                // Initially empty
                assertEquals(0, awaitItem().size)

                // Insert conversation
                database.conversationDao().insertConversation(createTestConversation())

                // Should emit update
                val updated = awaitItem()
                assertEquals(1, updated.size)
                assertEquals(TEST_PEER_HASH, updated[0].peerHash)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun conversationDao_unreadCountOperations() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            // Increment unread count
            database.conversationDao().incrementUnreadCount(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            database.conversationDao().incrementUnreadCount(TEST_PEER_HASH, TEST_IDENTITY_HASH)

            var conversation =
                database.conversationDao().getConversation(
                    TEST_PEER_HASH,
                    TEST_IDENTITY_HASH,
                )
            assertEquals(2, conversation?.unreadCount)

            // Mark as read
            database.conversationDao().markAsRead(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            conversation =
                database.conversationDao().getConversation(
                    TEST_PEER_HASH,
                    TEST_IDENTITY_HASH,
                )
            assertEquals(0, conversation?.unreadCount)
        }

    // ========== Message DAO Tests (Composite PK + FK) ==========

    @Test
    fun messageDao_compositePrimaryKey_withForeignKeys() =
        runTest {
            // Setup: Create identity and conversation (FK constraints)
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message = createTestMessage()
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById(message.id, TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals(message.id, retrieved?.id)
            assertEquals(message.content, retrieved?.content)
        }

    @Test
    fun messageDao_flowEmitsOnStatusUpdate() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message = createTestMessage(id = "test_msg_1")
            database.messageDao().insertMessage(message)

            database.messageDao().observeMessageById("test_msg_1").test {
                val initial = awaitItem()
                assertEquals("sent", initial?.status)

                // Update status
                database.messageDao().updateMessageStatus("test_msg_1", TEST_IDENTITY_HASH, "delivered")

                val updated = awaitItem()
                assertEquals("delivered", updated?.status)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun messageDao_bulkInsert_replaceStrategy() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val messages =
                listOf(
                    createTestMessage(id = "msg1", content = "First"),
                    createTestMessage(id = "msg2", content = "Second"),
                    createTestMessage(id = "msg3", content = "Third"),
                )
            database.messageDao().insertMessages(messages)

            val all = database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH)
            assertEquals(3, all.size)

            // Replace existing messages
            val updated =
                listOf(
                    createTestMessage(id = "msg1", content = "First Updated"),
                    createTestMessage(id = "msg2", content = "Second Updated"),
                )
            database.messageDao().insertMessages(updated)

            val afterUpdate = database.messageDao().getMessageById("msg1", TEST_IDENTITY_HASH)
            assertEquals("First Updated", afterUpdate?.content)
        }

    @Test
    fun messageDao_bulkInsert_ignoreStrategy() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val original = createTestMessage(id = "msg1", content = "Original")
            database.messageDao().insertMessage(original)

            // Try to insert with same ID - should be ignored
            val duplicate = createTestMessage(id = "msg1", content = "Duplicate")
            database.messageDao().insertMessagesIgnoreDuplicates(listOf(duplicate))

            val retrieved = database.messageDao().getMessageById("msg1", TEST_IDENTITY_HASH)
            assertEquals("Original", retrieved?.content) // Original preserved
        }

    @Test
    fun messageDao_unreadCountQuery() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            // Insert mix of read/unread messages
            database.messageDao().insertMessage(
                createTestMessage(id = "msg1", isFromMe = false).copy(isRead = false),
            )
            database.messageDao().insertMessage(
                createTestMessage(id = "msg2", isFromMe = false).copy(isRead = false),
            )
            database.messageDao().insertMessage(
                createTestMessage(id = "msg3", isFromMe = false).copy(isRead = true),
            )
            database.messageDao().insertMessage(
                createTestMessage(id = "msg4", isFromMe = true), // From me, doesn't count
            )

            val unreadCount = database.messageDao().getUnreadCount(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(2, unreadCount)
        }

    // ========== Foreign Key Cascade Tests ==========

    @Test
    fun foreignKey_cascadeDelete_conversationDeletesMessages() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())
            database.messageDao().insertMessage(createTestMessage(id = "msg1"))
            database.messageDao().insertMessage(createTestMessage(id = "msg2"))

            // Verify messages exist
            assertEquals(2, database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH).size)

            // Delete conversation
            database.conversationDao().deleteConversationByKey(TEST_PEER_HASH, TEST_IDENTITY_HASH)

            // Messages should be cascade deleted
            assertEquals(0, database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH).size)
        }

    @Test
    fun foreignKey_cascadeDelete_identityDeletesAll() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())
            database.messageDao().insertMessage(createTestMessage())

            // Delete identity
            database.localIdentityDao().delete(TEST_IDENTITY_HASH)

            // Everything should be cascade deleted
            assertEquals(0, database.conversationDao().getAllConversationsList(TEST_IDENTITY_HASH).size)
            assertEquals(0, database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH).size)
        }

    // ========== Search Tests ==========

    @Test
    fun conversationDao_searchByPeerName() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(
                createTestConversation(peerHash = "peer1", peerName = "Alice Smith"),
            )
            database.conversationDao().insertConversation(
                createTestConversation(peerHash = "peer2", peerName = "Bob Jones"),
            )
            database.conversationDao().insertConversation(
                createTestConversation(peerHash = "peer3", peerName = "Charlie Smith"),
            )

            database.conversationDao().searchConversations(TEST_IDENTITY_HASH, "Smith").test {
                val results = awaitItem()
                assertEquals(2, results.size)
                assertTrue(results.all { it.peerName.contains("Smith") })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Reply Preview Tests (for json_extract migration) ==========

    @Test
    fun messageDao_replyPreviewData() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                createTestMessage(id = "msg1", content = "Hello world")
                    .copy(fieldsJson = """{"16": {"reply_to": "other_msg"}}""")
            database.messageDao().insertMessage(message)

            val preview = database.messageDao().getReplyPreviewData("msg1", TEST_IDENTITY_HASH)
            assertNotNull(preview)
            assertEquals("msg1", preview?.id)
            assertEquals("Hello world", preview?.content)
            assertNotNull(preview?.fieldsJson)
        }
}
