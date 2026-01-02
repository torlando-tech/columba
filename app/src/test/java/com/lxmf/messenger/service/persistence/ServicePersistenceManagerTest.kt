package com.lxmf.messenger.service.persistence

import android.content.Context
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.service.di.ServiceDatabaseProvider
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServicePersistenceManager.
 *
 * Tests persistence of announces and messages from the service process,
 * including de-duplication, identity scoping, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServicePersistenceManagerTest {
    private lateinit var context: Context
    private lateinit var testScope: TestScope
    private lateinit var database: ColumbaDatabase
    private lateinit var announceDao: AnnounceDao
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var localIdentityDao: LocalIdentityDao
    private lateinit var peerIdentityDao: PeerIdentityDao
    private lateinit var persistenceManager: ServicePersistenceManager

    private val testDestinationHash = "0102030405060708"
    private val testPublicKey = ByteArray(32) { it.toByte() }
    private val testAppData = "Test App Data".toByteArray()
    private val testIdentityHash = "test_identity_hash"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        testScope = TestScope(UnconfinedTestDispatcher())
        database = mockk(relaxed = true)
        announceDao = mockk(relaxed = true)
        messageDao = mockk(relaxed = true)
        conversationDao = mockk(relaxed = true)
        localIdentityDao = mockk(relaxed = true)
        peerIdentityDao = mockk(relaxed = true)

        // Mock database DAOs
        every { database.announceDao() } returns announceDao
        every { database.messageDao() } returns messageDao
        every { database.conversationDao() } returns conversationDao
        every { database.localIdentityDao() } returns localIdentityDao
        every { database.peerIdentityDao() } returns peerIdentityDao

        // Mock ServiceDatabaseProvider singleton
        mockkObject(ServiceDatabaseProvider)
        every { ServiceDatabaseProvider.getDatabase(any()) } returns database

        persistenceManager = ServicePersistenceManager(context, testScope)
    }

    @After
    fun tearDown() {
        unmockkObject(ServiceDatabaseProvider)
        clearAllMocks()
    }

    // ========== persistAnnounce() Tests ==========

    @Test
    fun `persistAnnounce saves new announce to database`() = runTest {
        coEvery { announceDao.getAnnounce(testDestinationHash) } returns null
        coEvery { announceDao.upsertAnnounce(any()) } just Runs

        persistenceManager.persistAnnounce(
            destinationHash = testDestinationHash,
            peerName = "Test Peer",
            publicKey = testPublicKey,
            appData = testAppData,
            hops = 2,
            timestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = "BLE",
            receivingInterfaceType = "BLE",
            aspect = "lxmf.delivery",
            stampCost = null,
            stampCostFlexibility = null,
            peeringCost = null,
            iconName = null,
            iconForegroundColor = null,
            iconBackgroundColor = null,
        )

        testScope.advanceUntilIdle()

        coVerify { announceDao.upsertAnnounce(any()) }
    }

    @Test
    fun `persistAnnounce preserves existing favorite status`() = runTest {
        val existingAnnounce = AnnounceEntity(
            destinationHash = testDestinationHash,
            peerName = "Old Name",
            publicKey = testPublicKey,
            appData = null,
            hops = 1,
            lastSeenTimestamp = System.currentTimeMillis() - 10000,
            nodeType = "LXMF_PEER",
            receivingInterface = "BLE",
            isFavorite = true,
            favoritedTimestamp = System.currentTimeMillis() - 5000,
        )

        coEvery { announceDao.getAnnounce(testDestinationHash) } returns existingAnnounce
        coEvery { announceDao.upsertAnnounce(any()) } just Runs

        persistenceManager.persistAnnounce(
            destinationHash = testDestinationHash,
            peerName = "New Name",
            publicKey = testPublicKey,
            appData = testAppData,
            hops = 3,
            timestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = null,
            receivingInterfaceType = null,
            aspect = null,
            stampCost = null,
            stampCostFlexibility = null,
            peeringCost = null,
            iconName = null,
            iconForegroundColor = null,
            iconBackgroundColor = null,
        )

        testScope.advanceUntilIdle()

        coVerify {
            announceDao.upsertAnnounce(match { entity ->
                entity.isFavorite && entity.peerName == "New Name"
            })
        }
    }

    @Test
    fun `persistAnnounce preserves existing icon appearance when not provided`() = runTest {
        val existingAnnounce = AnnounceEntity(
            destinationHash = testDestinationHash,
            peerName = "Test Peer",
            publicKey = testPublicKey,
            appData = null,
            hops = 1,
            lastSeenTimestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = "BLE",
            iconName = "home",
            iconForegroundColor = "#FFFFFF",
            iconBackgroundColor = "#000000",
        )

        coEvery { announceDao.getAnnounce(testDestinationHash) } returns existingAnnounce
        coEvery { announceDao.upsertAnnounce(any()) } just Runs

        persistenceManager.persistAnnounce(
            destinationHash = testDestinationHash,
            peerName = "Test Peer",
            publicKey = testPublicKey,
            appData = null,
            hops = 2,
            timestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = null,
            receivingInterfaceType = null,
            aspect = null,
            stampCost = null,
            stampCostFlexibility = null,
            peeringCost = null,
            iconName = null,
            iconForegroundColor = null,
            iconBackgroundColor = null,
        )

        testScope.advanceUntilIdle()

        coVerify {
            announceDao.upsertAnnounce(match { entity ->
                entity.iconName == "home" &&
                    entity.iconForegroundColor == "#FFFFFF" &&
                    entity.iconBackgroundColor == "#000000"
            })
        }
    }

    @Test
    fun `persistAnnounce updates icon appearance when provided`() = runTest {
        val existingAnnounce = AnnounceEntity(
            destinationHash = testDestinationHash,
            peerName = "Test Peer",
            publicKey = testPublicKey,
            appData = null,
            hops = 1,
            lastSeenTimestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = "BLE",
            iconName = "home",
            iconForegroundColor = "#FFFFFF",
            iconBackgroundColor = "#000000",
        )

        coEvery { announceDao.getAnnounce(testDestinationHash) } returns existingAnnounce
        coEvery { announceDao.upsertAnnounce(any()) } just Runs

        persistenceManager.persistAnnounce(
            destinationHash = testDestinationHash,
            peerName = "Test Peer",
            publicKey = testPublicKey,
            appData = null,
            hops = 2,
            timestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = null,
            receivingInterfaceType = null,
            aspect = null,
            stampCost = null,
            stampCostFlexibility = null,
            peeringCost = null,
            iconName = "work",
            iconForegroundColor = "#FF0000",
            iconBackgroundColor = "#00FF00",
        )

        testScope.advanceUntilIdle()

        coVerify {
            announceDao.upsertAnnounce(match { entity ->
                entity.iconName == "work" &&
                    entity.iconForegroundColor == "#FF0000" &&
                    entity.iconBackgroundColor == "#00FF00"
            })
        }
    }

    @Test
    fun `persistAnnounce handles database exception gracefully`() = runTest {
        coEvery { announceDao.getAnnounce(any()) } throws RuntimeException("Database error")

        // Should not throw
        persistenceManager.persistAnnounce(
            destinationHash = testDestinationHash,
            peerName = "Test Peer",
            publicKey = testPublicKey,
            appData = null,
            hops = 1,
            timestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = null,
            receivingInterfaceType = null,
            aspect = null,
            stampCost = null,
            stampCostFlexibility = null,
            peeringCost = null,
            iconName = null,
            iconForegroundColor = null,
            iconBackgroundColor = null,
        )

        testScope.advanceUntilIdle()

        // Verify exception was handled (no crash)
    }

    // ========== persistPeerIdentity() Tests ==========

    @Test
    fun `persistPeerIdentity saves peer identity to database`() = runTest {
        coEvery { peerIdentityDao.insertPeerIdentity(any()) } just Runs

        persistenceManager.persistPeerIdentity(testDestinationHash, testPublicKey)

        testScope.advanceUntilIdle()

        coVerify { peerIdentityDao.insertPeerIdentity(any()) }
    }

    @Test
    fun `persistPeerIdentity handles exception gracefully`() = runTest {
        coEvery { peerIdentityDao.insertPeerIdentity(any()) } throws RuntimeException("Insert error")

        // Should not throw
        persistenceManager.persistPeerIdentity(testDestinationHash, testPublicKey)

        testScope.advanceUntilIdle()
    }

    // ========== persistMessage() Tests ==========

    @Test
    fun `persistMessage saves new message to database`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById(any(), any()) } returns null
        coEvery { conversationDao.getConversation(any(), any()) } returns null
        coEvery { conversationDao.insertConversation(any()) } just Runs
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { peerIdentityDao.insertPeerIdentity(any()) } just Runs

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello, world!",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = testPublicKey,
            replyToMessageId = null,
            deliveryMethod = "direct",
        )

        testScope.advanceUntilIdle()

        coVerify { messageDao.insertMessage(any()) }
        coVerify { conversationDao.insertConversation(any()) }
    }

    @Test
    fun `persistMessage updates existing conversation`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        val existingConversation = ConversationEntity(
            peerHash = "sender_hash",
            identityHash = testIdentityHash,
            peerName = "Sender",
            peerPublicKey = null,
            lastMessage = "Previous message",
            lastMessageTimestamp = System.currentTimeMillis() - 10000,
            unreadCount = 2,
            lastSeenTimestamp = 0,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById(any(), any()) } returns null
        coEvery { conversationDao.getConversation("sender_hash", testIdentityHash) } returns existingConversation
        coEvery { conversationDao.updateConversation(any()) } just Runs
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { peerIdentityDao.insertPeerIdentity(any()) } just Runs

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "New message",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = testPublicKey,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()

        coVerify {
            conversationDao.updateConversation(match { conv ->
                conv.unreadCount == 3 && conv.lastMessage == "New message"
            })
        }
    }

    @Test
    fun `persistMessage skips duplicate message`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        val existingMessage = MessageEntity(
            id = "test_message_hash",
            conversationHash = "sender_hash",
            identityHash = testIdentityHash,
            content = "Existing message",
            timestamp = System.currentTimeMillis(),
            isFromMe = false,
            status = "delivered",
            isRead = false,
            fieldsJson = null,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById("test_message_hash", testIdentityHash) } returns existingMessage

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()

        // Should NOT insert new message (duplicate)
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `persistMessage skips when no active identity`() = runTest {
        coEvery { localIdentityDao.getActiveIdentitySync() } returns null

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()

        // Should NOT attempt to insert message
        coVerify(exactly = 0) { messageDao.insertMessage(any()) }
    }

    @Test
    fun `persistMessage stores peer public key when provided`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById(any(), any()) } returns null
        coEvery { conversationDao.getConversation(any(), any()) } returns null
        coEvery { conversationDao.insertConversation(any()) } just Runs
        coEvery { messageDao.insertMessage(any()) } just Runs
        coEvery { peerIdentityDao.insertPeerIdentity(any()) } just Runs

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = testPublicKey,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()

        coVerify { peerIdentityDao.insertPeerIdentity(any()) }
    }

    @Test
    fun `persistMessage does not store peer identity when no public key`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById(any(), any()) } returns null
        coEvery { conversationDao.getConversation(any(), any()) } returns null
        coEvery { conversationDao.insertConversation(any()) } just Runs
        coEvery { messageDao.insertMessage(any()) } just Runs

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()

        coVerify(exactly = 0) { peerIdentityDao.insertPeerIdentity(any()) }
    }

    @Test
    fun `persistMessage handles exception gracefully`() = runTest {
        coEvery { localIdentityDao.getActiveIdentitySync() } throws RuntimeException("Database error")

        // Should not throw
        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello",
            sourceHash = "sender_hash",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()
    }

    @Test
    fun `persistMessage creates new conversation with formatted peer name`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById(any(), any()) } returns null
        coEvery { conversationDao.getConversation(any(), any()) } returns null
        coEvery { conversationDao.insertConversation(any()) } just Runs
        coEvery { messageDao.insertMessage(any()) } just Runs

        persistenceManager.persistMessage(
            messageHash = "test_message_hash",
            content = "Hello",
            sourceHash = "abcdef12345678",
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null,
            replyToMessageId = null,
            deliveryMethod = null,
        )

        testScope.advanceUntilIdle()

        coVerify {
            conversationDao.insertConversation(match { conv ->
                conv.peerName == "Peer ABCDEF12"
            })
        }
    }

    // ========== announceExists() Tests ==========

    @Test
    fun `announceExists returns true when announce exists`() = runTest {
        coEvery { announceDao.announceExists(testDestinationHash) } returns true

        val result = persistenceManager.announceExists(testDestinationHash)

        assertTrue(result)
    }

    @Test
    fun `announceExists returns false when announce does not exist`() = runTest {
        coEvery { announceDao.announceExists(testDestinationHash) } returns false

        val result = persistenceManager.announceExists(testDestinationHash)

        assertFalse(result)
    }

    @Test
    fun `announceExists returns false on exception`() = runTest {
        coEvery { announceDao.announceExists(any()) } throws RuntimeException("Database error")

        val result = persistenceManager.announceExists(testDestinationHash)

        assertFalse(result)
    }

    // ========== messageExists() Tests ==========

    @Test
    fun `messageExists returns true when message exists`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        val existingMessage = MessageEntity(
            id = "test_message_hash",
            conversationHash = "sender_hash",
            identityHash = testIdentityHash,
            content = "Test",
            timestamp = System.currentTimeMillis(),
            isFromMe = false,
            status = "delivered",
            isRead = false,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById("test_message_hash", testIdentityHash) } returns existingMessage

        val result = persistenceManager.messageExists("test_message_hash")

        assertTrue(result)
    }

    @Test
    fun `messageExists returns false when message does not exist`() = runTest {
        val activeIdentity = LocalIdentityEntity(
            identityHash = testIdentityHash,
            displayName = "Test",
            destinationHash = "dest_hash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

        coEvery { localIdentityDao.getActiveIdentitySync() } returns activeIdentity
        coEvery { messageDao.getMessageById(any(), any()) } returns null

        val result = persistenceManager.messageExists("test_message_hash")

        assertFalse(result)
    }

    @Test
    fun `messageExists returns false when no active identity`() = runTest {
        coEvery { localIdentityDao.getActiveIdentitySync() } returns null

        val result = persistenceManager.messageExists("test_message_hash")

        assertFalse(result)
    }

    @Test
    fun `messageExists returns false on exception`() = runTest {
        coEvery { localIdentityDao.getActiveIdentitySync() } throws RuntimeException("Database error")

        val result = persistenceManager.messageExists("test_message_hash")

        assertFalse(result)
    }

    // ========== close() Tests ==========

    @Test
    fun `close calls ServiceDatabaseProvider close`() {
        every { ServiceDatabaseProvider.close() } just Runs

        persistenceManager.close()

        io.mockk.verify { ServiceDatabaseProvider.close() }
    }
}
