package network.columba.app.rns.host.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.model.DeliveryStatusUpdate
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.host.di.ServiceDatabaseProvider
import network.columba.app.rns.host.notifications.ServiceMessageNotifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GitHub #875: a point-to-point send whose LXMF proof is dropped in flight (for
 * example when the TCP transport socket is torn down while the proof is in
 * transit) has no retry and no watchdog, so the row stays `pending` forever and
 * the bubble is never retryable while the app runs.
 *
 * The service owns the row's delivery state, so the deadline must be enforced
 * there, independent of whether any UI screen (or the app process) is alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class OutboundProofTimeoutTest {
    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private var databaseProviderMocked = false

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
    }

    @After
    fun teardown() {
        if (databaseProviderMocked) {
            unmockkObject(ServiceDatabaseProvider)
            databaseProviderMocked = false
        }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `stale opportunistic send is failed once its proof deadline passes`() =
        runTest {
            seedConversation()
            insertOutbound(status = "pending", deliveryMethod = "opportunistic", sentAt = System.currentTimeMillis() - 61_000L)

            val job = startCollector(this)

            advanceTimeBy(PROOF_SWEEP + 1_000L)
            runCurrent()

            val row = database.messageDao().getOutgoingMessageById(MESSAGE_HASH, IDENTITY_HASH)
            assertEquals("failed", row?.status)
            assertNotNull(row?.errorMessage)
            job.cancel()
        }

    @Test
    fun `fresh and terminal outbound rows are left alone`() =
        runTest {
            seedConversation()
            // Just sent: still inside the opportunistic deadline.
            insertOutbound(
                id = "fresh",
                status = "pending",
                deliveryMethod = "opportunistic",
                sentAt = System.currentTimeMillis() - 5_000L,
            )
            // Already settled by a proof: the guarded update must not downgrade it.
            insertOutbound(
                id = "settled",
                status = "delivered",
                deliveryMethod = "opportunistic",
                sentAt = System.currentTimeMillis() - 61_000L,
            )
            // Relay hand-off owns its own terminal state.
            insertOutbound(
                id = "relayed",
                status = "pending",
                deliveryMethod = "propagated",
                sentAt = System.currentTimeMillis() - 61_000L,
            )

            val job = startCollector(this)

            advanceTimeBy(PROOF_SWEEP + 1_000L)
            runCurrent()

            val dao = database.messageDao()
            assertEquals("pending", dao.getOutgoingMessageById("fresh", IDENTITY_HASH)?.status)
            assertEquals("delivered", dao.getOutgoingMessageById("settled", IDENTITY_HASH)?.status)
            assertEquals("pending", dao.getOutgoingMessageById("relayed", IDENTITY_HASH)?.status)
            assertNull(dao.getOutgoingMessageById("fresh", IDENTITY_HASH)?.errorMessage)
            job.cancel()
        }


    @Test
    fun `a re-keyed retry gets a fresh proof window`() =
        runTest {
            seedConversation()
            // Original send is long past every window; the retry re-used its timestamp.
            val originalSend = System.currentTimeMillis() - 10 * 60_000L
            insertOutbound(id = "retry", status = "pending", deliveryMethod = "opportunistic", sentAt = originalSend)

            val retryAt = System.currentTimeMillis()
            val persistence = newPersistence(this)
            persistence.persistDeliveryStatus(
                DeliveryStatusUpdate(
                    messageHash = "retry",
                    status = DeliveryStatus.PENDING,
                    timestamp = retryAt,
                    originatingIdentityHash = IDENTITY_HASH,
                ),
            )

            // Half a window after the retry the row must still be waiting ...
            persistence.failExpiredOutboundProofs(retryAt + 30_000L)
            assertEquals(
                "a retry must not inherit the original send's spent deadline",
                "pending",
                database.messageDao().getOutgoingMessageById("retry", IDENTITY_HASH)?.status,
            )

            // ... and only expiry of the retry's own window fails it.
            persistence.failExpiredOutboundProofs(retryAt + 61_000L)
            assertEquals(
                "failed",
                database.messageDao().getOutgoingMessageById("retry", IDENTITY_HASH)?.status,
            )
        }

    private fun newPersistence(scope: TestScope): ServicePersistenceManager {
        if (!databaseProviderMocked) {
            mockkObject(ServiceDatabaseProvider)
            databaseProviderMocked = true
            every { ServiceDatabaseProvider.getDatabase(any()) } returns database
        }
        val settings = mockk<ServiceSettingsAccessor>()
        return ServicePersistenceManager(context, scope, settings, startDeliveryReconciliation = false)
    }

    private fun startCollector(scope: TestScope): Job {
        val persistence = newPersistence(scope)
        val backend =
            mockk<RnsBackend>().apply {
                every { core } returns
                    mockk<RnsCore>().apply {
                        every { observeAnnounces() } returns MutableSharedFlow()
                        every { observeLinks() } returns emptyFlow()
                    }
                every { lxmf } returns
                    mockk<RnsLxmf>().apply {
                        every { observeMessages() } returns MutableSharedFlow()
                        every { observeDeliveryStatus() } returns MutableSharedFlow()
                    }
                every { telemetry } returns
                    mockk<RnsTelemetry>().apply {
                        every { locationTelemetryFlow } returns MutableSharedFlow()
                    }
                every { transportAdmin } returns
                    mockk<RnsTransportAdmin>().apply {
                        every { reactionReceivedFlow } returns MutableSharedFlow()
                    }
            }
        val notifier = mockk<ServiceMessageNotifier>()
        return PeerActivityCollector(backend, persistence, notifier) { System.currentTimeMillis() }.start(scope)
    }

    private suspend fun seedConversation() {
        database.localIdentityDao().insert(
            LocalIdentityEntity(
                identityHash = IDENTITY_HASH,
                displayName = "Local",
                destinationHash = "00".repeat(16),
                filePath = "unused",
                createdTimestamp = 1L,
                lastUsedTimestamp = 1L,
                isActive = true,
            ),
        )
        database.conversationDao().insertConversation(
            ConversationEntity(
                peerHash = PEER_HASH,
                identityHash = IDENTITY_HASH,
                peerName = "Peer",
                lastMessage = "",
                lastMessageTimestamp = 0L,
            ),
        )
    }

    private suspend fun insertOutbound(
        id: String = MESSAGE_HASH,
        status: String,
        deliveryMethod: String,
        sentAt: Long,
    ) {
        database.messageDao().insertMessage(
            MessageEntity(
                id = id,
                conversationHash = PEER_HASH,
                identityHash = IDENTITY_HASH,
                content = "hello",
                timestamp = sentAt,
                isFromMe = true,
                status = status,
                deliveryMethod = deliveryMethod,
            ),
        )
    }

    private companion object {
        const val PROOF_SWEEP = 30_000L
        const val IDENTITY_HASH = "local-identity"
        const val PEER_HASH = "abcdef0123456789abcdef0123456789"
        const val MESSAGE_HASH = "00112233445566778899aabbccddeeff"
    }
}
