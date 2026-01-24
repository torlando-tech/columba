package com.lxmf.messenger.data.db.migration

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.ConversationEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.db.entity.MessageEntity
import kotlinx.coroutines.test.runTest
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
 * Tests for Migration 30 to 31 - Signal Quality Fields.
 *
 * This migration adds receivedRssi (INTEGER) and receivedSnr (REAL) columns
 * to the messages table for displaying signal quality metrics in message detail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration30To31Test {
    private lateinit var database: ColumbaDatabase
    private lateinit var context: Context

    companion object {
        private const val TEST_IDENTITY_HASH = "test_identity_hash_123456789012"
        private const val TEST_DEST_HASH = "test_dest_hash_1234567890123456"
        private const val TEST_PEER_HASH = "test_peer_hash_12345678901234567"
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createTestIdentity() =
        LocalIdentityEntity(
            identityHash = TEST_IDENTITY_HASH,
            displayName = "Test Identity",
            destinationHash = TEST_DEST_HASH,
            filePath = "/test/path",
            keyData = null,
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    private fun createTestConversation() =
        ConversationEntity(
            peerHash = TEST_PEER_HASH,
            identityHash = TEST_IDENTITY_HASH,
            peerName = "Test Peer",
            lastMessage = "Hello",
            lastMessageTimestamp = System.currentTimeMillis(),
            unreadCount = 0,
        )

    @Test
    fun `message can be inserted with null RSSI and SNR`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg1",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Test message",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedRssi = null,
                    receivedSnr = null,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg1", TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertNull(retrieved?.receivedRssi)
            assertNull(retrieved?.receivedSnr)
        }

    @Test
    fun `message can be inserted with RSSI value`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg2",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Test message with RSSI",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedRssi = -75,
                    receivedSnr = null,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg2", TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals(-75, retrieved?.receivedRssi)
            assertNull(retrieved?.receivedSnr)
        }

    @Test
    fun `message can be inserted with SNR value`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg3",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Test message with SNR",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedRssi = null,
                    receivedSnr = 8.5f,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg3", TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertNull(retrieved?.receivedRssi)
            assertEquals(8.5f, retrieved?.receivedSnr)
        }

    @Test
    fun `message can be inserted with both RSSI and SNR values`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg4",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Test message with both",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedRssi = -85,
                    receivedSnr = 5.0f,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg4", TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals(-85, retrieved?.receivedRssi)
            assertEquals(5.0f, retrieved?.receivedSnr)
        }

    @Test
    fun `sent message can have null RSSI and SNR without error`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg5",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Sent message",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "sent",
                    isRead = true,
                    receivedRssi = null,
                    receivedSnr = null,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg5", TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals(true, retrieved?.isFromMe)
            assertNull(retrieved?.receivedRssi)
            assertNull(retrieved?.receivedSnr)
        }

    @Test
    fun `RSSI can store negative values`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg6",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Weak signal",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedRssi = -120,
                    receivedSnr = null,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg6", TEST_IDENTITY_HASH)
            assertEquals(-120, retrieved?.receivedRssi)
        }

    @Test
    fun `SNR can store negative values`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg7",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Noisy signal",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedRssi = -90,
                    receivedSnr = -7.5f,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg7", TEST_IDENTITY_HASH)
            assertEquals(-7.5f, retrieved?.receivedSnr)
        }

    @Test
    fun `message with all routing info fields populated`() =
        runTest {
            database.localIdentityDao().insert(createTestIdentity())
            database.conversationDao().insertConversation(createTestConversation())

            val message =
                MessageEntity(
                    id = "msg8",
                    conversationHash = TEST_PEER_HASH,
                    identityHash = TEST_IDENTITY_HASH,
                    content = "Full routing info",
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    status = "delivered",
                    isRead = false,
                    receivedHopCount = 2,
                    receivedInterface = "RNodeInterface",
                    receivedRssi = -70,
                    receivedSnr = 12.0f,
                )
            database.messageDao().insertMessage(message)

            val retrieved = database.messageDao().getMessageById("msg8", TEST_IDENTITY_HASH)
            assertNotNull(retrieved)
            assertEquals(2, retrieved?.receivedHopCount)
            assertEquals("RNodeInterface", retrieved?.receivedInterface)
            assertEquals(-70, retrieved?.receivedRssi)
            assertEquals(12.0f, retrieved?.receivedSnr)
        }
}
