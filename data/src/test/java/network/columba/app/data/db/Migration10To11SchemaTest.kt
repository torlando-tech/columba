package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TOR-24 acceptance: "migration test on a real fixture DB".
 *
 * `MigrationTestHelper.createDatabase(name, 2)` materializes the real version
 * 2 database from the committed `schemas/.../2.json` Room fixture — not a
 * hand-rolled approximation — and the chained run upgrades it all the way to
 * v11, validating every table/column/index against the exported v11 schema.
 * The threads migration was renumbered v4 → v5 to v10 → v11 when the P1/P2
 * branch rebased onto main (main's own v5-v10 landed independently).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration10To11SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `real v2 fixture upgrades through the full chain to a valid v11 schema`() {
        helper.createDatabase(DATABASE_NAME, 2).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            11,
            true,
            ColumbaDatabase.MIGRATION_2_3,
            ColumbaDatabase.MIGRATION_3_4,
            ColumbaDatabase.MIGRATION_4_5,
            ColumbaDatabase.MIGRATION_5_6,
            ColumbaDatabase.MIGRATION_6_7,
            ColumbaDatabase.MIGRATION_7_8,
            ColumbaDatabase.MIGRATION_8_9,
            ColumbaDatabase.MIGRATION_9_10,
            ColumbaDatabase.MIGRATION_10_11,
        ).close()
    }

    @Test
    fun `v10 fixture upgrades to a valid v11 schema`() {
        helper.createDatabase(DATABASE_NAME_V10, 10).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME_V10,
            11,
            true,
            ColumbaDatabase.MIGRATION_10_11,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "threads-v2-to-v11-room-schema-migration"
        const val DATABASE_NAME_V10 = "threads-v10-to-v11-room-schema-migration"
    }
}
