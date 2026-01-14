package com.lxmf.messenger.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.AllowedContactDao
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ContactDao
import com.lxmf.messenger.data.db.dao.ConversationDao
import com.lxmf.messenger.data.db.dao.CustomThemeDao
import com.lxmf.messenger.data.db.dao.GuardianConfigDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.dao.MessageDao
import com.lxmf.messenger.data.db.dao.OfflineMapRegionDao
import com.lxmf.messenger.data.db.dao.PeerIdentityDao
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.db.dao.RmspServerDao
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
    /**
     * All database migrations, exposed for use by service process.
     * The service process runs in :reticulum and needs its own Room instance
     * for direct database writes when the app process is killed.
     */
    val ALL_MIGRATIONS: Array<Migration> by lazy {
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
        )
    }

    const val DATABASE_NAME = "columba_database"

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

    // Migration from version 17 to 18: Add status column and make publicKey nullable for pending identity support
    // This enables importing contacts from Sideband using only destination hash (without public key)
    // SQLite doesn't support ALTER TABLE to change nullability, so we must recreate the table
    private val MIGRATION_17_18 =
        object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Step 1: Rename old table
                database.execSQL("ALTER TABLE contacts RENAME TO contacts_old")

                // Step 2: Create new table with nullable publicKey and status column
                database.execSQL(
                    """
                    CREATE TABLE contacts (
                        destinationHash TEXT NOT NULL,
                        identityHash TEXT NOT NULL,
                        publicKey BLOB,
                        customNickname TEXT,
                        notes TEXT,
                        tags TEXT,
                        addedTimestamp INTEGER NOT NULL,
                        addedVia TEXT NOT NULL,
                        lastInteractionTimestamp INTEGER NOT NULL,
                        isPinned INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        PRIMARY KEY(destinationHash, identityHash),
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )

                // Step 3: Copy data from old table (existing contacts get 'ACTIVE' status)
                database.execSQL(
                    """
                    INSERT INTO contacts (destinationHash, identityHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned, status)
                    SELECT destinationHash, identityHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned, 'ACTIVE'
                    FROM contacts_old
                    """.trimIndent(),
                )

                // Step 4: Drop old table
                database.execSQL("DROP TABLE contacts_old")

                // Step 5: Recreate index
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_identityHash ON contacts(identityHash)")
            }
        }

    // Migration from version 18 to 19: Clear auto-populated customNicknames
    // This fixes a bug where announce/conversation names were incorrectly stored in customNickname,
    // preventing peer name updates from showing when they re-announce.
    // Only clears customNickname where it matches the current announce peerName (i.e., was auto-populated)
    // AND the contact was added via ANNOUNCE or CONVERSATION. User-customized nicknames are preserved.
    // Also handles edge case where contacts table might not have been properly migrated from 17->18
    private val MIGRATION_18_19 =
        object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Check if contacts table has the correct structure (has status column)
                // If not, apply the 17->18 migration first
                val cursor = database.query("PRAGMA table_info(contacts)")
                var hasStatusColumn = false

                try {
                    while (cursor.moveToNext()) {
                        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (columnName == "status") {
                            hasStatusColumn = true
                            break
                        }
                    }
                } finally {
                    cursor.close()
                }

                // If contacts table is missing status column, apply 17->18 migration first
                if (!hasStatusColumn) {
                    // Step 1: Rename old table
                    database.execSQL("ALTER TABLE contacts RENAME TO contacts_old")

                    // Step 2: Create new table with nullable publicKey and status column
                    database.execSQL(
                        """
                        CREATE TABLE contacts (
                            destinationHash TEXT NOT NULL,
                            identityHash TEXT NOT NULL,
                            publicKey BLOB,
                            customNickname TEXT,
                            notes TEXT,
                            tags TEXT,
                            addedTimestamp INTEGER NOT NULL,
                            addedVia TEXT NOT NULL,
                            lastInteractionTimestamp INTEGER NOT NULL,
                            isPinned INTEGER NOT NULL,
                            status TEXT NOT NULL DEFAULT 'ACTIVE',
                            PRIMARY KEY(destinationHash, identityHash),
                            FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )

                    // Step 3: Copy data from old table (existing contacts get 'ACTIVE' status)
                    database.execSQL(
                        """
                        INSERT INTO contacts (destinationHash, identityHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned, status)
                        SELECT destinationHash, identityHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned, 'ACTIVE'
                        FROM contacts_old
                        """.trimIndent(),
                    )

                    // Step 4: Drop old table
                    database.execSQL("DROP TABLE contacts_old")

                    // Step 5: Recreate index
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_identityHash ON contacts(identityHash)")
                }

                // Now safe to do the UPDATE operation
                database.execSQL(
                    """
                    UPDATE contacts
                    SET customNickname = NULL
                    WHERE addedVia IN ('ANNOUNCE', 'CONVERSATION')
                    AND customNickname IS NOT NULL
                    AND EXISTS (
                        SELECT 1 FROM announces a
                        WHERE a.destinationHash = contacts.destinationHash
                        AND a.peerName = contacts.customNickname
                    )
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 19 to 20: Add database indices for query optimization
    // Also handles edge case where contacts table might not have been properly migrated from 17->18
    private val MIGRATION_19_20 =
        object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Check if contacts table needs to be fixed (missing status column or publicKey is NOT NULL)
                // This handles edge cases where database version is 19 but structure is from version 17
                val cursor = database.query("PRAGMA table_info(contacts)")
                var hasStatusColumn = false
                var publicKeyIsNullable = false

                try {
                    while (cursor.moveToNext()) {
                        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))

                        if (columnName == "status") {
                            hasStatusColumn = true
                        }
                        if (columnName == "publicKey" && notNull == 0) {
                            publicKeyIsNullable = true
                        }
                    }
                } finally {
                    cursor.close()
                }

                // If contacts table is missing status column or publicKey is NOT NULL, fix it
                if (!hasStatusColumn || !publicKeyIsNullable) {
                    // Step 1: Rename old table
                    database.execSQL("ALTER TABLE contacts RENAME TO contacts_old")

                    // Step 2: Create new table with nullable publicKey and status column
                    database.execSQL(
                        """
                        CREATE TABLE contacts (
                            destinationHash TEXT NOT NULL,
                            identityHash TEXT NOT NULL,
                            publicKey BLOB,
                            customNickname TEXT,
                            notes TEXT,
                            tags TEXT,
                            addedTimestamp INTEGER NOT NULL,
                            addedVia TEXT NOT NULL,
                            lastInteractionTimestamp INTEGER NOT NULL,
                            isPinned INTEGER NOT NULL,
                            status TEXT NOT NULL DEFAULT 'ACTIVE',
                            PRIMARY KEY(destinationHash, identityHash),
                            FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )

                    // Step 3: Copy data from old table (existing contacts get 'ACTIVE' status)
                    database.execSQL(
                        """
                        INSERT INTO contacts (destinationHash, identityHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned, status)
                        SELECT destinationHash, identityHash, publicKey, customNickname, notes, tags, addedTimestamp, addedVia, lastInteractionTimestamp, isPinned, 'ACTIVE'
                        FROM contacts_old
                        """.trimIndent(),
                    )

                    // Step 4: Drop old table
                    database.execSQL("DROP TABLE contacts_old")

                    // Step 5: Recreate index
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_identityHash ON contacts(identityHash)")
                }

                // MessageEntity indices
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages(timestamp)")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationHash_identityHash_timestamp ON messages(conversationHash, identityHash, timestamp)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationHash_identityHash_isFromMe_isRead ON messages(conversationHash, identityHash, isFromMe, isRead)",
                )

                // ConversationEntity indices
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_identityHash_lastMessageTimestamp ON conversations(identityHash, lastMessageTimestamp)",
                )

                // AnnounceEntity indices
                database.execSQL("CREATE INDEX IF NOT EXISTS index_announces_lastSeenTimestamp ON announces(lastSeenTimestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_announces_isFavorite_favoritedTimestamp ON announces(isFavorite, favoritedTimestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_announces_nodeType_lastSeenTimestamp ON announces(nodeType, lastSeenTimestamp)")

                // ContactEntity indices
                // Drop old indices that are no longer used (if they exist from previous schema)
                database.execSQL("DROP INDEX IF EXISTS index_contacts_identityHash_isPinned_customNickname")
                database.execSQL("DROP INDEX IF EXISTS index_contacts_destinationHash")
                // Create new indices
                database.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_identityHash_isPinned ON contacts(identityHash, isPinned)")

                // LocalIdentityEntity indices
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_identities_lastUsedTimestamp ON local_identities(lastUsedTimestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_identities_isActive ON local_identities(isActive)")

                // CustomThemeEntity indices
                database.execSQL("CREATE INDEX IF NOT EXISTS index_custom_themes_createdTimestamp ON custom_themes(createdTimestamp)")
            }
        }

    // Migration from version 20 to 21: Add stamp cost fields to announces table
    private val MIGRATION_20_21 =
        object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add stamp cost columns to announces table (nullable with default null)
                database.execSQL("ALTER TABLE announces ADD COLUMN stampCost INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE announces ADD COLUMN stampCostFlexibility INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE announces ADD COLUMN peeringCost INTEGER DEFAULT NULL")
            }
        }

    // Migration from version 21 to 22: Add isMyRelay field to contacts table for propagation node support
    private val MIGRATION_21_22 =
        object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add isMyRelay column to contacts table (default false)
                // Only one contact can be the user's relay at a time - enforced at application level
                database.execSQL("ALTER TABLE contacts ADD COLUMN isMyRelay INTEGER NOT NULL DEFAULT 0")
            }
        }

    // Migration from version 22 to 23: Add message delivery tracking fields
    // Stores delivery method (opportunistic/direct/propagated) and error message for failed deliveries
    private val MIGRATION_22_23 =
        object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add deliveryMethod column: "opportunistic", "direct", or "propagated"
                database.execSQL("ALTER TABLE messages ADD COLUMN deliveryMethod TEXT DEFAULT NULL")
                // Add errorMessage column for failed delivery details
                database.execSQL("ALTER TABLE messages ADD COLUMN errorMessage TEXT DEFAULT NULL")
            }
        }

    // Migration from version 23 to 24: Add receivingInterfaceType column for interface type icons
    // Stores the type of interface (AUTO_INTERFACE, TCP_CLIENT, ANDROID_BLE, RNODE) for display
    private val MIGRATION_23_24 =
        object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add receivingInterfaceType column (nullable)
                database.execSQL("ALTER TABLE announces ADD COLUMN receivingInterfaceType TEXT")

                // Backfill existing rows based on receivingInterface pattern
                database.execSQL(
                    """
                    UPDATE announces SET receivingInterfaceType =
                        CASE
                            WHEN receivingInterface LIKE 'AutoInterface%' THEN 'AUTO_INTERFACE'
                            WHEN receivingInterface LIKE 'TCPClient%' OR receivingInterface LIKE 'TCPInterface%' THEN 'TCP_CLIENT'
                            WHEN LOWER(receivingInterface) LIKE '%ble%' OR LOWER(receivingInterface) LIKE '%bluetooth%' THEN 'ANDROID_BLE'
                            WHEN LOWER(receivingInterface) LIKE '%rnode%' THEN 'RNODE'
                            ELSE NULL
                        END
                    WHERE receivingInterface IS NOT NULL AND receivingInterface != 'None'
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 24 to 25: Add received_locations table for location sharing
    // Stores location telemetry received from contacts for map display
    private val MIGRATION_24_25 =
        object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create received_locations table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS received_locations (
                        id TEXT NOT NULL PRIMARY KEY,
                        senderHash TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        accuracy REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        expiresAt INTEGER,
                        receivedAt INTEGER NOT NULL,
                        approximateRadius INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_received_locations_senderHash ON received_locations(senderHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_received_locations_senderHash_timestamp ON received_locations(senderHash, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_received_locations_expiresAt ON received_locations(expiresAt)")
            }
        }

    // Migration from version 25 to 26: Add message reply support
    // Adds replyToMessageId column and index for efficient reply lookups
    // Field 16 in LXMF is used as an extensible app extensions dict: {"reply_to": "message_id"}
    private val MIGRATION_25_26 =
        object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add replyToMessageId column (nullable)
                database.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId TEXT DEFAULT NULL")

                // Create index for efficient reply lookups
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_replyToMessageId ON messages(replyToMessageId)",
                )

                // Backfill existing messages from fieldsJson where field 16.reply_to exists
                // SQLite json_extract uses $ for root and . for object properties
                database.execSQL(
                    """
                    UPDATE messages
                    SET replyToMessageId = json_extract(fieldsJson, '$."16".reply_to')
                    WHERE fieldsJson IS NOT NULL
                    AND json_extract(fieldsJson, '$."16".reply_to') IS NOT NULL
                    """.trimIndent(),
                )
            }
        }

    // Migration from version 26 to 27: Add profile icon fields to local_identities and announces
    // Enables users to set custom icons with foreground/background colors for their identity
    // and to receive icon appearance from peers via announces
    private val MIGRATION_26_27 =
        object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add icon columns to local_identities table
                database.execSQL("ALTER TABLE local_identities ADD COLUMN iconName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE local_identities ADD COLUMN iconForegroundColor TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE local_identities ADD COLUMN iconBackgroundColor TEXT DEFAULT NULL")

                // Add icon columns to announces table
                database.execSQL("ALTER TABLE announces ADD COLUMN iconName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE announces ADD COLUMN iconForegroundColor TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE announces ADD COLUMN iconBackgroundColor TEXT DEFAULT NULL")
            }
        }

    // Migration from version 27 to 28: Add propagationTransferLimitKb to announces table
    // Stores the per-message transfer limit (in KB) announced by propagation nodes
    private val MIGRATION_27_28 =
        object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE announces ADD COLUMN propagationTransferLimitKb INTEGER DEFAULT NULL")
            }
        }

    // Migration from version 28 to 29: Add offline maps and RMSP server tables
    // Creates offline_map_regions for storing downloaded map regions with MapLibre OfflineManager support
    // Creates rmsp_servers for tracking discovered RMSP map servers
    private val MIGRATION_28_29 =
        object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create offline_map_regions table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_map_regions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        centerLatitude REAL NOT NULL,
                        centerLongitude REAL NOT NULL,
                        radiusKm INTEGER NOT NULL,
                        minZoom INTEGER NOT NULL,
                        maxZoom INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        mbtilesPath TEXT,
                        tileCount INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        downloadProgress REAL NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        source TEXT NOT NULL DEFAULT 'http',
                        tileVersion TEXT,
                        maplibreRegionId INTEGER
                    )
                    """.trimIndent(),
                )
                // Create indices for offline_map_regions
                database.execSQL("CREATE INDEX IF NOT EXISTS index_offline_map_regions_createdAt ON offline_map_regions(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_offline_map_regions_status ON offline_map_regions(status)")

                // Create rmsp_servers table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rmsp_servers (
                        destinationHash TEXT NOT NULL PRIMARY KEY,
                        serverName TEXT NOT NULL,
                        publicKey BLOB NOT NULL,
                        coverageGeohashes TEXT NOT NULL,
                        minZoom INTEGER NOT NULL,
                        maxZoom INTEGER NOT NULL,
                        formats TEXT NOT NULL,
                        layers TEXT NOT NULL,
                        dataUpdatedTimestamp INTEGER NOT NULL,
                        dataSize INTEGER,
                        version TEXT NOT NULL,
                        lastSeenTimestamp INTEGER NOT NULL,
                        hops INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // Create indices for rmsp_servers
                database.execSQL("CREATE INDEX IF NOT EXISTS index_rmsp_servers_lastSeenTimestamp ON rmsp_servers(lastSeenTimestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_rmsp_servers_hops ON rmsp_servers(hops)")
            }
        }

    // Migration from version 29 to 30: Add received message info fields
    // Stores hop count and receiving interface captured when messages are received
    private val MIGRATION_29_30 =
        object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add receivedHopCount column (nullable INTEGER for hop count at reception)
                database.execSQL("ALTER TABLE messages ADD COLUMN receivedHopCount INTEGER DEFAULT NULL")
                // Add receivedInterface column (nullable TEXT for interface name at reception)
                database.execSQL("ALTER TABLE messages ADD COLUMN receivedInterface TEXT DEFAULT NULL")
            }
        }

    // Migration from version 30 to 31: Add parental control tables
    // Creates guardian_config for storing guardian pairing and lock state
    // Creates allowed_contacts for the allow list when device is locked
    private val MIGRATION_30_31 =
        object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create guardian_config table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS guardian_config (
                        identityHash TEXT NOT NULL PRIMARY KEY,
                        guardianDestinationHash TEXT,
                        guardianPublicKey BLOB,
                        guardianName TEXT,
                        isLocked INTEGER NOT NULL DEFAULT 0,
                        lockedTimestamp INTEGER NOT NULL DEFAULT 0,
                        lastCommandNonce TEXT,
                        lastCommandTimestamp INTEGER NOT NULL DEFAULT 0,
                        pairedTimestamp INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_guardian_config_identityHash ON guardian_config(identityHash)")

                // Create allowed_contacts table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS allowed_contacts (
                        identityHash TEXT NOT NULL,
                        contactHash TEXT NOT NULL,
                        displayName TEXT,
                        addedTimestamp INTEGER NOT NULL,
                        PRIMARY KEY(identityHash, contactHash),
                        FOREIGN KEY(identityHash) REFERENCES local_identities(identityHash) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_allowed_contacts_identityHash ON allowed_contacts(identityHash)")
            }
        }

    @Suppress("SpreadOperator") // Spread is required by Room API; called once at initialization
    @Provides
    @Singleton
    fun provideColumbaDatabase(
        @ApplicationContext context: Context,
    ): ColumbaDatabase {
        return Room.databaseBuilder(
            context,
            ColumbaDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(*ALL_MIGRATIONS)
            .enableMultiInstanceInvalidation()
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
    fun provideReceivedLocationDao(database: ColumbaDatabase): ReceivedLocationDao {
        return database.receivedLocationDao()
    }

    @Provides
    fun provideOfflineMapRegionDao(database: ColumbaDatabase): OfflineMapRegionDao {
        return database.offlineMapRegionDao()
    }

    @Provides
    fun provideRmspServerDao(database: ColumbaDatabase): RmspServerDao {
        return database.rmspServerDao()
    }

    @Provides
    fun provideGuardianConfigDao(database: ColumbaDatabase): GuardianConfigDao {
        return database.guardianConfigDao()
    }

    @Provides
    fun provideAllowedContactDao(database: ColumbaDatabase): AllowedContactDao {
        return database.allowedContactDao()
    }

    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // This IS the DI provider for the IO dispatcher
    fun provideIODispatcher(): CoroutineDispatcher {
        return Dispatchers.IO
    }
}
