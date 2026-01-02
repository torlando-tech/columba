package com.lxmf.messenger.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
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
 * Tests for MessageDao operations.
 * Validates CRUD operations, status updates, bulk operations, and paging queries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var identityDao: LocalIdentityDao

    companion object {
        private const val IDENTITY_HASH = "identity_hash_12345678901234567"
        private const val PEER_HASH = "peer_hash_123456789012345678901"
        private const val DEST_HASH = "dest_hash_123456789012345678901"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        messageDao = database.messageDao()
        conversationDao = database.conversationDao()
        identityDao = database.localIdentityDao()

        // Setup required parent entities (FK constraints)
        runTest {
            identityDao.insert(createTestIdentity())
            conversationDao.insertConversation(createTestConversation())
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity() =
        LocalIdentityEntity(
            identityHash = IDENTITY_HASH,
            displayName = "Test Identity",
            destinationHash = DEST_HASH,
            filePath = "/test/identity.key",
            keyData = null,
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    private fun createTestConversation() =
        ConversationEntity(
            peerHash = PEER_HASH,
            identityHash = IDENTITY_HASH,
            peerName = "Test Peer",
            lastMessage = "Hello",
            lastMessageTimestamp = System.currentTimeMillis(),
            unreadCount = 0,
        )

    @Suppress("LongParameterList")
    private fun createTestMessage(
        id: String = "msg_${System.nanoTime()}",
        content: String = "Test message content",
        isFromMe: Boolean = true,
        timestamp: Long = System.currentTimeMillis(),
        status: String = "sent",
        isRead: Boolean = false,
        deliveryMethod: String? = null,
        errorMessage: String? = null,
        fieldsJson: String? = null,
        replyToMessageId: String? = null,
    ) = MessageEntity(
        id = id,
        conversationHash = PEER_HASH,
        identityHash = IDENTITY_HASH,
        content = content,
        timestamp = timestamp,
        isFromMe = isFromMe,
        status = status,
        isRead = isRead,
        deliveryMethod = deliveryMethod,
        errorMessage = errorMessage,
        fieldsJson = fieldsJson,
        replyToMessageId = replyToMessageId,
    )

    // ========== Insert Tests ==========

    @Test
    fun insertMessage_createsNewMessage() =
        runTest {
            val message = createTestMessage(id = "msg1", content = "Hello")
            messageDao.insertMessage(message)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals("msg1", retrieved?.id)
            assertEquals("Hello", retrieved?.content)
        }

    @Test
    fun insertMessage_replacesExistingMessage() =
        runTest {
            val original = createTestMessage(id = "msg1", content = "Original")
            messageDao.insertMessage(original)

            val updated = original.copy(content = "Updated")
            messageDao.insertMessage(updated)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("Updated", retrieved?.content)
        }

    // ========== Update Tests ==========

    @Test
    fun updateMessage_modifiesContent() =
        runTest {
            val message = createTestMessage(id = "msg1", content = "Original")
            messageDao.insertMessage(message)

            val updated = message.copy(content = "Modified")
            messageDao.updateMessage(updated)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("Modified", retrieved?.content)
        }

    @Test
    fun updateMessageStatus_changesStatus() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", status = "sent"))

            messageDao.updateMessageStatus("msg1", IDENTITY_HASH, "delivered")

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("delivered", retrieved?.status)
        }

    @Test
    fun updateMessageDeliveryDetails_setsDeliveryMethod() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))

            messageDao.updateMessageDeliveryDetails("msg1", IDENTITY_HASH, "direct", null)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("direct", retrieved?.deliveryMethod)
            assertNull(retrieved?.errorMessage)
        }

    @Test
    fun updateMessageDeliveryDetails_setsErrorMessage() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", status = "failed"))

            messageDao.updateMessageDeliveryDetails("msg1", IDENTITY_HASH, null, "Connection timeout")

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("Connection timeout", retrieved?.errorMessage)
        }

    @Test
    fun updateMessageFieldsJson_setsFieldsData() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))

            val fieldsJson = """{"6": "image_hex_data", "15": "markdown"}"""
            messageDao.updateMessageFieldsJson("msg1", IDENTITY_HASH, fieldsJson)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals(fieldsJson, retrieved?.fieldsJson)
        }

    // ========== Delete Tests ==========

    @Test
    fun deleteMessage_removesMessage() =
        runTest {
            val message = createTestMessage(id = "msg1")
            messageDao.insertMessage(message)
            assertTrue(messageDao.messageExists("msg1", IDENTITY_HASH))

            messageDao.deleteMessage(message)

            assertFalse(messageDao.messageExists("msg1", IDENTITY_HASH))
        }

    @Test
    fun deleteMessageById_removesMessage() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))

            messageDao.deleteMessageById("msg1", IDENTITY_HASH)

            assertFalse(messageDao.messageExists("msg1", IDENTITY_HASH))
        }

    @Test
    fun deleteMessagesForConversation_removesAllConversationMessages() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))
            messageDao.insertMessage(createTestMessage(id = "msg2"))
            messageDao.insertMessage(createTestMessage(id = "msg3"))

            messageDao.deleteMessagesForConversation(PEER_HASH, IDENTITY_HASH)

            val messages = messageDao.getAllMessagesForIdentity(IDENTITY_HASH)
            assertTrue(messages.isEmpty())
        }

    // ========== Query Tests ==========

    @Test
    fun getLastMessage_returnsNewestMessage() =
        runTest {
            val baseTime = System.currentTimeMillis()
            messageDao.insertMessage(createTestMessage(id = "msg1", timestamp = baseTime - 2000))
            messageDao.insertMessage(createTestMessage(id = "msg2", timestamp = baseTime - 1000))
            messageDao.insertMessage(createTestMessage(id = "msg3", timestamp = baseTime))

            val last = messageDao.getLastMessage(PEER_HASH, IDENTITY_HASH)
            assertEquals("msg3", last?.id)
        }

    @Test
    fun messageExists_returnsTrueForExistingMessage() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "existing"))

            assertTrue(messageDao.messageExists("existing", IDENTITY_HASH))
            assertFalse(messageDao.messageExists("nonexistent", IDENTITY_HASH))
        }

    // ========== Unread Count Tests ==========

    @Test
    fun getUnreadCount_countsOnlyUnreadReceivedMessages() =
        runTest {
            // Unread received messages (should count)
            messageDao.insertMessage(createTestMessage(id = "msg1", isFromMe = false, isRead = false))
            messageDao.insertMessage(createTestMessage(id = "msg2", isFromMe = false, isRead = false))

            // Read received message (should not count)
            messageDao.insertMessage(createTestMessage(id = "msg3", isFromMe = false, isRead = true))

            // Sent messages (should not count regardless of read status)
            messageDao.insertMessage(createTestMessage(id = "msg4", isFromMe = true, isRead = false))

            val count = messageDao.getUnreadCount(PEER_HASH, IDENTITY_HASH)
            assertEquals(2, count)
        }

    @Test
    fun markMessagesAsRead_marksAllReceivedAsRead() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", isFromMe = false, isRead = false))
            messageDao.insertMessage(createTestMessage(id = "msg2", isFromMe = false, isRead = false))
            messageDao.insertMessage(createTestMessage(id = "msg3", isFromMe = true, isRead = false))

            messageDao.markMessagesAsRead(PEER_HASH, IDENTITY_HASH)

            // Received messages should be marked as read
            assertTrue(messageDao.getMessageById("msg1", IDENTITY_HASH)?.isRead == true)
            assertTrue(messageDao.getMessageById("msg2", IDENTITY_HASH)?.isRead == true)

            // Sent message unchanged (only received messages are marked)
            val unreadCount = messageDao.getUnreadCount(PEER_HASH, IDENTITY_HASH)
            assertEquals(0, unreadCount)
        }

    // ========== Bulk Operations Tests ==========

    @Test
    fun insertMessages_bulkInsertsWithReplace() =
        runTest {
            val messages =
                (1..5).map { i ->
                    createTestMessage(id = "msg$i", content = "Message $i")
                }
            messageDao.insertMessages(messages)

            val all = messageDao.getAllMessagesForIdentity(IDENTITY_HASH)
            assertEquals(5, all.size)
        }

    @Test
    fun insertMessagesIgnoreDuplicates_preservesExisting() =
        runTest {
            val original = createTestMessage(id = "msg1", content = "Original")
            messageDao.insertMessage(original)

            val duplicates =
                listOf(
                    createTestMessage(id = "msg1", content = "Duplicate"),
                    createTestMessage(id = "msg2", content = "New"),
                )
            messageDao.insertMessagesIgnoreDuplicates(duplicates)

            assertEquals("Original", messageDao.getMessageById("msg1", IDENTITY_HASH)?.content)
            assertEquals("New", messageDao.getMessageById("msg2", IDENTITY_HASH)?.content)
        }

    // ========== Flow Tests (validates Room 2.8.x race condition fix) ==========

    @Test
    fun getMessagesForConversation_flowEmitsOnInsert() =
        runTest {
            messageDao.getMessagesForConversation(PEER_HASH, IDENTITY_HASH).test {
                assertEquals(0, awaitItem().size)

                messageDao.insertMessage(createTestMessage(id = "msg1"))
                assertEquals(1, awaitItem().size)

                messageDao.insertMessage(createTestMessage(id = "msg2"))
                assertEquals(2, awaitItem().size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeMessageById_flowEmitsOnStatusChange() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", status = "sent"))

            messageDao.observeMessageById("msg1").test {
                val initial = awaitItem()
                assertEquals("sent", initial?.status)

                messageDao.updateMessageStatus("msg1", IDENTITY_HASH, "delivered")
                val updated = awaitItem()
                assertEquals("delivered", updated?.status)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getMessagesForConversation_orderedByTimestampAscending() =
        runTest {
            val baseTime = System.currentTimeMillis()
            messageDao.insertMessage(createTestMessage(id = "msg3", timestamp = baseTime + 2000))
            messageDao.insertMessage(createTestMessage(id = "msg1", timestamp = baseTime))
            messageDao.insertMessage(createTestMessage(id = "msg2", timestamp = baseTime + 1000))

            messageDao.getMessagesForConversation(PEER_HASH, IDENTITY_HASH).test {
                val messages = awaitItem()
                assertEquals(3, messages.size)
                assertEquals("msg1", messages[0].id) // Oldest first
                assertEquals("msg2", messages[1].id)
                assertEquals("msg3", messages[2].id) // Newest last
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Reply Preview Tests ==========

    @Test
    fun getReplyPreviewData_returnsMinimalData() =
        runTest {
            val message =
                createTestMessage(
                    id = "msg1",
                    content = "This is a long message that we're replying to",
                    fieldsJson = """{"6": "image_data"}""",
                )
            messageDao.insertMessage(message)

            val preview = messageDao.getReplyPreviewData("msg1", IDENTITY_HASH)
            assertNotNull(preview)
            assertEquals("msg1", preview?.id)
            assertEquals("This is a long message that we're replying to", preview?.content)
            assertEquals(true, preview?.isFromMe)
            assertNotNull(preview?.fieldsJson)
            assertEquals(PEER_HASH, preview?.conversationHash)
        }

    @Test
    fun getReplyPreviewData_returnsNullForNonexistent() =
        runTest {
            val preview = messageDao.getReplyPreviewData("nonexistent", IDENTITY_HASH)
            assertNull(preview)
        }
}
