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
         * Extract the `fields[16].reactions` blob out of a legacy
         * `fieldsJson`, returning `(newFieldsJson, reactionsJson)`.
         * Returns null if there is no reactions blob to extract or if
         * `fieldsJson` is unparseable.
         *
         * Public so [MigrationImporter] (in `:app`) can reuse it when
         * importing pre-v2 backup bundles whose messages still carry
         * the legacy overload.
         */
        /**
         * v2 → v3: normalize `destinationHash` to lowercase across the
         * contacts table (and the join tables that key off it) so that
         * case-variant rows for the same logical peer cannot coexist.
         *
         * Background: the `contacts` PK is the composite
         * (destinationHash, identityHash), stored under SQLite's default
         * BINARY collation. Older releases (≤0.8.15) and any code path
         * that didn't normalize before insert could persist a row with a
         * mixed-case hash. A later lowercase insert of the same logical
         * peer then created a *second* row, and
         * `ContactDao.getEnrichedContacts` returned both — surfacing as
         * an IllegalArgumentException in LazyColumn at /contacts when
         * the keys `${prefix}_${destinationHash.lowercase()}` collided
         * (COLUMBA-3F).
         *
         * Strategy: for each (identityHash, lower(destinationHash))
         * group, pick a "survivor" row (highest lastInteractionTimestamp,
         * tiebreak on isPinned, then ACTIVE status, then non-null
         * publicKey), repoint all FK-ish references in the dependent
         * tables to the lowercase canonical hash, delete the losing
         * rows, then rewrite the survivor's destinationHash to its
         * lowercase form. Finally, lowercase destinationHash in the
         * sibling tables that participate in the enriched join so the
         * case-sensitive ON conditions continue to match.
         *
         * Wrapped in a single transaction so a partial migration cannot
         * leave dangling references.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.beginTransaction()
                    try {
                        // 1) Find all (identityHash, lower(destinationHash)) groups with
                        //    more than one row in the contacts table — these are the
                        //    case-variant duplicates we need to collapse.
                        val dupGroups = mutableListOf<Pair<String, String>>() // (identityHash, lowerHash)
                        db.query(
                            """
                            SELECT identityHash, lower(destinationHash) AS lh, COUNT(*) AS c
                            FROM contacts
                            GROUP BY identityHash, lower(destinationHash)
                            HAVING c > 1
                            """.trimIndent(),
                        ).use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow("identityHash")
                            val lhCol = cursor.getColumnIndexOrThrow("lh")
                            while (cursor.moveToNext()) {
                                dupGroups.add(cursor.getString(idCol) to cursor.getString(lhCol))
                            }
                        }

                        for ((identityHash, lowerHash) in dupGroups) {
                            // 2) Pick the survivor: latest interaction wins; tiebreak on
                            //    isPinned DESC, status='ACTIVE' first, non-null publicKey.
                            var survivorHash: String? = null
                            db.query(
                                """
                                SELECT destinationHash
                                FROM contacts
                                WHERE identityHash = ?
                                  AND lower(destinationHash) = ?
                                ORDER BY lastInteractionTimestamp DESC,
                                         isPinned DESC,
                                         CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                                         CASE WHEN publicKey IS NOT NULL THEN 0 ELSE 1 END,
                                         addedTimestamp DESC
                                LIMIT 1
                                """.trimIndent(),
                                arrayOf<Any?>(identityHash, lowerHash),
                            ).use { c ->
                                if (c.moveToFirst()) survivorHash = c.getString(0)
                            }
                            val keep = survivorHash ?: continue

                            // 3) Delete the loser rows (everything for this group except the survivor).
                            db.execSQL(
                                """
                                DELETE FROM contacts
                                WHERE identityHash = ?
                                  AND lower(destinationHash) = ?
                                  AND destinationHash <> ?
                                """.trimIndent(),
                                arrayOf<Any?>(identityHash, lowerHash, keep),
                            )

                            // 4) Rewrite the survivor's destinationHash to its lowercase form
                            //    (only if it isn't already lowercase).
                            if (keep != lowerHash) {
                                db.execSQL(
                                    """
                                    UPDATE contacts
                                    SET destinationHash = ?
                                    WHERE identityHash = ? AND destinationHash = ?
                                    """.trimIndent(),
                                    arrayOf<Any?>(lowerHash, identityHash, keep),
                                )
                            }
                        }

                        // 5) Lowercase any remaining mixed-case rows in contacts that
                        //    weren't part of a duplicate group (safe: no PK collision
                        //    because we already collapsed those).
                        db.execSQL(
                            """
                            UPDATE contacts
                            SET destinationHash = lower(destinationHash)
                            WHERE destinationHash <> lower(destinationHash)
                            """.trimIndent(),
                        )

                        // 6) Normalize join-side tables so the case-sensitive ON
                        //    conditions in ContactDao.getEnrichedContacts continue
                        //    to match. These tables key on destinationHash / peerHash /
                        //    senderHash; we lowercase in-place. For tables with a
                        //    composite PK (conversations) duplicates are theoretically
                        //    possible — guard with INSERT OR IGNORE-style merge by
                        //    deleting losers first when collisions exist.
                        normalizeSimpleHashColumn(db, table = "announces", column = "destinationHash")
                        normalizeSimpleHashColumn(db, table = "peer_icons", column = "destinationHash")
                        normalizeSimpleHashColumn(db, table = "received_locations", column = "senderHash")
                        normalizeCompositeHashColumn(
                            db,
                            table = "conversations",
                            hashColumn = "peerHash",
                            otherKeyColumn = "identityHash",
                        )
                        normalizeCompositeHashColumn(
                            db,
                            table = "blocked_peers",
                            hashColumn = "peerHash",
                            otherKeyColumn = "identityHash",
                        )

                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }

        /**
         * Lowercase [column] in [table] in-place. Assumes [column] is the
         * sole identity for the row (or part of a unique constraint that
         * tolerates lower(x) without collisions). Mixed-case-only rows
         * are rewritten; rows already lowercase are left alone.
         */
        private fun normalizeSimpleHashColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
        ) {
            db.execSQL(
                "UPDATE $table SET $column = lower($column) WHERE $column <> lower($column)",
            )
        }

        /**
         * Lowercase [hashColumn] in a table whose uniqueness depends on
         * (hashColumn, otherKeyColumn). Before rewriting, drop any row
         * whose lowercase form would collide with an already-lowercase
         * row for the same [otherKeyColumn] (best-effort: prefer the
         * lowercase row, which is the one the rest of the app writes).
         */
        private fun normalizeCompositeHashColumn(
            db: SupportSQLiteDatabase,
            table: String,
            hashColumn: String,
            otherKeyColumn: String,
        ) {
            db.execSQL(
                """
                DELETE FROM $table
                WHERE $hashColumn <> lower($hashColumn)
                  AND EXISTS (
                    SELECT 1 FROM $table t2
                    WHERE t2.$otherKeyColumn = $table.$otherKeyColumn
                      AND t2.$hashColumn = lower($table.$hashColumn)
                  )
                """.trimIndent(),
            )
            db.execSQL(
                "UPDATE $table SET $hashColumn = lower($hashColumn) WHERE $hashColumn <> lower($hashColumn)",
            )
        }

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
