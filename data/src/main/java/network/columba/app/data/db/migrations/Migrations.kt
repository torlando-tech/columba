package network.columba.app.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations for [network.columba.app.data.db.ColumbaDatabase].
 *
 * Migrations are wired into the database builder via `.addMigrations(...)`.
 * The default `fallbackToDestructiveMigration()` builder option used elsewhere
 * is a destructive fallback — Room only invokes it when no Migration is
 * provided for a version step. By providing explicit Migration objects here
 * for each version bump, real user data is preserved across upgrades.
 *
 * # Schema versioning convention
 *
 *   v1 = baseline (pre-2026-04-30)
 *   v2 = adds messages.signatureVerified column
 *
 * When bumping the version, ALWAYS:
 *   1. Add the new Migration object below.
 *   2. Add it to [ALL_MIGRATIONS] so all three database providers
 *      (DatabaseModule, ServiceDatabaseProvider, InterfaceDatabaseModule)
 *      pick it up automatically — there is one source of truth here, not
 *      three drifting copies.
 *   3. Bump the `version = N` value in the @Database annotation in
 *      ColumbaDatabase.kt to match.
 *   4. Add an entry to the version-history comment above.
 */

/**
 * v1 → v2: add `messages.signatureVerified INTEGER` column.
 *
 * Nullable Boolean column for received messages: true when the LXMF
 * signature was verified against a known sender identity, false when
 * the sender's identity was unknown at receive time (`SOURCE_UNKNOWN`
 * — potential forgery), null for legacy rows that predate this column
 * and for sent messages (signing is local — implicitly authentic).
 *
 * Backfilling NULL on legacy rows is the deliberate policy: the UI
 * treats NULL as "no warning" to preserve the historical display for
 * rows that existed before the column was added. Showing every legacy
 * message as "unverified" would be alarming and inaccurate (most are
 * from peers we'd already have on file).
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN signatureVerified INTEGER")
        }
    }

/**
 * Ordered list of every migration. Wired into every database builder
 * via `.addMigrations(*ALL_MIGRATIONS)`.
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_2,
    )
