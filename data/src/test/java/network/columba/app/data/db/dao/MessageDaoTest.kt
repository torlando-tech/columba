package network.columba.app.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.di.DatabaseModule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import network.columba.app.domain.model.SignatureStatus
import org.junit.Assert.assertArrayEquals

/**
 * Tests for MessageDao operations.
 * Validates CRUD operations, status updates, bulk operations, and paging queries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var identityDao: LocalIdentityDao

    companion object {
        private const val IDENTITY_HASH = "identity_hash_12345678901234567"
        private const val PEER_HASH = "peer_hash_123456789012345678901"
        private const val DEST_HASH = "dest_hash_123456789012345678901"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        messageDao = database.messageDao()
        conversationDao = database.conversationDao()
        identityDao = database.localIdentityDao()

        // Setup required parent entities (FK constraints)
        runTest {
            identityDao.insert(createTestIdentity())
            conversationDao.insertConversation(createTestConversation())
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity() =
        LocalIdentityEntity(
            identityHash = IDENTITY_HASH,
            displayName = "Test Identity",
            destinationHash = DEST_HASH,
            filePath = "/test/identity.key",
            keyData = null,
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    private fun createTestConversation() =
        ConversationEntity(
            peerHash = PEER_HASH,
            identityHash = IDENTITY_HASH,
            peerName = "Test Peer",
            lastMessage = "Hello",
            lastMessageTimestamp = System.currentTimeMillis(),
            unreadCount = 0,
        )

    @Suppress("LongParameterList")
    private fun createTestMessage(
        id: String = "msg_${System.nanoTime()}",
        content: String = "Test message content",
        isFromMe: Boolean = true,
        timestamp: Long = System.currentTimeMillis(),
        status: String = "sent",
        isRead: Boolean = false,
        deliveryMethod: String? = null,
        errorMessage: String? = null,
        fieldsJson: String? = null,
        replyToMessageId: String? = null,
    ) = MessageEntity(
        id = id,
        conversationHash = PEER_HASH,
        identityHash = IDENTITY_HASH,
        content = content,
        timestamp = timestamp,
        isFromMe = isFromMe,
        status = status,
        isRead = isRead,
        deliveryMethod = deliveryMethod,
        errorMessage = errorMessage,
        fieldsJson = fieldsJson,
        replyToMessageId = replyToMessageId,
    )

    // ========== Insert Tests ==========

    @Test
    fun insertMessage_createsNewMessage() =
        runTest {
            val message = createTestMessage(id = "msg1", content = "Hello")
            messageDao.insertMessage(message)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals("msg1", retrieved?.id)
            assertEquals("Hello", retrieved?.content)
        }

    @Test
    fun insertMessage_replacesExistingMessage() =
        runTest {
            val original = createTestMessage(id = "msg1", content = "Original")
            messageDao.insertMessage(original)

            val updated = original.copy(content = "Updated")
            messageDao.insertMessage(updated)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("Updated", retrieved?.content)
        }

    // ========== Update Tests ==========

    @Test
    fun updateMessage_modifiesContent() =
        runTest {
            val message = createTestMessage(id = "msg1", content = "Original")
            messageDao.insertMessage(message)

            val updated = message.copy(content = "Modified")
            messageDao.updateMessage(updated)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("Modified", retrieved?.content)
        }

    @Test
    fun updateMessageStatus_changesStatus() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", status = "sent"))

            messageDao.updateMessageStatus("msg1", IDENTITY_HASH, "delivered")

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("delivered", retrieved?.status)
        }

    @Test
    fun applyDeliveryStatus_enforcesClosedMonotonicLifecycle() =
        runTest {
            suspend fun reduce(current: String, incoming: String,
            ): String {
                val id = "$current-$incoming"
                messageDao.insertMessage(createTestMessage(id = id, status = current))
                messageDao.applyDeliveryStatus(id, IDENTITY_HASH, incoming)
                return requireNotNull(messageDao.getMessageById(id, IDENTITY_HASH)).status
            }

            assertEquals("retrying_propagated", reduce("pending", "retrying_propagated"))
            assertEquals("propagated", reduce("retrying_propagated", "propagated"))
            assertEquals("delivered", reduce("propagated", "delivered"))
            assertEquals("failed", reduce("pending", "failed"))
            assertEquals("failed", reduce("sent", "failed"))
            assertEquals("delivered", reduce("failed", "delivered"))
            assertEquals("failed", reduce("propagated", "failed"))
            assertEquals("delivered", reduce("delivered", "failed"))
            assertEquals("pending", reduce("pending", "unknown"))
        }

    @Test
    fun applyDeliveryStatus_rejectedRetryDoesNotMutateDeliveredMetadata() =
        runTest {
            val message =
                createTestMessage(id = "delivered-stale-retry", status = "delivered").copy(
                    deliveryMethod = "direct",
                    errorMessage = null,
                )
            messageDao.insertMessage(message)

            messageDao.applyDeliveryStatus(message.id, IDENTITY_HASH, "retrying_propagated")

            val reduced = requireNotNull(messageDao.getMessageById(message.id, IDENTITY_HASH))
            assertEquals("delivered", reduced.status)
            assertEquals("direct", reduced.deliveryMethod)
            assertNull(reduced.errorMessage)
        }

    @Test
    fun applyDeliveryStatus_rejectedRetryDoesNotMutateFailedMetadata() =
        runTest {
            val message =
                createTestMessage(id = "failed-stale-retry", status = "failed").copy(
                    deliveryMethod = "direct",
                    errorMessage = "submission failed",
                )
            messageDao.insertMessage(message)

            messageDao.applyDeliveryStatus(message.id, IDENTITY_HASH, "retrying_propagated")

            val reduced = requireNotNull(messageDao.getMessageById(message.id, IDENTITY_HASH))
            assertEquals("failed", reduced.status)
            assertEquals("direct", reduced.deliveryMethod)
            assertEquals("submission failed", reduced.errorMessage)
        }

    @Test
    fun updateMessageDeliveryDetails_setsDeliveryMethod() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))

            messageDao.updateMessageDeliveryDetails("msg1", IDENTITY_HASH, "direct", null)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("direct", retrieved?.deliveryMethod)
            assertNull(retrieved?.errorMessage)
        }

    @Test
    fun updateMessageDeliveryDetails_setsErrorMessage() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", status = "failed"))

            messageDao.updateMessageDeliveryDetails("msg1", IDENTITY_HASH, null, "Connection timeout")

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals("Connection timeout", retrieved?.errorMessage)
        }

    @Test
    fun updateMessageFieldsJson_setsFieldsData() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))

            val fieldsJson = """{"6": "image_hex_data", "15": "markdown"}"""
            messageDao.updateMessageFieldsJson("msg1", IDENTITY_HASH, fieldsJson)

            val retrieved = messageDao.getMessageById("msg1", IDENTITY_HASH)
            assertEquals(fieldsJson, retrieved?.fieldsJson)
        }

    // ========== Delete Tests ==========

    @Test
    fun deleteMessage_removesMessage() =
        runTest {
            val message = createTestMessage(id = "msg1")
            messageDao.insertMessage(message)
            assertTrue(messageDao.messageExists("msg1", IDENTITY_HASH))

            messageDao.deleteMessage(message)

            assertFalse(messageDao.messageExists("msg1", IDENTITY_HASH))
        }

    @Test
    fun deleteMessageById_removesMessage() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))

            messageDao.deleteMessageById("msg1", IDENTITY_HASH)

            assertFalse(messageDao.messageExists("msg1", IDENTITY_HASH))
        }

    @Test
    fun deleteMessagesForConversation_removesAllConversationMessages() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1"))
            messageDao.insertMessage(createTestMessage(id = "msg2"))
            messageDao.insertMessage(createTestMessage(id = "msg3"))

            messageDao.deleteMessagesForConversation(PEER_HASH, IDENTITY_HASH)

            val messages = messageDao.getAllMessagesForIdentity(IDENTITY_HASH)
            assertTrue(messages.isEmpty())
        }

    // ========== Query Tests ==========

    @Test
    fun getLastMessage_returnsNewestMessage() =
        runTest {
            val baseTime = System.currentTimeMillis()
            messageDao.insertMessage(createTestMessage(id = "msg1", timestamp = baseTime - 2000))
            messageDao.insertMessage(createTestMessage(id = "msg2", timestamp = baseTime - 1000))
            messageDao.insertMessage(createTestMessage(id = "msg3", timestamp = baseTime))

            val last = messageDao.getLastMessage(PEER_HASH, IDENTITY_HASH)
            assertEquals("msg3", last?.id)
        }

    @Test
    fun messageExists_returnsTrueForExistingMessage() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "existing"))

            assertTrue(messageDao.messageExists("existing", IDENTITY_HASH))
            assertFalse(messageDao.messageExists("nonexistent", IDENTITY_HASH))
        }

    // ========== Unread Count Tests ==========

    @Test
    fun getUnreadCount_countsOnlyUnreadReceivedMessages() =
        runTest {
            // Unread received messages (should count)
            messageDao.insertMessage(createTestMessage(id = "msg1", isFromMe = false, isRead = false))
            messageDao.insertMessage(createTestMessage(id = "msg2", isFromMe = false, isRead = false))

            // Read received message (should not count)
            messageDao.insertMessage(createTestMessage(id = "msg3", isFromMe = false, isRead = true))

            // Sent messages (should not count regardless of read status)
            messageDao.insertMessage(createTestMessage(id = "msg4", isFromMe = true, isRead = false))

            val count = messageDao.getUnreadCount(PEER_HASH, IDENTITY_HASH)
            assertEquals(2, count)
        }

    @Test
    fun markMessagesAsRead_marksAllReceivedAsRead() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", isFromMe = false, isRead = false))
            messageDao.insertMessage(createTestMessage(id = "msg2", isFromMe = false, isRead = false))
            messageDao.insertMessage(createTestMessage(id = "msg3", isFromMe = true, isRead = false))

            messageDao.markMessagesAsRead(PEER_HASH, IDENTITY_HASH)

            // Received messages should be marked as read
            assertTrue(messageDao.getMessageById("msg1", IDENTITY_HASH)?.isRead == true)
            assertTrue(messageDao.getMessageById("msg2", IDENTITY_HASH)?.isRead == true)

            // Sent message unchanged (only received messages are marked)
            val unreadCount = messageDao.getUnreadCount(PEER_HASH, IDENTITY_HASH)
            assertEquals(0, unreadCount)
        }

    // ========== Bulk Operations Tests ==========

    @Test
    fun insertMessages_bulkInsertsWithReplace() =
        runTest {
            val messages =
                (1..5).map { i ->
                    createTestMessage(id = "msg$i", content = "Message $i")
                }
            messageDao.insertMessages(messages)

            val all = messageDao.getAllMessagesForIdentity(IDENTITY_HASH)
            assertEquals(5, all.size)
        }

    @Test
    fun insertMessagesIgnoreDuplicates_preservesExisting() =
        runTest {
            val original = createTestMessage(id = "msg1", content = "Original")
            messageDao.insertMessage(original)

            val duplicates =
                listOf(
                    createTestMessage(id = "msg1", content = "Duplicate"),
                    createTestMessage(id = "msg2", content = "New"),
                )
            messageDao.insertMessagesIgnoreDuplicates(duplicates)

            assertEquals("Original", messageDao.getMessageById("msg1", IDENTITY_HASH)?.content)
            assertEquals("New", messageDao.getMessageById("msg2", IDENTITY_HASH)?.content)
        }

    @Test
    fun unknownSourceWithoutEvidenceRemainsInconclusiveRatherThanPromisingReverification() =
        runTest {
            for ((id, evidence) in listOf("missing-evidence" to null, "empty-evidence" to byteArrayOf())) {
                val message =
                    createTestMessage(id = id, isFromMe = false).copy(signatureStatus = SignatureStatus.SOURCE_UNKNOWN)
                messageDao.insertMessageWithSignatureEvidence(message, evidence)
                assertEquals(SignatureStatus.UNVERIFIED, messageDao.getMessageById(id, IDENTITY_HASH)?.signatureStatus)
                assertNull(messageDao.readSignatureEvidence(id, IDENTITY_HASH))
            }
            assertEquals(0, messageDao.observePendingSignatureCount().first())
        }

    @Test
    fun pendingSignatures_areBoundedAndPagedAcrossLocalIdentities() =
        runTest {
            val otherIdentity = "zz-identity"
            val otherSender = "sender-z"
            identityDao.insert(createTestIdentity().copy(identityHash = otherIdentity, destinationHash = "destination-z"))
            conversationDao.insertConversation(createTestConversation().copy(identityHash = otherIdentity))
            conversationDao.insertConversation(createTestConversation().copy(peerHash = otherSender))
            val pending =
                createTestMessage(id = "message-a", isFromMe = false).copy(
                    signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                )
            for (message in listOf(
                pending,
                pending.copy(identityHash = otherIdentity),
                pending.copy(id = "message-b"),
                pending.copy(id = "other-sender", conversationHash = otherSender),
                pending.copy(id = "legacy", signatureStatus = SignatureStatus.UNVERIFIED),
                pending.copy(id = "outgoing", isFromMe = true),
                pending.copy(id = "invalid", signatureStatus = SignatureStatus.INVALID),
            )) {
                messageDao.insertMessageWithSignatureEvidence(message, byteArrayOf(1, 2, 3))
            }
            messageDao.insertMessage(pending.copy(id = "missing-evidence"))

            assertEquals(listOf(PEER_HASH), messageDao.getPendingSignatureSenders("", 1))
            assertEquals(listOf(otherSender), messageDao.getPendingSignatureSenders(PEER_HASH, 1))
            assertTrue(messageDao.getPendingSignatureSenders(otherSender, 1).isEmpty())
            val first = messageDao.getPendingSignatureMessages(PEER_HASH, "", "", 1).single()
            assertEquals("message-a", first.id)
            assertEquals(IDENTITY_HASH, first.identityHash)
            assertArrayEquals(byteArrayOf(1, 2, 3), messageDao.readSignatureEvidence(first.id, first.identityHash))
            val second = messageDao.getPendingSignatureMessages(PEER_HASH, first.id, first.identityHash, 1).single()
            assertEquals("message-a", second.id)
            assertEquals(otherIdentity, second.identityHash)
            val third = messageDao.getPendingSignatureMessages(PEER_HASH, second.id, second.identityHash, 1).single()
            assertEquals("message-b", third.id)
            assertTrue(messageDao.getPendingSignatureMessages(PEER_HASH, third.id, third.identityHash, 1).isEmpty())
            assertEquals(4, messageDao.observePendingSignatureCount().first())
        }

    @Test
    fun resolvePendingSignature_isTerminalAtomicAndIdentityScoped() =
        runTest {
            val otherIdentity = "identity-z"
            identityDao.insert(createTestIdentity().copy(identityHash = otherIdentity, destinationHash = "destination-z"))
            conversationDao.insertConversation(createTestConversation().copy(identityHash = otherIdentity))
            for (terminal in listOf(SignatureStatus.VERIFIED, SignatureStatus.INVALID)) {
                val original =
                    createTestMessage(id = terminal.name, content = "unchanged", isFromMe = false).copy(
                        signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                    )
                val evidence = byteArrayOf(7, 8, 9)
                messageDao.insertMessageWithSignatureEvidence(original, evidence)
                messageDao.insertMessageWithSignatureEvidence(original.copy(identityHash = otherIdentity), evidence)
                assertEquals(0, messageDao.resolvePendingSignature(original.id, "wrong-identity", terminal))
                assertEquals(0, messageDao.resolvePendingSignature(original.id, IDENTITY_HASH, SignatureStatus.UNVERIFIED))
                assertEquals(0, messageDao.resolvePendingSignature(original.id, IDENTITY_HASH, SignatureStatus.SOURCE_UNKNOWN))
                assertEquals(1, messageDao.resolvePendingSignature(original.id, IDENTITY_HASH, terminal))
                assertEquals(
                    original.copy(signatureStatus = terminal),
                    messageDao.getMessageById(original.id, IDENTITY_HASH),
                )
                assertNull(messageDao.readSignatureEvidence(original.id, IDENTITY_HASH))
                val conflicting = if (terminal == SignatureStatus.VERIFIED) SignatureStatus.INVALID else SignatureStatus.VERIFIED
                assertEquals(0, messageDao.resolvePendingSignature(original.id, IDENTITY_HASH, conflicting))
                val untouched = requireNotNull(messageDao.getMessageById(original.id, otherIdentity))
                assertEquals(SignatureStatus.SOURCE_UNKNOWN, untouched.signatureStatus)
                assertArrayEquals(evidence, messageDao.readSignatureEvidence(original.id, otherIdentity))
            }
        }

    @Test
    fun resolvePendingSignature_neverReclassifiesLegacyOrOutgoingRows() =
        runTest {
            for (status in listOf(null, SignatureStatus.UNVERIFIED, SignatureStatus.VERIFIED, SignatureStatus.INVALID)) {
                val original = createTestMessage(id = "status-$status", isFromMe = false).copy(signatureStatus = status)
                messageDao.insertMessage(original)
                assertEquals(0, messageDao.resolvePendingSignature(original.id, IDENTITY_HASH, SignatureStatus.INVALID))
                assertEquals(original, messageDao.getMessageById(original.id, IDENTITY_HASH))
            }
            val outgoing =
                createTestMessage(id = "outgoing").copy(
                    signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                )
            messageDao.insertMessageWithSignatureEvidence(outgoing, byteArrayOf(1))
            assertEquals(0, messageDao.resolvePendingSignature(outgoing.id, IDENTITY_HASH, SignatureStatus.INVALID))
            assertEquals(SignatureStatus.SOURCE_UNKNOWN, messageDao.getMessageById(outgoing.id, IDENTITY_HASH)?.signatureStatus)
            assertNull(messageDao.readSignatureEvidence(outgoing.id, IDENTITY_HASH))
        }

    @Test
    fun pendingSignatureObservation_emitsWhenPayloadChangesWithoutChangingCount() =
        runTest {
            messageDao.observePendingSignatureCount().test {
                assertEquals(0, awaitItem())
                val pending =
                    createTestMessage(id = "pending", isFromMe = false).copy(
                        signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                    )
                messageDao.insertMessageWithSignatureEvidence(pending, byteArrayOf(1))
                assertEquals(1, awaitItem())
                messageDao.insertMessageWithSignatureEvidence(pending, byteArrayOf(2))
                assertEquals(1, awaitItem())
                messageDao.resolvePendingSignature(pending.id, IDENTITY_HASH, SignatureStatus.VERIFIED)
                assertEquals(0, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun pendingEvidence_survivesDatabaseReopenAndTerminalResolution() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "pending-signatures-restart"
            database.close()
            context.deleteDatabase(name)
            try {
                database = Room.databaseBuilder(context, ColumbaDatabase::class.java, name).build()
                database.localIdentityDao().insert(createTestIdentity())
                database.conversationDao().insertConversation(createTestConversation())
                database.messageDao().insertMessageWithSignatureEvidence(
                    createTestMessage(id = "durable", isFromMe = false).copy(
                        signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                    ),
                    byteArrayOf(4, 5, 6),
                )
                database.close()
                database = Room.databaseBuilder(context, ColumbaDatabase::class.java, name).build()
                val reopened = database.messageDao()
                assertEquals(listOf(PEER_HASH), reopened.getPendingSignatureSenders("", 10))
                val pending = reopened.getPendingSignatureMessages(PEER_HASH, "", "", 10).single()
                assertArrayEquals(byteArrayOf(4, 5, 6), reopened.readSignatureEvidence(pending.id, pending.identityHash))
                assertEquals(1, reopened.resolvePendingSignature(pending.id, pending.identityHash, SignatureStatus.VERIFIED))
                database.close()
                database = Room.databaseBuilder(context, ColumbaDatabase::class.java, name).build()
                assertTrue(database.messageDao().getPendingSignatureSenders("", 10).isEmpty())
                val resolved = requireNotNull(database.messageDao().getMessageById("durable", IDENTITY_HASH))
                assertEquals(SignatureStatus.VERIFIED, resolved.signatureStatus)
                assertNull(database.messageDao().readSignatureEvidence("durable", IDENTITY_HASH))
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun largeEvidence_neverEntersMessageCursorsAndReassemblesExactly() =
        runTest {
            val evidence = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
            val pending =
                createTestMessage(id = "large-payload", isFromMe = false).copy(
                    signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                )
            messageDao.insertMessageWithSignatureEvidence(pending, evidence)
            assertEquals(pending, messageDao.getMessageById(pending.id, IDENTITY_HASH))
            assertEquals(listOf(pending), messageDao.getMessagesForConversation(PEER_HASH, IDENTITY_HASH).first())
            assertEquals(listOf(PendingSignatureMessage(pending.id, IDENTITY_HASH)), messageDao.getPendingSignatureMessages(PEER_HASH, "", "", 10))
            assertEquals(evidence.size.toLong(), messageDao.getSignatureEvidenceSize(pending.id, IDENTITY_HASH))
            assertEquals(256 * 1024, messageDao.getSignatureEvidenceChunk(pending.id, IDENTITY_HASH, 0)?.size)
            assertEquals(17, messageDao.getSignatureEvidenceChunk(pending.id, IDENTITY_HASH, 12)?.size)
            assertArrayEquals(evidence, messageDao.readSignatureEvidence(pending.id, IDENTITY_HASH))
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM message_signature_evidence WHERE messageId = 'large-payload' AND chunkIndex = 1",
            )
            assertNull("incomplete evidence cannot be treated as an authentic payload", messageDao.readSignatureEvidence(pending.id, IDENTITY_HASH))
        }

    @Test
    fun evidenceCascadesOnMessageConversationAndIdentityDeletion() =
        runTest {
            val otherIdentity = "other-identity"
            identityDao.insert(createTestIdentity().copy(identityHash = otherIdentity, destinationHash = "other-destination"))
            conversationDao.insertConversation(createTestConversation().copy(identityHash = otherIdentity))
            val pending =
                createTestMessage(id = "same-id", isFromMe = false).copy(
                    signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                )
            val evidence = byteArrayOf(1, 2, 3)
            messageDao.insertMessageWithSignatureEvidence(pending, evidence)
            messageDao.insertMessageWithSignatureEvidence(pending.copy(identityHash = otherIdentity), evidence)
            messageDao.deleteMessageById(pending.id, IDENTITY_HASH)
            assertNull(messageDao.readSignatureEvidence(pending.id, IDENTITY_HASH))
            assertArrayEquals(evidence, messageDao.readSignatureEvidence(pending.id, otherIdentity))
            messageDao.insertMessageWithSignatureEvidence(pending, evidence)
            conversationDao.deleteConversationByKey(PEER_HASH, IDENTITY_HASH)
            assertNull(messageDao.readSignatureEvidence(pending.id, IDENTITY_HASH))
            assertArrayEquals(evidence, messageDao.readSignatureEvidence(pending.id, otherIdentity))
            identityDao.delete(otherIdentity)
            assertNull(messageDao.readSignatureEvidence(pending.id, otherIdentity))
            assertEquals(0, messageDao.observePendingSignatureCount().first())
        }

    // ========== Flow Tests (validates Room 2.8.x race condition fix) ==========

    @Test
    fun getMessagesForConversation_flowEmitsOnInsert() =
        runTest {
            messageDao.getMessagesForConversation(PEER_HASH, IDENTITY_HASH).test {
                assertEquals(0, awaitItem().size)

                messageDao.insertMessage(createTestMessage(id = "msg1"))
                assertEquals(1, awaitItem().size)

                messageDao.insertMessage(createTestMessage(id = "msg2"))
                assertEquals(2, awaitItem().size)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeMessageById_flowEmitsOnStatusChange() =
        runTest {
            messageDao.insertMessage(createTestMessage(id = "msg1", status = "sent"))

            messageDao.observeMessageById("msg1").test {
                val initial = awaitItem()
                assertEquals("sent", initial?.status)

                messageDao.updateMessageStatus("msg1", IDENTITY_HASH, "delivered")
                val updated = awaitItem()
                assertEquals("delivered", updated?.status)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getMessagesForConversation_orderedByTimestampAscending() =
        runTest {
            val baseTime = System.currentTimeMillis()
            messageDao.insertMessage(createTestMessage(id = "msg3", timestamp = baseTime + 2000))
            messageDao.insertMessage(createTestMessage(id = "msg1", timestamp = baseTime))
            messageDao.insertMessage(createTestMessage(id = "msg2", timestamp = baseTime + 1000))

            messageDao.getMessagesForConversation(PEER_HASH, IDENTITY_HASH).test {
                val messages = awaitItem()
                assertEquals(3, messages.size)
                assertEquals("msg1", messages[0].id) // Oldest first
                assertEquals("msg2", messages[1].id)
                assertEquals("msg3", messages[2].id) // Newest last
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Reply Preview Tests ==========

    @Test
    fun getReplyPreviewData_returnsMinimalData() =
        runTest {
            val message =
                createTestMessage(
                    id = "msg1",
                    content = "This is a long message that we're replying to",
                    fieldsJson = """{"6": "image_data"}""",
                )
            messageDao.insertMessage(message)

            val preview = messageDao.getReplyPreviewData("msg1", IDENTITY_HASH)
            assertNotNull(preview)
            assertEquals("msg1", preview?.id)
            assertEquals("This is a long message that we're replying to", preview?.content)
            assertEquals(true, preview?.isFromMe)
            assertNotNull(preview?.fieldsJson)
            assertEquals(PEER_HASH, preview?.conversationHash)
        }

    @Test
    fun getReplyPreviewData_returnsNullForNonexistent() =
        runTest {
            val preview = messageDao.getReplyPreviewData("nonexistent", IDENTITY_HASH)
            assertNull(preview)
        }

    // ========== Notification Reconciliation Tests ==========

    @Test
    fun pendingNotificationProjectionExcludesOutgoingTruncatesAndIncludesReadRows() =
        runTest {
            messageDao.insertMessage(
                createTestMessage(
                    id = "pending",
                    content = "x".repeat(150),
                    isFromMe = false,
                ).copy(notificationHandled = false),
            )
            messageDao.insertMessage(
                createTestMessage(id = "outgoing-pending", isFromMe = true)
                    .copy(notificationHandled = false),
            )
            messageDao.insertMessage(
                createTestMessage(id = "read-pending", isFromMe = false, isRead = true)
                    .copy(notificationHandled = false),
            )
            messageDao.insertMessage(createTestMessage(id = "handled", isFromMe = false))

            val pending = messageDao.getPendingNotificationMessages(IDENTITY_HASH, 32)
            assertEquals(listOf("pending", "read-pending"), pending.map { it.id })
            assertEquals("pending", pending[0].id)
            assertEquals("x".repeat(100), pending[0].preview)
            assertEquals(false, pending[0].isRead)
            assertEquals(PEER_HASH, pending[0].conversationHash)
            assertEquals(true, pending[1].isRead)
            assertEquals(2, messageDao.observePendingNotificationCount(IDENTITY_HASH).first())
        }

    @Test
    fun notificationAcknowledgementIsIdentityScopedAndIdempotent() =
        runTest {
            identityDao.insert(
                createTestIdentity()
                    .copy(identityHash = "other-identity", destinationHash = "other-destination"),
            )
            conversationDao.insertConversation(createTestConversation().copy(identityHash = "other-identity"))
            messageDao.insertMessage(createTestMessage(id = "pending", isFromMe = false).copy(notificationHandled = false))
            messageDao.insertMessage(
                createTestMessage(id = "other-pending", isFromMe = false)
                    .copy(identityHash = "other-identity", notificationHandled = false),
            )

            assertEquals(0, messageDao.ackNotificationHandled("pending", "other-identity"))
            assertEquals(false, messageDao.getMessageById("pending", IDENTITY_HASH)?.notificationHandled)
            assertEquals(listOf("pending"), messageDao.getPendingNotificationMessages(IDENTITY_HASH, 32).map { it.id })
            assertEquals(1, messageDao.ackNotificationHandled("pending", IDENTITY_HASH))
            assertEquals(0, messageDao.ackNotificationHandled("pending", IDENTITY_HASH))
            assertEquals(true, messageDao.getMessageById("pending", IDENTITY_HASH)?.notificationHandled)
            assertEquals(0, messageDao.getPendingNotificationMessages(IDENTITY_HASH, 32).size)
            assertEquals(listOf("other-pending"), messageDao.getPendingNotificationMessages("other-identity", 32).map { it.id })
        }

    @Test
    fun notificationStateSurvivesCloseAndReopen() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "notification-state-reopen"
            context.deleteDatabase(name)
            val first = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
            try {
                first.localIdentityDao().insert(createTestIdentity())
                first.conversationDao().insertConversation(createTestConversation())
                first.messageDao().insertMessage(
                    createTestMessage(id = "pending", isFromMe = false).copy(notificationHandled = false),
                )
                first.messageDao().insertMessage(createTestMessage(id = "handled", isFromMe = false))
                assertEquals(
                    listOf("pending"),
                    first.messageDao().getPendingNotificationMessages(IDENTITY_HASH, 32).map { it.id },
                )
            } finally {
                first.close()
            }
            val second = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
            try {
                assertEquals(
                    listOf("pending"),
                    second.messageDao().getPendingNotificationMessages(IDENTITY_HASH, 32).map { it.id },
                )
                assertEquals(true, second.messageDao().getMessageById("handled", IDENTITY_HASH)?.notificationHandled)
                assertEquals(1, second.messageDao().ackNotificationHandled("pending", IDENTITY_HASH))
            } finally {
                second.close()
                context.deleteDatabase(name)
            }
        }
}
