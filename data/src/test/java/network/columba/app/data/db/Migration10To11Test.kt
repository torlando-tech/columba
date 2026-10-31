package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v10 → v11 (threads P1, renumbered from the branch's v4 → v5 on rebase onto
 * main) is strictly additive (threads-spec §5): existing rows survive
 * untouched, every pre-thread message keeps `threadId = NULL` (Main), and the
 * new `threads` table + indexes exist and are usable.
 *
 * The fixture is the real exported v10 schema (via `MigrationTestHelper`),
 * seeded with a conversation, a Main message, and a draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration10To11Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration preserves rows and leaves all pre-thread data in Main`() {
        val v10 = helper.createDatabase(NAME, 10)
        seed(v10)
        v10.close()

        val v11 =
            helper.runMigrationsAndValidate(
                NAME,
                11,
                true,
                ColumbaDatabase.MIGRATION_10_11,
            )

        v11.query(
            "SELECT id, content, threadId FROM messages WHERE id = 'msg-1'",
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("msg-1", c.getString(0))
            assertEquals("hello main", c.getString(1))
            assertTrue("pre-existing messages must be Main", c.isNull(2))
        }

        v11.query("SELECT content, threadId FROM drafts WHERE conversationHash = 'peer-a'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("draft text", c.getString(0))
            assertTrue("pre-existing drafts must be Main", c.isNull(1))
        }

        v11.query("SELECT COUNT(*) FROM conversations").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }

        // threads table exists, empty, and accepts a well-formed row.
        v11.query("SELECT COUNT(*) FROM threads").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        v11.execSQL(
            "INSERT INTO threads (threadId, conversationHash, identityHash, rootMessageId, title, createdAt, lastActivityAt, archivedAt) " +
                "VALUES (X'0102030405060708090A0B0C0D0E0F10', 'peer-a', 'id-hash', 'msg-1', 'first thread', 100, 200, NULL)",
        )
        v11.query("SELECT title, rootMessageId FROM threads").use { c ->
            c.moveToFirst()
            assertEquals("first thread", c.getString(0))
            assertEquals("msg-1", c.getString(1))
        }

        // Unique index (conversationHash, identityHash, threadId) enforced.
        var duplicateRejected = false
        try {
            v11.execSQL(
                "INSERT INTO threads (threadId, conversationHash, identityHash, rootMessageId, title, createdAt, lastActivityAt, archivedAt) " +
                    "VALUES (X'0102030405060708090A0B0C0D0E0F10', 'peer-a', 'id-hash', NULL, 'dup', 100, 200, NULL)",
            )
        } catch (_: Exception) {
            duplicateRejected = true
        }
        assertTrue("unique index (conversationId, threadId) must be enforced", duplicateRejected)

        // Same threadId under a different conversation is a distinct thread.
        v11.execSQL(
            "INSERT INTO conversations (peerHash, identityHash, peerName, lastMessage, lastMessageTimestamp, unreadCount, lastSeenTimestamp) " +
                "VALUES ('peer-b', 'id-hash', 'Peer B', '', 0, 0, 0)",
        )
        v11.execSQL(
            "INSERT INTO threads (threadId, conversationHash, identityHash, rootMessageId, title, createdAt, lastActivityAt, archivedAt) " +
                "VALUES (X'0102030405060708090A0B0C0D0E0F10', 'peer-b', 'id-hash', NULL, 'same bytes other peer', 100, 200, NULL)",
        )
        v11.query("SELECT COUNT(*) FROM threads").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }

        v11.close()
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, keyEncryptionVersion, createdTimestamp, lastUsedTimestamp, isActive) " +
                "VALUES ('id-hash', 'Test', 'dest-hash', '', 0, 1, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO conversations (peerHash, identityHash, peerName, lastMessage, lastMessageTimestamp, unreadCount, lastSeenTimestamp) " +
                "VALUES ('peer-a', 'id-hash', 'Peer A', 'hello main', 1000, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO messages (id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead) " +
                "VALUES ('msg-1', 'peer-a', 'id-hash', 'hello main', 1000, 0, 'delivered', 0)",
        )
        db.execSQL(
            "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp) " +
                "VALUES ('peer-a', 'id-hash', 'draft text', 1000)",
        )
    }

    private companion object {
        const val NAME = "threads-v10-data-migration"
    }
}
