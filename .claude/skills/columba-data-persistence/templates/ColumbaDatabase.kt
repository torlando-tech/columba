package com.example.columba.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Columba Room Database
 *
 * Central database for Columba messenger app storing messages, conversations,
 * peers, and attachments.
 *
 * Version History:
 * - Version 1: Initial schema (messages, conversations)
 * - Version 2: Added title field to messages, appearance to conversations
 * - Version 3: Added peers table, attachments table
 *
 * @see MessageEntity
 * @see ConversationEntity
 * @see PeerEntity
 * @see AttachmentEntity
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        PeerEntity::class,
        AttachmentEntity::class
    ],
    version = 3,
    exportSchema = true  // Enable schema export for migration testing
)
abstract class ColumbaDatabase : RoomDatabase() {

    // ========================================================================
    // DAOs
    // ========================================================================

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun peerDao(): PeerDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        private const val DATABASE_NAME = "columba_database"

        @Volatile
        private var INSTANCE: ColumbaDatabase? = null

        /**
         * Get database instance (for non-DI usage)
         *
         * Prefer Hilt injection in production code. This is mainly for testing.
         */
        fun getInstance(context: Context): ColumbaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * Build database with all migrations
         */
        private fun buildDatabase(context: Context): ColumbaDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ColumbaDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3
                )
                .addCallback(DatabaseCallback())
                .build()
        }

        /**
         * Build in-memory database (for testing only)
         */
        fun buildInMemory(context: Context): ColumbaDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                ColumbaDatabase::class.java
            )
                .allowMainThreadQueries()  // Only for tests!
                .build()
        }

        // ====================================================================
        // Migrations
        // ====================================================================

        /**
         * Migration 1 → 2
         *
         * Changes:
         * - Add `title` column to messages table (default empty string)
         * - Add `appearance` column to conversations table (nullable)
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add title column to messages
                db.execSQL("ALTER TABLE messages ADD COLUMN title TEXT NOT NULL DEFAULT ''")

                // Add appearance column to conversations
                db.execSQL("ALTER TABLE conversations ADD COLUMN appearance TEXT")

                // Note: SQLite ALTER TABLE is limited. For complex changes,
                // create new table, copy data, drop old, rename new.
            }
        }

        /**
         * Migration 2 → 3
         *
         * Changes:
         * - Create peers table
         * - Create attachments table
         * - Populate peers from existing conversations
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create peers table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS peers (
                        peer_hash TEXT PRIMARY KEY NOT NULL,
                        display_name TEXT NOT NULL,
                        public_key BLOB,
                        appearance TEXT,
                        last_seen INTEGER,
                        is_announced INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                """)

                // Create index on last_seen for sorting
                db.execSQL("CREATE INDEX index_peers_last_seen ON peers(last_seen)")

                // Create attachments table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        message_id TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        hash TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
                    )
                """)

                // Create index on message_id for fast lookups
                db.execSQL("CREATE INDEX index_attachments_message_id ON attachments(message_id)")

                // Populate peers table from existing conversations
                db.execSQL("""
                    INSERT INTO peers (peer_hash, display_name, appearance, last_seen, created_at)
                    SELECT
                        peer_hash,
                        peer_name,
                        appearance,
                        last_message_timestamp,
                        created_at
                    FROM conversations
                """)
            }
        }

        // ====================================================================
        // Destructive Migration (for development only!)
        // ====================================================================

        /**
         * Build database with fallback to destructive migration
         *
         * ⚠️ WARNING: This will delete all data if migration is missing!
         * Only use during development. Never in production.
         */
        fun buildWithDestructiveMigration(context: Context): ColumbaDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                ColumbaDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()  // ⚠️ DELETES ALL DATA
                .build()
        }
    }

    /**
     * Database callback for initialization and lifecycle events
     */
    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Database created for the first time
            // Can insert default data here if needed
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // Enable foreign key constraints (disabled by default in SQLite)
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }
}

// ============================================================================
// Hilt Module (for dependency injection)
// ============================================================================

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing database and DAO dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): ColumbaDatabase {
        return ColumbaDatabase.getInstance(context)
    }

    @Provides
    fun provideMessageDao(database: ColumbaDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    fun provideConversationDao(database: ColumbaDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun providePeerDao(database: ColumbaDatabase): PeerDao {
        return database.peerDao()
    }

    @Provides
    fun provideAttachmentDao(database: ColumbaDatabase): AttachmentDao {
        return database.attachmentDao()
    }
}

// ============================================================================
// Database Utilities
// ============================================================================

/**
 * Utility functions for database operations
 */
object DatabaseUtils {

    /**
     * Clear all data from database (for testing/debugging)
     */
    suspend fun clearAllData(database: ColumbaDatabase) {
        database.messageDao().deleteAll()
        database.conversationDao().deleteAll()
        database.peerDao().deleteAll()
        database.attachmentDao().deleteAll()
    }

    /**
     * Get database size in bytes
     */
    fun getDatabaseSize(context: Context): Long {
        val dbFile = context.getDatabasePath(ColumbaDatabase.DATABASE_NAME)
        return if (dbFile.exists()) dbFile.length() else 0
    }

    /**
     * Vacuum database to reclaim space after deletions
     */
    suspend fun vacuumDatabase(database: ColumbaDatabase) {
        database.openHelper.writableDatabase.execSQL("VACUUM")
    }

    /**
     * Get database info for debugging
     */
    fun getDatabaseInfo(context: Context): DatabaseInfo {
        val dbFile = context.getDatabasePath(ColumbaDatabase.DATABASE_NAME)
        return DatabaseInfo(
            exists = dbFile.exists(),
            path = dbFile.absolutePath,
            sizeBytes = if (dbFile.exists()) dbFile.length() else 0,
            lastModified = if (dbFile.exists()) dbFile.lastModified() else 0
        )
    }

    data class DatabaseInfo(
        val exists: Boolean,
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long
    )
}

// ============================================================================
// Type Converters (if needed for complex types)
// ============================================================================

import androidx.room.TypeConverter
import java.util.Date

/**
 * Type converters for Room database
 *
 * Add to @Database annotation:
 * @TypeConverters(Converters::class)
 */
class Converters {

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromStringList(value: String?): List<String>? {
        return value?.split(",")?.filter { it.isNotBlank() }
    }

    @TypeConverter
    fun stringListToString(list: List<String>?): String? {
        return list?.joinToString(",")
    }

    // Add more converters as needed (e.g., for JSON objects)
}
