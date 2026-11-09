package network.columba.app.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteException
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import network.columba.app.R
import network.columba.app.data.crypto.IdentityKeyEncryptor
import network.columba.app.data.crypto.IdentityKeyMigrator
import network.columba.app.data.crypto.IdentityKeyProvider
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.PendingNotificationMessage
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.model.NotificationPrivacyLevel
import network.columba.app.data.repository.AnnounceRepository
import network.columba.app.data.repository.ContactRepository
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.data.storage.AttachmentStorageManager
import network.columba.app.notifications.NotificationHelper
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.test.DatabaseTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import network.columba.app.data.repository.Message as DataMessage

/**
 * Real-Room, real-NotificationManager coverage of durable notification catch-up.
 *
 * The backend flows are the only faked boundary: messages persisted before or without an
 * IPC callback must still notify exactly once, and the completion marker must survive
 * collector restarts, dismissed notifications, and replays.
 */
class MessageCollectorNotificationDatabaseTest : DatabaseTest() {
    private lateinit var notificationManager: NotificationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var activeConversationManager: ActiveConversationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var identityRepository: IdentityRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var announceRepository: AnnounceRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsLxmf: RnsLxmf
    private lateinit var messageFlow: MutableSharedFlow<ReceivedMessage>
    private lateinit var notificationsEnabled: MutableStateFlow<Boolean>
    private lateinit var threadsEnabled: MutableStateFlow<Boolean>
    private lateinit var notificationPrivacy: MutableStateFlow<NotificationPrivacyLevel>
    private var collector: MessageCollector? = null

    private val peerHash = "peer_hash_notification_0000000001"
    private val secondPeerHash = "peer_hash_notification_0000000002"
    private val senderHash = ByteArray(16) { (it + 1).toByte() }
    private val senderHashHex = senderHash.joinToString("") { "%02x".format(it) }
    private val ownDestHash = ByteArray(16) { (it + 17).toByte() }

    @Before
    fun setupCollectorDependencies() {
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notificationsEnabled = MutableStateFlow(true)
        threadsEnabled = MutableStateFlow(false)
        notificationPrivacy = MutableStateFlow(NotificationPrivacyLevel.FULL)
        settingsRepository = mockk()
        every { settingsRepository.notificationsEnabledFlow } returns notificationsEnabled
        every { settingsRepository.notificationReceivedMessageFlow } returns flowOf(true)
        every { settingsRepository.notificationReceivedMessageFavoriteFlow } returns flowOf(true)
        every { settingsRepository.threadsEnabledFlow } returns threadsEnabled
        every { settingsRepository.notificationPrivacyLevelFlow } returns notificationPrivacy

        activeConversationManager = ActiveConversationManager()
        notificationHelper = NotificationHelper(context, settingsRepository, activeConversationManager)
        notificationHelper.isAppInForeground = { false }

        identityRepository =
            IdentityRepository(
                identityDao = localIdentityDao,
                database = database,
                context = context,
                ioDispatcher = Dispatchers.IO,
                keyEncryptor = mockk<IdentityKeyEncryptor>(),
                keyMigrator = mockk<IdentityKeyMigrator>(),
                keyProvider = mockk<IdentityKeyProvider>(),
            )
        conversationRepository =
            ConversationRepository(
                conversationDao = conversationDao,
                messageDao = messageDao,
                peerIdentityDao = peerIdentityDao,
                localIdentityDao = localIdentityDao,
                attachmentStorage = AttachmentStorageManager(context),
                draftDao = draftDao,
                threadDao = database.threadDao(),
                database = database,
            )
        announceRepository = mockk()
        coEvery { announceRepository.getAnnounce(any()) } returns null
        contactRepository = mockk()
        coEvery { contactRepository.getContact(any()) } returns null
        coEvery { contactRepository.hasContact(any()) } returns false

        messageFlow = MutableSharedFlow(extraBufferCapacity = 10)
        rnsLxmf = mockk()
        every { rnsLxmf.observeMessages() } returns messageFlow
        rnsCore = mockk()
        every { rnsCore.observeAnnounces() } returns emptyFlow()
    }

