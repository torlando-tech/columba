package network.columba.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.entity.LocalIdentityEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration4To5Test {
    private lateinit var database: ColumbaDatabase

    companion object {
        private const val DB_NAME = "call-history-migration-test"
        private const val IDENTITY_HASH = "identity_hash_12345678901234567"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Build at version 4 (no call-history tables)
        database =
            Room.databaseBuilder(context, ColumbaDatabase::class.java, DB_NAME)
                .addMigrations(ColumbaDatabase.MIGRATION_4_5)
                .build()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `migration 4 to 5 preserves pre-existing data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Pre-create a v4 database with an identity row via the v4 schema path
        val pre =
            Room.databaseBuilder(context, ColumbaDatabase::class.java, DB_NAME)
                .addMigrations(ColumbaDatabase.MIGRATION_1_2, ColumbaDatabase.MIGRATION_2_3, ColumbaDatabase.MIGRATION_3_4)
                .build()
        pre.localIdentityDao().insert(
            LocalIdentityEntity(
                identityHash = IDENTITY_HASH,
                displayName = "Test",
                destinationHash = "dest",
                filePath = "/test.key",
                keyData = null,
                createdTimestamp = 1L,
                lastUsedTimestamp = 2L,
                isActive = true,
            ),
        )
        pre.close()

        database =
            Room.databaseBuilder(context, ColumbaDatabase::class.java, DB_NAME)
                .addMigrations(
                    ColumbaDatabase.MIGRATION_1_2,
                    ColumbaDatabase.MIGRATION_2_3,
                    ColumbaDatabase.MIGRATION_3_4,
                    ColumbaDatabase.MIGRATION_4_5,
                )
                .build()

        val identity = database.localIdentityDao().getActiveIdentity().first()
        assertNotNull(identity)
        assertEquals(IDENTITY_HASH, identity?.identityHash)
    }
}
