package network.columba.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.di.DatabaseModule
import network.columba.app.data.storage.AttachmentStorageManager
import network.columba.app.domain.model.SignatureStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationRepositoryConcurrencyTest {
    @Test
    fun `independent writers deduplicate atomically and cannot overwrite a resolved signature`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "concurrent-message-writers"
        context.deleteDatabase(name)
        val first = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
        val second = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
        try {
            first.localIdentityDao().insert(identity("local-identity", isActive = true))
            val writers = listOf(repository(first, context), repository(second, context))
            val evidence = byteArrayOf(1, 2, 3)
            val message = Message(
                id = "same-delivery",
                destinationHash = "sender",
                content = "Original content",
                timestamp = 123,
                isFromMe = false,
                signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                signatureEvidence = evidence,
            )
            saveConcurrently(writers, message)
            assertEquals(1, first.messageDao().getAllMessagesForIdentity("local-identity").size)
            assertEquals(1, first.conversationDao().getConversation("sender", "local-identity")?.unreadCount)
            assertArrayEquals(evidence, first.messageDao().readSignatureEvidence(message.id, "local-identity"))

            assertEquals(
                1,
                saveConcurrently(writers, message.copy(content = "Stale replay", timestamp = 456), second),
            )
            val saved = requireNotNull(first.messageDao().getMessageById(message.id, "local-identity"))
            assertEquals("Original content", saved.content)
            assertEquals(123L, saved.timestamp)
            assertEquals(SignatureStatus.VERIFIED, saved.signatureStatus)
            assertNull(first.messageDao().readSignatureEvidence(message.id, "local-identity"))
            assertEquals(1, first.messageDao().getAllMessagesForIdentity("local-identity").size)
            assertEquals(1, first.conversationDao().getConversation("sender", "local-identity")?.unreadCount)
            val conversation = requireNotNull(first.conversationDao().getConversation("sender", "local-identity"))
            assertEquals("Original content", conversation.lastMessage)
            assertEquals(123L, conversation.lastMessageTimestamp)
        } finally {
            first.close()
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `replayed message cannot move preview or ordering backwards`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "replayed-conversation-metadata"
        context.deleteDatabase(name)
        val first = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
        val second = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
        try {
            first.localIdentityDao().insert(identity("local-identity", isActive = true))
            val writers = listOf(repository(first, context), repository(second, context))
            val evidence = byteArrayOf(7, 8, 9)
            val older = Message(
                id = "older-message",
                destinationHash = "sender",
                content = "Older content",
                timestamp = 100,
                isFromMe = false,
                receivedAt = 100,
                signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                signatureEvidence = evidence,
            )
            val newer = Message(
                id = "newer-message",
                destinationHash = "sender",
                content = "Newer content",
                timestamp = 200,
                isFromMe = false,
                receivedAt = 200,
            )
            writers[0].saveMessage("sender", "Sender", older)
            first.messageDao().resolvePendingSignature(older.id, "local-identity", SignatureStatus.VERIFIED)
            writers[0].saveMessage("sender", "Sender", newer)

            val beforeReplay = requireNotNull(first.conversationDao().getConversation("sender", "local-identity"))
            assertEquals("Newer content", beforeReplay.lastMessage)
            assertEquals(200L, beforeReplay.lastMessageTimestamp)
            assertEquals(2, beforeReplay.unreadCount)
            assertEquals(2, first.messageDao().getAllMessagesForIdentity("local-identity").size)

            writers[1].saveMessage(
                "sender",
                "Sender",
                older.copy(content = "Stale replay", timestamp = 300, receivedAt = 300),
            )

            val afterReplay = requireNotNull(first.conversationDao().getConversation("sender", "local-identity"))
            assertEquals("Newer content", afterReplay.lastMessage)
            assertEquals(200L, afterReplay.lastMessageTimestamp)
            assertEquals(2, afterReplay.unreadCount)
            assertEquals(2, first.messageDao().getAllMessagesForIdentity("local-identity").size)
            val storedOlder = requireNotNull(first.messageDao().getMessageById(older.id, "local-identity"))
            assertEquals("Older content", storedOlder.content)
            assertEquals(100L, storedOlder.timestamp)
            assertEquals(100L, storedOlder.receivedAt)
            assertEquals(SignatureStatus.VERIFIED, storedOlder.signatureStatus)
            assertNull(first.messageDao().readSignatureEvidence(older.id, "local-identity"))
        } finally {
            first.close()
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `explicit recipient identity remains correct when inactive or no identity is active`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "inactive-original-recipient"
        context.deleteDatabase(name)
        val database = DatabaseModule.buildColumbaDatabase(context, name, multiInstanceInvalidation = false)
        try {
            database.localIdentityDao().insert(identity("original-recipient", isActive = false))
            database.localIdentityDao().insert(identity("current-ui-identity", isActive = true))
            val original = requireNotNull(
                database.localIdentityDao().getIdentityByDestinationHash("original-recipient-destination"),
            )
            assertEquals("original-recipient", original.identityHash)
            assertEquals(false, original.isActive)
            val repository = repository(database, context)
            val evidence = byteArrayOf(4, 5, 6)
            val message = Message(
                id = "inactive-recipient-message",
                destinationHash = "sender",
                content = "Delivered to original identity",
                timestamp = 123,
                isFromMe = false,
                signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                signatureEvidence = evidence,
            )
            repository.saveMessage("sender", "Sender", message, localIdentityHash = original.identityHash)
            assertEquals(original.identityHash, database.messageDao().getMessageById(message.id, original.identityHash)?.identityHash)
            assertArrayEquals(evidence, database.messageDao().readSignatureEvidence(message.id, original.identityHash))
            assertNull(database.messageDao().getMessageById(message.id, "current-ui-identity"))
            assertNull(database.conversationDao().getConversation("sender", "current-ui-identity"))
            repository.saveMessage("sender", "Sender", message.copy(id = "active-default"))
            assertEquals("current-ui-identity", database.messageDao().getMessageById("active-default", "current-ui-identity")?.identityHash)
            database.localIdentityDao().deactivateAll()
            repository.saveMessage("sender", "Sender", message.copy(id = "no-active-identity"), localIdentityHash = original.identityHash)
            assertEquals(original.identityHash, database.messageDao().getMessageById("no-active-identity", original.identityHash)?.identityHash)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun saveConcurrently(
        writers: List<ConversationRepository>,
        message: Message,
        resolveDatabase: ColumbaDatabase? = null,
    ): Int? = coroutineScope {
        val start = CompletableDeferred<Unit>()
        val saves = (0 until 16).map { index ->
            async(Dispatchers.Default) {
                start.await()
                writers[index % writers.size].saveMessage("sender", "Sender", message)
            }
        }
        val resolution = resolveDatabase?.let { database ->
            async(Dispatchers.Default) {
                start.await()
                database.messageDao().resolvePendingSignature(message.id, "local-identity", SignatureStatus.VERIFIED)
            }
        }
        start.complete(Unit)
        saves.awaitAll()
        resolution?.await()
    }

    private fun identity(identityHash: String, isActive: Boolean) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = "Local",
        destinationHash = "$identityHash-destination",
        filePath = "/$identityHash.key",
        keyData = null,
        createdTimestamp = 1,
        lastUsedTimestamp = 1,
        isActive = isActive,
    )

    private fun repository(database: ColumbaDatabase, context: Context) = ConversationRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        peerIdentityDao = database.peerIdentityDao(),
        localIdentityDao = database.localIdentityDao(),
        attachmentStorage = AttachmentStorageManager(context),
        draftDao = database.draftDao(),
        database = database,
    )
}
