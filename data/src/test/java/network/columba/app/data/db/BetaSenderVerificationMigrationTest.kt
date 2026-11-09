package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Beta fixtures are the immutable PR1254 exports from commit 19fb7a73a, compacted only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class BetaSenderVerificationMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `beta v11 upgrades directly preserving evidence and marking historical notifications handled`() {
        assertBetaUpgrade(11)
    }

    @Test
    fun `beta v12 upgrades preserving pending notifications signatures evidence icons and drafts`() {
        assertBetaUpgrade(12)
    }

    @Test
    fun `main v11 direct route preserves thread bytes drafts and converts the legacy flag`() {
        val name = "main-v11-direct-to-v13"
        val parents: List<List<String?>>
        helper.createDatabase(name, 11).use { before ->
            seedParents(before)
            before.execSQL(
                "INSERT INTO messages (id, conversationHash, identityHash, content, timestamp, " +
                    "isFromMe, status, isRead, signatureVerified, threadId) " +
                    "VALUES ('thread-message', 'sender', 'identity-a', 'thread content', 123, 0, 'delivered', 0, 1, X'0001FF2A')",
            )
            before.execSQL(
                "INSERT INTO threads (threadId, conversationHash, identityHash, rootMessageId, title, createdAt, lastActivityAt) " +
                    "VALUES (X'0001FF2A', 'sender', 'identity-a', 'thread-message', 'Preserved thread', 100, 123)",
            )
            before.execSQL("UPDATE drafts SET threadId = X'0001FF2A' WHERE identityHash = 'identity-a'")
            parents = snapshots(before, PARENT_QUERY)
        }
        helper.runMigrationsAndValidate(name, 13, true, *ColumbaDatabase.MIGRATIONS).use { after ->
            assertEquals(parents, snapshots(after, PARENT_QUERY))
            after.query("SELECT signatureStatus, notificationHandled, threadId FROM messages").use {
                assertTrue(it.moveToFirst())
                assertEquals("VERIFIED", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertArrayEquals(byteArrayOf(0, 1, -1, 42), it.getBlob(2))
                assertFalse(it.moveToNext())
            }
            after.query("SELECT content, hex(threadId) FROM drafts ORDER BY identityHash").use {
                assertTrue(it.moveToFirst())
                assertEquals("Preserved draft identity-a", it.getString(0))
                assertEquals("0001FF2A", it.getString(1))
                assertTrue(it.moveToNext())
                assertEquals("Preserved draft identity-b", it.getString(0))
                assertEquals("", it.getString(1))
                assertFalse(it.moveToNext())
            }
            after.query("SELECT title, hex(threadId) FROM threads").use {
                assertTrue(it.moveToFirst())
                assertEquals("Preserved thread", it.getString(0))
                assertEquals("0001FF2A", it.getString(1))
            }
            after.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    private fun assertBetaUpgrade(version: Int) {
        val name = "beta-sender-v$version-to-main-v13"
        val messages: List<List<String?>>
        val parents: List<List<String?>>
        val drafts: List<List<String?>>
        val evidence: List<List<String?>>
        createBetaFixture(name, version).use { fixture ->
            val before = fixture.writableDatabase
            seedParents(before)
            seedBetaMessages(before, version)
            messages = messageSnapshots(before, hasNotificationMarker = version == 12)
            parents = snapshots(before, PARENT_QUERY)
            drafts = snapshots(before, DRAFT_QUERY)
            evidence = snapshots(before, EVIDENCE_QUERY)
        }
        helper.runMigrationsAndValidate(name, 13, true, *ColumbaDatabase.MIGRATIONS).use { after ->
            assertEquals(messages, messageSnapshots(after, hasNotificationMarker = true))
            assertEquals(parents, snapshots(after, PARENT_QUERY))
            assertEquals(drafts, snapshots(after, DRAFT_QUERY))
            assertEquals(evidence, snapshots(after, EVIDENCE_QUERY))
            assertEquals(4, evidence.size)
            after.query("SELECT COUNT(*) FROM messages WHERE threadId IS NOT NULL").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            after.query("SELECT COUNT(*) FROM drafts WHERE threadId = X''").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
            after.query("SELECT COUNT(*) FROM threads").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            after.query("SELECT COUNT(*) FROM messages WHERE notificationHandled = 0").use {
                assertTrue(it.moveToFirst())
                assertEquals(if (version == 12) 4 else 0, it.getInt(0))
            }
            after.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    private fun createBetaFixture(
        name: String,
        version: Int,
    ): SupportSQLiteOpenHelper {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schema =
            instrumentation.context.assets.open("beta-sender-verification/$version.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("database")
            }
        assertEquals(version, schema.getInt("version"))
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(instrumentation.targetContext)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = createExportedSchema(db, schema)

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = error("The immutable beta fixture must be created at v$version, not upgraded")
                    },
                ).build(),
        )
    }

    private fun createExportedSchema(
        db: SupportSQLiteDatabase,
        schema: JSONObject,
    ) {
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (indexNumber in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(indexNumber).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        assertEquals("the beta fixtures contain no views", 0, schema.optJSONArray("views")?.length() ?: 0)
        val setup = schema.getJSONArray("setupQueries")
        for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
    }

    private fun seedParents(db: SupportSQLiteDatabase) {
        listOf("identity-a", "identity-b").forEach { identityHash ->
            db.execSQL(
                "INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, " +
                    "keyEncryptionVersion, createdTimestamp, lastUsedTimestamp, isActive) " +
                    "VALUES (?, 'Preserved identity', ?, '/identity.key', 0, 11, 22, 1)",
                arrayOf(identityHash, "destination-$identityHash"),
            )
            db.execSQL(
                "INSERT INTO conversations (peerHash, identityHash, peerName, lastMessage, " +
                    "lastMessageTimestamp, unreadCount, lastSeenTimestamp) " +
                    "VALUES ('sender', ?, 'Preserved peer', 'latest', 500, 3, 55)",
                arrayOf(identityHash),
            )
            db.execSQL(
                "INSERT INTO drafts (conversationHash, identityHash, content, updatedTimestamp) VALUES ('sender', ?, ?, 99)",
                arrayOf(identityHash, "Preserved draft $identityHash"),
            )
        }
        db.execSQL(
            "INSERT INTO peer_icons (destinationHash, iconName, foregroundColor, backgroundColor, updatedTimestamp) " +
                "VALUES ('sender', 'account', '00ff00', '000000', 88)",
        )
    }

    private fun seedBetaMessages(
        db: SupportSQLiteDatabase,
        version: Int,
    ) {
        listOf("identity-a", "identity-b").forEach { identityHash ->
            listOf("SOURCE_UNKNOWN", "VERIFIED", "INVALID", "UNVERIFIED", null).forEachIndexed { index, status ->
                db.execSQL(
                    """
                    INSERT INTO messages
                        (id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead,
                         fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId,
                         receivedHopCount, receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface,
                         signatureStatus)
                    VALUES (?, 'sender', ?, ?, ?, ?, 'delivered', ?, '{"6":"image"}', '{"reaction":["sender"]}',
                        'propagated', 'preserved error', 'reply-id', 7, 'TCP', -82, 4.25, ?, 'AutoInterface', ?)
                    """,
                    arrayOf<Any?>(
                        "message-$index",
                        identityHash,
                        "content-$index",
                        100 + index,
                        if (status == null) 1 else 0,
                        index % 2,
                        200 + index,
                        status,
                    ),
                )
                if (version == 12 && index < 2) {
                    db.execSQL(
                        "UPDATE messages SET notificationHandled = 0 WHERE id = ? AND identityHash = ?",
                        arrayOf("message-$index", identityHash),
                    )
                }
            }
            listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)).forEachIndexed { index, bytes ->
                db.execSQL(
                    "INSERT INTO message_signature_evidence (messageId, identityHash, chunkIndex, bytes) " +
                        "VALUES ('message-0', ?, ?, ?)",
                    arrayOf<Any>(identityHash, index, bytes),
                )
            }
        }
    }

    private fun messageSnapshots(
        db: SupportSQLiteDatabase,
        hasNotificationMarker: Boolean,
    ): List<List<String?>> {
        val marker = if (hasNotificationMarker) "notificationHandled" else "1"
        return snapshots(db, "SELECT $MESSAGE_COLUMNS, $marker FROM messages ORDER BY identityHash, receivedAt, id")
    }

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
        const val MESSAGE_COLUMNS =
            "id, conversationHash, identityHash, content, timestamp, isFromMe, status, isRead, " +
                "fieldsJson, reactionsJson, deliveryMethod, errorMessage, replyToMessageId, receivedHopCount, " +
                "receivedInterface, receivedRssi, receivedSnr, receivedAt, sentInterface, signatureStatus"
        const val PARENT_QUERY =
            "SELECT peerHash, identityHash, peerName, lastMessage, lastMessageTimestamp, unreadCount, lastSeenTimestamp, " +
                "iconName, foregroundColor, backgroundColor, updatedTimestamp " +
                "FROM conversations JOIN peer_icons ON peerHash = destinationHash ORDER BY identityHash"
        const val DRAFT_QUERY =
            "SELECT conversationHash, identityHash, content, updatedTimestamp FROM drafts ORDER BY identityHash"
        const val EVIDENCE_QUERY =
            "SELECT messageId, identityHash, chunkIndex, hex(bytes) FROM message_signature_evidence " +
                "ORDER BY messageId, identityHash, chunkIndex"
    }
}
