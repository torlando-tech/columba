package network.columba.app.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingDeliveryStatusDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var dao: PendingDeliveryStatusDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java).build()
        dao = database.pendingDeliveryStatusDao()
    }

    @After
    fun teardown() = database.close()

    @Test
    fun `pending reducer rejects stale retry metadata as one decision`() = runTest {
        dao.reduce("message", "failed", 20L)
        dao.reduce("message", "retrying_propagated", 30L)

        val pending = requireNotNull(dao.get("message"))
        assertEquals("failed", pending.status)
        assertEquals(20L, pending.updatedAt)
    }

    @Test
    fun `pending reducer accepts inverse higher authority delivered evidence`() = runTest {
        dao.reduce("message", "failed", 20L)
        dao.reduce("message", "delivered", 30L)

        val pending = requireNotNull(dao.get("message"))
        assertEquals("delivered", pending.status)
        assertEquals(30L, pending.updatedAt)
    }

    @Test
    fun `cleanup is age and count bounded`() = runTest {
        dao.reduce("old", "failed", 1L)
        dao.reduce("middle", "failed", 2L)
        dao.reduce("new", "delivered", 3L)

        dao.deleteOlderThan(2L)
        dao.trimToNewest(1)

        assertNull(dao.get("old"))
        assertNull(dao.get("middle"))
        assertEquals("delivered", dao.get("new")?.status)
    }
}
