package com.lxmf.messenger.service

import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
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
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MessageCollector.
 * Tests message collection, public key extraction, and database persistence.
 */
class MessageCollectorTest {
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var messageCollector: MessageCollector

    // Use extraBufferCapacity to ensure emissions aren't dropped before collector is ready
    private lateinit var messageFlow: MutableSharedFlow<ReceivedMessage>

    private val testSourceHash = ByteArray(16) { it.toByte() }
    private val testDestHash = ByteArray(16) { (it + 16).toByte() }
    private val testSourceHashHex = testSourceHash.joinToString("") { "%02x".format(it) }
    private val testDestHashHex = testDestHash.joinToString("") { "%02x".format(it) }
    private val testPublicKey = ByteArray(64) { it.toByte() }

    @Before
    fun setup() {
        reticulumProtocol = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        identityRepository = mockk()
        notificationHelper = mockk(relaxed = true)

        messageFlow = MutableSharedFlow(extraBufferCapacity = 10)

        // Mock protocol flows
        every { reticulumProtocol.observeMessages() } returns messageFlow
        every { reticulumProtocol.observeAnnounces() } returns flowOf() // Empty flow for announces

        // Mock identity repository to return active identity matching test destination
        coEvery { identityRepository.getActiveIdentitySync() } returns
            LocalIdentityEntity(
                identityHash = "test_identity_hash",
                displayName = "Test",
                destinationHash = testDestHashHex,
                filePath = "/test/path",
                createdTimestamp = System.currentTimeMillis(),
                lastUsedTimestamp = System.currentTimeMillis(),
                isActive = true,
            )

        // Mock conversation repository default behaviors
        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.updatePeerPublicKey(any(), any()) } just Runs
        coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs
        coEvery { conversationRepository.getConversation(any()) } returns null
        coEvery { conversationRepository.updatePeerName(any(), any()) } just Runs
        coEvery { conversationRepository.getMessageById(any()) } returns null // For de-duplication check

        // Mock announce repository
        coEvery { announceRepository.getAnnounce(any()) } returns null