    @After
    fun teardown() {
        collector?.stopCollecting()
        collector = null
        clearAllMocks()
    }

    private fun startCollector(notificationDao: MessageDao = messageDao) {
        collector =
            MessageCollector(
                rnsCore = rnsCore,
                rnsLxmf = rnsLxmf,
                conversationRepository = conversationRepository,
                announceRepository = announceRepository,
                contactRepository = contactRepository,
                identityRepository = identityRepository,
                notificationHelper = notificationHelper,
                peerIconDao = database.peerIconDao(),
                messageDao = notificationDao,
            ).also { it.startCollecting() }
    }

    private fun activeMessageNotification(hash: String) =
        notificationManager.activeNotifications.firstOrNull {
            it.id == NotificationHelper.NOTIFICATION_ID_MESSAGE &&
                it.tag == NotificationHelper.messageNotificationTag(hash)
        }

    private suspend fun awaitNotification(hash: String) {
        withTimeout(5_000) {
            while (activeMessageNotification(hash) == null) delay(20)
        }
    }

    private suspend fun awaitHandled(
        identityHash: String,
        messageId: String,
    ) {
        withTimeout(5_000) {
            while (messageDao.getMessageById(messageId, identityHash)?.notificationHandled != true) delay(20)
        }
    }

    private suspend fun awaitPending(
        identityHash: String,
        messageId: String,
    ) {
        withTimeout(5_000) {
            while (messageDao.getPendingNotificationMessages(identityHash, 32).none { it.id == messageId }) delay(20)
        }
    }

    /** Bounded observation window: fails if a notification for [hash] appears within [windowMs]. */
    private suspend fun assertNoNotificationAppears(
        hash: String,
        windowMs: Long = 500,
    ) {
        withTimeoutOrNull(windowMs) {
            while (true) {
                assertNull("unexpected notification for $hash", activeMessageNotification(hash))
                delay(20)
            }
        }
    }

    private fun inbound(
        id: String,
        content: String,
    ) = ReceivedMessage(
        messageHash = id,
        content = content,
        sourceHash = senderHash,
        destinationHash = ownDestHash,
        timestamp = 1_000L,
    )

    private suspend fun seedInboundBeforeStart(
        identity: LocalIdentityEntity,
        id: String,
        content: String,
        threadId: ByteArray? = null,
    ) {
        conversationRepository.saveMessage(
            peerHash = peerHash,
            peerName = "Peer",
            message =
                DataMessage(
                    id = id,
                    destinationHash = peerHash,
                    content = content,
                    timestamp = 1_000L,
                    isFromMe = false,
                    status = "delivered",
                    receivedAt = 1_000L,
                    threadId = threadId,
                ),
            localIdentityHash = identity.identityHash,
        )
    }

    @Test
    fun `row persisted before start posts its real notification and records handling`() =
        runBlocking {
            val identity = insertTestIdentity()
            seedInboundBeforeStart(identity, "seeded", "Hello before start")
            assertNotHandled(identity.identityHash, "seeded")

            startCollector()

            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "seeded")
            val posted = requireNotNull(activeMessageNotification(peerHash))
            assertEquals("Hello before start", posted.notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("Peer", posted.notification.extras.getString(Notification.EXTRA_TITLE))
        }

