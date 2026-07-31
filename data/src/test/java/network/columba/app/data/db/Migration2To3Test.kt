package network.columba.app.data.db

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration2To3Test {
    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase
    private val databaseName = "peer-activity-migration-test"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE announces(destinationHash TEXT NOT NULL PRIMARY KEY, lastSeenTimestamp INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE received_locations(id TEXT NOT NULL PRIMARY KEY, senderHash TEXT NOT NULL, receivedAt INTEGER NOT NULL)")
                db.execSQL(
                    "CREATE TABLE messages(" +
                        "id TEXT NOT NULL, identityHash TEXT NOT NULL, conversationHash TEXT NOT NULL, " +
                        "isFromMe INTEGER NOT NULL, receivedAt INTEGER, status TEXT NOT NULL, PRIMARY KEY(id, identityHash))",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build(),
        )
        db = helper.writableDatabase
    }

    @After
    fun teardown() {
        helper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `migration backfills only trustworthy inbound reception timestamps`() {
        db.execSQL("INSERT INTO announces VALUES ('announce-peer', 100)")
        db.execSQL("INSERT INTO received_locations VALUES ('loc', 'telemetry-peer', 300)")
        db.execSQL("INSERT INTO messages VALUES ('incoming', 'owner', 'message-peer', 0, 200, 'delivered')")
        db.execSQL("INSERT INTO messages VALUES ('outgoing', 'owner', 'outgoing-peer', 1, 999, 'sent')")
        db.execSQL("INSERT INTO messages VALUES ('legacy', 'owner', 'legacy-peer', 0, NULL, 'delivered')")

        ColumbaDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(100L, activityTimestamp("announce-peer"))
        assertEquals(200L, activityTimestamp("message-peer"))
        assertNull(activityTimestamp("telemetry-peer"))
        assertNull(activityTimestamp("outgoing-peer"))
        assertNull(activityTimestamp("legacy-peer"))
        assertEquals(1, eventCount("message:incoming"))
        assertEquals(0, eventCount("proof:outgoing"))
    }

    @Test
    fun `migration rejects implausible future timestamps`() {
        val future = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        db.execSQL("INSERT INTO announces VALUES ('future-announce', $future)")
        db.execSQL("INSERT INTO messages VALUES ('future-message', 'owner', 'future-peer', 0, $future, 'delivered')")

        ColumbaDatabase.MIGRATION_2_3.migrate(db)

        assertNull(activityTimestamp("future-announce"))
        assertNull(activityTimestamp("future-peer"))
        assertEquals(1, eventCount("message:future-message"))
    }

    @Test
    fun `migration deterministically keeps newest case variant announce`() {
        db.execSQL("INSERT INTO announces VALUES ('CASE-PEER', 100)")
        db.execSQL("INSERT INTO announces VALUES ('case-peer', 200)")

        ColumbaDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(200L, activityTimestamp("case-peer"))
    }

    @Test
    fun `migration chooses newest trustworthy source per peer`() {
        db.execSQL("INSERT INTO announces VALUES ('same-peer', 100)")
        db.execSQL("INSERT INTO received_locations VALUES ('loc', 'same-peer', 300)")
        db.execSQL("INSERT INTO messages VALUES ('incoming', 'owner', 'same-peer', 0, 200, 'delivered')")

        ColumbaDatabase.MIGRATION_2_3.migrate(db)

        assertEquals(200L, activityTimestamp("same-peer"))
        db.query("SELECT activityType FROM peer_activity WHERE destinationHash = 'same-peer'").use {
            it.moveToFirst()
            assertEquals("MESSAGE", it.getString(0))
        }
    }

    private fun eventCount(eventId: String): Int =
        db.query(
            "SELECT COUNT(*) FROM peer_activity_events WHERE eventId = ?",
            arrayOf(eventId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun activityTimestamp(destinationHash: String): Long? =
        db.query(
            "SELECT lastReceivedAt FROM peer_activity WHERE destinationHash = ?",
            arrayOf(destinationHash),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
}
