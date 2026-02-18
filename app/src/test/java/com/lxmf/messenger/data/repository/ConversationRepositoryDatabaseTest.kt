package com.lxmf.messenger.data.repository

import app.cash.turbine.test
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.storage.AttachmentStorageManager
import com.lxmf.messenger.test.DatabaseTest
import io.mockk.every
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Database-backed tests for ConversationRepository.
 *
 * Unlike the mock-based ConversationRepositoryTest, these tests use a real in-memory
 * Room database to verify actual behavior including:
 * - Message deduplication logic (the actual INSERT vs skip decision)
 * - Foreign key constraint satisfaction
 * - Conversation update atomicity
 * - Unread count correctness
 *
 * The AttachmentStorageManager is still mocked since it only handles large attachments
 * and doesn't affect the core message storage logic being tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryDatabaseTest : DatabaseTest() {
    private lateinit var repository: ConversationRepository
    private lateinit var mockAttachmentStorage: AttachmentStorageManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setupRepository() {
        Dispatchers.setMain(testDispatcher)

        // Mock attachment storage since we're not testing large attachment extraction
        mockAttachmentStorage = mockk()
        every { mockAttachmentStorage.saveAttachment(any(), any(), any()) } returns null

        runTest {
            // Insert required identity for FK constraints
            insertTestIdentity()
        }

        repository =
            ConversationRepository(
                conversationDao = conversationDao,
                messageDao = messageDao,
                peerIdentityDao = peerIdentityDao,
                localIdentityDao = localIdentityDao,
                attachmentStorage = mockAttachmentStorage,
                draftDao = draftDao,
            )
    }

    @After
    fun teardownDispatcher() {
        Dispatchers.resetMain()
    }

    // ========== Message Deduplication Tests ==========

    @Test
    fun `saveMessage inserts new message when it does not exist`() =
        runTest {
            // Given: A new message that doesn't exist in the database
            val message =
                Message(
                    id = "msg_123",
                    destinationHash = TEST_PEER_HASH,
                    content = "Hello",
                    timestamp = 1000L,
                    isFromMe = false,
                    status = "delivered",
                )

            // When: Save the message
            repository.saveMessage(TEST_PEER_HASH, "Peer Name", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Message should exist in database with correct content
            val savedMessage = messageDao.getMessageById("msg_123", TEST_IDENTITY_HASH)
            assertNotNull("Message should be saved to database", savedMessage)
            assertEquals("Hello", savedMessage?.content)
            assertEquals(1000L, savedMessage?.timestamp)
        }

    @Test
    fun `saveMessage does NOT overwrite existing message - key deduplication test`() =
        runTest {
            // This is the CRITICAL test that validates deduplication behavior
            // It tests the actual production code path, not mock behavior

            val originalTimestamp = 1000L
            val replayTimestamp = 5000L // LXMF replay would have a different timestamp

            // Step 1: Save original message (simulating import)
            val originalMessage =
                Message(
                    id = "msg_dup_test",
                    destinationHash = TEST_PEER_HASH,
                    content = "Original Content",
                    timestamp = originalTimestamp,
                    isFromMe = false,
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", originalMessage, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify original was saved
            val afterFirstSave = messageDao.getMessageById("msg_dup_test", TEST_IDENTITY_HASH)
            assertNotNull("Original message should exist", afterFirstSave)
            assertEquals(originalTimestamp, afterFirstSave?.timestamp)

            // Step 2: Try to save same message ID with different timestamp (LXMF replay)
            val replayMessage =
                Message(
                    id = "msg_dup_test", // SAME ID
                    destinationHash = TEST_PEER_HASH,
                    content = "Replayed Content", // Different content (but same ID)
                    timestamp = replayTimestamp, // Different timestamp
                    isFromMe = false,
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", replayMessage, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Original message should be preserved (not overwritten)
            val afterReplay = messageDao.getMessageById("msg_dup_test", TEST_IDENTITY_HASH)
            assertEquals(
                "Original timestamp should be preserved",
                originalTimestamp,
                afterReplay?.timestamp,
            )
            assertEquals(
                "Original content should be preserved",
                "Original Content",
                afterReplay?.content,
            )

            // Verify only one message exists
            val allMessages = messageDao.getAllMessagesForIdentity(TEST_IDENTITY_HASH)
            assertEquals("Should have exactly 1 message (duplicate not inserted)", 1, allMessages.size)
        }

    @Test
    fun `saveMessage creates conversation when it does not exist`() =
        runTest {
            // Given: No conversation exists for this peer
            val peerHash = "new_peer_hash_1234567890123456"
            assertNull(
                "Precondition: conversation should not exist",
                conversationDao.getConversation(peerHash, TEST_IDENTITY_HASH),
            )

            val message =
                Message(
                    id = "msg_new_conv",
                    destinationHash = peerHash,
                    content = "First message",
                    timestamp = 1000L,
                    isFromMe = false,
                    status = "delivered",
                )

            // When: Save message to new conversation
            repository.saveMessage(peerHash, "New Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Conversation should be created
            val conversation = conversationDao.getConversation(peerHash, TEST_IDENTITY_HASH)
            assertNotNull("Conversation should be created", conversation)
            assertEquals("New Peer", conversation?.peerName)
            assertEquals("First message", conversation?.lastMessage)
            assertEquals(1, conversation?.unreadCount) // Received message = unread
        }

    @Test
    fun `saveMessage does not increment unread count for duplicate messages`() =
        runTest {
            // Setup: Create conversation with 5 unread
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Previous",
                    lastMessageTimestamp = 500L,
                    unreadCount = 5,
                    lastSeenTimestamp = 0L,
                ),
            )

            // Save original message
            val message =
                Message(
                    id = "msg_unread_test",
                    destinationHash = TEST_PEER_HASH,
                    content = "Hello",
                    timestamp = 1000L,
                    isFromMe = false,
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Unread should be 6 now (5 + 1 new message)
            val afterFirst = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(6, afterFirst?.unreadCount)

            // Try to save duplicate
            val duplicate = message.copy(timestamp = 9999L) // Same ID, different timestamp
            repository.saveMessage(TEST_PEER_HASH, "Peer", duplicate, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Unread should STILL be 6 (not 7)
            val afterDuplicate = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(
                "Unread count should not increment for duplicate",
                6,
                afterDuplicate?.unreadCount,
            )
        }

    @Test
    fun `saveMessage increments unread count for NEW received messages`() =
        runTest {
            // Setup: Create conversation with 2 unread
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Previous",
                    lastMessageTimestamp = 500L,
                    unreadCount = 2,
                    lastSeenTimestamp = 0L,
                ),
            )

            // Save new received message
            val message =
                Message(
                    id = "msg_new_${System.nanoTime()}",
                    destinationHash = TEST_PEER_HASH,
                    content = "New message",
                    timestamp = 1000L,
                    isFromMe = false, // RECEIVED message
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            val conversation = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(
                "Unread should increment for new received message",
                3,
                conversation?.unreadCount,
            )
        }

    @Test
    fun `saveMessage does NOT increment unread count for sent messages`() =
        runTest {
            // Setup: Create conversation with 2 unread
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Previous",
                    lastMessageTimestamp = 500L,
                    unreadCount = 2,
                    lastSeenTimestamp = 0L,
                ),
            )

            // Save sent message (isFromMe = true)
            val message =
                Message(
                    id = "msg_sent_${System.nanoTime()}",
                    destinationHash = TEST_PEER_HASH,
                    content = "My message",
                    timestamp = 1000L,
                    isFromMe = true, // SENT message
                    status = "pending",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            val conversation = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(
                "Unread should NOT increment for sent message",
                2,
                conversation?.unreadCount,
            )
        }

    // ========== Conversation Flow Tests ==========

    @Test
    fun `getConversations returns conversations for active identity`() =
        runTest {
            // Setup: Create a conversation
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Test Peer",
                    peerPublicKey = null,
                    lastMessage = "Hello",
                    lastMessageTimestamp = 1000L,
                    unreadCount = 1,
                    lastSeenTimestamp = 0L,
                ),
            )

            // When: Observe conversations
            repository.getConversations().test {
                val conversations = awaitItem()

                // Then: Should contain our conversation
                assertEquals(1, conversations.size)
                assertEquals(TEST_PEER_HASH, conversations[0].peerHash)
                assertEquals("Test Peer", conversations[0].peerName)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `getConversations returns empty when no active identity`() =
        runTest {
            // Setup: Deactivate the identity
            localIdentityDao.setActive("nonexistent_identity")

            // When: Observe conversations
            repository.getConversations().test {
                val conversations = awaitItem()

                // Then: Should be empty (no active identity)
                assertTrue("Should return empty list when no active identity", conversations.isEmpty())

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Mark as Read Tests ==========

    @Test
    fun `markConversationAsRead clears unread count`() =
        runTest {
            // Setup: Create conversation with unread messages
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Hello",
                    lastMessageTimestamp = 1000L,
                    unreadCount = 5,
                    lastSeenTimestamp = 0L,
                ),
            )

            // Verify precondition
            val before = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(5, before?.unreadCount)

            // When: Mark as read
            repository.markConversationAsRead(TEST_PEER_HASH)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Unread count should be 0
            val after = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals(0, after?.unreadCount)
        }

    // ========== Message Status Update Tests ==========

    @Test
    fun `updateMessageStatus changes message status correctly`() =
        runTest {
            // Setup: Save a message
            val message =
                Message(
                    id = "msg_status_test",
                    destinationHash = TEST_PEER_HASH,
                    content = "Test",
                    timestamp = 1000L,
                    isFromMe = true,
                    status = "pending",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify initial status
            val initial = messageDao.getMessageById("msg_status_test", TEST_IDENTITY_HASH)
            assertEquals("pending", initial?.status)

            // When: Update status
            repository.updateMessageStatus("msg_status_test", "delivered")
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Status should be updated
            val updated = messageDao.getMessageById("msg_status_test", TEST_IDENTITY_HASH)
            assertEquals("delivered", updated?.status)
        }

    // ========== Delete Conversation Tests ==========

    @Test
    fun `deleteConversation removes conversation and messages`() =
        runTest {
            // Setup: Create conversation with messages
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Hello",
                    lastMessageTimestamp = 1000L,
                    unreadCount = 0,
                    lastSeenTimestamp = 0L,
                ),
            )

            val message =
                Message(
                    id = "msg_to_delete",
                    destinationHash = TEST_PEER_HASH,
                    content = "Delete me",
                    timestamp = 1000L,
                    isFromMe = false,
                    status = "delivered",
                )
            repository.saveMessage(TEST_PEER_HASH, "Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify message exists
            assertTrue(messageDao.messageExists("msg_to_delete", TEST_IDENTITY_HASH))

            // When: Delete conversation
            repository.deleteConversation(TEST_PEER_HASH)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Conversation should be deleted
            assertNull(conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH))

            // And messages should be cascade-deleted (via FK constraint)
            assertFalse(messageDao.messageExists("msg_to_delete", TEST_IDENTITY_HASH))
        }

    // ========== Content Sanitization Tests ==========

    @Test
    fun `saveMessage sanitizes message content`() =
        runTest {
            // Given: Message with control characters
            val message =
                Message(
                    id = "msg_sanitize",
                    destinationHash = TEST_PEER_HASH,
                    content = "Hello\u0000World\u001FTest", // Contains null and other control chars
                    timestamp = 1000L,
                    isFromMe = false,
                    status = "delivered",
                )

            // When: Save message
            repository.saveMessage(TEST_PEER_HASH, "Peer", message, null)
            testDispatcher.scheduler.advanceUntilIdle()

            // Then: Control characters should be removed
            val saved = messageDao.getMessageById("msg_sanitize", TEST_IDENTITY_HASH)
            assertNotNull(saved)
            assertFalse("Content should not contain null char", saved!!.content.contains('\u0000'))
            assertFalse("Content should not contain control chars", saved.content.contains('\u001F'))
        }
}