        messageCollector =
            MessageCollector(
                reticulumProtocol = reticulumProtocol,
                conversationRepository = conversationRepository,
                announceRepository = announceRepository,
                contactRepository = contactRepository,
                identityRepository = identityRepository,
                notificationHelper = notificationHelper,
            )
    }

    @After
    fun tearDown() {
        messageCollector.stopCollecting()
        clearAllMocks()
    }

    @Test
    fun `processMessage with publicKey stores key to database`() =
        runBlocking {
            // Given: A message with public key
            val testMessage =
                ReceivedMessage(
                    messageHash = "test_message_123",
                    content = "Hello world",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = testPublicKey,
                )

            // When: Start collecting
            messageCollector.startCollecting()

            // Wait for collector to be ready on IO dispatcher
            kotlinx.coroutines.delay(50)

            // Emit message
            messageFlow.emit(testMessage)

            // Wait for IO dispatcher to process
            kotlinx.coroutines.delay(200)

            // Then: updatePeerPublicKey should be called
            coVerify(timeout = 2000) {
                conversationRepository.updatePeerPublicKey(testSourceHashHex, testPublicKey)
            }

            // And: saveMessage should be called with the message's public key
            coVerify(timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = any(),
                    peerPublicKey = testPublicKey,
                )
            }
        }

    @Test
    fun `processMessage without publicKey uses database fallback`() =
        runBlocking {
            // Given: A message without public key, but database has public key
            // No public key in message
            val testMessage =
                ReceivedMessage(
                    messageHash = "test_message_456",
                    content = "Hello world",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            val databasePublicKey = ByteArray(64) { (it + 100).toByte() }
            coEvery { conversationRepository.getPeerPublicKey(testSourceHashHex) } returns databasePublicKey

            // When: Start collecting
            messageCollector.startCollecting()

            // Wait for collector to be ready on IO dispatcher
            kotlinx.coroutines.delay(50)

            // Emit message
            messageFlow.emit(testMessage)

            // Wait for IO dispatcher to process
            kotlinx.coroutines.delay(200)

            // Then: getPeerPublicKey should be called as fallback
            coVerify(timeout = 2000) {
                conversationRepository.getPeerPublicKey(testSourceHashHex)
            }

            // And: updatePeerPublicKey should NOT be called (no message key to store)
            coVerify(exactly = 0, timeout = 2000) {
                conversationRepository.updatePeerPublicKey(any(), any())
            }

            // And: saveMessage should be called with the database's public key
            coVerify(timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = any(),
                    peerPublicKey = databasePublicKey,
                )
            }
        }

    @Test
    fun `processMessage without publicKey passes null when database also has no key`() =
        runBlocking {
            // Given: A message without public key and database also has no key
            // No public key in message
            val testMessage =
                ReceivedMessage(
                    messageHash = "test_message_789",
                    content = "Hello world",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            coEvery { conversationRepository.getPeerPublicKey(testSourceHashHex) } returns null

            // When: Start collecting
            messageCollector.startCollecting()

            // Wait for collector to be ready on IO dispatcher
            kotlinx.coroutines.delay(50)

            // Emit message
            messageFlow.emit(testMessage)

            // Wait for IO dispatcher to process
            kotlinx.coroutines.delay(200)

            // Then: saveMessage should be called with null public key
            coVerify(timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = any(),
                    peerPublicKey = null,
                )
            }
        }

    // ========== De-duplication Tests ==========

    @Test
    fun `processMessage skips duplicate message already in database`() =
        runBlocking {
            // Given: A message that already exists in the database
            val testMessage =
                ReceivedMessage(
                    messageHash = "existing_message",
                    content = "Already exists",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // Message already exists in database (persisted by service)
            coEvery { conversationRepository.getMessageById("existing_message") } returns mockk()

            // When: Start collecting
            messageCollector.startCollecting()

            // Wait for collector to be ready
            kotlinx.coroutines.delay(50)

            // Emit message
            messageFlow.emit(testMessage)

            // Wait for processing
            kotlinx.coroutines.delay(200)

            // Then: saveMessage should NOT be called (duplicate skipped)
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
            // Given: A message that we've already processed
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

            coEvery { conversationRepository.getMessageById(any()) } returns null

            // When: Start collecting and emit the same message twice
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(50)

            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // First message should be saved
            coVerify(exactly = 1, timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = any(),
                    peerPublicKey = any(),
                )
            }

            // Emit the same message again
            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Second message should be skipped (in-memory cache)
            coVerify(exactly = 1, timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = any(),
                    peerPublicKey = any(),
                )
            }
        }

    @Test
    fun `processMessage skips message for wrong identity`() =
        runBlocking {
            // Given: A message sent to a different identity
            val wrongDestHash = ByteArray(16) { (it + 100).toByte() }
            val testMessage =
                ReceivedMessage(
                    messageHash = "wrong_dest_message",
                    content = "Wrong destination",
                    sourceHash = testSourceHash,
                    destinationHash = wrongDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(50)

            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Message should NOT be saved (wrong identity)
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
    fun `processMessage skips when no active identity`() =
        runBlocking {
            // Given: No active identity
            coEvery { identityRepository.getActiveIdentitySync() } returns null

            val testMessage =
                ReceivedMessage(
                    messageHash = "no_identity_message",
                    content = "Hello",
                    sourceHash = testSourceHash,
                    destinationHash = testDestHash,
                    timestamp = System.currentTimeMillis(),
                    fieldsJson = null,
                    publicKey = null,
                )

            // When: Start collecting
            messageCollector.startCollecting()
            kotlinx.coroutines.delay(50)

            messageFlow.emit(testMessage)
            kotlinx.coroutines.delay(200)

            // Then: Message should NOT be saved
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(
                    peerHash = any(),
                    peerName = any(),
                    message = any(),
                    peerPublicKey = any(),
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
            messageCollector.updatePeerName(peerHash, "New Name")

            // Wait for database update
            kotlinx.coroutines.delay(100)

            // Then: Database should be updated
            coVerify(timeout = 1000) {
                conversationRepository.updatePeerName(peerHash, "New Name")
            }
        }

    @Test
    fun `updatePeerName ignores blank names`() =
        runBlocking {
            val peerHash = "test_peer_hash"

            // When
            messageCollector.updatePeerName(peerHash, "")

            // Wait
            kotlinx.coroutines.delay(100)

            // Then: Database should NOT be updated
            coVerify(exactly = 0) {
                conversationRepository.updatePeerName(any(), any())
            }
        }
}
