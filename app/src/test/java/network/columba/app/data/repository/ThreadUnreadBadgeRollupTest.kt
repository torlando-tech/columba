package network.columba.app.data.repository

import network.columba.app.data.storage.AttachmentStorageManager
import network.columba.app.test.DatabaseTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Threads P3 (spec §8 Phase 3): the contact-card unread badge must roll up
 * every thread — at all times `conversations.unreadCount` equals the sum of
 * the per-target unread counts (Main + each thread), including after the
 * thread-aware read paths mark only one target read.
 *
 * Real in-memory Room database: the roll-up is a SQL re-derivation
 * (`syncUnreadCountFromMessages`), so mock-based tests would prove nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadUnreadBadgeRollupTest : DatabaseTest() {
    private lateinit var repository: ConversationRepository
    private val testDispatcher = StandardTestDispatcher()

    private val threadOne = ByteArray(16) { it.toByte() }
    private val threadTwo = ByteArray(16) { (it + 32).toByte() }

    @Before
    fun setupRepository() {
        Dispatchers.setMain(testDispatcher)

        val attachmentStorage = mockk<AttachmentStorageManager>()
        every { attachmentStorage.saveAttachment(any(), any(), any()) } returns null

        runTest { insertTestIdentity() }

        repository =
            ConversationRepository(
                conversationDao = conversationDao,
                messageDao = messageDao,
                peerIdentityDao = peerIdentityDao,
                localIdentityDao = localIdentityDao,
                attachmentStorage = attachmentStorage,
                draftDao = draftDao,
                threadDao = threadDao,
            )
    }

    @After
    fun teardownDispatcher() {
        Dispatchers.resetMain()
    }

    private suspend fun receive(
        id: String,
        threadId: ByteArray?,
    ) {
        repository.saveMessage(
            peerHash = TEST_PEER_HASH,
            peerName = "Peer",
            message =
                Message(
                    id = id,
                    destinationHash = TEST_PEER_HASH,
                    content = "Message $id",
                    timestamp = 1000L,
                    isFromMe = false,
                    status = "delivered",
                    threadId = threadId,
                ),
        )
    }

    /** Sum of the per-target unread rows (Main = null-threadId row). */
    private suspend fun perThreadUnreadSum(): Int =
        repository.observeUnreadCountsPerThread(TEST_PEER_HASH).first().sumOf { it.unreadCount }

    private suspend fun cardBadge(): Int =
        repository.getConversation(TEST_PEER_HASH)?.unreadCount ?: -1

    @Test
    fun `card badge equals the per-thread unread sum after mixed receives`() =
        runTest {
            receive("m1", threadId = null)
            receive("m2", threadId = null)
            receive("t1a", threadId = threadOne)
            receive("t1b", threadId = threadOne)
            receive("t1c", threadId = threadOne)
            receive("t2a", threadId = threadTwo)

            assertEquals(6, perThreadUnreadSum())
            assertEquals(
                "Contact-card badge must equal the sum of per-thread unread",
                6,
                cardBadge(),
            )
        }

    @Test
    fun `opening a thread re-derives the card badge from the remaining unread`() =
        runTest {
            receive("m1", threadId = null)
            receive("m2", threadId = null)
            receive("t1a", threadId = threadOne)
            receive("t1b", threadId = threadOne)
            receive("t2a", threadId = threadTwo)

            repository.markThreadAsRead(TEST_PEER_HASH, threadOne)

            assertEquals(
                "Badge must drop by exactly the read thread's unread, keeping Main + other threads",
                3,
                cardBadge(),
            )
            assertEquals(3, perThreadUnreadSum())
            assertEquals(0, repository.unreadCountForThread(TEST_PEER_HASH, threadOne))
        }

    @Test
    fun `opening Main keeps the card badge counting still-unread threads`() =
        runTest {
            receive("m1", threadId = null)
            receive("t1a", threadId = threadOne)
            receive("t1b", threadId = threadOne)
            receive("t2a", threadId = threadTwo)

            repository.markMainAsRead(TEST_PEER_HASH)

            assertEquals(
                "Opening Main must NOT zero the badge while threads are unread",
                3,
                cardBadge(),
            )
            assertEquals(0, repository.unreadCountForMain(TEST_PEER_HASH))
            assertEquals(2, repository.unreadCountForThread(TEST_PEER_HASH, threadOne))
            assertEquals(3, perThreadUnreadSum())
        }

    @Test
    fun `badge reaches zero only when every target is read`() =
        runTest {
            receive("m1", threadId = null)
            receive("t1a", threadId = threadOne)
            receive("t2a", threadId = threadTwo)

            repository.markMainAsRead(TEST_PEER_HASH)
            repository.markThreadAsRead(TEST_PEER_HASH, threadOne)
            assertEquals(1, cardBadge())

            repository.markThreadAsRead(TEST_PEER_HASH, threadTwo)
            assertEquals(0, cardBadge())
            assertEquals(0, perThreadUnreadSum())
        }
}
