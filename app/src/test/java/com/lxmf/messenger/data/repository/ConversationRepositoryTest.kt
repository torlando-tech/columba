package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ConversationRepository.
 * Tests message saving behavior, particularly that existing messages are not overwritten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryTest {
    private lateinit var repository: ConversationRepository
    private lateinit var mockMessageDao: MessageDao
    private lateinit var mockConversationDao: ConversationDao
    private lateinit var mockLocalIdentityDao: LocalIdentityDao
    private lateinit var mockPeerIdentityDao: PeerIdentityDao
    private val testDispatcher = StandardTestDispatcher()

    private val testIdentityHash = "test_identity_hash"
    private val testPeerHash = "test_peer_hash"

    private val testIdentity = LocalIdentityEntity(
        identityHash = testIdentityHash,
        displayName = "Test Identity",
        destinationHash = "dest_hash",
        filePath = "/path/to/identity",
        createdTimestamp = 1000L,
        lastUsedTimestamp = 2000L,
        isActive = true,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockMessageDao = mockk(relaxed = true)
        mockConversationDao = mockk(relaxed = true)
        mockLocalIdentityDao = mockk(relaxed = true)
        mockPeerIdentityDao = mockk(relaxed = true)

        // Default: active identity exists
        every { mockLocalIdentityDao.getActiveIdentity() } returns flowOf(testIdentity)
        coEvery { mockLocalIdentityDao.getActiveIdentitySync() } returns testIdentity

        repository = ConversationRepository(
            messageDao = mockMessageDao,
            conversationDao = mockConversationDao,
            localIdentityDao = mockLocalIdentityDao,
            peerIdentityDao = mockPeerIdentityDao,
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Message Save Tests ==========

    @Test
    fun `saveMessage inserts new message when it does not exist`() = runTest {
        // Given: Message does not exist in database
        coEvery { mockMessageDao.messageExists("msg_123", testIdentityHash) } returns false
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns null

        val message = Message(
            id = "msg_123",
            destinationHash = testPeerHash,
            content = "Hello",
            timestamp = 1000L,
            isFromMe = false,
            status = "delivered",
        )

        // When
        repository.saveMessage(testPeerHash, "Peer Name", message, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Message should be inserted
        coVerify(exactly = 1) { mockMessageDao.insertMessage(any()) }
    }

    @Test
    fun `saveMessage does not overwrite existing message`() = runTest {
        // Given: Message ALREADY exists in database (e.g., from import)
        coEvery { mockMessageDao.messageExists("msg_123", testIdentityHash) } returns true
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns
            ConversationEntity(
                peerHash = testPeerHash,
                identityHash = testIdentityHash,
                peerName = "Peer Name",
                peerPublicKey = null,
                lastMessage = "Previous message",
                lastMessageTimestamp = 500L,
                unreadCount = 0,
                lastSeenTimestamp = 0L,
            )

        val message = Message(
            id = "msg_123",
            destinationHash = testPeerHash,
            content = "Hello",
            timestamp = 2000L, // Different timestamp (LXMF replay would use current time)
            isFromMe = false,
            status = "delivered",
        )

        // When: LXMF replays the message with a new timestamp
        repository.saveMessage(testPeerHash, "Peer Name", message, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Message should NOT be inserted (preserves original timestamp)
        coVerify(exactly = 0) { mockMessageDao.insertMessage(any()) }
    }

    @Test
    fun `saveMessage updates conversation even when message exists`() = runTest {
        // Given: Message exists but conversation should still be updated
        coEvery { mockMessageDao.messageExists("msg_123", testIdentityHash) } returns true
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns
            ConversationEntity(
                peerHash = testPeerHash,
                identityHash = testIdentityHash,
                peerName = "Old Name",
                peerPublicKey = null,
                lastMessage = "Previous message",
                lastMessageTimestamp = 500L,
                unreadCount = 0,
                lastSeenTimestamp = 0L,
            )

        val message = Message(
            id = "msg_123",
            destinationHash = testPeerHash,
            content = "Hello",
            timestamp = 2000L,
            isFromMe = false,
            status = "delivered",
        )

        // When
        repository.saveMessage(testPeerHash, "New Name", message, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Conversation should still be updated (e.g., peer name change)
        coVerify(exactly = 1) { mockConversationDao.updateConversation(any()) }
    }

    @Test
    fun `saveMessage does not increment unread count for duplicate messages`() = runTest {
        // Given: Message already exists
        coEvery { mockMessageDao.messageExists("msg_123", testIdentityHash) } returns true
        val existingConversation = ConversationEntity(
            peerHash = testPeerHash,
            identityHash = testIdentityHash,
            peerName = "Peer Name",
            peerPublicKey = null,
            lastMessage = "Previous message",
            lastMessageTimestamp = 500L,
            unreadCount = 5, // Existing unread count
            lastSeenTimestamp = 0L,
        )
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns existingConversation

        val message = Message(
            id = "msg_123",
            destinationHash = testPeerHash,
            content = "Hello",
            timestamp = 2000L,
            isFromMe = false,
            status = "delivered",
        )

        // When
        repository.saveMessage(testPeerHash, "Peer Name", message, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Unread count should remain unchanged (not incremented for duplicate)
        coVerify {
            mockConversationDao.updateConversation(match { it.unreadCount == 5 })
        }
    }

    @Test
    fun `saveMessage increments unread count for new received messages`() = runTest {
        // Given: Message does NOT exist
        coEvery { mockMessageDao.messageExists("msg_new", testIdentityHash) } returns false
        val existingConversation = ConversationEntity(
            peerHash = testPeerHash,
            identityHash = testIdentityHash,
            peerName = "Peer Name",
            peerPublicKey = null,
            lastMessage = "Previous message",
            lastMessageTimestamp = 500L,
            unreadCount = 5,
            lastSeenTimestamp = 0L,
        )
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns existingConversation

        val message = Message(
            id = "msg_new",
            destinationHash = testPeerHash,
            content = "New message",
            timestamp = 2000L,
            isFromMe = false, // Received message
            status = "delivered",
        )

        // When
        repository.saveMessage(testPeerHash, "Peer Name", message, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then: Unread count should be incremented
        coVerify {
            mockConversationDao.updateConversation(match { it.unreadCount == 6 })
        }
    }

    @Test
    fun `saveMessage preserves timestamp order after import and replay`() = runTest {
        // This test simulates the import + LXMF replay scenario
        // 1. First, a message is imported with original timestamp
        // 2. Then, LXMF replays the same message with a new timestamp
        // The original timestamp should be preserved

        val originalTimestamp = 1000L
        val replayTimestamp = 5000L // Much later (simulating System.currentTimeMillis())

        // First call: Message is new (imported)
        coEvery { mockMessageDao.messageExists("msg_imported", testIdentityHash) } returns false
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns null

        val importedMessage = Message(
            id = "msg_imported",
            destinationHash = testPeerHash,
            content = "Hello",
            timestamp = originalTimestamp,
            isFromMe = false,
            status = "delivered",
        )

        repository.saveMessage(testPeerHash, "Peer", importedMessage, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify first insert happened
        coVerify(exactly = 1) {
            mockMessageDao.insertMessage(match { it.timestamp == originalTimestamp })
        }

        // Second call: Message now exists (LXMF replay)
        coEvery { mockMessageDao.messageExists("msg_imported", testIdentityHash) } returns true
        coEvery { mockConversationDao.getConversation(testPeerHash, testIdentityHash) } returns
            ConversationEntity(
                peerHash = testPeerHash,
                identityHash = testIdentityHash,
                peerName = "Peer",
                peerPublicKey = null,
                lastMessage = "Hello",
                lastMessageTimestamp = originalTimestamp,
                unreadCount = 1,
                lastSeenTimestamp = 0L,
            )

        val replayedMessage = Message(
            id = "msg_imported", // Same ID
            destinationHash = testPeerHash,
            content = "Hello",
            timestamp = replayTimestamp, // New timestamp from LXMF replay
            isFromMe = false,
            status = "delivered",
        )

        repository.saveMessage(testPeerHash, "Peer", replayedMessage, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify NO second insert (original timestamp preserved)
        coVerify(exactly = 1) { mockMessageDao.insertMessage(any()) }
    }
}