    @Test
    fun `transient pending read failure recovers without restarting collector`() =
        runBlocking {
            val identity = insertTestIdentity()
            seedInboundBeforeStart(identity, "read-retry", "Recovered pending message")
            val failOnce = AtomicBoolean(true)
            val notificationDao =
                object : MessageDao by messageDao {
                    override suspend fun getPendingNotificationMessages(
                        identityHash: String,
                        limit: Int,
                    ): List<PendingNotificationMessage> {
                        if (failOnce.getAndSet(false)) throw SQLiteException("Transient pending read failure")
                        return messageDao.getPendingNotificationMessages(identityHash, limit)
                    }
                }

            startCollector(notificationDao)

            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "read-retry")
            assertEquals(
                "Recovered pending message",
                activeMessageNotification(peerHash)?.notification?.extras?.getString(Notification.EXTRA_TEXT),
            )
            seedInboundBeforeStart(identity, "after-read-retry", "Later message still notifies")
            awaitHandled(identity.identityHash, "after-read-retry")
            assertEquals(
                "Later message still notifies",
                activeMessageNotification(peerHash)?.notification?.extras?.getString(Notification.EXTRA_TEXT),
            )
        }

    @Test
    fun `transient notification observation failure resubscribes without a backend event`() =
        runBlocking {
            val identity = insertTestIdentity()
            seedInboundBeforeStart(identity, "observe-retry", "Recovered observation")
            val failOnce = AtomicBoolean(true)
            val notificationDao =
                object : MessageDao by messageDao {
                    override fun observePendingNotificationCount(identityHash: String): Flow<Int> =
                        flow {
                            if (failOnce.getAndSet(false)) throw SQLiteException("Transient observation failure")
                            emitAll(messageDao.observePendingNotificationCount(identityHash))
                        }
                }

            startCollector(notificationDao)

            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "observe-retry")
            assertEquals(
                "Recovered observation",
                activeMessageNotification(peerHash)?.notification?.extras?.getString(Notification.EXTRA_TEXT),
            )
        }

    @Test
    fun `dismissed notification never reappears after collector restart and replay`() =
        runBlocking {
            val identity = insertTestIdentity()
            seedInboundBeforeStart(identity, "replayed", "Original content")
            startCollector()
            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "replayed")

            val conversationBefore =
                requireNotNull(conversationDao.getConversation(peerHash, identity.identityHash))
            notificationManager.cancel(
                NotificationHelper.messageNotificationTag(peerHash),
                NotificationHelper.NOTIFICATION_ID_MESSAGE,
            )
            assertNull(activeMessageNotification(peerHash))

            collector?.stopCollecting()
            startCollector()
            awaitSubscription()

            // A fresh icon on the duplicate callback proves the replay actually reached the
            // collector's duplicate branch while staying silent.
            messageFlow.emit(
                inbound("replayed", "Stale replay").copy(
                    iconAppearance =
                        network.columba.app.rns.api.model.IconAppearance(
                            iconName = "replayed-icon",
                            foregroundColor = "00ff00",
                            backgroundColor = "000000",
                        ),
                ),
            )
            withTimeout(5_000) {
                while (database.peerIconDao().getIcon(senderHashHex) == null) delay(20)
            }
            assertNoNotificationAppears(peerHash)

            val conversationAfter =
                requireNotNull(conversationDao.getConversation(peerHash, identity.identityHash))
            assertEquals("Original content", conversationAfter.lastMessage)
            assertEquals(conversationBefore.lastMessageTimestamp, conversationAfter.lastMessageTimestamp)
            assertEquals(1, conversationAfter.unreadCount)
            assertEquals(
                "Original content",
                messageDao.getMessageById("replayed", identity.identityHash)?.content,
            )
            assertEquals(true, messageDao.getMessageById("replayed", identity.identityHash)?.notificationHandled)
            assertEquals(1, messageDao.getAllMessagesForIdentity(identity.identityHash).size)
        }

    @Test
    fun `insert after observation notifies without an IPC message`() =
        runBlocking {
            val identity = insertTestIdentity()
            startCollector()
            assertNoNotificationAppears(peerHash, windowMs = 200)

            seedInboundBeforeStart(identity, "live-room-insert", "No IPC needed")

            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "live-room-insert")
        }

    @Test
    fun `active identity switch processes only the newly active identity's pending rows`() =
        runBlocking {
            val first =
                insertTestIdentity(
                    identityHash = "first-identity",
                    isActive = true,
                )
            val second =
                insertTestIdentity(
                    identityHash = "second-identity",
                    isActive = false,
                )
            startCollector()

            seedInboundBeforeStart(first, "first-message", "First identity message")
            awaitNotification(peerHash)
            awaitHandled(first.identityHash, "first-message")

            conversationRepository.saveMessage(
                peerHash = secondPeerHash,
                peerName = "Second Peer",
                message =
                    DataMessage(
                        id = "second-message",
                        destinationHash = secondPeerHash,
                        content = "Second identity message",
                        timestamp = 2_000L,
                        isFromMe = false,
                        receivedAt = 2_000L,
                    ),
                localIdentityHash = second.identityHash,
            )
            awaitPending(second.identityHash, "second-message")
            assertNoNotificationAppears(secondPeerHash, windowMs = 300)
            assertNotHandled(second.identityHash, "second-message")

            localIdentityDao.setActive(second.identityHash)

            awaitNotification(secondPeerHash)
            awaitHandled(second.identityHash, "second-message")
            assertEquals(true, messageDao.getMessageById("first-message", first.identityHash)?.notificationHandled)
            assertEquals(0, messageDao.getPendingNotificationMessages(first.identityHash, 32).size)
            assertEquals(0, messageDao.getPendingNotificationMessages(second.identityHash, 32).size)
        }

    @Test
    fun `already read row is acknowledged silently`() =
        runBlocking {
            val identity = insertTestIdentity()
            seedInboundBeforeStart(identity, "read-row", "Already seen")
            conversationRepository.markConversationAsRead(peerHash)
            assertTrue(messageDao.getMessageById("read-row", identity.identityHash)?.isRead ?: false)

            startCollector()

            awaitHandled(identity.identityHash, "read-row")
            assertNull(activeMessageNotification(peerHash))
        }

    @Test
    fun `disabled preference acknowledges without posting and never posts retroactively`() =
        runBlocking {
            val identity = insertTestIdentity()
            notificationsEnabled.value = false
            seedInboundBeforeStart(identity, "suppressed-preference", "Suppressed")

            startCollector()

            awaitHandled(identity.identityHash, "suppressed-preference")
            notificationsEnabled.value = true
            assertNoNotificationAppears(peerHash)
        }

    @Test
    fun `denied permission acknowledges without posting and never posts retroactively`() =
        runBlocking {
            val identity = insertTestIdentity()
            shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            seedInboundBeforeStart(identity, "suppressed-permission", "Suppressed")

            startCollector()

            awaitHandled(identity.identityHash, "suppressed-permission")
            shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertNoNotificationAppears(peerHash)
        }

    @Test
    fun `foreground active conversation acknowledges without posting and never posts retroactively`() =
        runBlocking {
            val identity = insertTestIdentity()
            notificationHelper.isAppInForeground = { true }
            activeConversationManager.setActive(peerHash)
            seedInboundBeforeStart(identity, "suppressed-foreground", "Suppressed")

            startCollector()

            awaitHandled(identity.identityHash, "suppressed-foreground")
            activeConversationManager.setActive(null)
            notificationHelper.isAppInForeground = { false }
            assertNoNotificationAppears(peerHash)
        }

    @Test
    fun `catch-up notification keeps its thread when another thread is visible`() =
        runBlocking {
            val identity = insertTestIdentity()
            threadsEnabled.value = true
            notificationHelper.isAppInForeground = { true }
            activeConversationManager.setActive(peerHash, "different-thread")
            seedInboundBeforeStart(
                identity,
                "thread-catch-up",
                "Message for another thread",
                threadId = ByteArray(16) { it.toByte() },
            )

            startCollector()

            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "thread-catch-up")
            val posted = requireNotNull(activeMessageNotification(peerHash))
            val target = shadowOf(posted.notification.contentIntent).savedIntent
            assertEquals(
                "000102030405060708090a0b0c0d0e0f",
                target.getStringExtra(NotificationHelper.EXTRA_THREAD_ID),
            )
        }

    @Test
    fun `catch-up for the displayed thread is acknowledged without an alert`() =
        runBlocking {
            val identity = insertTestIdentity()
            threadsEnabled.value = true
            notificationHelper.isAppInForeground = { true }
            activeConversationManager.setActive(peerHash, "000102030405060708090a0b0c0d0e0f")
            seedInboundBeforeStart(
                identity,
                "visible-thread",
                "Already on screen",
                threadId = ByteArray(16) { it.toByte() },
            )

            startCollector()

            awaitHandled(identity.identityHash, "visible-thread")
            assertNull(activeMessageNotification(peerHash))
            activeConversationManager.setActive(null)
            assertNoNotificationAppears(peerHash)
        }

    @Test
    fun `private catch-up notification does not expose stored sender or message text`() =
        runBlocking {
            val identity = insertTestIdentity()
            notificationPrivacy.value = NotificationPrivacyLevel.NONE
            seedInboundBeforeStart(identity, "private-catch-up", "Secret stored message")

            startCollector()

            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "private-catch-up")
            val notification = requireNotNull(activeMessageNotification(peerHash)).notification
            assertEquals(
                context.getString(R.string.notif_generic_message),
                notification.extras.getString(Notification.EXTRA_TITLE),
            )
            assertNull(notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(
                context.getString(R.string.notif_generic_message),
                notification.publicVersion.extras.getString(Notification.EXTRA_TITLE),
            )
            assertNull(notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        }

    @Test
    fun `independent writers yield one pending delivery and one notification handling`() =
        runBlocking {
            val identity = insertTestIdentity()
            // A second writer repository over the same Room database stands in for the
            // service process's independent writer.
            val serviceWriter =
                ConversationRepository(
                    conversationDao = conversationDao,
                    messageDao = messageDao,
                    peerIdentityDao = peerIdentityDao,
                    localIdentityDao = localIdentityDao,
                    attachmentStorage = AttachmentStorageManager(context),
                    draftDao = draftDao,
                    threadDao = database.threadDao(),
                    database = database,
                )
            val message =
                DataMessage(
                    id = "shared-delivery",
                    destinationHash = peerHash,
                    content = "Original",
                    timestamp = 1_000L,
                    isFromMe = false,
                    receivedAt = 1_000L,
                )
            conversationRepository.saveMessage(
                peerHash = peerHash,
                peerName = "Peer",
                message = message,
                localIdentityHash = identity.identityHash,
            )
            serviceWriter.saveMessage(
                peerHash = peerHash,
                peerName = "Peer",
                message = message.copy(content = "Service replay", receivedAt = 2_000L),
                localIdentityHash = identity.identityHash,
            )
            assertEquals(1, messageDao.getAllMessagesForIdentity(identity.identityHash).size)
            assertEquals(1, conversationDao.getConversation(peerHash, identity.identityHash)?.unreadCount)

            startCollector()
            awaitNotification(peerHash)
            awaitHandled(identity.identityHash, "shared-delivery")

            assertNotNull(activeMessageNotification(peerHash))
            assertEquals(
                1,
                notificationManager.activeNotifications.count {
                    it.tag == NotificationHelper.messageNotificationTag(peerHash)
                },
            )
            assertEquals("Original", conversationDao.getConversation(peerHash, identity.identityHash)?.lastMessage)
            assertEquals(0, messageDao.ackNotificationHandled("shared-delivery", "unrelated-identity"))
            assertNotNull(activeMessageNotification(peerHash))
        }

    private suspend fun awaitSubscription() {
        withTimeout(5_000) {
            while (messageFlow.subscriptionCount.value == 0) delay(20)
        }
    }

    private suspend fun assertNotHandled(
        identityHash: String,
        messageId: String,
    ) {
        assertEquals(false, messageDao.getMessageById(messageId, identityHash)?.notificationHandled)
    }
}
