package com.lxmf.messenger.data.db.migration

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for database migration from version 26 to 27.
 * This migration adds profile icon fields (iconName, iconForegroundColor, iconBackgroundColor)
 * to the local_identities and announces tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration26To27Test {
    private val testDbName = "migration_test_db"
    private lateinit var context: Context
    private var db: SQLiteDatabase? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clean up any existing test database
        context.deleteDatabase(testDbName)
    }

    @After
    fun teardown() {
        db?.close()
        context.deleteDatabase(testDbName)
    }

    /**
     * Simulates the migration from version 26 to 27.
     */
    private fun runMigration26To27(database: SQLiteDatabase) {
        // Add icon columns to local_identities table
        database.execSQL("ALTER TABLE local_identities ADD COLUMN iconName TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE local_identities ADD COLUMN iconForegroundColor TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE local_identities ADD COLUMN iconBackgroundColor TEXT DEFAULT NULL")

        // Add icon columns to announces table
        database.execSQL("ALTER TABLE announces ADD COLUMN iconName TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE announces ADD COLUMN iconForegroundColor TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE announces ADD COLUMN iconBackgroundColor TEXT DEFAULT NULL")
    }

    // ========== local_identities Table Tests ==========

    @Test
    fun migration_26_27_localIdentities_hasIconNameColumn() {
        // Given: Create a database at version 26 with local_identities table
        db = createDatabaseV26()

        // Insert test data before migration
        db!!.execSQL(
            """
            INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, createdTimestamp, lastUsedTimestamp, isActive)
            VALUES ('hash123', 'Test Identity', 'dest123', '/path/to/file', ${System.currentTimeMillis()}, ${System.currentTimeMillis()}, 1)
            """.trimIndent(),
        )

        // When: Run migration
        runMigration26To27(db!!)

        // Then: Verify iconName column exists
        val cursor = db!!.rawQuery("PRAGMA table_info(local_identities)", null)
        val columns = getColumnNames(cursor)
        cursor.close()

        assertTrue("iconName column should exist in local_identities", columns.contains("iconName"))
    }

    @Test
    fun migration_26_27_localIdentities_hasIconForegroundColorColumn() {
        // Given
        db = createDatabaseV26()

        // When
        runMigration26To27(db!!)

        // Then
        val cursor = db!!.rawQuery("PRAGMA table_info(local_identities)", null)
        val columns = getColumnNames(cursor)
        cursor.close()

        assertTrue("iconForegroundColor column should exist in local_identities", columns.contains("iconForegroundColor"))
    }

    @Test
    fun migration_26_27_localIdentities_hasIconBackgroundColorColumn() {
        // Given
        db = createDatabaseV26()

        // When
        runMigration26To27(db!!)

        // Then
        val cursor = db!!.rawQuery("PRAGMA table_info(local_identities)", null)
        val columns = getColumnNames(cursor)
        cursor.close()

        assertTrue("iconBackgroundColor column should exist in local_identities", columns.contains("iconBackgroundColor"))
    }

    @Test
    fun migration_26_27_localIdentities_newColumnsDefaultToNull() {
        // Given: Create database and insert data before migration
        db = createDatabaseV26()
        db!!.execSQL(
            """
            INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, createdTimestamp, lastUsedTimestamp, isActive)
            VALUES ('hash123', 'Test Identity', 'dest123', '/path/to/file', ${System.currentTimeMillis()}, ${System.currentTimeMillis()}, 1)
            """.trimIndent(),
        )

        // When: Run migration
        runMigration26To27(db!!)

        // Then: Verify existing row has NULL values for new columns
        val cursor = db!!.rawQuery("SELECT iconName, iconForegroundColor, iconBackgroundColor FROM local_identities WHERE identityHash = 'hash123'", null)
        assertTrue("Should find the test identity", cursor.moveToFirst())

        assertTrue("iconName should be NULL", cursor.isNull(0))
        assertTrue("iconForegroundColor should be NULL", cursor.isNull(1))
        assertTrue("iconBackgroundColor should be NULL", cursor.isNull(2))

        cursor.close()
    }

    @Test
    fun migration_26_27_localIdentities_preservesExistingData() {
        // Given: Create database with pre-existing data
        db = createDatabaseV26()
        val timestamp = System.currentTimeMillis()
        db!!.execSQL(
            """
            INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, createdTimestamp, lastUsedTimestamp, isActive)
            VALUES ('hash123', 'My Identity', 'dest456', '/storage/identity', $timestamp, $timestamp, 1)
            """.trimIndent(),
        )

        // When: Run migration
        runMigration26To27(db!!)

        // Then: Verify existing data is preserved
        val cursor =
            db!!.rawQuery(
                "SELECT identityHash, displayName, destinationHash, filePath, isActive FROM local_identities WHERE identityHash = 'hash123'",
                null,
            )
        assertTrue("Should find the test identity", cursor.moveToFirst())

        assertEquals("hash123", cursor.getString(0))
        assertEquals("My Identity", cursor.getString(1))
        assertEquals("dest456", cursor.getString(2))
        assertEquals("/storage/identity", cursor.getString(3))
        assertEquals(1, cursor.getInt(4))

        cursor.close()
    }

    // ========== announces Table Tests ==========

    @Test
    fun migration_26_27_announces_hasIconNameColumn() {
        // Given
        db = createDatabaseV26()

        // When
        runMigration26To27(db!!)

        // Then
        val cursor = db!!.rawQuery("PRAGMA table_info(announces)", null)
        val columns = getColumnNames(cursor)
        cursor.close()

        assertTrue("iconName column should exist in announces", columns.contains("iconName"))
    }

    @Test
    fun migration_26_27_announces_hasIconForegroundColorColumn() {
        // Given
        db = createDatabaseV26()

        // When
        runMigration26To27(db!!)

        // Then
        val cursor = db!!.rawQuery("PRAGMA table_info(announces)", null)
        val columns = getColumnNames(cursor)
        cursor.close()

        assertTrue("iconForegroundColor column should exist in announces", columns.contains("iconForegroundColor"))
    }

    @Test
    fun migration_26_27_announces_hasIconBackgroundColorColumn() {
        // Given
        db = createDatabaseV26()

        // When
        runMigration26To27(db!!)

        // Then
        val cursor = db!!.rawQuery("PRAGMA table_info(announces)", null)
        val columns = getColumnNames(cursor)
        cursor.close()

        assertTrue("iconBackgroundColor column should exist in announces", columns.contains("iconBackgroundColor"))
    }

    @Test
    fun migration_26_27_announces_newColumnsDefaultToNull() {
        // Given: Create database and insert announce data before migration
        db = createDatabaseV26()
        db!!.execSQL(
            """
            INSERT INTO announces (destinationHash, peerName, publicKey, hops, lastSeenTimestamp, nodeType, isFavorite)
            VALUES ('dest123', 'Test Peer', X'0102030405', 2, ${System.currentTimeMillis()}, 'node', 0)
            """.trimIndent(),
        )

        // When: Run migration
        runMigration26To27(db!!)

        // Then: Verify existing row has NULL values for new columns
        val cursor = db!!.rawQuery("SELECT iconName, iconForegroundColor, iconBackgroundColor FROM announces WHERE destinationHash = 'dest123'", null)
        assertTrue("Should find the test announce", cursor.moveToFirst())

        assertTrue("iconName should be NULL", cursor.isNull(0))
        assertTrue("iconForegroundColor should be NULL", cursor.isNull(1))
        assertTrue("iconBackgroundColor should be NULL", cursor.isNull(2))

        cursor.close()
    }

    @Test
    fun migration_26_27_announces_preservesExistingData() {
        // Given: Create database with pre-existing announce data
        db = createDatabaseV26()
        val timestamp = System.currentTimeMillis()
        db!!.execSQL(
            """
            INSERT INTO announces (destinationHash, peerName, publicKey, hops, lastSeenTimestamp, nodeType, isFavorite, receivingInterface)
            VALUES ('dest789', 'Remote Peer', X'ABCDEF', 5, $timestamp, 'node', 1, 'AutoInterface')
            """.trimIndent(),
        )

        // When: Run migration
        runMigration26To27(db!!)

        // Then: Verify existing data is preserved
        val cursor =
            db!!.rawQuery(
                "SELECT destinationHash, peerName, hops, nodeType, isFavorite, receivingInterface FROM announces WHERE destinationHash = 'dest789'",
                null,
            )
        assertTrue("Should find the test announce", cursor.moveToFirst())

        assertEquals("dest789", cursor.getString(0))
        assertEquals("Remote Peer", cursor.getString(1))
        assertEquals(5, cursor.getInt(2))
        assertEquals("node", cursor.getString(3))
        assertEquals(1, cursor.getInt(4))
        assertEquals("AutoInterface", cursor.getString(5))

        cursor.close()
    }

    @Test
    fun migration_26_27_announces_canInsertWithIconData() {
        // Given: Create database and run migration
        db = createDatabaseV26()
        runMigration26To27(db!!)

        // When: Insert announce with icon data
        db!!.execSQL(
            """
            INSERT INTO announces (destinationHash, peerName, publicKey, hops, lastSeenTimestamp, nodeType, isFavorite, iconName, iconForegroundColor, iconBackgroundColor)
            VALUES ('dest999', 'Icon Peer', X'112233', 1, ${System.currentTimeMillis()}, 'node', 0, 'account', 'FFFFFF', '1E88E5')
            """.trimIndent(),
        )

        // Then: Verify icon data was inserted correctly
        val cursor = db!!.rawQuery("SELECT iconName, iconForegroundColor, iconBackgroundColor FROM announces WHERE destinationHash = 'dest999'", null)
        assertTrue("Should find the test announce", cursor.moveToFirst())

        assertEquals("account", cursor.getString(0))
        assertEquals("FFFFFF", cursor.getString(1))
        assertEquals("1E88E5", cursor.getString(2))

        cursor.close()
    }

    @Test
    fun migration_26_27_localIdentities_canInsertWithIconData() {
        // Given: Create database and run migration
        db = createDatabaseV26()
        runMigration26To27(db!!)

        // When: Insert identity with icon data
        val timestamp = System.currentTimeMillis()
        db!!.execSQL(
            """
            INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, createdTimestamp, lastUsedTimestamp, isActive, iconName, iconForegroundColor, iconBackgroundColor)
            VALUES ('hash999', 'Icon Identity', 'dest999', '/path', $timestamp, $timestamp, 1, 'star', '000000', 'FFD700')
            """.trimIndent(),
        )

        // Then: Verify icon data was inserted correctly
        val cursor = db!!.rawQuery("SELECT iconName, iconForegroundColor, iconBackgroundColor FROM local_identities WHERE identityHash = 'hash999'", null)
        assertTrue("Should find the test identity", cursor.moveToFirst())

        assertEquals("star", cursor.getString(0))
        assertEquals("000000", cursor.getString(1))
        assertEquals("FFD700", cursor.getString(2))

        cursor.close()
    }

    @Test
    fun migration_26_27_multipleRows_preservesAllData() {
        // Given: Create database with multiple announces and identities
        db = createDatabaseV26()
        val timestamp = System.currentTimeMillis()

        // Insert multiple identities
        for (i in 1..3) {
            db!!.execSQL(
                """
                INSERT INTO local_identities (identityHash, displayName, destinationHash, filePath, createdTimestamp, lastUsedTimestamp, isActive)
                VALUES ('hash$i', 'Identity $i', 'dest$i', '/path/$i', $timestamp, $timestamp, ${if (i == 1) 1 else 0})
                """.trimIndent(),
            )
        }

        // Insert multiple announces
        for (i in 1..3) {
            db!!.execSQL(
                """
                INSERT INTO announces (destinationHash, peerName, publicKey, hops, lastSeenTimestamp, nodeType, isFavorite)
                VALUES ('announce$i', 'Peer $i', X'0$i', $i, $timestamp, 'node', ${if (i == 1) 1 else 0})
                """.trimIndent(),
            )
        }

        // When: Run migration
        runMigration26To27(db!!)

        // Then: Verify all rows preserved in local_identities
        val identitiesCursor = db!!.rawQuery("SELECT COUNT(*) FROM local_identities", null)
        identitiesCursor.moveToFirst()
        assertEquals("All identities should be preserved", 3, identitiesCursor.getInt(0))
        identitiesCursor.close()

        // Verify all rows preserved in announces
        val announcesCursor = db!!.rawQuery("SELECT COUNT(*) FROM announces", null)
        announcesCursor.moveToFirst()
        assertEquals("All announces should be preserved", 3, announcesCursor.getInt(0))
        announcesCursor.close()
    }

    // ========== Helper Methods ==========

    /**
     * Creates a database at version 26 with the schema before profile icon columns.
     */
    private fun createDatabaseV26(): SQLiteDatabase {
        val helper =
            object : SQLiteOpenHelper(context, testDbName, null, 26) {
                override fun onCreate(db: SQLiteDatabase) {
                    // Create local_identities table without icon columns (version 26 schema)
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS local_identities (
                            identityHash TEXT NOT NULL PRIMARY KEY,
                            displayName TEXT NOT NULL,
                            destinationHash TEXT NOT NULL,
                            filePath TEXT NOT NULL,
                            keyData BLOB,
                            createdTimestamp INTEGER NOT NULL,
                            lastUsedTimestamp INTEGER NOT NULL,
                            isActive INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )

                    // Create announces table without icon columns (version 26 schema)
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS announces (
                            destinationHash TEXT NOT NULL PRIMARY KEY,
                            peerName TEXT NOT NULL,
                            publicKey BLOB NOT NULL,
                            appData BLOB,
                            hops INTEGER NOT NULL,
                            lastSeenTimestamp INTEGER NOT NULL,
                            nodeType TEXT NOT NULL,
                            receivingInterface TEXT,
                            receivingInterfaceType TEXT,
                            aspect TEXT,
                            isFavorite INTEGER NOT NULL,
                            favoritedTimestamp INTEGER,
                            stampCost INTEGER,
                            stampCostFlexibility INTEGER,
                            peeringCost INTEGER
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(
                    db: SQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    // Not used in this test
                }
            }
        return helper.writableDatabase
    }

    /**
     * Extracts column names from a PRAGMA table_info cursor.
     */
    private fun getColumnNames(cursor: Cursor): Set<String> {
        val columns = mutableSetOf<String>()
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex >= 0) {
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        return columns
    }
}
