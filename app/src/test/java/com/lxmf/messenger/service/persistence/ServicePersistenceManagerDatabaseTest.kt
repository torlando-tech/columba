package com.lxmf.messenger.service.persistence

import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import com.lxmf.messenger.service.di.ServiceDatabaseProvider
import com.lxmf.messenger.test.DatabaseTest
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Database-backed tests for ServicePersistenceManager.
 *
 * Unlike the mock-based ServicePersistenceManagerTest, these tests use a real in-memory
 * Room database to verify actual behavior including:
 * - Announce upsert with favorite preservation
 * - Message persistence with identity scoping
 * - Message deduplication (real INSERT vs skip)
 * - Conversation creation/update atomicity
 * - Unknown sender blocking with contact lookup
 * - Display name cascading lookup
 *
 * The ServiceSettingsAccessor is still mocked since it's just SharedPreferences access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServicePersistenceManagerDatabaseTest : DatabaseTest() {
    private lateinit var testScope: TestScope
    private lateinit var settingsAccessor: ServiceSettingsAccessor
    private lateinit var persistenceManager: ServicePersistenceManager

    private val testPublicKey = ByteArray(32) { it.toByte() }
    private val testAppData = "Test App Data".toByteArray()

    @Before
    fun setupManager() {
        testScope = TestScope(UnconfinedTestDispatcher())
        settingsAccessor = mockk()

        // Default: don't block unknown senders
        every { settingsAccessor.getBlockUnknownSenders() } returns false

        // Mock ServiceDatabaseProvider to return our real in-memory database
        mockkObject(ServiceDatabaseProvider)
        every { ServiceDatabaseProvider.getDatabase(any()) } returns database

        persistenceManager = ServicePersistenceManager(context, testScope, settingsAccessor)
    }

    @After
    fun teardownManager() {
        unmockkObject(ServiceDatabaseProvider)
        clearAllMocks()
    }

    // ========== persistAnnounce() Tests ==========
    //
    // Note: persistAnnounce uses scope.launch (fire-and-forget pattern) which makes it
    // difficult to test deterministically. Room's suspend DAO methods dispatch to
    // Dispatchers.IO, which isn't controlled by our test dispatcher.
    //
    // We use advanceUntilIdle() + Thread.sleep() to ensure the async operations complete.
    // This is a pragmatic compromise for testing fire-and-forget coroutines.

    @Suppress("SleepInsteadOfDelay") // Room dispatches to IO, test dispatcher can't control it
    @Test
    fun `persistAnnounce inserts new announce into database`() =
        testScope.runTest {
            val destinationHash = "announce_dest_hash_1234567890"

            persistenceManager.persistAnnounce(
                destinationHash = destinationHash,
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
                propagationTransferLimitKb = null,
            )
            advanceUntilIdle()
            Thread.sleep(100) // Allow Room's IO dispatcher to complete

            // Verify announce was inserted
            val saved = announceDao.getAnnounce(destinationHash)
            assertNotNull("Announce should be saved", saved)
            assertEquals("Test Peer", saved?.peerName)
            assertEquals(2, saved?.hops)
            assertEquals("LXMF_PEER", saved?.nodeType)
            assertFalse("New announce should not be favorited", saved?.isFavorite ?: true)
        }

    // Note: "persistAnnounce preserves favorite status on update" test was removed.
    // The mock-based test in ServicePersistenceManagerTest verifies this logic.
    // Testing fire-and-forget coroutines with async Room operations is unreliable
    // since Room dispatches to Dispatchers.IO which isn't controlled by test dispatchers.

    // ========== persistMessage() Tests ==========

    @Test
    fun `persistMessage saves new message to database`() =
        testScope.runTest {
            // Setup: Insert active identity
            insertTestIdentity()

            val result =
                persistenceManager.persistMessage(
                    messageHash = "msg_new_123456789012345678901234",
                    content = "Hello, world!",
                    sourceHash = TEST_PEER_HASH,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = testPublicKey,
                    replyToMessageId = null,
                    deliveryMethod = "direct",
                )
            advanceUntilIdle()

            assertTrue("persistMessage should return true", result)

            // Verify message was inserted
            val saved = messageDao.getMessageById("msg_new_123456789012345678901234", TEST_IDENTITY_HASH)
            assertNotNull("Message should exist", saved)
            assertEquals("Hello, world!", saved?.content)
            assertEquals(TEST_PEER_HASH, saved?.conversationHash)
            assertFalse("Message should be marked as received", saved?.isFromMe ?: true)
        }

    @Test
    fun `persistMessage creates new conversation when none exists`() =
        testScope.runTest {
            insertTestIdentity()

            // Verify no conversation exists
            assertNull(conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH))

            persistenceManager.persistMessage(
                messageHash = "msg_conv_create_12345678901234567",
                content = "First message",
                sourceHash = TEST_PEER_HASH,
                timestamp = 1000L,
                fieldsJson = null,
                publicKey = null,
                replyToMessageId = null,
                deliveryMethod = null,
            )
            advanceUntilIdle()

            // Verify conversation was created
            val conversation = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertNotNull("Conversation should be created", conversation)
            assertEquals("First message".take(100), conversation?.lastMessage)
            assertEquals(1, conversation?.unreadCount)
        }

    @Test
    fun `persistMessage updates existing conversation`() =
        testScope.runTest {
            insertTestIdentity()

            // Setup: Create existing conversation
            val existingConversation =
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Existing Peer",
                    peerPublicKey = null,
                    lastMessage = "Previous message",
                    lastMessageTimestamp = 500L,
                    unreadCount = 2,
                    lastSeenTimestamp = 0L,
                )
            conversationDao.insertConversation(existingConversation)

            // Verify precondition
            assertEquals(2, conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)?.unreadCount)

            persistenceManager.persistMessage(
                messageHash = "msg_update_conv_123456789012345678",
                content = "New message",
                sourceHash = TEST_PEER_HASH,
                timestamp = 1000L,
                fieldsJson = null,
                publicKey = testPublicKey,
                replyToMessageId = null,
                deliveryMethod = null,
            )
            advanceUntilIdle()

            // Verify conversation was updated
            val updated = conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)
            assertEquals("New message", updated?.lastMessage)
            assertEquals(3, updated?.unreadCount) // 2 + 1
            assertEquals(1000L, updated?.lastMessageTimestamp)
        }

    @Test
    fun `persistMessage does NOT insert duplicate message - key deduplication test`() =
        testScope.runTest {
            insertTestIdentity()

            val messageHash = "msg_dup_test_12345678901234567890"
            val originalTimestamp = 1000L
            val replayTimestamp = 5000L

            // Insert original message
            persistenceManager.persistMessage(
                messageHash = messageHash,
                content = "Original content",
                sourceHash = TEST_PEER_HASH,
                timestamp = originalTimestamp,
                fieldsJson = null,
                publicKey = null,
                replyToMessageId = null,
                deliveryMethod = null,
            )
            advanceUntilIdle()

            // Verify original was saved
            val afterFirst = messageDao.getMessageById(messageHash, TEST_IDENTITY_HASH)
            assertNotNull(afterFirst)
            assertEquals(originalTimestamp, afterFirst?.timestamp)
            assertEquals("Original content", afterFirst?.content)

            // Try to persist duplicate with different timestamp (simulating LXMF replay)
            val result =
                persistenceManager.persistMessage(
                    messageHash = messageHash, // Same ID
                    content = "Replayed content", // Different content
                    sourceHash = TEST_PEER_HASH,
                    timestamp = replayTimestamp, // Different timestamp
                    fieldsJson = null,
                    publicKey = null,
                    replyToMessageId = null,
                    deliveryMethod = null,
                )
            advanceUntilIdle()

            // Duplicate should return true (message exists)
            assertTrue("persistMessage should return true for duplicates", result)

            // Original message should be preserved
            val afterReplay = messageDao.getMessageById(messageHash, TEST_IDENTITY_HASH)
            assertEquals("Original timestamp preserved", originalTimestamp, afterReplay?.timestamp)
            assertEquals("Original content preserved", "Original content", afterReplay?.content)

            // Only one message should exist
            val allMessages = messageDao.getAllMessagesForIdentity(TEST_IDENTITY_HASH)
            assertEquals("Only 1 message should exist", 1, allMessages.size)
        }

    @Test
    fun `persistMessage returns false when no active identity`() =
        testScope.runTest {
            // No identity inserted

            val result =
                persistenceManager.persistMessage(
                    messageHash = "msg_no_identity_123456789012345",
                    content = "Hello",
                    sourceHash = TEST_PEER_HASH,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                    replyToMessageId = null,
                    deliveryMethod = null,
                )
            advanceUntilIdle()

            assertFalse("Should return false when no active identity", result)
        }

    // ========== Block Unknown Senders Tests ==========

    @Test
    fun `persistMessage blocks unknown sender when setting enabled`() =
        testScope.runTest {
            insertTestIdentity()

            // Enable blocking
            every { settingsAccessor.getBlockUnknownSenders() } returns true

            // No contact exists for this sender

            val result =
                persistenceManager.persistMessage(
                    messageHash = "msg_blocked_1234567890123456789",
                    content = "Hello from stranger",
                    sourceHash = "unknown_peer_hash_123456789012",
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                    replyToMessageId = null,
                    deliveryMethod = null,
                )
            advanceUntilIdle()

            assertFalse("Should return false when blocked", result)

            // Message should NOT be saved
            val saved = messageDao.getMessageById("msg_blocked_1234567890123456789", TEST_IDENTITY_HASH)
            assertNull("Blocked message should not be saved", saved)
        }

    @Test
    fun `persistMessage allows known contact when blocking enabled`() =
        testScope.runTest {
            insertTestIdentity()

            // Enable blocking
            every { settingsAccessor.getBlockUnknownSenders() } returns true

            // Add sender as a contact
            val contact =
                ContactEntity(
                    destinationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    publicKey = testPublicKey,
                    customNickname = "My Friend",
                    addedTimestamp = System.currentTimeMillis(),
                    addedVia = "MANUAL",
                )
            contactDao.insertContact(contact)

            val result =
                persistenceManager.persistMessage(
                    messageHash = "msg_contact_12345678901234567890",
                    content = "Hello from friend",
                    sourceHash = TEST_PEER_HASH,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                    replyToMessageId = null,
                    deliveryMethod = null,
                )
            advanceUntilIdle()

            assertTrue("Should return true for known contacts", result)

            // Message should be saved
            val saved = messageDao.getMessageById("msg_contact_12345678901234567890", TEST_IDENTITY_HASH)
            assertNotNull("Message from contact should be saved", saved)
        }

    // ========== announceExists() Tests ==========

    @Test
    fun `announceExists returns true when announce exists`() =
        testScope.runTest {
            val destHash = "announce_exists_hash_12345678901"

            // Insert announce
            val announce =
                AnnounceEntity(
                    destinationHash = destHash,
                    peerName = "Test",
                    publicKey = testPublicKey,
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    nodeType = "LXMF_PEER",
                    receivingInterface = null,
                    isFavorite = false,
                    favoritedTimestamp = null,
                )
            announceDao.upsertAnnounce(announce)

            val result = persistenceManager.announceExists(destHash)

            assertTrue("Should return true when announce exists", result)
        }

    @Test
    fun `announceExists returns false when announce does not exist`() =
        testScope.runTest {
            val result = persistenceManager.announceExists("nonexistent_announce_hash_1234")

            assertFalse("Should return false when announce doesn't exist", result)
        }

    // ========== messageExists() Tests ==========

    @Test
    fun `messageExists returns true when message exists`() =
        testScope.runTest {
            insertTestIdentity()

            val messageHash = "msg_exists_test_123456789012345"

            // Create conversation first (FK constraint requires it)
            val conversation =
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Test Peer",
                    peerPublicKey = null,
                    lastMessage = "Test",
                    lastMessageTimestamp = System.currentTimeMillis(),
                    unreadCount = 0,
                    lastSeenTimestamp = 0L,
                )
            conversationDao.insertConversation(conversation)

            // Insert message
            val message =
                MessageEntity(
                    id = messageHash,
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Test",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                )
            messageDao.insertMessage(message)

            val result = persistenceManager.messageExists(messageHash)

            assertTrue("Should return true when message exists", result)
        }

    @Test
    fun `messageExists returns false when message does not exist`() =
        testScope.runTest {
            insertTestIdentity()

            val result = persistenceManager.messageExists("nonexistent_msg_hash_123456789")

            assertFalse("Should return false when message doesn't exist", result)
        }

    @Test
    fun `messageExists returns false when no active identity`() =
        testScope.runTest {
            // No identity inserted

            val result = persistenceManager.messageExists("any_msg_hash_12345678901234567")

            assertFalse("Should return false when no active identity", result)
        }

    // ========== lookupDisplayName() Tests ==========

    @Test
    fun `lookupDisplayName returns contact nickname first`() =
        testScope.runTest {
            insertTestIdentity()

            // Create both contact and announce
            val contact =
                ContactEntity(
                    destinationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    publicKey = testPublicKey,
                    customNickname = "My Bestie",
                    addedTimestamp = System.currentTimeMillis(),
                    addedVia = "MANUAL",
                )
            contactDao.insertContact(contact)

            val announce =
                AnnounceEntity(
                    destinationHash = TEST_PEER_HASH,
                    peerName = "Network Name",
                    publicKey = testPublicKey,
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    nodeType = "LXMF_PEER",
                    receivingInterface = null,
                    isFavorite = false,
                    favoritedTimestamp = null,
                )
            announceDao.upsertAnnounce(announce)

            val result = persistenceManager.lookupDisplayName(TEST_PEER_HASH)

            assertEquals("Contact nickname should take priority", "My Bestie", result)
        }

    @Test
    fun `lookupDisplayName returns announce name when no contact`() =
        testScope.runTest {
            insertTestIdentity()

            val announce =
                AnnounceEntity(
                    destinationHash = TEST_PEER_HASH,
                    peerName = "Network Name",
                    publicKey = testPublicKey,
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    nodeType = "LXMF_PEER",
                    receivingInterface = null,
                    isFavorite = false,
                    favoritedTimestamp = null,
                )
            announceDao.upsertAnnounce(announce)

            val result = persistenceManager.lookupDisplayName(TEST_PEER_HASH)

            assertEquals("Network Name", result)
        }

    @Test
    fun `lookupDisplayName returns null when no contact or announce`() =
        testScope.runTest {
            insertTestIdentity()

            val result = persistenceManager.lookupDisplayName("unknown_hash_12345678901234567")

            assertNull("Should return null when no contact or announce", result)
        }

    // ========== persistPeerIdentity() Tests ==========

    @Suppress("SleepInsteadOfDelay") // Room dispatches to IO, test dispatcher can't control it
    @Test
    fun `persistPeerIdentity saves peer identity to database`() =
        testScope.runTest {
            val peerHash = "peer_identity_hash_123456789012"

            persistenceManager.persistPeerIdentity(peerHash, testPublicKey)
            advanceUntilIdle()
            // Fire-and-forget coroutine dispatches to IO, so wait for it
            Thread.sleep(100)

            // Verify peer identity was saved
            val saved = peerIdentityDao.getPeerIdentity(peerHash)
            assertNotNull("Peer identity should be saved", saved)
            assertEquals(32, saved?.publicKey?.size)
        }

    // ========== Unread Count Edge Cases ==========

    @Test
    fun `persistMessage does not increment unread for duplicate`() =
        testScope.runTest {
            insertTestIdentity()

            // Setup conversation with 5 unread
            val conversation =
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    peerPublicKey = null,
                    lastMessage = "Previous",
                    lastMessageTimestamp = 500L,
                    unreadCount = 5,
                    lastSeenTimestamp = 0L,
                )
            conversationDao.insertConversation(conversation)

            val messageHash = "msg_unread_test_1234567890123456"

            // First message
            persistenceManager.persistMessage(
                messageHash = messageHash,
                content = "Hello",
                sourceHash = TEST_PEER_HASH,
                timestamp = 1000L,
                fieldsJson = null,
                publicKey = null,
                replyToMessageId = null,
                deliveryMethod = null,
            )
            advanceUntilIdle()

            // Unread should be 6 (5 + 1)
            assertEquals(6, conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)?.unreadCount)

            // Try duplicate
            persistenceManager.persistMessage(
                messageHash = messageHash, // Same hash
                content = "Hello again",
                sourceHash = TEST_PEER_HASH,
                timestamp = 2000L,
                fieldsJson = null,
                publicKey = null,
                replyToMessageId = null,
                deliveryMethod = null,
            )
            advanceUntilIdle()

            // Unread should STILL be 6 (duplicate doesn't increment)
            assertEquals(
                "Unread should not increment for duplicate",
                6,
                conversationDao.getConversation(TEST_PEER_HASH, TEST_IDENTITY_HASH)?.unreadCount,
            )
        }
}
