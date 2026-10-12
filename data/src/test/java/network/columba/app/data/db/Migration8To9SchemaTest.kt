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
@Config(sdk = [24], application = Application::class)
class Migration8To9SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 9 schema`() {
        helper.createDatabase(DATABASE_NAME, 8).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            9,
            true,
            ColumbaDatabase.MIGRATION_8_9,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "preferred-propagation-nodes-failure-reason-migration"
    }
}
