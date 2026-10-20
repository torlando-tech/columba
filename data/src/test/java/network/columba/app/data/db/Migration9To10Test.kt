package network.columba.app.data.db

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip test for [ColumbaDatabase.MIGRATION_9_10] — the v9 → v10 step that
 * adds the nullable `messages.signatureVerified INTEGER` column.
 *
 * Exercises the real production migration: stands up a minimal v9 `messages`
 * table, seeds a pre-existing row, runs `MIGRATION_9_10.migrate(db)`, and asserts
 *   1. the column did not exist before the migration,
 *   2. it exists afterward, and
 *   3. pre-existing rows backfill to NULL (the "no warning" state the UI relies
 *      on for legacy messages — not `false`, which would falsely flag every old
 *      message as a potential forgery).
 *
 * Room's full schema-match validation (column type/affinity vs the
 * `MessageEntity` definition) is covered by [Migration9To10SchemaTest], which
 * runs this migration through `MigrationTestHelper.runMigrationsAndValidate`;
 * this test pins the migration SQL itself.
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

    private fun columnExists(column: String): Boolean =
        db.query("PRAGMA table_info(messages)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIdx) else null }
                .any { it == column }
        }

    @Test
    fun `adds nullable signatureVerified column and backfills existing rows to null`() {
        db.execSQL(
            "INSERT INTO messages (id, identityHash, content) VALUES ('m1', 'id1', 'legacy')",
        )
        assertFalse("column must not exist before the migration", columnExists("signatureVerified"))

        ColumbaDatabase.MIGRATION_9_10.migrate(db)

        assertTrue("column must exist after the migration", columnExists("signatureVerified"))

        db.query("SELECT signatureVerified FROM messages WHERE id = 'm1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(
                "pre-existing rows must backfill to NULL, not false",
                cursor.isNull(cursor.getColumnIndexOrThrow("signatureVerified")),
            )
        }
    }
}
