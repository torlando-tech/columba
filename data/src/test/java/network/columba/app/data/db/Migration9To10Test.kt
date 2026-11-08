package network.columba.app.data.db

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the historical nullable flag migration. Existing messages must retain no recorded
 * verification result; [Migration9To10SchemaTest] validates the complete historical schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration9To10Test {
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // In-memory (name = null) v9 `messages` table. Only the columns the
        // migration cares about are needed — the ALTER adds its column
        // regardless of the surrounding schema.
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE messages (" +
                                    "id TEXT NOT NULL, " +
                                    "identityHash TEXT NOT NULL, " +
                                    "content TEXT, " +
                                    "PRIMARY KEY(id, identityHash))",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `historical verification migration backfills existing rows to null`() {
        db.execSQL(
            "INSERT INTO messages (id, identityHash, content) VALUES ('m1', 'id1', 'legacy')",
        )

        ColumbaDatabase.MIGRATION_9_10.migrate(db)

        db.query("SELECT signatureVerified FROM messages WHERE id = 'm1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(
                "pre-existing rows must backfill to NULL, not false",
                cursor.isNull(0),
            )
        }
    }
}
