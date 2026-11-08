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
 * v11 → v12 (renumbered from the branch's v5 → v6 on rebase onto main)
 * rebuilds `drafts` so the primary key gains `threadId`
 * (threads-spec §5, P2 per-thread drafts). The invariants that matter:
 *  - every pre-thread draft survives and becomes the Main row
 *    (`threadId = X''`, the NOT NULL empty-blob sentinel),
 *  - a v11 draft already tagged with a real thread id keeps its bytes,
 *  - the new PK enforces one draft per (conversation, identity, thread),
 *  - the `identityHash` index is recreated.
 *
 * The fixture is the real exported v11 schema (via `MigrationTestHelper`),
 * seeded with a conversation, a Main draft, and a thread-tagged draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration11To12Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration rebuilds drafts pk without losing rows`() {
        val v11 = helper.createDatabase(NAME, 11)
        seed(v11)
        v11.close()

        val v12 =
            helper.runMigrationsAndValidate(
                NAME,
                12,
                true,
                ColumbaDatabase.MIGRATION_11_12,
            )

        // Pre-thread draft (threadId NULL on v11) becomes the Main sentinel row.
        v12.query(
            "SELECT content, threadId FROM drafts " +
                "WHERE conversationHash = 'peer-a' AND identityHash = 'id-hash' AND threadId = X''",
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("draft text", c.getString(0))
            assertEquals("Main draft must be the empty-blob sentinel", 0, c.getBlob(1).size)
        }
        // A v11 draft already tagged with a thread id keeps its exact bytes
        // (seeded under peer-b: the v11 PK allowed only one draft per
        // conversation, so each fixture conversation carries exactly one row).
        v12.query(
            "SELECT content FROM drafts " +
                "WHERE conversationHash = 'peer-b' AND identityHash = 'id-hash' " +
                "AND threadId = X'0102030405060708090A0B0C0D0E0F10'",
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("thread draft", c.getString(0))
        }
        // threadId is NOT NULL: the sentinel is the only representation of Main.
        v12.query("PRAGMA table_info(drafts)").use { c ->
            var sawThreadIdNotNull = false
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == "threadId") {
                    sawThreadIdNotNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
                }
            }
            assertTrue("threadId must be NOT NULL in the rebuilt PK", sawThreadIdNotNull)
        }

        // PK (conversationHash, identityHash, threadId): a second Main draft is rejected.
        var duplicateRejected = false
        try {
            v12.execSQL(
                "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp, threadId) " +
                    "VALUES ('peer-a', 'id-hash', 'second main draft', 2000, X'')",
            )
        } catch (_: Exception) {
            duplicateRejected = true
        }
        assertTrue("PK must enforce one draft per (conversation, identity, thread)", duplicateRejected)

        // Same conversation, different thread id is a distinct row (multi-draft storage).
        v12.execSQL(
            "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp, threadId) " +
                "VALUES ('peer-a', 'id-hash', 'another thread', 2000, X'11223344556677889900AABBCCDDEEFF')",
        )
        v12.query("SELECT COUNT(*) FROM drafts WHERE conversationHash = 'peer-a'").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }

        // identityHash index recreated (Room schema declares index_drafts_identityHash).
        v12.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_drafts_identityHash'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }

        v12.close()
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
            "INSERT INTO conversations (peerHash, identityHash, peerName, lastMessage, lastMessageTimestamp, unreadCount, lastSeenTimestamp) " +
                "VALUES ('peer-b', 'id-hash', 'Peer B', 'threaded', 1000, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp) " +
                "VALUES ('peer-a', 'id-hash', 'draft text', 1000)",
        )
        db.execSQL(
            "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp, threadId) " +
                "VALUES ('peer-b', 'id-hash', 'thread draft', 1000, X'0102030405060708090A0B0C0D0E0F10')",
        )
    }

    private companion object {
        const val NAME = "threads-v11-data-migration"
    }
}
