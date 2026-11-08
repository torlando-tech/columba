package network.columba.app.service

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.data.repository.Message as DataMessage
import network.columba.app.notifications.NotificationHelper
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.ReceivedMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the live collector, which is now a persistence fallback and icon
 * enricher only. Notification ownership and catch-up are covered against real Room and
 * NotificationManager in MessageCollectorNotificationDatabaseTest.
 */
class MessageCollectorTest {
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsLxmf: RnsLxmf
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var peerIconDao: PeerIconDao
    private lateinit var messageDao: MessageDao
    private lateinit var messageCollector: MessageCollector

    private lateinit var messageFlow: MutableSharedFlow<ReceivedMessage>

    private val testSourceHash = ByteArray(16) { it.toByte() }
    private val testDestHash = ByteArray(16) { (it + 16).toByte() }
    private val testSourceHashHex = testSourceHash.joinToString("") { "%02x".format(it) }

    @Before
    fun setup() {
        rnsCore = mockk()
        rnsLxmf = mockk()
        conversationRepository = mockk()
        announceRepository = mockk()
        contactRepository = mockk()
        identityRepository = mockk()
        notificationHelper = mockk()
        peerIconDao = mockk()
        messageDao = mockk()

        coEvery { notificationHelper.notifyMessageReceived(any(), any(), any(), any()) } just Runs
        coEvery { peerIconDao.getIcon(any()) } returns null
        coEvery { peerIconDao.upsertIcon(any()) } just Runs

        messageFlow = MutableSharedFlow(extraBufferCapacity = 10)
        every { rnsLxmf.observeMessages() } returns messageFlow
        every { rnsCore.observeAnnounces() } returns flowOf()

        coEvery { conversationRepository.getPeerPublicKey(any()) } returns null
        coEvery { conversationRepository.updatePeerPublicKey(any(), any()) } just Runs
        coEvery { conversationRepository.saveMessage(any(), any(), any(), any()) } just Runs
        coEvery { conversationRepository.getConversation(any()) } returns null
        coEvery { conversationRepository.updatePeerName(any(), any()) } just Runs
        coEvery { conversationRepository.getMessageById(any()) } returns null
        coEvery { announceRepository.getAnnounce(any()) } returns null
        coEvery { contactRepository.hasContact(any()) } returns false

        coEvery { identityRepository.getActiveIdentitySync() } returns
            mockk {
                every { identityHash } returns "local-identity"
                every { destinationHash } returns testDestHash.joinToString("") { "%02x".format(it) }
            }
        every { identityRepository.activeIdentity } returns flowOf(null)

        every { messageDao.observePendingNotificationCount(any()) } returns flowOf(0)
        coEvery { messageDao.getPendingNotificationMessages(any(), any()) } returns emptyList()
        coEvery { messageDao.ackNotificationHandled(any(), any()) } returns 0

        messageCollector = buildCollector()
    }

    @After
    fun tearDown() {
        messageCollector.stopCollecting()
        clearAllMocks()
    }

    private fun buildCollector() =
        MessageCollector(
            rnsCore = rnsCore,
            rnsLxmf = rnsLxmf,
            conversationRepository = conversationRepository,
            announceRepository = announceRepository,
            contactRepository = contactRepository,
            identityRepository = identityRepository,
            notificationHelper = notificationHelper,
            peerIconDao = peerIconDao,
            messageDao = messageDao,
        )

    private fun receivedMessage(
        id: String,
        content: String = "Hello",
        iconName: String? = null,
    ) = ReceivedMessage(
        messageHash = id,
        content = content,
        sourceHash = testSourceHash,
        destinationHash = testDestHash,
        timestamp = System.currentTimeMillis(),
        fieldsJson = null,
        publicKey = null,
        iconAppearance =
            iconName?.let {
                network.columba.app.rns.api.model.IconAppearance(
                    iconName = it,
                    foregroundColor = "00ff00",
                    backgroundColor = "000000",
                )
            },
    )

    @Test
    fun `live message is persisted as fallback without posting a notification`() =
        runBlocking {
            messageCollector.startCollecting()
            delay(50)
            messageFlow.emit(receivedMessage("fresh_message"))
            delay(200)

            val saved = slot<DataMessage>()
            coVerify(timeout = 2000) {
                conversationRepository.saveMessage(
                    peerHash = testSourceHashHex,
                    peerName = any(),
                    message = capture(saved),
                    peerPublicKey = any(),
                )
            }
            assertFalse("fallback persistence must store the message as inbound", saved.captured.isFromMe)
            assertEquals(1, messageCollector.messagesCollected.value)
            coVerify(exactly = 0) {
                notificationHelper.notifyMessageReceived(any(), any(), any(), any())
            }
        }

    @Test
    fun `already persisted message refreshes icon and never posts a notification`() =
        runBlocking {
            coEvery { conversationRepository.getMessageById("persisted_message") } returns
                mockk {
                    every { isRead } returns false
                }

            messageCollector.startCollecting()
            delay(50)
            messageFlow.emit(receivedMessage("persisted_message", iconName = "account"))
            delay(200)

            coVerify(timeout = 2000) {
                peerIconDao.upsertIcon(
                    match {
                        it.destinationHash == testSourceHashHex &&
                            it.iconName == "account" &&
                            it.foregroundColor == "00ff00" &&
                            it.backgroundColor == "000000"
                    },
                )
            }
            coVerify(exactly = 0) {
                conversationRepository.saveMessage(any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                notificationHelper.notifyMessageReceived(any(), any(), any(), any())
            }
            assertEquals(0, messageCollector.messagesCollected.value)
        }

    @Test
    fun `stopCollecting clears caches`() =
        runBlocking {
            messageCollector.startCollecting()
            delay(50)
            messageCollector.stopCollecting()

            val stats = messageCollector.getStats()
            assertTrue(stats.contains("Known peers: 0"))
        }

    @Test
    fun `startCollecting is idempotent after stop`() =
        runBlocking {
            messageCollector.startCollecting()
            messageCollector.startCollecting()

            messageCollector.stopCollecting()
            val result = runCatching { messageCollector.startCollecting() }
            assertTrue("restart after stop must succeed", result.isSuccess)
        }

    @Test
    fun `updatePeerName caches peer name`() =
        runBlocking {
            val peerHash = "test_peer_hash"

            val result = runCatching { messageCollector.updatePeerName(peerHash, "New Name") }
            delay(100)

            assertTrue("updatePeerName should complete without throwing", result.isSuccess)
            coVerify(timeout = 1000) {
                conversationRepository.updatePeerName(peerHash, "New Name")
            }
        }

    @Test
    fun `updatePeerName ignores blank names`() =
        runBlocking {
            val result = runCatching { messageCollector.updatePeerName("test_peer_hash", "") }
            delay(100)

            assertTrue("updatePeerName should complete without throwing", result.isSuccess)
            coVerify(exactly = 0) {
                conversationRepository.updatePeerName(any(), any())
            }
        }
}
