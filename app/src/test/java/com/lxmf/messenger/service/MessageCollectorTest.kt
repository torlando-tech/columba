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
import com.lxmf.messenger.data.repository.Message as DataMessage

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
        coEvery { identityRepository.getActiveIdentitySync() } returns LocalIdentityEntity(
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

        // Mock announce repository
        coEvery { announceRepository.getAnnounce(any()) } returns null

        messageCollector = MessageCollector(
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
    fun `processMessage with publicKey stores key to database`() = runBlocking {
        // Given: A message with public key
        val testMessage = ReceivedMessage(
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
    fun `processMessage without publicKey uses database fallback`() = runBlocking {
        // Given: A message without public key, but database has public key
        val testMessage = ReceivedMessage(
            messageHash = "test_message_456",
            content = "Hello world",
            sourceHash = testSourceHash,
            destinationHash = testDestHash,
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null, // No public key in message
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
    fun `processMessage without publicKey passes null when database also has no key`() = runBlocking {
        // Given: A message without public key and database also has no key
        val testMessage = ReceivedMessage(
            messageHash = "test_message_789",
            content = "Hello world",
            sourceHash = testSourceHash,
            destinationHash = testDestHash,
            timestamp = System.currentTimeMillis(),
            fieldsJson = null,
            publicKey = null, // No public key in message
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
}
