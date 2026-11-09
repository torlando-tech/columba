package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class Migration11To12SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration preserves rows and evidence and starts every historical row handled`() {
        helper.createDatabase(DATABASE_NAME, 11).use { before ->
            seedIdentity(before, "identity-a")
            seedMessage(before, id = "received", identityHash = "identity-a", isFromMe = 0)
            seedMessage(before, id = "sent", identityHash = "identity-a", isFromMe = 1)
            seedEvidence(before, messageId = "received", identityHash = "identity-a", bytes = byteArrayOf(1, 2, 3))
            seedEvidence(before, messageId = "sent", identityHash = "identity-a", bytes = byteArrayOf(4, 5))
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 12, true, ColumbaDatabase.MIGRATION_11_12).use { after ->
            after.query(
                """
                SELECT id, content, timestamp, isFromMe, receivedAt, signatureStatus, notificationHandled
                FROM messages ORDER BY id
                """,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("received", cursor.getString(0))
                assertEquals("untouched content", cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(456L, cursor.getLong(4))
                assertEquals("SOURCE_UNKNOWN", cursor.getString(5))
                assertEquals("historical rows must not be replayed", 1, cursor.getInt(6))
                assertTrue(cursor.moveToNext())
                assertEquals("sent", cursor.getString(0))
                assertEquals(1, cursor.getInt(3))
                assertEquals(1, cursor.getInt(6))
                assertFalse(cursor.moveToNext())
            }

            after.query(
                "SELECT messageId, identityHash, chunkIndex, bytes FROM message_signature_evidence ORDER BY messageId, chunkIndex",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("received", cursor.getString(0))
                assertEquals("identity-a", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertArrayEquals(byteArrayOf(1, 2, 3), cursor.getBlob(3))
                assertTrue(cursor.moveToNext())
                assertEquals("sent", cursor.getString(0))
                assertArrayEquals(byteArrayOf(4, 5), cursor.getBlob(3))
                assertFalse(cursor.moveToNext())
            }

            val column = columnInfo(after, "notificationHandled")
            assertEquals("INTEGER", column.type)
            assertEquals(1, column.notNull)
            assertEquals("1", column.defaultValue)
            assertEquals(
                listOf("identityHash", "isFromMe", "notificationHandled"),
                indexColumns(after, "index_messages_identityHash_isFromMe_notificationHandled"),
            )
            after.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
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
        isFromMe: Int,
    ) {
        db.execSQL(
            """
            INSERT INTO messages
                (id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead,
                 fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId,
                 receivedHopCount, receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface,
                 signatureStatus)
            VALUES (?, 'sender', ?, 'untouched content', 123, ?, 'delivered', 1,
                '{"6":"image"}', '{"reaction":["sender"]}', 'propagated', 'preserved error', 'reply-id',
                7, 'TCP', -82, 4.25, 456, 'AutoInterface', 'SOURCE_UNKNOWN')
            """,
            arrayOf<Any?>(id, identityHash, isFromMe),
        )
    }

    private fun seedEvidence(
        db: SupportSQLiteDatabase,
        messageId: String,
        identityHash: String,
        bytes: ByteArray,
    ) {
        db.execSQL(
            "INSERT INTO message_signature_evidence (messageId, identityHash, chunkIndex, bytes) VALUES (?, ?, 0, ?)",
            arrayOf<Any?>(messageId, identityHash, bytes),
        )
    }

    private data class Column(
        val type: String,
        val notNull: Int,
        val defaultValue: String?,
    )

    private fun columnInfo(
        db: SupportSQLiteDatabase,
        name: String,
    ): Column {
        db.query("PRAGMA table_info(messages)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == name) {
                    return Column(
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                    )
                }
            }
        }
        throw AssertionError("column $name is missing")
    }

    private fun indexColumns(
        db: SupportSQLiteDatabase,
        indexName: String,
    ): List<String> =
        db.query("PRAGMA index_info($indexName)").use { cursor ->
            val seq = cursor.getColumnIndexOrThrow("seqno")
            val name = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(name) to cursor.getInt(seq))
            }
        }.sortedBy { it.second }.map { it.first }

    private companion object {
        const val DATABASE_NAME = "messages-notification-handled-migration"
    }
}
