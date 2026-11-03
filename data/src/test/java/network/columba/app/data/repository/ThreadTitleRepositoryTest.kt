package network.columba.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.storage.AttachmentStorageManager
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
 * Thread titles are local labels (threads-spec §6): NULL means "automatic",
 * and a thread minted by `+ New` has no row until its first draft/message, so
 * naming it at creation must insert the row (issue 06 "title optional at
 * creation", entry point in the chat app bar).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadTitleRepositoryTest {
    private lateinit var db: ColumbaDatabase
    private lateinit var repository: ConversationRepository

    private val identityHash = "local-identity-hash"
    private val peerHash = "peer-hash"
    private val freshThread = ByteArray(16) { (it + 1).toByte() }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            ConversationRepository(
                db.conversationDao(),
                db.messageDao(),
                db.peerIdentityDao(),
                db.localIdentityDao(),
                AttachmentStorageManager(context),
                db.draftDao(),
                db.threadDao(),
                db,
            )
        kotlinx.coroutines.runBlocking {
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
            // threads has a composite FK onto conversations: naming a fresh
            // thread inserts a row only for a conversation that exists.
            db.conversationDao().insertConversation(
                network.columba.app.data.db.entity.ConversationEntity(
                    peerHash = peerHash,
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

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `naming a fresh thread inserts its row with sanitized title`() =
        kotlinx.coroutines.runBlocking {
            repository.renameThread(peerHash, freshThread, "  Trip\nbudget  ")

            val row = repository.getThread(peerHash, freshThread)
            assertNotNull("naming a message-less thread must create its row", row)
            assertEquals("Trip budget", row?.title)
        }

    @Test
    fun `rename null resets an existing local title to automatic`() = kotlinx.coroutines.runBlocking {
        repository.renameThread(peerHash, freshThread, "Trip planning")
        repository.renameThread(peerHash, freshThread, null)

        val row = repository.getThread(peerHash, freshThread)
        assertNotNull("reset must keep the thread row", row)
        assertNull("NULL is the automatic-excerpt state", row?.title)
    }

    @Test
    fun `blank title on a fresh thread creates no row`() = kotlinx.coroutines.runBlocking {
        repository.renameThread(peerHash, freshThread, "   \n\t ")

        assertNull(repository.getThread(peerHash, freshThread))
        assertEquals(0, db.threadDao().getAllThreadsList(identityHash).size)
    }

    @Test
    fun `Main is not a thread row so renaming it is a no-op`() = kotlinx.coroutines.runBlocking {
        repository.renameThread(peerHash, ByteArray(0), "Main label")

        assertEquals(0, db.threadDao().getAllThreadsList(identityHash).size)
    }
}
