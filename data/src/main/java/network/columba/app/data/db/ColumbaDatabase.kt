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
import network.columba.app.data.db.dao.PeerActivityDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.dao.PeerIdentityDao
import network.columba.app.data.db.dao.PendingDeliveryStatusDao
import network.columba.app.data.db.dao.ReceivedLocationDao
import network.columba.app.data.db.dao.RmspServerDao
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.dao.CallHistoryDeletionDao
import network.columba.app.data.db.entity.CallHistoryDeletionEntity
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.AnnounceInterfaceSightingEntity
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.CustomThemeEntity
import network.columba.app.data.db.entity.DraftEntity
import network.columba.app.data.db.entity.InterfaceFirstSeenEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.OfflineMapRegionEntity
import network.columba.app.data.db.entity.PeerActivityEntity
import network.columba.app.data.db.entity.PeerActivityEventEntity
import network.columba.app.data.db.entity.PeerIconEntity
import network.columba.app.data.db.entity.PeerIdentityEntity
import network.columba.app.data.db.entity.PendingDeliveryStatusEntity
import network.columba.app.data.db.entity.ReceivedLocationEntity
import network.columba.app.data.db.entity.RmspServerEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AnnounceEntity::class,
        AnnounceInterfaceSightingEntity::class,
        PeerIdentityEntity::class,
        PeerIconEntity::class,
        ContactEntity::class,
        CustomThemeEntity::class,
        LocalIdentityEntity::class,
        ReceivedLocationEntity::class,
        OfflineMapRegionEntity::class,
        RmspServerEntity::class,
        CallHistoryEntity::class,
        CallHistoryDeletionEntity::class,
        DraftEntity::class,
        BlockedPeerEntity::class,
        InterfaceFirstSeenEntity::class,
        PeerActivityEntity::class,
        PeerActivityEventEntity::class,
        PendingDeliveryStatusEntity::class,
    ],
    version = 7,
    exportSchema = true,
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
         * v2 → v3: add the durable source of truth for verified inbound
         * peer activity. Only local reception timestamps are backfilled.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `peer_activity` (" +
                            "`destinationHash` TEXT NOT NULL, " +
                            "`lastReceivedAt` INTEGER NOT NULL, " +
                            "`activityType` TEXT NOT NULL, " +
                            "PRIMARY KEY(`destinationHash`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `peer_activity_events` (" +
                            "`eventId` TEXT NOT NULL, PRIMARY KEY(`eventId`))",
                    )
                    // Existing messages/proofs must be marked as already admitted so
                    // backend replay after upgrade cannot make them look newly received.
                    db.execSQL(
                        "INSERT OR IGNORE INTO peer_activity_events(eventId) " +
                            "SELECT 'message:' || LOWER(id) FROM messages WHERE isFromMe = 0",
                    )
                    db.execSQL(
                        "INSERT OR IGNORE INTO peer_activity_events(eventId) " +
                            "SELECT 'proof:' || LOWER(id) FROM messages " +
                            "WHERE isFromMe = 1 AND LOWER(status) = 'delivered'",
                    )
                    val maxTrustedReceivedAt = System.currentTimeMillis() + 5 * 60 * 1000L
                    backfillPeerActivity(
                        db,
                        "SELECT LOWER(destinationHash) AS hash, MAX(lastSeenTimestamp) AS receivedAt " +
                            "FROM announces WHERE lastSeenTimestamp > 0 " +
                            "AND lastSeenTimestamp <= $maxTrustedReceivedAt " +
                            "GROUP BY LOWER(destinationHash)",
                        "ANNOUNCE",
                    )
                    backfillPeerActivity(
                        db,
                        "SELECT LOWER(conversationHash) AS hash, MAX(receivedAt) AS receivedAt " +
                            "FROM messages WHERE isFromMe = 0 AND receivedAt IS NOT NULL " +
                            "AND receivedAt > 0 AND receivedAt <= $maxTrustedReceivedAt " +
                            "GROUP BY LOWER(conversationHash)",
                        "MESSAGE",
                    )
                }
            }

        /**
         * v3 → v4: retain recent accepted-path history by interface type.
         * Existing announces are backfilled with their current interface so
         * stable filtering works immediately after upgrade.
         */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `announce_interface_sightings` (
                            `destinationHash` TEXT NOT NULL,
                            `interfaceType` TEXT NOT NULL,
                            `receivingInterface` TEXT,
                            `lastSeenTimestamp` INTEGER NOT NULL,
                            `hops` INTEGER NOT NULL,
                            PRIMARY KEY(`destinationHash`, `interfaceType`),
                            FOREIGN KEY(`destinationHash`) REFERENCES `announces`(`destinationHash`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_announce_interface_sightings_destinationHash` " +
                            "ON `announce_interface_sightings` (`destinationHash`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_announce_interface_sightings_interfaceType_lastSeenTimestamp` " +
                            "ON `announce_interface_sightings` (`interfaceType`, `lastSeenTimestamp`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_announce_interface_sightings_lastSeenTimestamp` " +
                            "ON `announce_interface_sightings` (`lastSeenTimestamp`)",
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO announce_interface_sightings
                            (destinationHash, interfaceType, receivingInterface, lastSeenTimestamp, hops)
                        SELECT
                            destinationHash,
                            CASE
                                WHEN receivingInterfaceType IN ('AUTO', 'AUTO_INTERFACE') THEN 'AUTO'
                                WHEN receivingInterfaceType = 'TCP_CLIENT' THEN 'TCP_CLIENT'
                                WHEN receivingInterfaceType = 'TCP_SERVER' THEN 'TCP_SERVER'
                                WHEN receivingInterfaceType IN ('BLE', 'ANDROID_BLE') THEN 'BLE'
                                WHEN receivingInterfaceType = 'RNODE' THEN 'RNODE'
                                WHEN receivingInterfaceType = 'SHARED_INSTANCE' THEN 'SHARED_INSTANCE'
                                WHEN lower(receivingInterface) LIKE '%autointerface%' OR
                                     lower(receivingInterface) LIKE '%auto discovery%' THEN 'AUTO'
                                WHEN lower(receivingInterface) LIKE '%rnode%' OR
                                     lower(receivingInterface) LIKE '%kiss%' OR
                                     lower(receivingInterface) LIKE '%lora%' OR
                                     lower(receivingInterface) LIKE '%weave%' THEN 'RNODE'
                                WHEN lower(receivingInterface) LIKE '%tcpserver%' THEN 'TCP_SERVER'
                                WHEN lower(receivingInterface) LIKE '%tcpclient%' OR
                                     lower(receivingInterface) LIKE '%tcpinterface%' OR
                                     lower(receivingInterface) LIKE '%backbone%' THEN 'TCP_CLIENT'
                                WHEN lower(receivingInterface) LIKE '%androidble%' OR
                                     lower(receivingInterface) LIKE '%ble%' OR
                                     lower(receivingInterface) LIKE '%bluetooth%' THEN 'BLE'
                                WHEN lower(receivingInterface) LIKE '%shared instance%' THEN 'SHARED_INSTANCE'
                                ELSE 'UNKNOWN'
                            END,
                            receivingInterface,
                            lastSeenTimestamp,
                            hops
                        FROM announces
                        """.trimIndent(),
                    )
                    // Persist the same canonical value on the parent so current-path
                    // fallback remains filterable after the 30-day sighting expires.
                    db.execSQL(
                        "UPDATE announces SET receivingInterfaceType = (" +
                            "SELECT interfaceType FROM announce_interface_sightings sighting " +
                            "WHERE sighting.destinationHash = announces.destinationHash)",
                    )
                }
            }

        private fun backfillPeerActivity(
            db: SupportSQLiteDatabase,
            sourceQuery: String,
            activityType: String,
        ) {
            db.execSQL(
                "INSERT OR REPLACE INTO peer_activity(destinationHash, lastReceivedAt, activityType) " +
                    "SELECT source.hash, source.receivedAt, '$activityType' FROM ($sourceQuery) source " +
                    "LEFT JOIN peer_activity existing ON existing.destinationHash = source.hash " +
                    "WHERE existing.lastReceivedAt IS NULL OR source.receivedAt > existing.lastReceivedAt",
            )
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

        /** Add the reduced call-history tables. */
        val MIGRATION_4_5: Migration =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `call_history` (
                            `callAttemptId` TEXT NOT NULL,
                            `localIdentityHash` TEXT NOT NULL,
                            `remoteIdentityHash` TEXT NOT NULL,
                            `direction` TEXT NOT NULL,
                            `peerDisplayNameSnapshot` TEXT,
                            `codecProfileCode` INTEGER,
                            `attemptedAt` INTEGER NOT NULL,
                            `ringingAt` INTEGER,
                            `connectedAt` INTEGER,
                            `endedAt` INTEGER,
                            `outcome` TEXT,
                            `inferredEnding` INTEGER NOT NULL,
                            `failureReason` TEXT,
                            `serviceInstanceId` TEXT NOT NULL,
                            PRIMARY KEY(`callAttemptId`),
                            FOREIGN KEY(`localIdentityHash`) REFERENCES `local_identities`(`identityHash`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """,
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `call_history_deletions` (" +
                            "`callAttemptId` TEXT NOT NULL, " +
                            "`localIdentityHash` TEXT NOT NULL, " +
                            "`deletedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`callAttemptId`), " +
                            "FOREIGN KEY(`localIdentityHash`) REFERENCES `local_identities`(`identityHash`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_call_history_localIdentityHash_attemptedAt` " +
                            "ON `call_history` (`localIdentityHash`, `attemptedAt`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_call_history_remoteIdentityHash` ON `call_history` (`remoteIdentityHash`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_call_history_deletions_localIdentityHash` ON `call_history_deletions` (`localIdentityHash`)",
                    )
                }
            }


        /** Add identity-scoped blocking aspect to blocked_peers. */
        val MIGRATION_5_6: Migration =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE blocked_peers ADD COLUMN routingAspect TEXT")
                }
            }

        /** Add the service-owned durable inbox for pre-row delivery lifecycle events. */
        val MIGRATION_6_7: Migration =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `pending_delivery_status` (" +
                            "`messageHash` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`messageHash`))",
                    )
                }
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

    abstract fun peerActivityDao(): PeerActivityDao

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

    abstract fun callHistoryDao(): CallHistoryDao

    abstract fun callHistoryDeletionDao(): CallHistoryDeletionDao

    abstract fun pendingDeliveryStatusDao(): PendingDeliveryStatusDao
}
