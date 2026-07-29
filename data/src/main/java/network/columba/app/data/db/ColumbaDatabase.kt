package network.columba.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import network.columba.app.data.db.dao.AnnounceDao
import network.columba.app.data.db.dao.BlockedPeerDao
import network.columba.app.data.db.dao.ContactDao
import network.columba.app.data.db.dao.ConversationDao
import network.columba.app.data.db.dao.CustomThemeDao
import network.columba.app.data.db.dao.DraftDao
import network.columba.app.data.db.dao.InterfaceFirstSeenDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.OfflineMapRegionDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.dao.PeerIdentityDao
import network.columba.app.data.db.dao.ReceivedLocationDao
import network.columba.app.data.db.dao.RmspServerDao
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.CustomThemeEntity
import network.columba.app.data.db.entity.DraftEntity
import network.columba.app.data.db.entity.InterfaceFirstSeenEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.OfflineMapRegionEntity
import network.columba.app.data.db.entity.PeerIconEntity
import network.columba.app.data.db.entity.PeerIdentityEntity
import network.columba.app.data.db.entity.ReceivedLocationEntity
import network.columba.app.data.db.entity.RmspServerEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AnnounceEntity::class,
        PeerIdentityEntity::class,
        PeerIconEntity::class,
        ContactEntity::class,
        CustomThemeEntity::class,
        LocalIdentityEntity::class,
        ReceivedLocationEntity::class,
        OfflineMapRegionEntity::class,
        RmspServerEntity::class,
        DraftEntity::class,
        BlockedPeerEntity::class,
        InterfaceFirstSeenEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ColumbaDatabase : RoomDatabase() {
    companion object {
        /**
         * v1 → v2: split reactions out of `fieldsJson` overload into a
         * dedicated `reactionsJson` column.
         *
         * Prior shape (in `fieldsJson`):
         *   `{"16": {"reactions": {"👍": [sender_hex, ...]}, "reply_to": "..."}}`
         *
         * New shape:
         *   `reactionsJson = {"👍": [sender_hex, ...]}` (flat — no wrapper)
         *   `fieldsJson`   = same as before but with the `reactions` key
         *                    stripped out of `fields[16]` (and field 16
         *                    removed entirely if it becomes empty).
         *
         * Backfill happens row-by-row in Kotlin (json1's `json_remove` would
         * also work, but the parsed-rebuild path here keeps both the strip
         * and the copy in a single deterministic place + survives
         * malformed fieldsJson without aborting the migration).
         *
         * Migration is best-effort: malformed/unparseable `fieldsJson`
         * rows keep their original blob and just get a null
         * `reactionsJson`. UI parse path tolerates either being null.
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT")

                    db.query(
                        "SELECT id, identityHash, fieldsJson FROM messages " +
                            "WHERE fieldsJson IS NOT NULL AND fieldsJson LIKE '%reactions%'",
                    ).use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow("id")
                        val identityCol = cursor.getColumnIndexOrThrow("identityHash")
                        val fieldsCol = cursor.getColumnIndexOrThrow("fieldsJson")
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(idCol)
                            val identityHash = cursor.getString(identityCol)
                            val fieldsJson = cursor.getString(fieldsCol) ?: continue

                            val (newFieldsJson, reactionsJson) =
                                splitReactionsOutOfFieldsJson(fieldsJson) ?: continue

                            db.execSQL(
                                "UPDATE messages SET fieldsJson = ?, reactionsJson = ? " +
                                    "WHERE id = ? AND identityHash = ?",
                                arrayOf<Any?>(newFieldsJson, reactionsJson, id, identityHash),
                            )
                        }
                    }
                }
            }

        /**
         * v2 → v3: add nullable `messages.signatureVerified INTEGER` column.
         *
         * Surfaces LXMF signature-verification state per received message so
         * the UI can warn on unverified senders. Pure additive `ALTER` — no
         * data transform. Existing rows backfill to
         * NULL, which the UI treats as "no warning": showing every legacy
         * message as unverified would be inaccurate (most are from peers we
         * already had on file) and alarming.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN signatureVerified INTEGER")
                }
            }

        /**
         * Extract the `fields[16].reactions` blob out of a legacy
         * `fieldsJson`, returning `(newFieldsJson, reactionsJson)`.
         * Returns null if there is no reactions blob to extract or if
         * `fieldsJson` is unparseable.
         *
         * Public so [MigrationImporter] (in `:app`) can reuse it when
         * importing pre-v2 backup bundles whose messages still carry
         * the legacy overload.
         */
        @Suppress("ReturnCount")
        fun splitReactionsOutOfFieldsJson(fieldsJson: String): Pair<String, String>? =
            try {
                val root = JSONObject(fieldsJson)
                val field16 = root.optJSONObject("16") ?: return null
                val reactions = field16.optJSONObject("reactions") ?: return null
                val reactionsBlob = reactions.toString()

                field16.remove("reactions")
                if (field16.length() == 0) {
                    root.remove("16")
                }
                root.toString() to reactionsBlob
            } catch (_: Exception) {
                null
            }
    }

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun announceDao(): AnnounceDao

    abstract fun peerIdentityDao(): PeerIdentityDao

    abstract fun peerIconDao(): PeerIconDao

    abstract fun contactDao(): ContactDao

    abstract fun customThemeDao(): CustomThemeDao

    abstract fun localIdentityDao(): LocalIdentityDao

    abstract fun receivedLocationDao(): ReceivedLocationDao

    abstract fun offlineMapRegionDao(): OfflineMapRegionDao

    abstract fun rmspServerDao(): RmspServerDao

    abstract fun draftDao(): DraftDao

    abstract fun blockedPeerDao(): BlockedPeerDao

    abstract fun interfaceFirstSeenDao(): InterfaceFirstSeenDao
}
