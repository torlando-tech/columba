package network.columba.app.rns.host.persistence

import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.host.di.ServiceDatabaseProvider
import network.columba.app.test.DatabaseTest
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private val testPublicKey = ByteArray(64) { it.toByte() }
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

        persistenceManager = ServicePersistenceManager(context, testScope, settingsAccessor, false)
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
                announcePacketHash = "00112233",
                isPathResponse = false,
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
            val activity = database.peerActivityDao().getActivity(destinationHash)
            assertEquals(saved?.lastSeenTimestamp, activity?.lastReceivedAt)
            assertEquals("ANNOUNCE", activity?.activityType)
        }

    @Test
    fun `path response updates announce metadata without advancing peer activity`() =
        testScope.runTest {
            val destinationHash = "path_response_peer"
            insertTestIdentity()
            contactDao.insertContact(
                ContactEntity(
                    destinationHash = destinationHash,
                    identityHash = TEST_IDENTITY_HASH,
                    publicKey = testPublicKey,
                    addedTimestamp = 500L,
                    addedVia = "MANUAL",
                ),
            )

            database.peerActivityDao().recordActivity(
                destinationHash = destinationHash,
                receivedAt = 1_000L,
                activityType = "MESSAGE",
            )

            assertTrue(
                persistenceManager.persistAnnounce(
                    destinationHash = destinationHash,
                    peerName = "Cached Peer",
                    publicKey = testPublicKey,
                    appData = testAppData,
                    hops = 3,
                    timestamp = 2_000L,
                    nodeType = "PEER",
                    receivingInterface = "TCP",
                    receivingInterfaceType = "TCP",
                    aspect = "lxmf.delivery",
                    stampCost = null,
                    stampCostFlexibility = null,
                    peeringCost = null,
                    propagationTransferLimitKb = null,
                    announcePacketHash = "aabbccdd",
                    isPathResponse = true,
                ),
            )

            val announce = announceDao.getAnnounce(destinationHash)
            assertNotNull(announce)
            assertEquals("Cached Peer", announce?.peerName)
            assertEquals(3, announce?.hops)
            assertEquals(0L, announce?.lastSeenTimestamp)
            val activity = database.peerActivityDao().getActivity(destinationHash)
            assertEquals(1_000L, activity?.lastReceivedAt)
            assertEquals("MESSAGE", activity?.activityType)
            val identityHash = network.columba.app.data.util.HashUtils.computeIdentityHash(testPublicKey)
            assertEquals(0L, peerIdentityDao.getPeerIdentity(identityHash)?.lastSeenTimestamp)
            val contact = contactDao.getEnrichedContacts(TEST_IDENTITY_HASH, currentTime = 2_000L).first().single()
            assertEquals(1_000L, contact.lastSeenTimestamp)
        }

    @Test
    fun `replayed announce packet updates peer activity only once`() =
        testScope.runTest {
            val destinationHash = "replayed_announce_peer"

            suspend fun persist(timestamp: Long) =
                persistenceManager.persistAnnounce(
                    destinationHash = destinationHash,
                    peerName = "Peer",
                    publicKey = testPublicKey,
                    appData = testAppData,
                    hops = 1,
                    timestamp = timestamp,
                    nodeType = "PEER",
                    receivingInterface = "TCP",
                    receivingInterfaceType = "TCP",
                    aspect = "lxmf.delivery",
                    stampCost = null,
                    stampCostFlexibility = null,
                    peeringCost = null,
                    propagationTransferLimitKb = null,
                    announcePacketHash = "11223344",
                    isPathResponse = false,
                )

            assertTrue(persist(1_000L))
            assertTrue(persist(9_000L))

            val activity = database.peerActivityDao().getActivity(destinationHash)
            assertEquals(1_000L, activity?.lastReceivedAt)
            assertEquals("ANNOUNCE", activity?.activityType)
            assertEquals(1_000L, announceDao.getAnnounce(destinationHash)?.lastSeenTimestamp)
        }

    @Test
    fun `hashless announce updates metadata without advancing peer activity`() =
        testScope.runTest {
            val destinationHash = "hashless_announce_peer"

            assertTrue(
                persistenceManager.persistAnnounce(
                    destinationHash = destinationHash,
                    peerName = "Peer",
                    publicKey = testPublicKey,
                    appData = testAppData,
                    hops = 1,
                    timestamp = 4_000L,
                    nodeType = "PEER",
                    receivingInterface = "TCP",
                    receivingInterfaceType = "TCP",
                    aspect = "lxmf.delivery",
                    stampCost = null,
                    stampCostFlexibility = null,
                    peeringCost = null,
                    propagationTransferLimitKb = null,
                    announcePacketHash = null,
                    isPathResponse = false,
                ),
            )

            assertNotNull(announceDao.getAnnounce(destinationHash))
            assertNull(database.peerActivityDao().getActivity(destinationHash))
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
            // lastMessageTimestamp now uses local receive time (System.currentTimeMillis()),
            // not the sender's timestamp, to prevent clock-drift reordering.
            assertTrue(
                "lastMessageTimestamp should be recent wall-clock time",
                updated!!.lastMessageTimestamp > 500L,
            )
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

    // ========== Blocked Peer Tests ==========

    @Test
    fun `persistMessage blocks message from blocked peer`() =
        testScope.runTest {
            insertTestIdentity()

            // Block the peer in the database
            val blockedPeerDao = database.blockedPeerDao()
            blockedPeerDao.insertBlockedPeer(
                network.columba.app.data.db.entity.BlockedPeerEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerIdentityHash = null,
                    displayName = "Blocked User",
                    blockedTimestamp = System.currentTimeMillis(),
                    isBlackholeEnabled = false,
                ),
            )

            val result =
                persistenceManager.persistMessage(
                    messageHash = "msg_from_blocked_12345678901234",
                    content = "You should not see this",
                    sourceHash = TEST_PEER_HASH,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                    replyToMessageId = null,
                    deliveryMethod = null,
                )
            advanceUntilIdle()

            assertFalse("Should return false for blocked peer", result)

            val saved = messageDao.getMessageById("msg_from_blocked_12345678901234", TEST_IDENTITY_HASH)
            assertNull("Message from blocked peer should not be saved", saved)
        }

    @Test
    fun `persistMessage allows message from non-blocked peer`() =
        testScope.runTest {
            insertTestIdentity()

            // Block a different peer
            val blockedPeerDao = database.blockedPeerDao()
            blockedPeerDao.insertBlockedPeer(
                network.columba.app.data.db.entity.BlockedPeerEntity(
                    peerHash = "some_other_peer_hash_1234567890",
                    identityHash = TEST_IDENTITY_HASH,
                    peerIdentityHash = null,
                    displayName = "Other Blocked User",
                    blockedTimestamp = System.currentTimeMillis(),
                    isBlackholeEnabled = false,
                ),
            )

            val result =
                persistenceManager.persistMessage(
                    messageHash = "msg_from_allowed_12345678901234",
                    content = "Hello from unblocked peer",
                    sourceHash = TEST_PEER_HASH,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                    replyToMessageId = null,
                    deliveryMethod = null,
                )
            advanceUntilIdle()

            assertTrue("Should return true for non-blocked peer", result)

            val saved = messageDao.getMessageById("msg_from_allowed_12345678901234", TEST_IDENTITY_HASH)
            assertNotNull("Message from non-blocked peer should be saved", saved)
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

    // ========== computedIdentityHash Tests (COLUMBA-28) ==========

    @Suppress("SleepInsteadOfDelay") // Room dispatches to IO, test dispatcher can't control it
    @Test
    fun `persistAnnounce stores computedIdentityHash in database`() =
        testScope.runTest {
            val destinationHash = "announce_identity_hash_test_12345"

            persistenceManager.persistAnnounce(
                destinationHash = destinationHash,
                peerName = "Identity Test Peer",
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
                propagationTransferLimitKb = null,
            )
            advanceUntilIdle()
            Thread.sleep(100) // Allow Room's IO dispatcher to complete

            val saved = announceDao.getAnnounce(destinationHash)
            assertNotNull("Announce should be saved", saved)
            assertNotNull("computedIdentityHash should be set", saved?.computedIdentityHash)
            assertEquals("computedIdentityHash should be 32 hex chars", 32, saved?.computedIdentityHash?.length)
            assertEquals(
                "computedIdentityHash should be lowercase",
                saved?.computedIdentityHash,
                saved?.computedIdentityHash?.lowercase(),
            )
        }

    @Suppress("SleepInsteadOfDelay") // Room dispatches to IO, test dispatcher can't control it
    @Test
    fun `persistAnnounce computedIdentityHash matches HashUtils output`() =
        testScope.runTest {
            val destinationHash = "announce_hash_match_test_12345678"

            persistenceManager.persistAnnounce(
                destinationHash = destinationHash,
                peerName = "Hash Match Test",
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
                propagationTransferLimitKb = null,
            )
            advanceUntilIdle()
            Thread.sleep(100) // Allow Room's IO dispatcher to complete

            val saved = announceDao.getAnnounce(destinationHash)
            val expectedHash =
                network.columba.app.data.util.HashUtils
                    .computeIdentityHash(testPublicKey)
            assertEquals("computedIdentityHash should match HashUtils", expectedHash, saved?.computedIdentityHash)
        }

    @Test
    fun `lookupDisplayName finds announce by identity hash fallback`() =
        testScope.runTest {
            insertTestIdentity()

            // Insert announce with a computedIdentityHash
            val identityHash =
                network.columba.app.data.util.HashUtils
                    .computeIdentityHash(testPublicKey)
            val announce =
                AnnounceEntity(
                    destinationHash = "real_dest_hash_123456789012345678",
                    peerName = "Found Via Identity Hash",
                    publicKey = testPublicKey,
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    nodeType = "LXMF_PEER",
                    receivingInterface = null,
                    isFavorite = false,
                    favoritedTimestamp = null,
                    computedIdentityHash = identityHash,
                )
            announceDao.upsertAnnounce(announce)

            // Look up by identity hash (NOT destination hash) — the LXST call path
            val result = persistenceManager.lookupDisplayName(identityHash)

            assertEquals("Should find peer name via identity hash", "Found Via Identity Hash", result)
        }

    @Test
    fun `lookupDisplayName returns null when identity hash not found`() =
        testScope.runTest {
            insertTestIdentity()

            val result = persistenceManager.lookupDisplayName("nonexistent_identity_hash_12345")

            assertNull("Should return null for unknown identity hash", result)
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
            assertEquals(64, saved?.publicKey?.size)
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

    @Test
    fun `incoming message records local reception time instead of sender clock`() =
        testScope.runTest {
            insertTestIdentity()
            val before = System.currentTimeMillis()
            persistenceManager.persistMessage(
                messageHash = "clock-skew-message",
                content = "from the future",
                sourceHash = TEST_PEER_HASH,
                timestamp = Long.MAX_VALUE,
                fieldsJson = null,
                publicKey = null,
                replyToMessageId = null,
                deliveryMethod = "direct",
            )
            val after = System.currentTimeMillis()

            val activity = database.peerActivityDao().getActivity(TEST_PEER_HASH)
            assertNotNull(activity)
            assertTrue(activity!!.lastReceivedAt in before..after)
            assertEquals("MESSAGE", activity.activityType)
        }

    @Test
    fun `outgoing undelivered message does not update peer activity`() =
        testScope.runTest {
            insertTestIdentity()
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    lastMessage = "pending",
                    lastMessageTimestamp = 999L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = "outgoing-pending",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "hello",
                    timestamp = 999L,
                    isFromMe = true,
                    status = "pending",
                    isRead = true,
                ),
            )

            assertNull(database.peerActivityDao().getActivity(TEST_PEER_HASH))
            assertFalse(persistenceManager.persistDeliveryProof("unknown-message", 1_000L))
            assertNull(database.peerActivityDao().getActivity(TEST_PEER_HASH))
        }

    @Test
    fun `pre-row lifecycle survives service manager restart and later canonical Room load`() =
        testScope.runTest {
            insertTestIdentity()
            assertTrue(persistenceManager.persistDeliveryStatus("late-outgoing", DeliveryStatus.DELIVERED))
            assertEquals(
                "delivered",
                database.pendingDeliveryStatusDao().get(TEST_IDENTITY_HASH, "late-outgoing")?.status,
            )

            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    lastMessage = "pending",
                    lastMessageTimestamp = 999L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = "late-outgoing",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "hello",
                    timestamp = 999L,
                    isFromMe = true,
                    status = "pending",
                    isRead = true,
                    deliveryMethod = "direct",
                ),
            )

            ServicePersistenceManager(context, backgroundScope, settingsAccessor, true)

            // A later UI subscriber/rebind reads canonical Room state; no IPC replay is required.
            val canonical =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        messageDao.observeMessageById("late-outgoing").first { it?.status == "delivered" }
                    }
                }
            assertEquals("delivered", canonical?.status)
            assertNull(database.pendingDeliveryStatusDao().get(TEST_IDENTITY_HASH, "late-outgoing"))
        }

    @Test
    fun `pre-row propagated retry then delivered reloads delivered with propagated method`() =
        testScope.runTest {
            insertTestIdentity()
            assertTrue(persistenceManager.persistDeliveryStatus("late-fallback", DeliveryStatus.RETRYING_PROPAGATED))
            assertTrue(persistenceManager.persistDeliveryStatus("late-fallback", DeliveryStatus.DELIVERED))

            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    lastMessage = "pending",
                    lastMessageTimestamp = 999L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = "late-fallback",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "hello",
                    timestamp = 999L,
                    isFromMe = true,
                    status = "pending",
                    isRead = true,
                    deliveryMethod = "direct",
                ),
            )

            ServicePersistenceManager(context, backgroundScope, settingsAccessor, true)
            val canonical =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        messageDao.observeMessageById("late-fallback").first { it?.status == "delivered" }
                    }
                }
            assertEquals("delivered", canonical?.status)
            assertEquals("propagated", canonical?.deliveryMethod)
            assertNull(database.pendingDeliveryStatusDao().get(TEST_IDENTITY_HASH, "late-fallback"))
        }

    @Test
    fun `pre-row event stays with admitted identity across switch and duplicate hash insertion`() =
        testScope.runTest {
            val identityA = TEST_IDENTITY_HASH
            val identityB = "other-identity"
            val duplicateHash = "duplicate-outgoing"
            insertTestIdentity(identityHash = identityA, isActive = true)
            insertTestIdentity(identityHash = identityB, displayName = "Other", isActive = false)

            assertTrue(persistenceManager.persistDeliveryStatus(duplicateHash, DeliveryStatus.DELIVERED))
            assertEquals(
                "delivered",
                database.pendingDeliveryStatusDao().get(identityA, duplicateHash)?.status,
            )

            localIdentityDao.setActive(identityB)
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = identityB,
                    peerName = "Peer B",
                    lastMessage = "pending",
                    lastMessageTimestamp = 2_000L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = duplicateHash,
                    conversationHash = TEST_PEER_HASH,
                    identityHash = identityB,
                    content = "from B",
                    timestamp = 2_000L,
                    isFromMe = true,
                    status = "pending",
                    isRead = true,
                ),
            )

            assertTrue(persistenceManager.persistDeliveryStatus("reconcile-trigger-b", DeliveryStatus.FAILED))
            assertEquals("pending", messageDao.getMessageById(duplicateHash, identityB)?.status)
            assertNotNull(database.pendingDeliveryStatusDao().get(identityA, duplicateHash))

            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = identityA,
                    peerName = "Peer A",
                    lastMessage = "pending",
                    lastMessageTimestamp = 1_000L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = duplicateHash,
                    conversationHash = TEST_PEER_HASH,
                    identityHash = identityA,
                    content = "from A",
                    timestamp = 1_000L,
                    isFromMe = true,
                    status = "pending",
                    isRead = true,
                ),
            )

            assertTrue(persistenceManager.persistDeliveryStatus("reconcile-trigger-b", DeliveryStatus.DELIVERED))
            assertEquals("delivered", messageDao.getMessageById(duplicateHash, identityA)?.status)
            assertEquals("pending", messageDao.getMessageById(duplicateHash, identityB)?.status)
            assertNull(database.pendingDeliveryStatusDao().get(identityA, duplicateHash))
        }

    @Test
    fun `incoming activity admission rejects replay blocked unknown and propagated messages`() =
        testScope.runTest {
            insertTestIdentity()

            assertTrue(persistenceManager.persistIncomingMessageActivity("fresh", TEST_PEER_HASH, "direct", 100L))
            assertFalse(persistenceManager.persistIncomingMessageActivity("fresh", TEST_PEER_HASH, "direct", 200L))
            assertEquals(100L, database.peerActivityDao().getActivity(TEST_PEER_HASH)?.lastReceivedAt)

            database.blockedPeerDao().insertBlockedPeer(
                network.columba.app.data.db.entity.BlockedPeerEntity(
                    peerHash = "blocked-peer",
                    identityHash = TEST_IDENTITY_HASH,
                    peerIdentityHash = null,
                    displayName = "Blocked",
                    blockedTimestamp = 1L,
                    isBlackholeEnabled = false,
                ),
            )
            assertFalse(persistenceManager.persistIncomingMessageActivity("blocked", "blocked-peer", "direct", 300L))
            assertNull(database.peerActivityDao().getActivity("blocked-peer"))

            every { settingsAccessor.getBlockUnknownSenders() } returns true
            assertFalse(persistenceManager.persistIncomingMessageActivity("unknown", "unknown-peer", "direct", 400L))
            assertNull(database.peerActivityDao().getActivity("unknown-peer"))

            every { settingsAccessor.getBlockUnknownSenders() } returns false
            assertFalse(persistenceManager.persistIncomingMessageActivity("propagated", "relay-peer", "propagated", 500L))
            assertNull(database.peerActivityDao().getActivity("relay-peer"))
        }

    @Test
    fun `incoming activity admission requires active identity`() =
        testScope.runTest {
            assertFalse(persistenceManager.persistIncomingMessageActivity("message", TEST_PEER_HASH, "direct", 100L))
            assertNull(database.peerActivityDao().getActivity(TEST_PEER_HASH))
        }

    @Test
    fun `verified delivery proof updates outgoing recipient activity`() =
        testScope.runTest {
            insertTestIdentity()
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    lastMessage = "sent",
                    lastMessageTimestamp = 10L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = "outgoing-delivered",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "hello",
                    timestamp = 10L,
                    isFromMe = true,
                    status = "sent",
                    isRead = true,
                ),
            )

            assertTrue(persistenceManager.persistDeliveryProof("outgoing-delivered", 500L))
            assertFalse(persistenceManager.persistDeliveryProof("outgoing-delivered", 900L))
            val activity = database.peerActivityDao().getActivity(TEST_PEER_HASH)
            assertEquals(500L, activity?.lastReceivedAt)
            assertEquals("PROOF", activity?.activityType)
        }

    @Test
    fun `incoming message cannot be mistaken for outgoing proof`() =
        testScope.runTest {
            insertTestIdentity()
            persistenceManager.persistMessage(
                messageHash = "incoming-proof-candidate",
                content = "hello",
                sourceHash = TEST_PEER_HASH,
                timestamp = 1L,
                fieldsJson = null,
                publicKey = null,
                replyToMessageId = null,
                deliveryMethod = "direct",
            )
            val original = database.peerActivityDao().getActivity(TEST_PEER_HASH)

            assertFalse(persistenceManager.persistDeliveryProof("incoming-proof-candidate", original!!.lastReceivedAt + 100L))
            assertEquals(original, database.peerActivityDao().getActivity(TEST_PEER_HASH))
        }

    @Test
    fun `delivery proof resolves message after active identity changes`() =
        testScope.runTest {
            insertTestIdentity()
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    peerName = "Peer",
                    lastMessage = "sent",
                    lastMessageTimestamp = 10L,
                ),
            )
            messageDao.insertMessage(
                MessageEntity(
                    id = "proof-after-switch",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "hello",
                    timestamp = 10L,
                    isFromMe = true,
                    status = "sent",
                    isRead = true,
                ),
            )
            database.localIdentityDao().insert(
                network.columba.app.data.db.entity.LocalIdentityEntity(
                    identityHash = "other-identity",
                    displayName = "Other",
                    destinationHash = "other-destination",
                    filePath = "/other",
                    createdTimestamp = 2L,
                    lastUsedTimestamp = 2L,
                    isActive = false,
                ),
            )
            database.localIdentityDao().setActive("other-identity")

            assertTrue(persistenceManager.persistDeliveryProof("proof-after-switch", 700L))
            assertEquals(700L, database.peerActivityDao().getActivity(TEST_PEER_HASH)?.lastReceivedAt)
        }

    @Test
    fun `direct telemetry applies provenance replay and privacy admission`() =
        testScope.runTest {
            insertTestIdentity()
            assertFalse(
                persistenceManager.persistTelemetryActivity(
                    TEST_PEER_HASH,
                    eventId = "frame-1",
                    receivedAt = 100L,
                    isDirect = false,
                ),
            )
            assertNull(database.peerActivityDao().getActivity(TEST_PEER_HASH))

            assertTrue(
                persistenceManager.persistTelemetryActivity(
                    TEST_PEER_HASH,
                    eventId = "frame-1",
                    receivedAt = 200L,
                    isDirect = true,
                ),
            )
            assertFalse(
                persistenceManager.persistTelemetryActivity(
                    TEST_PEER_HASH,
                    eventId = "frame-1",
                    receivedAt = 300L,
                    isDirect = true,
                ),
            )
            val activity = database.peerActivityDao().getActivity(TEST_PEER_HASH)
            assertEquals(200L, activity?.lastReceivedAt)
            assertEquals("TELEMETRY", activity?.activityType)

            database.blockedPeerDao().insertBlockedPeer(
                network.columba.app.data.db.entity.BlockedPeerEntity(
                    peerHash = "blocked-telemetry",
                    identityHash = TEST_IDENTITY_HASH,
                    peerIdentityHash = null,
                    displayName = "Blocked",
                    blockedTimestamp = 1L,
                    isBlackholeEnabled = false,
                ),
            )
            assertFalse(
                persistenceManager.persistTelemetryActivity(
                    "blocked-telemetry",
                    eventId = "blocked-frame",
                    receivedAt = 400L,
                    isDirect = true,
                ),
            )

            every { settingsAccessor.getBlockUnknownSenders() } returns true
            assertFalse(
                persistenceManager.persistTelemetryActivity(
                    "unknown-telemetry",
                    eventId = "unknown-frame",
                    receivedAt = 500L,
                    isDirect = true,
                ),
            )
        }
}
