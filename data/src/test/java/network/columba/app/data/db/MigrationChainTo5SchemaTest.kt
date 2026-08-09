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
 * Validates the full released migration chain (2 -> 3 -> 4 -> 5) in one pass.
 *
 * Each individual hop is covered by its own [Migration2To3SchemaTest],
 * [Migration3To4SchemaTest], and [Migration4To5SchemaTest]; this test proves the
 * hops compose and that running all of them together lands exactly on the exported
 * Room version 5 schema. Version 2 is the earliest version with an exported schema
 * (version 1 predates schema export and is covered by [Migration1To2Test]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationChainTo5SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `full chain 2 to 5 matches exported Room version 5 schema`() {
        helper.createDatabase(DATABASE_NAME, 2).close()
        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                5,
                true,
                ColumbaDatabase.MIGRATION_2_3,
                ColumbaDatabase.MIGRATION_3_4,
                ColumbaDatabase.MIGRATION_4_5,
            )
            .close()
    }

    private companion object {
        const val DATABASE_NAME = "call-history-full-chain-schema-migration"
    }
}
