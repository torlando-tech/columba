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
 * TOR-25 acceptance: the real exported v11 fixture upgrades to a schema that
 * validates against the committed `schemas/.../12.json`, and the full chain
 * from the v2 fixture reaches the same valid v12 shape. The drafts migration
 * was renumbered v5 → v6 to v11 → v12 when the P1/P2 branch rebased onto
 * main (main's own v5-v10 landed independently).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration11To12SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `real v2 fixture upgrades through the full chain to a valid v12 schema`() {
        helper.createDatabase(DATABASE_NAME, 2).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            12,
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
            ColumbaDatabase.MIGRATION_11_12,
        ).close()
    }

    @Test
    fun `v11 fixture upgrades to a valid v12 schema`() {
        helper.createDatabase(DATABASE_NAME_V11, 11).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME_V11,
            12,
            true,
            ColumbaDatabase.MIGRATION_11_12,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "threads-v2-to-v12-room-schema-migration"
        const val DATABASE_NAME_V11 = "threads-v11-to-v12-room-schema-migration"
    }
}
