package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class Migration10To11SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration preserves every unrelated field and maps ambiguous failures without evidence`() {
        val before = helper.createDatabase(DATABASE_NAME, 10)
        seedIdentity(before, "identity-a")
        seedIdentity(before, "identity-b")
        seedMessage(before, "verified", "identity-a", 1)
        seedMessage(before, "ambiguous", "identity-a", 0)
        seedMessage(before, "legacy", "identity-a", null)
        seedMessage(before, "verified", "identity-b", 0)
        val original = snapshots(before)
        before.close()

        helper.runMigrationsAndValidate(DATABASE_NAME, 11, true, ColumbaDatabase.MIGRATION_10_11).use { after ->
            assertEquals(original, snapshots(after))
            after.query("SELECT id, identityHash, signatureStatus FROM messages ORDER BY id, identityHash").use {
                val results = mutableMapOf<Pair<String, String>, String?>()
                while (it.moveToNext()) {
                    results[it.getString(0) to it.getString(1)] = if (it.isNull(2)) null else it.getString(2)
                }
                assertEquals("VERIFIED", results["verified" to "identity-a"])
                assertEquals("UNVERIFIED", results["ambiguous" to "identity-a"])
                assertEquals("UNVERIFIED", results["verified" to "identity-b"])
                assertNull(results["legacy" to "identity-a"])
                assertFalse(results.values.contains("INVALID"))
            }
            after.query("PRAGMA table_info(messages)").use {
                val columns = mutableListOf<String>()
                while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name"))
                assertEquals(UNCHANGED_COLUMNS.split(", ") + "signatureStatus", columns)
            }
            after.query("SELECT COUNT(*) FROM message_signature_evidence").use {
                assertTrue(it.moveToFirst())
                assertEquals("old rows have no original signed payload", 0, it.getInt(0))
            }
            after.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun `rebuilt table preserves identity scoped cascading deletion`() {
        helper.createDatabase(CASCADE_DATABASE_NAME, 10).use { before ->
            seedIdentity(before, "identity-a")
            seedIdentity(before, "identity-b")
            seedMessage(before, "same-id", "identity-a", 1)
            seedMessage(before, "same-id", "identity-b", 0)
        }
        helper.runMigrationsAndValidate(CASCADE_DATABASE_NAME, 11, true, ColumbaDatabase.MIGRATION_10_11).use { after ->
            after.execSQL("PRAGMA foreign_keys = ON")
            after.execSQL("DELETE FROM conversations WHERE identityHash = 'identity-a'")
            after.query("SELECT identityHash FROM messages").use {
                assertTrue(it.moveToFirst())
                assertEquals("identity-b", it.getString(0))
                assertFalse(it.moveToNext())
            }
            after.execSQL("DELETE FROM local_identities WHERE identityHash = 'identity-b'")
            after.query("SELECT COUNT(*) FROM messages").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    private fun seedIdentity(
        db: SupportSQLiteDatabase,
        identityHash: String,
    ) {
        db.execSQL(
            """
            INSERT INTO local_identities
                (identityHash, displayName, destinationHash, filePath, keyEncryptionVersion,
                 createdTimestamp, lastUsedTimestamp, isActive)
            VALUES (?, 'Preserved identity', ?, '/identity.key', 0, 11, 22, 1)
            """,
            arrayOf(identityHash, "destination-$identityHash"),
        )
        db.execSQL(
            """
            INSERT INTO conversations
                (peerHash, identityHash, peerName, lastMessage, lastMessageTimestamp, unreadCount, lastSeenTimestamp)
            VALUES ('sender', ?, 'Preserved peer', 'latest', 44, 3, 55)
            """,
            arrayOf(identityHash),
        )
    }

    private fun seedMessage(
        db: SupportSQLiteDatabase,
        id: String,
        identityHash: String,
        legacyResult: Int?,
    ) {
        db.execSQL(
            """
            INSERT INTO messages
                (id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead,
                 fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId,
                 receivedHopCount, receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface,
                 signatureVerified)
            VALUES (?, 'sender', ?, 'untouched content', 123, 0, 'delivered', 1,
                '{"6":"image"}', '{"reaction":["sender"]}', 'propagated', 'preserved error', 'reply-id',
                7, 'TCP', -82, 4.25, 456, 'AutoInterface', ?)
            """,
            arrayOf<Any?>(id, identityHash, legacyResult),
        )
    }

    private fun snapshots(db: SupportSQLiteDatabase): List<List<String?>> =
        db.query("SELECT $UNCHANGED_COLUMNS FROM messages ORDER BY id, identityHash").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private companion object {
        const val DATABASE_NAME = "messages-signature-status-migration"
        const val CASCADE_DATABASE_NAME = "messages-signature-status-cascade-migration"
        const val UNCHANGED_COLUMNS =
            "id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead, " +
                "fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId, receivedHopCount, " +
                "receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface"
    }
}
