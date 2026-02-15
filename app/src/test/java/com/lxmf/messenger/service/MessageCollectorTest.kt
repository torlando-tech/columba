package com.lxmf.messenger.service

import com.lxmf.messenger.data.db.dao.PeerIconDao
import com.lxmf.messenger.data.repository.AnnounceRepository
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.reticulum.protocol.ReceivedMessage
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MessageCollector.
 * Tests notification behavior for messages that were persisted by ServicePersistenceManager.
 *
 * Note: MessageCollector no longer persists messages itself - all persistence happens in
 * ServicePersistenceManager which enforces privacy settings like "block unknown senders".
 * MessageCollector only shows notifications for messages that exist in the database.
 */
class MessageCollectorTest {
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var peerIconDao: PeerIconDao
    private lateinit var conversationLinkManager: ConversationLinkManager
    private lateinit var messageCollector: MessageCollector

    // Use extraBufferCapacity to ensure emissions aren't dropped before collector is ready
    private lateinit var messageFlow: MutableSharedFlow<ReceivedMessage>

    private val testSourceHash = ByteArray(16) { it.toByte() }
    private val testDestHash = ByteArray(16) { (it + 16).toByte() }
    private val testSourceHashHex = testSourceHash.joinToString("") { "%02x".format(it) }

    @Before
    fun setup() {
        reticulumProtocol = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        identityRepository = mockk()
        notificationHelper = mockk()
        peerIconDao = mockk()
        conversationLinkManager = mockk()

        // Default behavior for conversationLinkManager
        every { conversationLinkManager.recordPeerActivity(any(), any()) } just Runs
        every { conversationLinkManager.recordPeerActivity(any()) } just Runs

        // Explicit stubs for notificationHelper (suspend function)
        coEvery { notificationHelper.notifyMessageReceived(any(), any(), any(), any()) } returns Unit

        // Explicit stubs for peerIconDao
        coEvery { peerIconDao.getIcon(any()) } returns null

        messageFlow = MutableSharedFlow(extraBufferCapacity = 10)

        // Mock protocol flows
        every { reticulumProtocol.observeMessages() } returns messageFlow
        every { reticulumProtocol.observeAnnounces() } returns flowOf() // Empty flow for announces

        // Mock conversation repository default behaviors
        // Note: getMessageById is no longer called - MessageCollector trusts broadcasts
        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.updatePeerPublicKey(any(), any()) } just Runs
        coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs
        coEvery { conversationRepository.getConversation(any()) } returns null
        coEvery { conversationRepository.updatePeerName(any(), any()) } just Runs
        coEvery { conversationRepository.getMessageById(any()) } returns null

        // Mock announce repository
        coEvery { announceRepository.getAnnounce(any()) } returns null

        // Mock identity repository - return a mock active identity matching test destination
        coEvery { identityRepository.getActiveIdentitySync() } returns
            mockk {
                every { destinationHash } returns testDestHash.joinToString("") { "%02x".format(it) }
            }

