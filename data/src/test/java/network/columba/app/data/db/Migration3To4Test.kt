package network.columba.app.data.db

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration3To4Test {
    private lateinit var context: Context
    private val databaseName = "migration-3-4-test.db"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun teardown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration_backfillsCurrentInterfaceAsFirstSighting() {
        openAtVersion3().close()
        val migrated = openAtVersion4()

        migrated.query(
            "SELECT destinationHash, interfaceType, receivingInterface, lastSeenTimestamp, hops " +
                "FROM announce_interface_sightings",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("destination", cursor.getString(0))
            assertEquals("RNODE", cursor.getString(1))
            assertEquals("RNodeInterface[Radio]", cursor.getString(2))
            assertEquals(1234L, cursor.getLong(3))
            assertEquals(2, cursor.getInt(4))
        }
        migrated.query(
            "SELECT receivingInterfaceType FROM announces WHERE destinationHash = 'destination'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("RNODE", cursor.getString(0))
        }
        migrated.close()
    }

    private fun openAtVersion3(): SupportSQLiteDatabase {
        val callback =
            object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE announces (
                            destinationHash TEXT NOT NULL PRIMARY KEY,
                            receivingInterface TEXT,
                            receivingInterfaceType TEXT,
                            lastSeenTimestamp INTEGER NOT NULL,
                            hops INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO announces VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>("destination", "RNodeInterface[Radio]", "UNKNOWN", 1234L, 2),
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            }
        return createHelper(callback).writableDatabase
    }

    private fun openAtVersion4(): SupportSQLiteDatabase {
        val callback =
            object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    ColumbaDatabase.MIGRATION_3_4.migrate(db)
                }
            }
        return createHelper(callback).writableDatabase
    }

    private fun createHelper(callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(databaseName)
                .callback(callback)
                .build(),
        )
}
