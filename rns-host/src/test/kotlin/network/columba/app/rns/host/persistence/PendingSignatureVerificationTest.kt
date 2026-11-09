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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import network.reticulum.common.toHexString
import network.reticulum.crypto.Hashes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `late peer-only key replacement resolves retained evidence without another message or announce`() =
        runTest {
            // Inline Room executors let the test scheduler drain the initial
            // reconciliation before the only subsequent write: a peer-key replacement.
            database.close()
            database =
                Room.databaseBuilder(context, ColumbaDatabase::class.java, DATABASE_NAME)
                    .allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            insertMessage(PythonLxmfFixture.wire)
            insertIdentityAndConversation("inactive-identity", isActive = false)
            insertMessage(PythonLxmfFixture.forgedWire, "inactive-identity")
            val peers = database.peerIdentityDao()
            peers.insertPeerIdentity(PeerIdentityEntity("other-aspect-key", ByteArray(64), 1L))
            val job = PendingSignatureVerification(database).startReconciliation(this)
            try {
                advanceUntilIdle()
                assertEquals(SignatureStatus.SOURCE_UNKNOWN, message().signatureStatus)
                assertEquals(
                    SignatureStatus.SOURCE_UNKNOWN,
                    database.messageDao().getMessageById(PythonLxmfFixture.messageHash, "inactive-identity")?.signatureStatus,
                )
                // The peer count stays one. Neither messages nor announces change.
                peers.insertPeerIdentity(PeerIdentityEntity("other-aspect-key", PythonLxmfFixture.publicKey, 2L))
                advanceUntilIdle()
                assertEquals(SignatureStatus.VERIFIED, message().signatureStatus)
                assertEquals(
                    SignatureStatus.INVALID,
                    database.messageDao().getMessageById(PythonLxmfFixture.messageHash, "inactive-identity")?.signatureStatus,
                )
                assertNull(database.messageDao().readSignatureEvidence(PythonLxmfFixture.messageHash, LOCAL_IDENTITY))
                assertNull(database.messageDao().readSignatureEvidence(PythonLxmfFixture.messageHash, "inactive-identity"))
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun `foreign aspect keys resolve multiple sender batches across identities after reload`() =
        runBlocking {
            insertIdentityAndConversation("inactive-identity", isActive = false)
            insertMessage(PythonLxmfFixture.wire)
            insertMessage(PythonLxmfFixture.forgedWire, "inactive-identity")
            // Distractors fill multiple key pages, all with the same timestamp.
            // A malformed key and stale direct lookups must not hide a later match.
            database.peerIdentityDao().insertPeerIdentities(
                (0 until 65).map { index ->
                    PeerIdentityEntity("a-${index.toString().padStart(3, '0')}", ByteArray(64), 1L)
                } +
                    listOf(
                        PeerIdentityEntity("b-malformed", byteArrayOf(1), 1L),
                        PeerIdentityEntity(PythonLxmfFixture.source, ByteArray(64), 1L),
                        PeerIdentityEntity("z-foreign-aspect", PythonLxmfFixture.publicKey, 1L),
                    ),
            )
            database.announceDao().upsertAnnounce(announce().copy(publicKey = ByteArray(64)))
            // Each packet claims a different source, but retains the original
            // signature. Matching its persisted key must therefore yield INVALID,
            // not SOURCE_UNKNOWN. More than 32 sources also crosses sender pages.
            val forgedMessages =
                (1..35).map { index ->
                    val publicKey = PythonLxmfFixture.publicKey.copyOf()
                    publicKey[0] = (publicKey[0].toInt() xor index).toByte()
                    val sender = requireNotNull(LxmfSignatureVerifier.senderHash(publicKey))
                    val evidence = PythonLxmfFixture.wire.copyOf()
                    sender.hexToByteArray().copyInto(evidence, destinationOffset = 16)
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(evidence, 0, 32)
                    digest.update(evidence, 96, evidence.size - 96)
                    val messageId = digest.digest().toHexString()
                    val identityHash = if (index % 2 == 0) LOCAL_IDENTITY else "inactive-identity"
                    database.conversationDao().insertConversation(
                        ConversationEntity(sender, identityHash, "Other aspect", lastMessage = "preview", lastMessageTimestamp = 1L),
                    )
                    database.peerIdentityDao().insertPeerIdentity(
                        PeerIdentityEntity("z-other-aspect-${index.toString().padStart(3, '0')}", publicKey, 1L),
                    )
                    database.messageDao().insertMessageWithSignatureEvidence(
                        MessageEntity(
                            id = messageId,
                            conversationHash = sender,
                            identityHash = identityHash,
                            content = "display text is not evidence",
                            timestamp = 1L,
                            isFromMe = false,
                            signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                        ),
                        evidence,
                    )
                    messageId to identityHash
                }
            database.close()
            database = openDatabase()
            PendingSignatureVerification(database).reconcile()
            assertEquals(SignatureStatus.VERIFIED, message().signatureStatus)
            assertEquals(
                SignatureStatus.INVALID,
                database.messageDao().getMessageById(PythonLxmfFixture.messageHash, "inactive-identity")?.signatureStatus,
            )
            assertNull(database.messageDao().readSignatureEvidence(PythonLxmfFixture.messageHash, LOCAL_IDENTITY))
            assertNull(database.messageDao().readSignatureEvidence(PythonLxmfFixture.messageHash, "inactive-identity"))
            for ((messageId, identityHash) in forgedMessages) {
                assertEquals(SignatureStatus.INVALID, database.messageDao().getMessageById(messageId, identityHash)?.signatureStatus)
                assertNull(database.messageDao().readSignatureEvidence(messageId, identityHash))
            }
            assertEquals(emptyList<String>(), database.messageDao().getPendingSignatureSenders("", 32))
        }

    @Test
    fun `peer key cursor is deterministic despite tied or changing timestamps and earlier deletion`() =
        runBlocking {
            val peers = database.peerIdentityDao()
            peers.insertPeerIdentities(
                listOf("d", "b", "a", "c").map { PeerIdentityEntity(it, PythonLxmfFixture.publicKey, 1L) },
            )
            val firstPage = peers.getPeerIdentitiesAfter("", 2)
            assertEquals(listOf("a", "b"), firstPage.map { it.peerHash })
            peers.updatePeerIdentity(requireNotNull(peers.getPeerIdentity("a")).copy(lastSeenTimestamp = 100L))
            peers.updatePeerIdentity(requireNotNull(peers.getPeerIdentity("d")).copy(lastSeenTimestamp = 200L))
            peers.deletePeerIdentity("a")
            val secondPage = peers.getPeerIdentitiesAfter(firstPage.last().peerHash, 2)
            assertEquals(listOf("c", "d"), secondPage.map { it.peerHash })
            assertEquals(emptyList<PeerIdentityEntity>(), peers.getPeerIdentitiesAfter(secondPage.last().peerHash, 2))
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

    @Test
    fun `candidate key lookup includes only inbound unknown senders with retained evidence across identities`() =
        runBlocking {
            insertMessage(PythonLxmfFixture.wire)
            insertIdentityAndConversation("inactive-identity", isActive = false)
            insertMessage(PythonLxmfFixture.wire, "inactive-identity")
            val original = message()
            val ignoredSenders = listOf("outgoing", "legacy", "no-evidence")
            for (sender in ignoredSenders) {
                database.conversationDao().insertConversation(
                    ConversationEntity(sender, LOCAL_IDENTITY, sender, lastMessage = "ignored", lastMessageTimestamp = 1L),
                )
                val ignored =
                    original.copy(
                        id = sender,
                        conversationHash = sender,
                        isFromMe = sender == "outgoing",
                        signatureStatus = if (sender == "legacy") SignatureStatus.UNVERIFIED else SignatureStatus.SOURCE_UNKNOWN,
                    )
                if (sender == "no-evidence") {
                    database.messageDao().insertMessage(ignored)
                } else {
                    database.messageDao().insertMessageWithSignatureEvidence(ignored, PythonLxmfFixture.wire)
                }
            }
            assertEquals(
                listOf(PythonLxmfFixture.source),
                database.messageDao().getPendingSignatureSendersMatching(
                    ignoredSenders + listOf(PythonLxmfFixture.source, "absent"),
                ),
            )
            assertEquals(
                emptyList<String>(),
                database.messageDao().getPendingSignatureSendersMatching(ignoredSenders),
            )
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
