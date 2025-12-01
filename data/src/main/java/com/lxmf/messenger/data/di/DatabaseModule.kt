package com.lxmf.messenger.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.CustomThemeDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // Migration from version 1 to 2: Add peerPublicKey column to conversations table
    private val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add peerPublicKey column to conversations table
                // BLOB type is used for ByteArray storage
                database.execSQL("ALTER TABLE conversations ADD COLUMN peerPublicKey BLOB DEFAULT NULL")
            }
        }

    // Migration from version 2 to 3: Add announces table
    private val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create announces table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS announces (
                        destinationHash TEXT NOT NULL PRIMARY KEY,
                        peerName TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        appData BLOB,
                        hops INTEGER NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        nodeType TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 3 to 4: Add receivingInterface column to announces table
    private val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add receivingInterface column to announces table
                database.execSQL("ALTER TABLE announces ADD COLUMN receivingInterface TEXT DEFAULT NULL")
            }
        }

    // Migration from version 4 to 5: Add peer_identities table
    private val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create peer_identities table for storing known peer public keys
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS peer_identities (
                        peerHash TEXT NOT NULL PRIMARY KEY,
                        publicKey BLOB NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )

                // Migrate existing public keys from conversations table to peer_identities table
                database.execSQL(
                    """
                    INSERT INTO peer_identities (peerHash, publicKey, lastSeenTimestamp)
                    SELECT peerHash, peerPublicKey, lastSeenTimestamp
                    FROM conversations
                    WHERE peerPublicKey IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 5 to 6: Clear corrupted peer_identities table
    // Prior to this migration, peerHash contained destination hashes instead of identity hashes,
    // causing identity restoration failures. We clear the table and let peers be re-learned
    // from fresh announces with the correct identity hash.
    private val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Delete all existing peer identities (they have wrong hash type)
                database.execSQL("DELETE FROM peer_identities")
            }
        }

    // Migration from version 6 to 7: Add favorite fields to announces table
    // Adds isFavorite boolean and favoritedTimestamp for saved peers feature
    private val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add isFavorite column (defaults to false for existing rows)
                database.execSQL("ALTER TABLE announces ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                // Add favoritedTimestamp column (nullable, defaults to NULL)
                database.execSQL("ALTER TABLE announces ADD COLUMN favoritedTimestamp INTEGER DEFAULT NULL")
            }
        }

    // Migration from version 7 to 8: Add contacts table and migrate favorited announces
    // Creates unified contacts system combining saved peers and manually-added contacts
    private val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create contacts table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS contacts (
                        destinationHash TEXT NOT NULL PRIMARY KEY,
                        publicKey BLOB NOT NULL,
                        customNickname TEXT DEFAULT NULL,
                        notes TEXT DEFAULT NULL,
                        tags TEXT DEFAULT NULL,
                        addedTimestamp INTEGER NOT NULL,
                        addedVia TEXT NOT NULL,
                        lastInteractionTimestamp INTEGER NOT NULL DEFAULT 0,
                        isPinned INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )

                // Migrate existing favorited announces to contacts table
                // Use favoritedTimestamp as addedTimestamp, or current time if null
                database.execSQL(
                    """
                    INSERT INTO contacts (destinationHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned)
                    SELECT
                        destinationHash,
                        publicKey,
                        NULL as customNickname,
                        NULL as notes,
                        NULL as tags,
                        COALESCE(favoritedTimestamp, lastSeenTimestamp) as addedTimestamp,
                        'ANNOUNCE' as addedVia,
                        0 as lastInteractionTimestamp,
                        0 as isPinned
                    FROM announces
                    WHERE isFavorite = 1
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 8 to 9: Add custom_themes table
    // Enables users to create and save their own custom color themes
    private val MIGRATION_8_9 =
        object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create custom_themes table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS custom_themes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        createdTimestamp INTEGER NOT NULL,
                        modifiedTimestamp INTEGER NOT NULL,
                        baseTheme TEXT DEFAULT NULL,
                        lightPrimary INTEGER NOT NULL,
                        lightOnPrimary INTEGER NOT NULL,
                        lightPrimaryContainer INTEGER NOT NULL,
                        lightOnPrimaryContainer INTEGER NOT NULL,
                        lightSecondary INTEGER NOT NULL,
                        lightOnSecondary INTEGER NOT NULL,
                        lightSecondaryContainer INTEGER NOT NULL,
                        lightOnSecondaryContainer INTEGER NOT NULL,
                        lightTertiary INTEGER NOT NULL,
                        lightOnTertiary INTEGER NOT NULL,
                        lightTertiaryContainer INTEGER NOT NULL,
                        lightOnTertiaryContainer INTEGER NOT NULL,
                        lightError INTEGER NOT NULL,
                        lightOnError INTEGER NOT NULL,
                        lightErrorContainer INTEGER NOT NULL,
                        lightOnErrorContainer INTEGER NOT NULL,
                        lightBackground INTEGER NOT NULL,
                        lightOnBackground INTEGER NOT NULL,
                        lightSurface INTEGER NOT NULL,
                        lightOnSurface INTEGER NOT NULL,
                        lightSurfaceVariant INTEGER NOT NULL,
                        lightOnSurfaceVariant INTEGER NOT NULL,
                        lightOutline INTEGER NOT NULL,
                        lightOutlineVariant INTEGER NOT NULL,
                        darkPrimary INTEGER NOT NULL,
                        darkOnPrimary INTEGER NOT NULL,
                        darkPrimaryContainer INTEGER NOT NULL,
                        darkOnPrimaryContainer INTEGER NOT NULL,
                        darkSecondary INTEGER NOT NULL,
                        darkOnSecondary INTEGER NOT NULL,
                        darkSecondaryContainer INTEGER NOT NULL,
                        darkOnSecondaryContainer INTEGER NOT NULL,
                        darkTertiary INTEGER NOT NULL,
                        darkOnTertiary INTEGER NOT NULL,
                        darkTertiaryContainer INTEGER NOT NULL,
                        darkOnTertiaryContainer INTEGER NOT NULL,
                        darkError INTEGER NOT NULL,
                        darkOnError INTEGER NOT NULL,
                        darkErrorContainer INTEGER NOT NULL,
                        darkOnErrorContainer INTEGER NOT NULL,
                        darkBackground INTEGER NOT NULL,
                        darkOnBackground INTEGER NOT NULL,
                        darkSurface INTEGER NOT NULL,
                        darkOnSurface INTEGER NOT NULL,
                        darkSurfaceVariant INTEGER NOT NULL,
                        darkOnSurfaceVariant INTEGER NOT NULL,
                        darkOutline INTEGER NOT NULL,
                        darkOutlineVariant INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 9 to 10: No schema changes
    // Version bump to align with main branch after custom themes merge
    private val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No schema changes - version bump only
            }
        }

    // Migration from version 10 to 11: Add seed color columns to custom_themes table
    // Fixes progressive darkening bug by storing original user-selected colors
    private val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add seed color columns to custom_themes table
                database.execSQL("ALTER TABLE custom_themes ADD COLUMN seedPrimary INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE custom_themes ADD COLUMN seedSecondary INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE custom_themes ADD COLUMN seedTertiary INTEGER NOT NULL DEFAULT 0")

                // Populate seed colors from existing palette colors for existing themes
                // This prevents darkening on first re-edit of existing themes
                database.execSQL(
                    """
                    UPDATE custom_themes
                    SET seedPrimary = lightPrimary,
                        seedSecondary = lightSecondary,
                        seedTertiary = lightTertiary
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 11 to 12: Add multi-identity support
    // Creates local_identities table and adds identityHash foreign keys to all data tables
    // Existing data will be associated with a placeholder identity that will be replaced during app initialization
    private val MIGRATION_11_12 =
        object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                // 1. Create local_identities table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_identities (
                        identityHash TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        destinationHash TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        createdTimestamp INTEGER NOT NULL,
                        lastUsedTimestamp INTEGER NOT NULL,
                        isActive INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )

                // 2. Insert a placeholder identity for migration
                // This will be replaced with the actual default_identity during app initialization
                database.execSQL(
                    """
                    INSERT INTO local_identities (
                        identityHash,
                        displayName,
                        destinationHash,
                        filePath,
                        createdTimestamp,
                        lastUsedTimestamp,
                        isActive
                    ) VALUES (
                        'migration_placeholder',
                        'Default Identity',
                        'migration_placeholder',
                        'storage/default_identity',
                        $now,
                        $now,
                        1
                    )
                    """.trimIndent(),
                )

                // 3. Recreate tables with identityHash column (NOT NULL with foreign key)
                // We have to recreate because SQLite doesn't support ALTER TABLE to add NOT NULL with FK

                // Recreate conversations table
                database.execSQL("ALTER TABLE conversations RENAME TO conversations_old")
                database.execSQL(
                    """
                    CREATE TABLE conversations (
                        peerHash TEXT NOT NULL PRIMARY KEY,
                        peerName TEXT NOT NULL,
                        peerPublicKey BLOB,
                        lastMessage TEXT NOT NULL,
                        lastMessageTimestamp INTEGER NOT NULL,
                        unreadCount INTEGER NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        identityHash TEXT NOT NULL,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO conversations
                    SELECT peerHash, peerName, peerPublicKey, lastMessage, lastMessageTimestamp,
                           unreadCount, lastSeenTimestamp, 'migration_placeholder'
                    FROM conversations_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE conversations_old")

                // Recreate contacts table
                database.execSQL("ALTER TABLE contacts RENAME TO contacts_old")
                database.execSQL(
                    """
                    CREATE TABLE contacts (
                        destinationHash TEXT NOT NULL PRIMARY KEY,
                        publicKey BLOB NOT NULL,
                        customNickname TEXT,
                        notes TEXT,
                        tags TEXT,
                        addedTimestamp INTEGER NOT NULL,
                        addedVia TEXT NOT NULL,
                        lastInteractionTimestamp INTEGER NOT NULL,
                        isPinned INTEGER NOT NULL,
                        identityHash TEXT NOT NULL,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO contacts
                    SELECT destinationHash, publicKey, customNickname, notes, tags, addedTimestamp,
                           addedVia, lastInteractionTimestamp, isPinned, 'migration_placeholder'
                    FROM contacts_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE contacts_old")

                // Recreate peer_identities table
                database.execSQL("ALTER TABLE peer_identities RENAME TO peer_identities_old")
                database.execSQL(
                    """
                    CREATE TABLE peer_identities (
                        peerHash TEXT NOT NULL PRIMARY KEY,
                        publicKey BLOB NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        identityHash TEXT NOT NULL,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO peer_identities
                    SELECT peerHash, publicKey, lastSeenTimestamp, 'migration_placeholder'
                    FROM peer_identities_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE peer_identities_old")

                // Recreate announces table
                database.execSQL("ALTER TABLE announces RENAME TO announces_old")
                database.execSQL(
                    """
                    CREATE TABLE announces (
                        destinationHash TEXT NOT NULL PRIMARY KEY,
                        peerName TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        appData BLOB,
                        hops INTEGER NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        nodeType TEXT NOT NULL,
                        receivingInterface TEXT,
                        isFavorite INTEGER NOT NULL,
                        favoritedTimestamp INTEGER,
                        identityHash TEXT NOT NULL,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO announces
                    SELECT destinationHash, peerName, publicKey, appData, hops, lastSeenTimestamp,
                           nodeType, receivingInterface, isFavorite, NULL, 'migration_placeholder'
                    FROM announces_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE announces_old")

                // Recreate messages table
                database.execSQL("ALTER TABLE messages RENAME TO messages_old")
                database.execSQL(
                    """
                    CREATE TABLE messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversationHash TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isFromMe INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        isRead INTEGER NOT NULL,
                        identityHash TEXT NOT NULL,
                        FOREIGN KEY(conversationHash) REFERENCES conversations(peerHash) ON DELETE CASCADE,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO messages
                    SELECT id, conversationHash, content, timestamp, isFromMe, status, isRead, 'migration_placeholder'
                    FROM messages_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE messages_old")

                // 4. Create indexes on identityHash columns for faster queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_identityHash ON conversations(identityHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_identityHash ON contacts(identityHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_peer_identities_identityHash ON peer_identities(identityHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_announces_identityHash ON announces(identityHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_identityHash ON messages(identityHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationHash ON messages(conversationHash)")

                // Note: The 'migration_placeholder' identity will be replaced with the actual
                // default_identity during app initialization by IdentityMigrationHelper
            }
        }

    // Migration from version 12 to 13: Add LXMF fields support for attachments
    // Adds fieldsJson column to messages table for storing LXMF message fields (images, audio, etc.)
    private val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add fieldsJson column to messages table (nullable)
                // Fields are stored as JSON: {"6": "hex_image_data", "7": "hex_audio_data"}
                // Keys are LXMF field types: 5=FILE_ATTACHMENTS, 6=IMAGE, 7=AUDIO, 15=RENDERER
                database.execSQL("ALTER TABLE messages ADD COLUMN fieldsJson TEXT DEFAULT NULL")
            }
        }

    // Migration from version 13 to 14: No schema changes
    // Version bump after merge to resolve schema hash mismatch
    // The merge brought in changes from main but the schema is already correct
    private val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No schema changes - version bump only to reset schema hash
            }
        }

    // Migration from version 14 to 15: Identity-based data separation with composite primary keys
    // Changes primary keys from single column to composite (existingPK, identityHash) to allow
    // the same peer to have different data for each local identity
    private val MIGRATION_14_15 =
        object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // IMPORTANT: Order matters due to FK dependencies
                // Messages depends on Conversations, so handle Conversations first

                // === 1. Recreate conversations with composite PK ===
                database.execSQL("ALTER TABLE conversations RENAME TO conversations_old")
                database.execSQL(
                    """
                    CREATE TABLE conversations (
                        peerHash TEXT NOT NULL,
                        identityHash TEXT NOT NULL,
                        peerName TEXT NOT NULL,
                        peerPublicKey BLOB,
                        lastMessage TEXT NOT NULL,
                        lastMessageTimestamp INTEGER NOT NULL,
                        unreadCount INTEGER NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        PRIMARY KEY(peerHash, identityHash),
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO conversations
                    SELECT peerHash, identityHash, peerName, peerPublicKey, lastMessage,
                           lastMessageTimestamp, unreadCount, lastSeenTimestamp
                    FROM conversations_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE conversations_old")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_identityHash ON conversations(identityHash)")

                // === 2. Recreate messages with composite PK and updated FK ===
                database.execSQL("ALTER TABLE messages RENAME TO messages_old")
                database.execSQL(
                    """
                    CREATE TABLE messages (
                        id TEXT NOT NULL,
                        conversationHash TEXT NOT NULL,
                        identityHash TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isFromMe INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        isRead INTEGER NOT NULL,
                        fieldsJson TEXT,
                        PRIMARY KEY(id, identityHash),
                        FOREIGN KEY(conversationHash, identityHash) REFERENCES conversations(peerHash, identityHash) ON DELETE CASCADE,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO messages
                    SELECT id, conversationHash, identityHash, content, timestamp,
                           isFromMe, status, isRead, fieldsJson
                    FROM messages_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE messages_old")
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_messages_conversationHash_identityHash
                    ON messages(conversationHash, identityHash)
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_identityHash ON messages(identityHash)",
                )

                // === 3. Recreate announces with composite PK ===
                database.execSQL("ALTER TABLE announces RENAME TO announces_old")
                database.execSQL(
                    """
                    CREATE TABLE announces (
                        destinationHash TEXT NOT NULL,
                        identityHash TEXT NOT NULL,
                        peerName TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        appData BLOB,
                        hops INTEGER NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        nodeType TEXT NOT NULL,
                        receivingInterface TEXT,
                        aspect TEXT,
                        isFavorite INTEGER NOT NULL,
                        favoritedTimestamp INTEGER,
                        PRIMARY KEY(destinationHash, identityHash),
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO announces
                    SELECT destinationHash, identityHash, peerName, publicKey, appData, hops,
                           lastSeenTimestamp, nodeType, receivingInterface, aspect, isFavorite, favoritedTimestamp
                    FROM announces_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE announces_old")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_announces_identityHash ON announces(identityHash)")

                // === 4. Recreate contacts with composite PK ===
                database.execSQL("ALTER TABLE contacts RENAME TO contacts_old")
                database.execSQL(
                    """
                    CREATE TABLE contacts (
                        destinationHash TEXT NOT NULL,
                        identityHash TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        customNickname TEXT,
                        notes TEXT,
                        tags TEXT,
                        addedTimestamp INTEGER NOT NULL,
                        addedVia TEXT NOT NULL,
                        lastInteractionTimestamp INTEGER NOT NULL,
                        isPinned INTEGER NOT NULL,
                        PRIMARY KEY(destinationHash, identityHash),
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO contacts
                    SELECT destinationHash, identityHash, publicKey, customNickname, notes, tags,
                           addedTimestamp, addedVia, lastInteractionTimestamp, isPinned
                    FROM contacts_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE contacts_old")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_identityHash ON contacts(identityHash)")

                // === 5. Recreate peer_identities with composite PK ===
                database.execSQL("ALTER TABLE peer_identities RENAME TO peer_identities_old")
                database.execSQL(
                    """
                    CREATE TABLE peer_identities (
                        peerHash TEXT NOT NULL,
                        identityHash TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        PRIMARY KEY(peerHash, identityHash),
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO peer_identities
                    SELECT peerHash, identityHash, publicKey, lastSeenTimestamp
                    FROM peer_identities_old
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE peer_identities_old")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_peer_identities_identityHash ON peer_identities(identityHash)")
            }
        }

    // Migration from version 15 to 16: Make announces and peer_identities global (not identity-scoped)
    // Announces and peer identities represent other nodes on the network, which exist regardless of
    // which local identity is active. This removes the identity separation for these tables.
    private val MIGRATION_15_16 =
        object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // === 1. Recreate announces table without identityHash ===
                // For duplicates (same peer announced to multiple identities), keep the most recent
                // and preserve favorite status from any identity that favorited it
                database.execSQL("ALTER TABLE announces RENAME TO announces_old")
                database.execSQL(
                    """
                    CREATE TABLE announces (
                        destinationHash TEXT NOT NULL PRIMARY KEY,
                        peerName TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        appData BLOB,
                        hops INTEGER NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        nodeType TEXT NOT NULL,
                        receivingInterface TEXT,
                        aspect TEXT,
                        isFavorite INTEGER NOT NULL,
                        favoritedTimestamp INTEGER
                    )
                    """.trimIndent(),
                )
                // Insert deduplicated announces:
                // - Group by destinationHash
                // - Keep the most recent lastSeenTimestamp
                // - Preserve isFavorite=1 if ANY identity favorited it
                // - Keep the earliest favoritedTimestamp from those that favorited
                database.execSQL(
                    """
                    INSERT INTO announces
                    SELECT
                        destinationHash,
                        peerName,
                        publicKey,
                        appData,
                        hops,
                        lastSeenTimestamp,
                        nodeType,
                        receivingInterface,
                        aspect,
                        isFavorite,
                        favoritedTimestamp
                    FROM announces_old
                    WHERE (destinationHash, lastSeenTimestamp) IN (
                        SELECT destinationHash, MAX(lastSeenTimestamp)
                        FROM announces_old
                        GROUP BY destinationHash
                    )
                    """.trimIndent(),
                )
                // Update favorites - if any row was favorited, mark the merged row as favorited
                database.execSQL(
                    """
                    UPDATE announces
                    SET isFavorite = 1,
                        favoritedTimestamp = (
                            SELECT MIN(favoritedTimestamp)
                            FROM announces_old
                            WHERE announces_old.destinationHash = announces.destinationHash
                            AND announces_old.isFavorite = 1
                        )
                    WHERE destinationHash IN (
                        SELECT destinationHash FROM announces_old WHERE isFavorite = 1
                    )
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE announces_old")

                // === 2. Recreate peer_identities table without identityHash ===
                // For duplicates, keep the most recent (by lastSeenTimestamp)
                database.execSQL("ALTER TABLE peer_identities RENAME TO peer_identities_old")
                database.execSQL(
                    """
                    CREATE TABLE peer_identities (
                        peerHash TEXT NOT NULL PRIMARY KEY,
                        publicKey BLOB NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // Insert deduplicated peer identities - keep most recent for each peerHash
                database.execSQL(
                    """
                    INSERT INTO peer_identities
                    SELECT peerHash, publicKey, lastSeenTimestamp
                    FROM peer_identities_old
                    WHERE (peerHash, lastSeenTimestamp) IN (
                        SELECT peerHash, MAX(lastSeenTimestamp)
                        FROM peer_identities_old
                        GROUP BY peerHash
                    )
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE peer_identities_old")
            }
        }

    // Migration from version 16 to 17: Add keyData column to local_identities for backup/recovery
    private val MIGRATION_16_17 =
        object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE local_identities ADD COLUMN keyData BLOB")
            }
        }

    @Provides
    @Singleton
    fun provideColumbaDatabase(
        @ApplicationContext context: Context,
    ): ColumbaDatabase {
        return Room.databaseBuilder(
            context,
            ColumbaDatabase::class.java,
            "columba_database",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
            .build()
    }

    @Provides
    fun provideConversationDao(database: ColumbaDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideMessageDao(database: ColumbaDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    fun provideAnnounceDao(database: ColumbaDatabase): AnnounceDao {
        return database.announceDao()
    }

    @Provides
    fun providePeerIdentityDao(database: ColumbaDatabase): PeerIdentityDao {
        return database.peerIdentityDao()
    }

    @Provides
    fun provideContactDao(database: ColumbaDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    fun provideCustomThemeDao(database: ColumbaDatabase): CustomThemeDao {
        return database.customThemeDao()
    }

    @Provides
    fun provideLocalIdentityDao(database: ColumbaDatabase): LocalIdentityDao {
        return database.localIdentityDao()
    }

    @Provides
    @Singleton
    fun provideIODispatcher(): CoroutineDispatcher {
        return Dispatchers.IO
    }
}
