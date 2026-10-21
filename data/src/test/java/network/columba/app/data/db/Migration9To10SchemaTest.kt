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
class Migration9To10SchemaTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ColumbaDatabase::class.java,
        )

    @Test
    fun `migration output matches exported Room version 10 schema`() {
        helper.createDatabase(DATABASE_NAME, 9).close()
        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            ColumbaDatabase.MIGRATION_9_10,
        ).close()
    }

    private companion object {
        const val DATABASE_NAME = "messages-signature-verified-migration"
    }
}
