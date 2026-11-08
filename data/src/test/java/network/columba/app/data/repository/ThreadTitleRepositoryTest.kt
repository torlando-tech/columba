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
 * and a thread exists only after its first message (D11: drafts never create
 * rows), so naming a message-less target must insert the row (issue 06 "title
 * optional at creation", entry point in the chat app bar).
 *
 * No conversation is seeded: `threads` has a composite FK onto
 * `conversations`, and a peer reached through `Start Chat` has no row yet, so
 * the repository has to create the parent itself (Greptile P2 on the naming
 * path).
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
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun conversations() = db.conversationDao().getAllConversationsList(identityHash)

    @Test
    fun `naming a fresh thread creates its parent conversation and the row`() =
        kotlinx.coroutines.runBlocking {
            repository.renameThread(peerHash, freshThread, "  Trip\nbudget  ", peerName = " Peer name ")

            assertEquals("naming must not fail the conversation FK", 1, conversations().size)
            assertEquals("Peer name", conversations().single().peerName)
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
        assertEquals("a rejected name must not touch conversations", 0, conversations().size)
    }

    @Test
    fun `Main is not a thread row so renaming it is a no-op`() = kotlinx.coroutines.runBlocking {
        repository.renameThread(peerHash, ByteArray(0), "Main label")

        assertEquals(0, db.threadDao().getAllThreadsList(identityHash).size)
        assertEquals(0, conversations().size)
    }

    @Test
    fun `a draft never creates a thread row`() = kotlinx.coroutines.runBlocking {
        repository.saveDraft(peerHash, "unsent text", freshThread)

        assertNull("a thread exists only after a message is sent in it", repository.getThread(peerHash, freshThread))
        assertEquals("unsent text", repository.getDraft(peerHash, freshThread))

        repository.saveDraft(peerHash, "", freshThread)

        assertNull(repository.getThread(peerHash, freshThread))
        assertNull("clearing the draft clears the text too", repository.getDraft(peerHash, freshThread))
    }

    @Test
    fun `a name identical to the draft text still survives`() = kotlinx.coroutines.runBlocking {
        repository.saveDraft(peerHash, "Build notes", freshThread)
        repository.renameThread(peerHash, freshThread, "Build notes")

        repository.saveDraft(peerHash, "", freshThread)

        assertNotNull(
            "matching the draft text must not make a user's name deletable",
            repository.getThread(peerHash, freshThread),
        )
        assertEquals(
            "the name, not the draft excerpt, is the row's title",
            "Build notes",
            repository.getThread(peerHash, freshThread)?.title,
        )
    }

    @Test
    fun `an emptied draft deletes a thread whose name was reset to automatic`() =
        kotlinx.coroutines.runBlocking {
            repository.renameThread(peerHash, freshThread, "Build notes")
            repository.renameThread(peerHash, freshThread, null)

            repository.saveDraft(peerHash, "", freshThread)

            assertNull(
                "a message-less row with no name has nothing to preserve",
                repository.getThread(peerHash, freshThread),
            )
        }

    @Test
    fun `clearing the draft of a renamed thread keeps it`() = kotlinx.coroutines.runBlocking {
        repository.saveDraft(peerHash, "unsent text", freshThread)
        repository.renameThread(peerHash, freshThread, "Build notes")

        repository.saveDraft(peerHash, "", freshThread)

        val row = repository.getThread(peerHash, freshThread)
        assertNotNull("the rename outranks the draft excerpt it replaced", row)
        assertEquals("Build notes", row?.title)
        assertNull("the draft itself is gone", repository.getDraft(peerHash, freshThread))
    }

    @Test
    fun `clearing a draft never creates an empty conversation`() = kotlinx.coroutines.runBlocking {
        repository.saveDraft(peerHash, "", freshThread)
        repository.saveDraft(peerHash, "")

        assertEquals(
            "an abandoned draft must not leave a peer in Chats",
            0,
            conversations().size,
        )
        assertNull(repository.getThread(peerHash, freshThread))
    }

    @Test
    fun `an emptied draft keeps a thread the user named`() = kotlinx.coroutines.runBlocking {
        repository.renameThread(peerHash, freshThread, "Build notes")

        repository.saveDraft(peerHash, "", freshThread)

        val row = repository.getThread(peerHash, freshThread)
        assertNotNull("a named thread survives an empty draft", row)
        assertEquals("Build notes", row?.title)
    }
}
