package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades main's exported threaded v12 database, not the beta sender-verification schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class Migration12To13SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration preserves message metadata threads and drafts and maps historical verification`() {
        val originalMessages: List<List<String?>>
        val originalThreads: List<List<String?>>
        val originalDrafts: List<List<String?>>
        helper.createDatabase(DATABASE_NAME, 12).use { before ->
            seedIdentity(before, "identity-a")
            seedIdentity(before, "identity-b")
            seedMessage(before, "verified", "identity-a", 1, isFromMe = 0, threadId = THREAD_ID)
            seedMessage(before, "ambiguous", "identity-a", 0, isFromMe = 0, threadId = null)
            seedMessage(before, "legacy", "identity-a", null, isFromMe = 1, threadId = THREAD_ID)
            seedMessage(before, "verified", "identity-b", 0, isFromMe = 0, threadId = THREAD_ID)
            originalMessages = messageSnapshots(before)
            originalThreads = snapshots(before, THREAD_QUERY)
            originalDrafts = snapshots(before, DRAFT_QUERY)
            before.query("SELECT name FROM sqlite_master WHERE name = 'message_signature_evidence'").use {
                assertFalse("main v12 has no retained evidence to migrate", it.moveToFirst())
            }
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 13, true, ColumbaDatabase.MIGRATION_12_13).use { after ->
            assertEquals(originalMessages, messageSnapshots(after))
            assertEquals(originalThreads, snapshots(after, THREAD_QUERY))
            assertEquals(originalDrafts, snapshots(after, DRAFT_QUERY))
            after.query("SELECT id, identityHash, signatureStatus, notificationHandled FROM messages ORDER BY id, identityHash").use {
                val results = mutableMapOf<Pair<String, String>, String?>()
                while (it.moveToNext()) {
                    results[it.getString(0) to it.getString(1)] = if (it.isNull(2)) null else it.getString(2)
                    assertEquals("historical incoming and outgoing rows must not replay alerts", 1, it.getInt(3))
                }
                assertEquals(4, results.size)
                assertEquals("VERIFIED", results["verified" to "identity-a"])
                assertEquals("UNVERIFIED", results["ambiguous" to "identity-a"])
                assertEquals("UNVERIFIED", results["verified" to "identity-b"])
                assertNull(results["legacy" to "identity-a"])
                assertFalse(results.values.contains("INVALID"))
                assertFalse(results.values.contains("SOURCE_UNKNOWN"))
            }
            after.query("SELECT threadId FROM messages WHERE id = 'ambiguous'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            after.query("SELECT threadId FROM messages WHERE id = 'verified' AND identityHash = 'identity-a'").use {
                assertTrue(it.moveToFirst())
                assertArrayEquals(THREAD_ID, it.getBlob(0))
            }
            after.query("SELECT COUNT(*) FROM message_signature_evidence").use {
                assertTrue(it.moveToFirst())
                assertEquals("no original signed payload exists for historical rows", 0, it.getInt(0))
            }
            assertSchemaDetails(after)
            after.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun `new evidence and all signature states work with identity scoped cascading deletion`() {
        helper.createDatabase(CASCADE_DATABASE_NAME, 12).use { before ->
            seedIdentity(before, "identity-a")
            seedIdentity(before, "identity-b")
            seedMessage(before, "same-id", "identity-a", 0, isFromMe = 0, threadId = THREAD_ID)
            seedMessage(before, "same-id", "identity-b", 0, isFromMe = 0, threadId = THREAD_ID)
        }
        helper.runMigrationsAndValidate(CASCADE_DATABASE_NAME, 13, true, ColumbaDatabase.MIGRATION_12_13).use { after ->
            after.execSQL("PRAGMA foreign_keys = ON")
            after.execSQL("UPDATE messages SET signatureStatus = 'SOURCE_UNKNOWN' WHERE identityHash = 'identity-a'")
            after.execSQL("UPDATE messages SET signatureStatus = 'INVALID' WHERE identityHash = 'identity-b'")
            seedEvidence(after, "identity-a", 0, byteArrayOf(1, 2, 3))
            seedEvidence(after, "identity-a", 1, byteArrayOf(4, 5))
            seedEvidence(after, "identity-b", 0, byteArrayOf(6, 7))
            after.query("SELECT signatureStatus FROM messages ORDER BY identityHash").use {
                assertTrue(it.moveToFirst())
                assertEquals("SOURCE_UNKNOWN", it.getString(0))
                assertTrue(it.moveToNext())
                assertEquals("INVALID", it.getString(0))
            }
            after.query("SELECT bytes FROM message_signature_evidence WHERE identityHash = 'identity-a' ORDER BY chunkIndex").use {
                assertTrue(it.moveToFirst())
                assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(0))
                assertTrue(it.moveToNext())
                assertArrayEquals(byteArrayOf(4, 5), it.getBlob(0))
                assertFalse(it.moveToNext())
            }
            after.execSQL("DELETE FROM conversations WHERE identityHash = 'identity-a'")
            after.query("SELECT identityHash FROM messages").use {
                assertTrue(it.moveToFirst())
                assertEquals("identity-b", it.getString(0))
                assertFalse(it.moveToNext())
            }
            after.query("SELECT identityHash, bytes FROM message_signature_evidence").use {
                assertTrue(it.moveToFirst())
                assertEquals("identity-b", it.getString(0))
                assertArrayEquals(byteArrayOf(6, 7), it.getBlob(1))
                assertFalse(it.moveToNext())
            }
            after.execSQL("DELETE FROM local_identities WHERE identityHash = 'identity-b'")
            listOf("messages", "message_signature_evidence", "threads", "drafts").forEach { table ->
                after.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("$table must cascade with its owner", 0, it.getInt(0))
                }
            }
            after.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun `real v2 fixture upgrades through the shared chain to the exported v13 schema`() {
        helper.createDatabase(CHAIN_DATABASE_NAME, 2).close()
        helper.runMigrationsAndValidate(
            CHAIN_DATABASE_NAME,
            13,
            true,
            *ColumbaDatabase.MIGRATIONS.filter { it.startVersion >= 2 }.toTypedArray(),
        ).close()
    }

    private fun assertSchemaDetails(db: SupportSQLiteDatabase) {
        db.query("PRAGMA table_info(messages)").use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                columns += name
                if (name == "notificationHandled") {
                    assertEquals("INTEGER", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("1", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertEquals(UNCHANGED_COLUMNS.split(", ") + listOf("signatureStatus", "notificationHandled", "threadId"), columns)
            assertFalse(columns.contains("signatureVerified"))
        }
        val expectedIndexes =
            listOf(
                listOf("conversationHash", "identityHash"),
                listOf("identityHash"),
                listOf("timestamp"),
                listOf("conversationHash", "identityHash", "timestamp"),
                listOf("conversationHash", "identityHash", "isFromMe", "isRead"),
                listOf("replyToMessageId"),
                listOf("signatureStatus", "conversationHash", "id", "identityHash"),
                listOf("identityHash", "isFromMe", "notificationHandled"),
                listOf("conversationHash", "identityHash", "threadId", "timestamp"),
            )
        expectedIndexes.forEach { columns ->
            val indexName = "index_messages_${columns.joinToString("_")}"
            db.query("PRAGMA index_info($indexName)").use { cursor ->
                val actual = buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                assertEquals(indexName, columns, actual)
            }
        }
        assertEquals(
            setOf(
                "conversations:conversationHash:peerHash:NO ACTION:CASCADE",
                "conversations:identityHash:identityHash:NO ACTION:CASCADE",
                "local_identities:identityHash:identityHash:NO ACTION:CASCADE",
            ),
            foreignKeys(db, "messages"),
        )
        assertEquals(
            setOf(
                "messages:messageId:id:NO ACTION:CASCADE",
                "messages:identityHash:identityHash:NO ACTION:CASCADE",
            ),
            foreignKeys(db, "message_signature_evidence"),
        )
        db.execSQL(
            "INSERT INTO messages (id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead) " +
                "VALUES ('default-marker', 'sender', 'identity-a', 'default', 999, 0, 'delivered', 0)",
        )
        db.query("SELECT notificationHandled FROM messages WHERE id = 'default-marker'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.execSQL("UPDATE messages SET notificationHandled = 0 WHERE id = 'default-marker'")
        db.query("SELECT notificationHandled FROM messages WHERE id = 'default-marker'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    private fun foreignKeys(
        db: SupportSQLiteDatabase,
        table: String,
    ): Set<String> =
        db.query("PRAGMA foreign_key_list($table)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(listOf("table", "from", "to", "on_update", "on_delete").joinToString(":") { name ->
                        cursor.getString(cursor.getColumnIndexOrThrow(name))
                    })
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
        db.execSQL(
            """
            INSERT INTO threads (threadId, conversationHash, identityHash, rootMessageId, title, createdAt, lastActivityAt, archivedAt)
            VALUES (?, 'sender', ?, 'root-id', 'Preserved thread', 66, 77, 88)
            """,
            arrayOf<Any>(THREAD_ID, identityHash),
        )
        listOf(byteArrayOf(), THREAD_ID).forEach { threadId ->
            db.execSQL(
                "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp, threadId) VALUES ('sender', ?, ?, 99, ?)",
                arrayOf<Any>(identityHash, if (threadId.isEmpty()) "Main draft" else "Thread draft", threadId),
            )
        }
    }

    private fun seedMessage(
        db: SupportSQLiteDatabase,
        id: String,
        identityHash: String,
        legacyResult: Int?,
        isFromMe: Int,
        threadId: ByteArray?,
    ) {
        db.execSQL(
            """
            INSERT INTO messages
                (id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead,
                 fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId,
                 receivedHopCount, receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface,
                 signatureVerified, threadId)
            VALUES (?, 'sender', ?, 'untouched content', 123, ?, 'delivered', 1,
                '{"6":"image"}', '{"reaction":["sender"]}', 'propagated', 'preserved error', 'reply-id',
                7, 'TCP', -82, 4.25, 456, 'AutoInterface', ?, ?)
            """,
            arrayOf<Any?>(id, identityHash, isFromMe, legacyResult, threadId),
        )
    }

    private fun seedEvidence(
        db: SupportSQLiteDatabase,
        identityHash: String,
        chunkIndex: Int,
        bytes: ByteArray,
    ) {
        db.execSQL(
            "INSERT INTO message_signature_evidence (messageId, identityHash, chunkIndex, bytes) VALUES ('same-id', ?, ?, ?)",
            arrayOf<Any>(identityHash, chunkIndex, bytes),
        )
    }

    private fun messageSnapshots(db: SupportSQLiteDatabase): List<List<String?>> =
        snapshots(db, "SELECT $UNCHANGED_COLUMNS, hex(threadId), threadId IS NULL FROM messages ORDER BY id, identityHash")

    private fun snapshots(
        db: SupportSQLiteDatabase,
        query: String,
    ): List<List<String?>> =
        db.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    private companion object {
        const val DATABASE_NAME = "main-v12-to-v13-sender-verification"
        const val CASCADE_DATABASE_NAME = "main-v12-to-v13-signature-evidence-cascade"
        const val CHAIN_DATABASE_NAME = "main-v2-to-v13-room-schema-migration"
        val THREAD_ID = byteArrayOf(0, 1, -1, 42)
        const val UNCHANGED_COLUMNS =
            "id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead, " +
                "fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId, receivedHopCount, " +
                "receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface"
        const val THREAD_QUERY =
            "SELECT hex(threadId), conversationHash, identityHash, rootMessageId, title, createdAt, lastActivityAt, archivedAt " +
                "FROM threads ORDER BY identityHash, hex(threadId)"
        const val DRAFT_QUERY =
            "SELECT conversationHash, identityHash, content, updatedTimestamp, hex(threadId) FROM drafts ORDER BY identityHash, hex(threadId)"
    }
}
