package network.columba.app.integration

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import network.columba.app.data.crypto.IdentityKeyEncryptor
import network.columba.app.data.crypto.IdentityKeyMigrator
import network.columba.app.data.crypto.IdentityKeyProvider
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.repository.IdentityRepository
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityRecoveryPersistenceInstrumentedTest {
    private val identityHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val destinationHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val peerHash = "cccccccccccccccccccccccccccccccc"
    private val recoveredKey = ByteArray(64) { index -> (index + 1).toByte() }

    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var repository: IdentityRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val encryptor = IdentityKeyEncryptor()
        val identityDao = database.localIdentityDao()
        lateinit var keyProvider: IdentityKeyProvider
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            keyProvider = IdentityKeyProvider(context, identityDao, encryptor)
        }
        repository =
            IdentityRepository(
                identityDao = identityDao,
                database = database,
                context = context,
                ioDispatcher = Dispatchers.IO,
                keyEncryptor = encryptor,
                keyMigrator = IdentityKeyMigrator(context, identityDao, encryptor),
                keyProvider = keyProvider,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rewrapPreservesIdentityMetadataConversationAndMessagesAndSurvivesDecrypt() = runBlocking {
        val restoredIdentity =
            LocalIdentityEntity(
                identityHash = identityHash,
                displayName = "Recovered identity",
                destinationHash = destinationHash,
                filePath = "",
                encryptedKeyData = byteArrayOf(1, 2, 3),
                keyEncryptionVersion = 1,
                createdTimestamp = 10L,
                lastUsedTimestamp = 20L,
                isActive = true,
                iconName = "person",
                iconForegroundColor = "FFFFFF",
                iconBackgroundColor = "000000",
            )
        val conversation =
            ConversationEntity(
                peerHash = peerHash,
                identityHash = identityHash,
                peerName = "Recovered peer",
                lastMessage = "third",
                lastMessageTimestamp = 300L,
                unreadCount = 2,
                lastSeenTimestamp = 25L,
            )
        val messages =
            listOf(
                message("message-1", "first", 100L),
                message("message-2", "second", 200L),
                message("message-3", "third", 300L),
            )
        database.localIdentityDao().insert(restoredIdentity)
        database.conversationDao().insertConversation(conversation)
        messages.forEach { database.messageDao().insertMessage(it) }

        val result = repository.rewrapKeyWithDeviceKey(identityHash, recoveredKey)

        assertTrue(result.isSuccess)
        val persisted = database.localIdentityDao().getIdentity(identityHash)
        assertNotNull(persisted)
        checkNotNull(persisted)
        assertEquals(restoredIdentity.identityHash, persisted.identityHash)
        assertEquals(restoredIdentity.displayName, persisted.displayName)
        assertEquals(restoredIdentity.destinationHash, persisted.destinationHash)
        assertEquals(restoredIdentity.createdTimestamp, persisted.createdTimestamp)
        assertEquals(restoredIdentity.lastUsedTimestamp, persisted.lastUsedTimestamp)
        assertEquals(restoredIdentity.isActive, persisted.isActive)
        assertEquals(restoredIdentity.iconName, persisted.iconName)
        assertEquals(restoredIdentity.iconForegroundColor, persisted.iconForegroundColor)
        assertEquals(restoredIdentity.iconBackgroundColor, persisted.iconBackgroundColor)
        assertNull(persisted.keyData)
        assertEquals(IdentityKeyEncryptor.VERSION_DEVICE_ONLY.toInt(), persisted.keyEncryptionVersion)
        assertNotNull(persisted.encryptedKeyData)
        assertFalse(persisted.encryptedKeyData!!.contentEquals(restoredIdentity.encryptedKeyData!!))

        val decrypted = repository.getDecryptedKeyData(identityHash).getOrThrow()
        assertArrayEquals(recoveredKey, decrypted)
        val freshEncryptor = IdentityKeyEncryptor()
        lateinit var freshProvider: IdentityKeyProvider
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            freshProvider =
                IdentityKeyProvider(
                    context,
                    database.localIdentityDao(),
                    freshEncryptor,
                )
        }
        val decryptedAfterProviderRestart = freshProvider.getDecryptedKeyData(identityHash).getOrThrow()
        assertArrayEquals(recoveredKey, decryptedAfterProviderRestart)
        val persistedConversation = database.conversationDao().getConversation(peerHash, identityHash)
        assertEquals(conversation, persistedConversation)
        val persistedMessages = database.messageDao().getMessagesForConversation(peerHash, identityHash).first()
        assertEquals(messages, persistedMessages)
    }

    @Test
    fun missingIdentityRowFailsRecoveryInsteadOfReportingFalseSuccess() = runBlocking {
        val result =
            repository.rewrapKeyWithDeviceKey(
                "dddddddddddddddddddddddddddddddd",
                recoveredKey,
            )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("was not found") == true)
        assertTrue(database.localIdentityDao().getAllIdentities().first().isEmpty())
    }

    @Test
    fun misSizedKeysAreRejectedWithoutChangingRestoredIdentity() = runBlocking {
        val restoredIdentity =
            LocalIdentityEntity(
                identityHash = identityHash,
                displayName = "Recovered identity",
                destinationHash = destinationHash,
                filePath = "",
                encryptedKeyData = byteArrayOf(9, 8, 7),
                keyEncryptionVersion = 1,
                createdTimestamp = 10L,
                lastUsedTimestamp = 20L,
                isActive = true,
            )
        database.localIdentityDao().insert(restoredIdentity)

        val shortResult = repository.rewrapKeyWithDeviceKey(identityHash, ByteArray(63))
        val overlongResult = repository.rewrapKeyWithDeviceKey(identityHash, ByteArray(65))

        assertTrue(shortResult.isFailure)
        assertTrue(overlongResult.isFailure)
        val persisted = checkNotNull(database.localIdentityDao().getIdentity(identityHash))
        assertEquals(restoredIdentity.displayName, persisted.displayName)
        assertEquals(restoredIdentity.destinationHash, persisted.destinationHash)
        assertEquals(restoredIdentity.keyEncryptionVersion, persisted.keyEncryptionVersion)
        assertArrayEquals(restoredIdentity.encryptedKeyData, persisted.encryptedKeyData)
    }

    private fun message(
        id: String,
        content: String,
        timestamp: Long,
    ) =
        MessageEntity(
            id = id,
            conversationHash = peerHash,
            identityHash = identityHash,
            content = content,
            timestamp = timestamp,
            isFromMe = false,
            status = "delivered",
            isRead = false,
            receivedAt = timestamp + 1,
        )
}
