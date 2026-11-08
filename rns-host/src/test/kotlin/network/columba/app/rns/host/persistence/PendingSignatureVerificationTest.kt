package network.columba.app.rns.host.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.PeerIdentityEntity
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.model.AnnounceEvent
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.NodeType
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.host.di.ServiceDatabaseProvider
import network.reticulum.common.hexToByteArray
import network.reticulum.crypto.Hashes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingSignatureVerificationTest {
    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = openDatabase()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runBlocking { insertIdentityAndConversation(LOCAL_IDENTITY) }
    }

    @After
    fun teardown() {
        scope.cancel()
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `service event collector persists and resolves an inactive recipient message without any UI observer`() =
        runBlocking {
            database.localIdentityDao().deactivateAll()
            database.localIdentityDao().insert(
                LocalIdentityEntity(
                    "different-active-identity", "Other", "00".repeat(16), "unused",
                    createdTimestamp = 1L, lastUsedTimestamp = 1L, isActive = true,
                ),
            )
            val incoming = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 1)
            val announceEvents = MutableSharedFlow<AnnounceEvent>(extraBufferCapacity = 1)
            val backend = mockk<RnsBackend>()
            every { backend.lxmf.observeMessages() } returns incoming
            every { backend.lxmf.observeDeliveryStatus() } returns emptyFlow()
            every { backend.core.observeAnnounces() } returns announceEvents
            every { backend.core.observeLinks() } returns emptyFlow()
            every { backend.telemetry.locationTelemetryFlow } returns MutableSharedFlow()
            every { backend.transportAdmin.reactionReceivedFlow } returns MutableSharedFlow()
            val settings = mockk<ServiceSettingsAccessor>()
            every { settings.getBlockUnknownSenders() } returns false
            mockkObject(ServiceDatabaseProvider)
            try {
                every { ServiceDatabaseProvider.getDatabase(any()) } returns database
                val manager = ServicePersistenceManager(context, scope, settings)
                PeerActivityCollector(backend, manager).start(scope)
                incoming.emit(
                    ReceivedMessage(
                        PythonLxmfFixture.messageHash, "Service received message",
                        PythonLxmfFixture.source.hexToByteArray(), PythonLxmfFixture.destination.hexToByteArray(), 1L,
                        signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                        signatureEvidence = PythonLxmfFixture.forgedWire,
                    ),
                )
                // No app MessageCollector or IPC message subscriber exists.
                awaitStatus(SignatureStatus.SOURCE_UNKNOWN)
                assertNull(database.messageDao().getMessageById(PythonLxmfFixture.messageHash, "different-active-identity"))
                announceEvents.emit(
                    AnnounceEvent(
                        destinationHash = PythonLxmfFixture.source.hexToByteArray(),
                        identity = Identity(Hashes.truncatedHash(PythonLxmfFixture.publicKey), PythonLxmfFixture.publicKey, null),
                        appData = null,
                        hops = 1,
                        timestamp = 1L,
                        nodeType = NodeType.PEER,
                        aspect = "lxmf.delivery",
                    ),
                )
                awaitStatus(SignatureStatus.INVALID)
                assertNull(database.messageDao().readSignatureEvidence(PythonLxmfFixture.messageHash, LOCAL_IDENTITY))
            } finally {
                scope.cancel()
                unmockkObject(ServiceDatabaseProvider)
            }
        }

    @Test
    fun `announce resolves genuine unknown message and emits a verified conversation update without UI`() =
        runBlocking {
            insertMessage(PythonLxmfFixture.wire)
            PendingSignatureVerification(database).startReconciliation(scope)
            assertEquals(SignatureStatus.SOURCE_UNKNOWN, message().signatureStatus)
            insertAnnounce()
            val resolved = awaitStatus(SignatureStatus.VERIFIED)
            assertNull(database.messageDao().readSignatureEvidence(resolved.id, resolved.identityHash))
            assertEquals("sanitized display text is not signed evidence", resolved.content)
        }

    @Test
    fun `announce resolves wrong-key signature to invalid without deleting message`() =
        runBlocking {
            insertMessage(PythonLxmfFixture.forgedWire)
            PendingSignatureVerification(database).startReconciliation(scope)
            insertAnnounce()
            val resolved = awaitStatus(SignatureStatus.INVALID)
            assertNull(database.messageDao().readSignatureEvidence(resolved.id, resolved.identityHash))
            assertEquals("sanitized display text is not signed evidence", resolved.content)
        }

    @Test
    fun `announce arriving before insertion still resolves the newly stored message`() =
        runBlocking<Unit> {
            insertAnnounce()
            PendingSignatureVerification(database).startReconciliation(scope)
            insertMessage(PythonLxmfFixture.stampedWire)
            awaitStatus(SignatureStatus.VERIFIED)
        }

    @Test
    fun `pending evidence and key survive database close and resolve on service restart`() =
        runBlocking<Unit> {
            insertMessage(PythonLxmfFixture.wire)
            insertAnnounce()
            database.close()
            database = openDatabase()
            PendingSignatureVerification(database).startReconciliation(scope)
            awaitStatus(SignatureStatus.VERIFIED)
        }

    @Test
    fun `another aspect identity key resolves messages for inactive local identities`() =
        runBlocking<Unit> {
            // A validated telephony announce has a different destination, but its
            // identity public key can verify the sender's LXMF messages as well.
            database.peerIdentityDao().insertPeerIdentity(
                PeerIdentityEntity("identity-key", PythonLxmfFixture.publicKey, 1L),
            )
            insertIdentityAndConversation("inactive-identity", isActive = false)
            insertMessage(PythonLxmfFixture.wire, identityHash = "inactive-identity")
            PendingSignatureVerification(database).startReconciliation(scope)
            awaitStatus(SignatureStatus.VERIFIED, "inactive-identity")
        }

    @Test
    fun `unrelated announce does not promote unknown sender or alter legacy unverified rows`() =
        runBlocking {
            insertMessage(PythonLxmfFixture.wire)
            database.messageDao().insertMessage(message().copy(id = "old-message", signatureStatus = SignatureStatus.UNVERIFIED))
            database.announceDao().upsertAnnounce(announce().copy(publicKey = ByteArray(64)))
            PendingSignatureVerification(database).reconcile()
            assertEquals(SignatureStatus.SOURCE_UNKNOWN, message().signatureStatus)
            assertEquals(SignatureStatus.UNVERIFIED, database.messageDao().getMessageById("old-message", LOCAL_IDENTITY)?.signatureStatus)
        }

    private fun openDatabase(): ColumbaDatabase =
        Room
            .databaseBuilder(context, ColumbaDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()

    private suspend fun insertIdentityAndConversation(
        identityHash: String,
        isActive: Boolean = true,
    ) {
        database.localIdentityDao().insert(
            LocalIdentityEntity(
                identityHash,
                "Local",
                PythonLxmfFixture.destination,
                "unused",
                createdTimestamp = 1L,
                lastUsedTimestamp = 1L,
                isActive = isActive,
            ),
        )
        database.conversationDao().insertConversation(
            ConversationEntity(PythonLxmfFixture.source, identityHash, "Alice", lastMessage = "hello", lastMessageTimestamp = 1L),
        )
    }

    private suspend fun insertMessage(
        evidence: ByteArray,
        identityHash: String = LOCAL_IDENTITY,
    ) {
        database.messageDao().insertMessageWithSignatureEvidence(
            MessageEntity(
                id = PythonLxmfFixture.messageHash,
                conversationHash = PythonLxmfFixture.source,
                identityHash = identityHash,
                content = "sanitized display text is not signed evidence",
                timestamp = 1L,
                isFromMe = false,
                signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
            ),
            evidence,
        )
    }

    private fun announce() = AnnounceEntity(PythonLxmfFixture.source, "Alice", PythonLxmfFixture.publicKey, null, 1, 1L, "PEER", null, aspect = "lxmf.delivery")

    private suspend fun insertAnnounce() = database.announceDao().upsertAnnounce(announce())

    private suspend fun message() = database.messageDao().getMessageById(PythonLxmfFixture.messageHash, LOCAL_IDENTITY)!!

    private suspend fun awaitStatus(
        status: SignatureStatus,
        identityHash: String = LOCAL_IDENTITY,
    ): MessageEntity =
        withTimeout(10_000L) {
            database
                .messageDao()
                .getMessagesForConversation(PythonLxmfFixture.source, identityHash)
                .first { rows -> rows.any { it.id == PythonLxmfFixture.messageHash && it.signatureStatus == status } }
                .first { it.id == PythonLxmfFixture.messageHash }
        }

    private companion object {
        const val DATABASE_NAME = "signature-resolution-test.db"
        const val LOCAL_IDENTITY = "local-identity"
    }
}
