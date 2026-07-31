package network.columba.app.data.db

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration2To3SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 3 schema`() {
        helper.createDatabase(DATABASE_NAME, 2).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            ColumbaDatabase.MIGRATION_2_3,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "peer-activity-room-schema-migration"
    }
}
