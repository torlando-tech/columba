package com.lxmf.messenger.integration

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.storage.AttachmentStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for message data flow.
 *
 * Tests the flow from message persistence through to observable state changes.
 * This verifies that:
 * 1. Messages saved via repository are persisted correctly
 * 2. Conversation state is updated atomically
 * 3. Flow observers receive emissions on data changes
 * 4. Message deduplication works in a realistic scenario
 *
 * These are instrumented tests running on a real Android device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class MessageDataFlowTest {
    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var repository: ConversationRepository

    companion object {
        const val TEST_IDENTITY_HASH = "test_identity_hash_12345678901234"
        const val TEST_DEST_HASH = "test_dest_hash_123456789012345678"
        const val TEST_PEER_HASH = "test_peer_hash_123456789012345678"
    }

    /**
     * Fake AttachmentStorageManager that does nothing.
     * Used instead of mockk to avoid minSdk conflicts with mockk-android.
     *
     * Note: AttachmentStorageManager methods are not open/virtual, so we can't override them.
     * Instead, we just use the real class - attachment operations will fail gracefully
     * (return null) since we're not testing file operations anyway.
     */

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Create in-memory database
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        // Create repository with real database and real attachment storage
        // (Attachment operations will work but we're not testing file storage here)
        repository =
            ConversationRepository(
                conversationDao = database.conversationDao(),
                messageDao = database.messageDao(),
                peerIdentityDao = database.peerIdentityDao(),
                localIdentityDao = database.localIdentityDao(),
                attachmentStorage = AttachmentStorageManager(context),
                draftDao = database.draftDao(),
            )
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertTestIdentity() {
        val identity =
            LocalIdentityEntity(
                identityHash = TEST_IDENTITY_HASH,
                displayName = "Test User",
                destinationHash = TEST_DEST_HASH,
                filePath = "/test/path",
                keyData = null,
                createdTimestamp = System.currentTimeMillis(),
                lastUsedTimestamp = System.currentTimeMillis(),
                isActive = true,
            )
        database.localIdentityDao().insert(identity)
    }

    /**
     * Test: Full message receipt flow
     *
     * Simulates receiving a message from the service layer:
     * 1. Message arrives and is saved via repository
     * 2. Conversation is created/updated
     * 3. Unread count increases
     * 4. Message can be retrieved
     */
    @Test
    fun messageReceiptFlow_createsConversationAndPersistsMessage() =
        runBlocking {
            // Setup
            insertTestIdentity()

            // Act: Simulate receiving a message (as the service would do)
            val message =
                com.lxmf.messenger.data.repository.Message(
                    id = "msg_flow_test_123456789012345678",
                    destinationHash = TEST_PEER_HASH,
                    content = "Hello from peer!",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                )

            repository.saveMessage(
                peerHash = TEST_PEER_HASH,
                peerName = "Test Peer",
                message = message,
                peerPublicKey = null,
            )

            // Assert: Message was persisted
            val savedMessage = database.messageDao().getMessageById(message.id, TEST_IDENTITY_HASH)
            assertNotNull("Message should be saved", savedMessage)
            assertEquals("Hello from peer!", savedMessage?.content)

            // Assert: Conversation was created with correct state
            val conversation = database.conversationDao().getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertNotNull("Conversation should be created", conversation)
            assertEquals("Test Peer", conversation?.peerName)
            assertEquals(1, conversation?.unreadCount)
            assertEquals("Hello from peer!".take(100), conversation?.lastMessage)
        }

    /**
     * Test: Message deduplication in realistic scenario
     *
     * Simulates LXMF replay scenario where the same message arrives multiple times
     * (e.g., via direct link and propagation node).
     */
    @Test
    fun messageReceiptFlow_deduplicatesReplayedMessages() =
        runBlocking {
            // Setup
            insertTestIdentity()

            val messageId = "msg_dup_flow_test_1234567890123456"
            val originalTimestamp = 1000L

            // First message (original)
            val original =
                com.lxmf.messenger.data.repository.Message(
                    id = messageId,
                    destinationHash = TEST_PEER_HASH,
                    content = "Original message",
                    timestamp = originalTimestamp,
                    isFromMe = false,
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", original, null)

            // Verify first message saved
            val afterFirst = database.messageDao().getMessageById(messageId, TEST_IDENTITY_HASH)
            assertEquals(originalTimestamp, afterFirst?.timestamp)

            // Create conversation for unread tracking
            val unreadAfterFirst =
                database
                    .conversationDao()
                    .getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
                    ?.unreadCount
            assertEquals(1, unreadAfterFirst)

            // Replay (same ID, different timestamp - simulating propagation node delivery)
            val replay =
                com.lxmf.messenger.data.repository.Message(
                    id = messageId, // SAME ID
                    destinationHash = TEST_PEER_HASH,
                    content = "Replayed content", // Different content
                    timestamp = 5000L, // Later timestamp
                    isFromMe = false,
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", replay, null)

            // Assert: Original message preserved
            val afterReplay = database.messageDao().getMessageById(messageId, TEST_IDENTITY_HASH)
            assertEquals("Original timestamp should be preserved", originalTimestamp, afterReplay?.timestamp)
            assertEquals("Original content should be preserved", "Original message", afterReplay?.content)

            // Assert: Only one message exists
            val allMessages = database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH)
            assertEquals("Should have exactly 1 message", 1, allMessages.size)

            // Assert: Unread count NOT incremented for duplicate
            val unreadAfterReplay =
                database
                    .conversationDao()
                    .getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
                    ?.unreadCount
            assertEquals("Unread count should NOT increment for duplicate", 1, unreadAfterReplay)
        }

    /**
     * Test: Conversation Flow emits on message receipt
     *
     * Verifies that observers of the conversation list receive
     * updates when new messages arrive.
     */
    @Test
    fun conversationFlow_emitsOnNewMessage() =
        runBlocking {
            // Setup
            insertTestIdentity()

            // Observe conversations
            repository.getConversations().test(timeout = 5.seconds) {
                // Initial: no conversations
                val initial = awaitItem()
                assertTrue("Initial list should be empty", initial.isEmpty())

                // Act: Send a message
                withContext(Dispatchers.IO) {
                    val message =
                        com.lxmf.messenger.data.repository.Message(
                            id = "msg_flow_emit_12345678901234567890",
                            destinationHash = TEST_PEER_HASH,
                            content = "Triggers emission",
                            timestamp = System.currentTimeMillis(),
                            isFromMe = false,
                            status = "delivered",
                        )
                    repository.saveMessage(TEST_PEER_HASH, "Emitting Peer", message, null)
                }

                // Assert: Should emit list with new conversation
                val afterMessage = awaitItem()
                assertEquals("Should have 1 conversation", 1, afterMessage.size)
                assertEquals(TEST_PEER_HASH, afterMessage[0].peerHash)
                assertEquals("Emitting Peer", afterMessage[0].peerName)

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Test: Sent message does NOT increment unread count
     *
     * Verifies that outgoing messages don't affect unread count.
     */
    @Test
    fun sentMessage_doesNotIncrementUnread() =
        runBlocking {
            // Setup: Create identity and existing conversation
            insertTestIdentity()

            val conversation =
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Previous",
                    lastMessageTimestamp = 500L,
                    unreadCount = 3, // 3 unread
                    lastSeenTimestamp = 0L,
                )
            database.conversationDao().insertConversation(conversation)

            // Act: Send outgoing message
            val sentMessage =
                com.lxmf.messenger.data.repository.Message(
                    id = "msg_sent_flow_test_1234567890123456",
                    destinationHash = TEST_PEER_HASH,
                    content = "My reply",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true, // OUTGOING
                    status = "pending",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", sentMessage, null)

            // Assert: Unread count unchanged
            val updated = database.conversationDao().getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals("Unread should NOT increment for sent messages", 3, updated?.unreadCount)

            // Assert: But last message IS updated
            assertEquals("My reply", updated?.lastMessage)
        }

    /**
     * Test: Mark as read clears unread count
     *
     * Verifies the read receipt flow works correctly.
     */
    @Test
    fun markAsRead_clearsUnreadCount() =
        runBlocking {
            // Setup
            insertTestIdentity()

            val conversation =
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Unread message",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    unreadCount = 5,
                    lastSeenTimestamp = 0L,
                )
            database.conversationDao().insertConversation(conversation)

            // Verify precondition
            assertEquals(5, database.conversationDao().getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)?.unreadCount)

            // Act: Mark as read
            repository.markConversationAsRead(TEST_PEER_HASH)

            // Assert: Unread is now 0
            val updated = database.conversationDao().getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals("Unread should be 0 after marking as read", 0, updated?.unreadCount)
        }

    /**
     * Test: Delete conversation removes all messages
     *
     * Verifies cascade delete works correctly.
     */
    @Test
    fun deleteConversation_removesAllMessages() =
        runBlocking {
            // Setup
            insertTestIdentity()

            // Create conversation
            val conversation =
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "To Delete",
                    peerPublicKey = null,
                    lastMessage = "Last",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    unreadCount = 0,
                    lastSeenTimestamp = 0L,
                )
            database.conversationDao().insertConversation(conversation)

            // Add multiple messages
            repeat(5) { i ->
                val message =
                    MessageEntity(
                        id = "msg_delete_$i",
                        conversationHash = TEST_PEER_HASH,
                        identityHash = TEST_IDENTITY_HASH,
                        content = "Message $i",
                        timestamp = System.currentTimeMillis() + i,
                        isFromMe = false,
                        status = "delivered",
                        isRead = false,
                    )
                database.messageDao().insertMessage(message)
            }

            // Verify messages exist
            val beforeDelete = database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH)
            assertEquals(5, beforeDelete.size)

            // Act: Delete conversation
            repository.deleteConversation(TEST_PEER_HASH)

            // Assert: Conversation deleted
            val deletedConv = database.conversationDao().getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertTrue("Conversation should be deleted", deletedConv == null)

            // Assert: All messages cascade deleted
            val afterDelete = database.messageDao().getAllMessagesForIdentity(TEST_IDENTITY_HASH)
            assertTrue("All messages should be cascade deleted", afterDelete.isEmpty())
        }
}
