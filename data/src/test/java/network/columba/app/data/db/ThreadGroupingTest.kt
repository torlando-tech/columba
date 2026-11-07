package network.columba.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.ThreadDao
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.ThreadEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Receive & grouping algorithm (threads-spec §4.3) against a real in-memory
 * Room database: implicit thread creation on first unknown T, first-sighting
 * root hook, unknown-root threads, dormancy sweep, per-thread unread counts,
 * and Main (threadId NULL) isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadGroupingTest {
    private lateinit var db: ColumbaDatabase
    private lateinit var threadDao: ThreadDao
    private lateinit var messageDao: MessageDao

    private val identityHash = "local-identity-hash"
    private val peerA = "peer-a-hash"
    private val peerB = "peer-b-hash"
    private val threadT = ByteArray(16) { (it + 1).toByte() }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        threadDao = db.threadDao()
        messageDao = db.messageDao()

        runBlocking {
            db.localIdentityDao().insert(
                LocalIdentityEntity(
                    identityHash = identityHash,
                    displayName = "Test",
                    destinationHash = "dest",
                    filePath = "",
                    createdTimestamp = 1,
                    lastUsedTimestamp = 1,
                    isActive = true,
                ),
            )
            for (peer in listOf(peerA, peerB)) {
                db.conversationDao().insertConversation(
                    ConversationEntity(
                        peerHash = peer,
                        identityHash = identityHash,
                        peerName = "Peer",
                        lastMessage = "",
                        lastMessageTimestamp = 0,
                        unreadCount = 0,
                        lastSeenTimestamp = 0,
                    ),
                )
            }
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun insertMessage(
        id: String,
        peerHash: String = peerA,
        threadId: ByteArray? = null,
        isFromMe: Boolean = false,
        isRead: Boolean = false,
        timestamp: Long = 1000,
    ) = messageDao.insertMessage(
        MessageEntity(
            id = id,
            conversationHash = peerHash,
            identityHash = identityHash,
            content = "content of $id",
            timestamp = timestamp,
            isFromMe = isFromMe,
            status = "delivered",
            isRead = isRead,
            threadId = threadId,
        ),
    )

    private suspend fun applyIncoming(
        threadId: ByteArray?,
        replyToMessageId: String? = null,
        content: String = "first thread message",
        activityAt: Long = 2000,
        peerHash: String = peerA,
    ) = ThreadGrouping.applyMessageActivity(
        threadDao = threadDao,
        messageDao = messageDao,
        identityHash = identityHash,
        conversationHash = peerHash,
        threadId = threadId,
        replyToMessageId = replyToMessageId,
        content = content,
        activityAt = activityAt,
    )

    private fun archive(
        id: ByteArray,
        at: Long,
    ) {
        db.openHelper.writableDatabase
            .execSQL("UPDATE threads SET archivedAt = ? WHERE threadId = ?", arrayOf<Any>(at, id))
    }

    // ========== implicit creation ==========

    @Test
    fun `first unknown T creates the thread titled with the first-message excerpt`() =
        runTest {
            applyIncoming(threadId = threadT, content = "hey, want to plan the hike this weekend?")

            val thread = threadDao.getThread(identityHash, peerA, threadT)
            assertNotNull("unknown T must implicitly create the thread row", thread)
            thread!!
            assertEquals("hey, want to plan the hike this weekend?", thread.title)
            assertNull("no root without a resolvable 0x30 target", thread.rootMessageId)
            assertNull(thread.archivedAt)
            assertEquals(2000L, thread.createdAt)
            assertEquals(2000L, thread.lastActivityAt)
        }

    @Test
    fun `known T only bumps activity - title and root are untouched`() =
        runTest {
            insertMessage("root-1", peerHash = peerA)
            applyIncoming(threadT, replyToMessageId = "root-1", content = "seed", activityAt = 2000)

            applyIncoming(threadT, content = "follow-up", activityAt = 5000)

            val thread = threadDao.getThread(identityHash, peerA, threadT)!!
            assertEquals("seed", thread.title)
            assertEquals("root-1", thread.rootMessageId)
            assertEquals(5000L, thread.lastActivityAt)
        }

    @Test
    fun `older activity never rewinds the watermark`() =
        runTest {
            applyIncoming(threadT, activityAt = 9000)
            applyIncoming(threadT, activityAt = 8000)
            assertEquals(9000L, threadDao.getThread(identityHash, peerA, threadT)!!.lastActivityAt)
        }

    @Test
    fun `null threadId is Main - no thread row is created`() =
        runTest {
            applyIncoming(threadId = null)
            assertTrue(threadDao.threadsForConversation(identityHash, peerA, true).first().isEmpty())
        }

    // ========== root hook (case 2 first sighting) ==========

    @Test
    fun `first sighting with a Main target attaches the root hook`() =
        runTest {
            insertMessage("root-1")
            applyIncoming(threadT, replyToMessageId = "root-1")

            assertEquals("root-1", threadDao.getThread(identityHash, peerA, threadT)!!.rootMessageId)
        }

    @Test
    fun `unknown-root thread - unresolved 0x30 target degrades to no root, never throws`() =
        runTest {
            // Root M was never received on this device (sent while we were off-line).
            applyIncoming(threadT, replyToMessageId = "never-received-hash")

            val thread = threadDao.getThread(identityHash, peerA, threadT)
            assertNotNull("thread must still be created", thread)
            assertNull(thread!!.rootMessageId)
        }

    @Test
    fun `target inside another thread is not adopted as root`() =
        runTest {
            insertMessage("threaded-1", threadId = ByteArray(16) { 9 })
            applyIncoming(threadT, replyToMessageId = "threaded-1")

            assertNull(threadDao.getThread(identityHash, peerA, threadT)!!.rootMessageId)
        }

    @Test
    fun `target in a different conversation is not adopted as root`() =
        runTest {
            insertMessage("other-peer-msg", peerHash = peerB)
            applyIncoming(threadT, replyToMessageId = "other-peer-msg")

            assertNull(threadDao.getThread(identityHash, peerA, threadT)!!.rootMessageId)
        }

    @Test
    fun `root attaches to any rootless row and is never overwritten once set`() =
        runTest {
            applyIncoming(threadT, content = "started off-screen") // no reply field at creation
            insertMessage("root-late")

            // A row without a root (off-screen start OR one created by a
            // rename) gains its root as soon as a reply resolves it.
            applyIncoming(threadT, replyToMessageId = "root-late", activityAt = 3000)

            assertEquals("root-late", threadDao.getThread(identityHash, peerA, threadT)!!.rootMessageId)

            // Once set, a later reply cannot move the root.
            insertMessage("root-later")
            applyIncoming(threadT, replyToMessageId = "root-later", activityAt = 4000)

            assertEquals("root-late", threadDao.getThread(identityHash, peerA, threadT)!!.rootMessageId)
        }

    // ========== dormancy sweep ==========

    @Test
    fun `dormancy sweep reports only non-archived threads idle beyond the window`() =
        runTest {
            val now = 10_000_000L
            val window = 14L * 24 * 60 * 60 * 1000
            val fresh = ByteArray(16) { 2 }
            val stale = ByteArray(16) { 3 }
            val staleArchived = ByteArray(16) { 4 }

            applyIncoming(fresh, activityAt = now - 1000)
            applyIncoming(stale, activityAt = now - window - 1)
            applyIncoming(staleArchived, activityAt = now - window - 1)
            archive(staleArchived, now)

            val dormant = threadDao.dormantThreads(identityHash, now - window)
            assertEquals(
                "only the non-archived stale thread",
                listOf(stale.toList()),
                dormant.map { it.threadId.toList() },
            )
        }

    @Test
    fun `any new message revives a dormant thread`() =
        runTest {
            val now = 10_000_000L
            val window = 14L * 24 * 60 * 60 * 1000
            applyIncoming(threadT, activityAt = now - window - 1)
            assertEquals(1, threadDao.dormantThreads(identityHash, now - window).size)

            applyIncoming(threadT, activityAt = now)
            assertEquals(0, threadDao.dormantThreads(identityHash, now - window).size)
        }

    // ========== queries ==========

    @Test
    fun `messagesForThread returns only that thread's messages in time order`() =
        runTest {
            insertMessage("main-1", timestamp = 1)
            insertMessage("t-2", threadId = threadT, timestamp = 3)
            insertMessage("t-1", threadId = threadT, timestamp = 2)
            insertMessage("other-t", threadId = ByteArray(16) { 7 }, timestamp = 4)

            val ids = messageDao.getMessagesForThread(peerA, identityHash, threadT).first().map { it.id }
            assertEquals(listOf("t-1", "t-2"), ids)
        }

    @Test
    fun `unread counts split per thread and Main`() =
        runTest {
            insertMessage("main-unread", isRead = false)
            insertMessage("main-read", isRead = true)
            insertMessage("main-mine", isFromMe = true)
            insertMessage("t-unread-1", threadId = threadT)
            insertMessage("t-unread-2", threadId = threadT)
            insertMessage("t-read", threadId = threadT, isRead = true)

            assertEquals(1, messageDao.getUnreadCountForMain(peerA, identityHash))
            assertEquals(2, messageDao.getUnreadCountForThread(peerA, identityHash, threadT))

            val grouped = messageDao.observeUnreadCountsPerThread(peerA, identityHash).first()
            val main = grouped.single { it.threadId == null }
            val t = grouped.single { it.threadId != null }
            assertEquals(1, main.unreadCount)
            assertEquals(2, t.unreadCount)
            assertTrue(t.threadId!!.contentEquals(threadT))
        }

    @Test
    fun `markThreadAsRead clears only that thread`() =
        runTest {
            insertMessage("main-unread")
            insertMessage("t-unread", threadId = threadT)

            messageDao.markThreadMessagesAsRead(peerA, identityHash, threadT)

            assertEquals(0, messageDao.getUnreadCountForThread(peerA, identityHash, threadT))
            assertEquals(1, messageDao.getUnreadCountForMain(peerA, identityHash))
        }

    @Test
    fun `threadsForConversation orders by activity desc and hides archived by default`() =
        runTest {
            val t1 = ByteArray(16) { 11 }
            val t2 = ByteArray(16) { 12 }
            val t3 = ByteArray(16) { 13 }
            applyIncoming(t1, activityAt = 100)
            applyIncoming(t2, activityAt = 300)
            applyIncoming(t3, activityAt = 200)
            archive(t2, 500)

            val visible = threadDao.threadsForConversation(identityHash, peerA, false).first()
            assertEquals(listOf(t3.toList(), t1.toList()), visible.map { it.threadId.toList() })

            val all = threadDao.threadsForConversation(identityHash, peerA, true).first()
            assertEquals(3, all.size)
        }

    @Test
    fun `same thread bytes under different conversations are distinct threads`() =
        runTest {
            applyIncoming(threadT, peerHash = peerA)
            applyIncoming(threadT, peerHash = peerB, content = "same id other peer")

            assertEquals(1, threadDao.threadsForConversation(identityHash, peerA, true).first().size)
            assertEquals(1, threadDao.threadsForConversation(identityHash, peerB, true).first().size)
            assertEquals(
                "same id other peer",
                threadDao.getThread(identityHash, peerB, threadT)!!.title,
            )
        }

    @Test
    fun `duplicate first sighting is ignored not duplicated`() =
        runTest {
            threadDao.insertThreadIgnore(threadEntity(threadT, createdAt = 1))
            assertEquals(
                "second insert of the same (conversation, threadId) must be ignored",
                -1L,
                threadDao.insertThreadIgnore(threadEntity(threadT, createdAt = 2)),
            )
            assertEquals(1, threadDao.threadsForConversation(identityHash, peerA, true).first().size)
        }

    private fun threadEntity(
        id: ByteArray,
        createdAt: Long,
    ) = ThreadEntity(
        threadId = id,
        conversationHash = peerA,
        identityHash = identityHash,
        rootMessageId = null,
        title = null,
        createdAt = createdAt,
        lastActivityAt = createdAt,
        archivedAt = null,
    )
}
