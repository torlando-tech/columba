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
@Config(sdk = [24], application = Application::class)
class PendingDeliveryStatusDaoTest {
    private companion object {
        const val IDENTITY = "identity-a"
    }

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
        dao.reduce(IDENTITY, "message", "failed", null, 20L)
        dao.reduce(IDENTITY, "message", "retrying_propagated", "propagated", 30L)

        val pending = requireNotNull(dao.get(IDENTITY, "message"))
        assertEquals("failed", pending.status)
        assertEquals(20L, pending.updatedAt)
    }

    @Test
    fun `pending reducer accepts inverse higher authority delivered evidence`() = runTest {
        dao.reduce(IDENTITY, "message", "failed", null, 20L)
        dao.reduce(IDENTITY, "message", "delivered", null, 30L)

        val pending = requireNotNull(dao.get(IDENTITY, "message"))
        assertEquals("delivered", pending.status)
        assertEquals(30L, pending.updatedAt)
    }

    @Test
    fun `delivered proof preserves propagated provenance on api 24`() = runTest {
        dao.reduce(IDENTITY, "message", "retrying_propagated", "propagated", 20L)
        dao.reduce(IDENTITY, "message", "delivered", null, 30L)

        val pending = requireNotNull(dao.get(IDENTITY, "message"))
        assertEquals("delivered", pending.status)
        assertEquals("propagated", pending.deliveryMethod)
        assertEquals(30L, pending.updatedAt)
    }

    @Test
    fun `direct delivered proof does not invent propagated provenance on api 24`() = runTest {
        dao.reduce(IDENTITY, "message", "delivered", null, 30L)

        val pending = requireNotNull(dao.get(IDENTITY, "message"))
        assertEquals("delivered", pending.status)
        assertNull(pending.deliveryMethod)
    }

    @Test
    fun `propagated may transition to failed while preserving provenance`() = runTest {
        dao.reduce(IDENTITY, "message", "propagated", "propagated", 20L)
        dao.reduce(IDENTITY, "message", "failed", null, 30L)

        val pending = requireNotNull(dao.get(IDENTITY, "message"))
        assertEquals("failed", pending.status)
        assertEquals("propagated", pending.deliveryMethod)
    }

    @Test
    fun `delayed primary proof wins after propagated acceptance and failure`() = runTest {
        dao.reduce(IDENTITY, "message", "retrying_propagated", "propagated", 10L)
        dao.reduce(IDENTITY, "message", "propagated", "propagated", 20L)
        dao.reduce(IDENTITY, "message", "failed", null, 30L)
        dao.reduce(IDENTITY, "message", "delivered", null, 40L)

        val pending = requireNotNull(dao.get(IDENTITY, "message"))
        assertEquals("delivered", pending.status)
        assertEquals("propagated", pending.deliveryMethod)
        assertEquals(40L, pending.updatedAt)
    }

    @Test
    fun `cleanup is age and count bounded`() = runTest {
        dao.reduce(IDENTITY, "old", "failed", null, 1L)
        dao.reduce(IDENTITY, "middle", "failed", null, 2L)
        dao.reduce(IDENTITY, "new", "delivered", null, 3L)

        dao.deleteOlderThan(2L)
        dao.trimToNewest(1)

        assertNull(dao.get(IDENTITY, "old"))
        assertNull(dao.get(IDENTITY, "middle"))
        assertEquals("delivered", dao.get(IDENTITY, "new")?.status)
    }

    @Test
    fun `same message hash reduces independently for each identity on api 24`() = runTest {
        dao.reduce("identity-a", "duplicate", "failed", null, 10L)
        dao.reduce("identity-b", "duplicate", "delivered", null, 20L)
        dao.reduce("identity-a", "duplicate", "delivered", null, 30L)

        assertEquals("delivered", dao.get("identity-a", "duplicate")?.status)
        assertEquals(30L, dao.get("identity-a", "duplicate")?.updatedAt)
        assertEquals("delivered", dao.get("identity-b", "duplicate")?.status)
        assertEquals(20L, dao.get("identity-b", "duplicate")?.updatedAt)

        dao.delete("identity-a", "duplicate")
        assertNull(dao.get("identity-a", "duplicate"))
        assertEquals("delivered", dao.get("identity-b", "duplicate")?.status)
    }
}