        messageCollector =
            MessageCollector(
                reticulumProtocol = reticulumProtocol,
                conversationRepository = conversationRepository,
                announceRepository = announceRepository,
                contactRepository = contactRepository,
                identityRepository = identityRepository,
                notificationHelper = notificationHelper,
                peerIconDao = peerIconDao,
                conversationLinkManager = conversationLinkManager,
            )
    }

    @After
    fun tearDown() {
        messageCollector.stopCollecting()
        clearAllMocks()
    }

    // ========== De-duplication Tests ==========
    // Note: Blocking tests are handled at the EventHandler/ServicePersistenceManager level.
    // MessageCollector only receives broadcasts for messages that were already persisted.

    @Test
    fun `processMessage shows notification for broadcast message`() =
        runBlocking {
            // Given: A message broadcast from EventHandler (already persisted by service)
            val testMessage =
                ReceivedMessage(
                    messageHash = "persisted_message",
                    content = "This was persisted",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Mock that message already exists in database (persisted by ServicePersistenceManager)
            // isRead = false means the user hasn't seen it yet, so notification should fire
            coEvery { conversationRepository.getMessageById("persisted_message") } returns
                mockk {
                    every { isRead } returns false
                }

            // When: Start collecting and emit message
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)

            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should be shown
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                )
            }

            // And: No persistence should be attempted (service already persisted)
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(
                    peerHash = any(),
                    peerName = any(),
                    message = any(),
                    peerPublicKey = any(),
                )
            }
        }

    @Test
    fun `processMessage skips in-memory duplicate`() =
        runBlocking {
            // Given: A message broadcast from EventHandler
            val testMessage =
                ReceivedMessage(
                    messageHash = "duplicate_message",
                    content = "Hello world",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting and emit the same message twice
            val result =
                runCatching {
                    messageCollector.startCollecting()
                    kotlinx.coroutines.delay(50)

                    messageFlow.emit(testMessage)
                    kotlinx.coroutines.delay(200)
                }

            // Then: Operation should complete without throwing
            assertTrue("Message emission should complete without throwing", result.isSuccess)

            // First message should trigger notification
            coVerify(exactly = 1, timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                )
            }

            // Emit the same message again
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Second message should be skipped (in-memory cache) - still only 1 notification
            coVerify(exactly = 1, timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = any(),
                )
            }
        }

    // ========== Lifecycle Tests ==========

    @Test
    fun `stopCollecting clears caches`() =
        runBlocking {
            // Given: Start collecting
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(50)

            // When: Stop collecting
            messageCollector.stopCollecting()

            // Then: getStats should show cleared state
            val stats = messageCollector.getStats()
            assert(stats.contains("Known peers: 0"))
        }

    @Test
    fun `startCollecting is idempotent`() =
        runBlocking {
            // Given: Already started
            messageCollector.startCollecting()

            // When: Start again
            messageCollector.startCollecting()

            // Then: No exception, single collection running
            // This is primarily testing no crash occurs
        }

    // ========== Peer Name Tests ==========

    @Test
    fun `updatePeerName caches peer name`() =
        runBlocking {
            val peerHash = "test_peer_hash"

            // When
            val result = runCatching { messageCollector.updatePeerName(peerHash, "New Name") }

            // Wait for database update
            kotlinx.coroutines.delay(100)

            // Then: Function completed and database should be updated
            assertTrue("updatePeerName should complete without throwing", result.isSuccess)
            coVerify(timeout = 1000) {
                conversationRepository.updatePeerName(peerHash, "New Name")
            }
        }

    @Test
    fun `updatePeerName ignores blank names`() =
        runBlocking {
            val peerHash = "test_peer_hash"

            // When
            val result = runCatching { messageCollector.updatePeerName(peerHash, "") }

            // Wait
            kotlinx.coroutines.delay(100)

            // Then: Function completed and database should NOT be updated
            assertTrue("updatePeerName should complete without throwing", result.isSuccess)
            coVerify(exactly = 0) {
                conversationRepository.updatePeerName(any(), any())
            }
        }

    // ========== Notification for Pre-Persisted Messages Tests ==========

    @Test
    fun `processMessage uses favorite status from announce for notification`() =
        runBlocking {
            // Given: A message from a favorite peer
            val testMessage =
                ReceivedMessage(
                    messageHash = "favorite_msg",
                    content = "Message from favorite",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Peer is a favorite
            coEvery { announceRepository.getAnnounce(testSourceHashHex) } returns
                mockk {
                    every { isFavorite } returns true
                }

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should be posted with isFavorite = true
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = true,
                )
            }
        }

    @Test
    fun `processMessage handles announce lookup failure gracefully for notifications`() =
        runBlocking {
            // Given: A message where announce lookup fails
            val testMessage =
                ReceivedMessage(
                    messageHash = "announce_error_msg",
                    content = "Announce lookup will fail",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Announce lookup throws exception
            coEvery { announceRepository.getAnnounce(testSourceHashHex) } throws RuntimeException("DB error")

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should still be posted with isFavorite = false (fail-safe default)
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = any(),
                    isFavorite = false,
                )
            }
        }

    @Test
    fun `processMessage uses cached peer name for notification`() =
        runBlocking {
            // Given: Update peer name cache first
            messageCollector.updatePeerName(testSourceHashHex, "Cached Peer Name")
            kotlinx.coroutines.delay(100)

            val testMessage =
                ReceivedMessage(
                    messageHash = "cached_name_msg",
                    content = "Test with cached name",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification should use the cached peer name
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = "Cached Peer Name",
                    messagePreview = any(),
                    isFavorite = any(),
                )
            }
        }

    @Test
    fun `processMessage truncates long message preview for notification`() =
        runBlocking {
            // Given: A message with content longer than 100 characters
            val longContent = "A".repeat(200)
            val testMessage =
                ReceivedMessage(
                    messageHash = "long_content_msg",
                    content = longContent,
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting and emit
            val startResult = runCatching { messageCollector.startCollecting() }
            assertTrue("startCollecting should complete without throwing", startResult.isSuccess)
            kotlinx.coroutines.delay(50)
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Notification preview should be truncated to 100 characters
            coVerify(timeout = 2000) {
                notificationHelper.notifyMessageReceived(
                    destinationHash = testSourceHashHex,
                    peerName = any(),
                    messagePreview = "A".repeat(100),
                    isFavorite = any(),
                )
            }
        }
}
