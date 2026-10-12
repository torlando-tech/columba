package network.columba.app.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.PreferredPropagationNodeEntity
import network.columba.app.data.repository.PreferredPropagationNodeRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferredPropagationNodeDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var dao: PreferredPropagationNodeDao
    private lateinit var repository: PreferredPropagationNodeRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.preferredPropagationNodeDao()
        repository = PreferredPropagationNodeRepository(dao)
    }

    @After
    fun teardown() = database.close()

    @Test
    fun `add appends in priority order and is idempotent on hash`() = runTest {
        repository.add("aaa", "ANY")
        repository.add("bbb", "WIFI_ONLY")
        repository.add("ccc", "CELLULAR_ONLY")

        val ordered = dao.observeOrdered().first()
        assertEquals(listOf("aaa", "bbb", "ccc"), ordered.map { it.destinationHash })
        assertEquals(listOf(0, 1, 2), ordered.map { it.priority })

        // Re-adding an existing hash is a no-op (no duplicate, priority preserved).
        repository.add("bbb", "ANY", displayName = "ignored")
        val after = dao.observeOrdered().first()
        assertEquals(3, after.size)
        assertEquals("WIFI_ONLY", after.first { it.destinationHash == "bbb" }.networkRestriction)
    }

    @Test
    fun `reorder persists the new priority order`() = runTest {
        repository.add("aaa", "ANY")
        repository.add("bbb", "ANY")
        repository.add("ccc", "ANY")

        repository.reorder(listOf("ccc", "aaa", "bbb"))

        val ordered = dao.observeOrdered().first()
        assertEquals(listOf("ccc", "aaa", "bbb"), ordered.map { it.destinationHash })
        assertEquals(listOf(0, 1, 2), ordered.map { it.priority })
    }

    @Test
    fun `remove deletes and re-packs priorities with no gap`() = runTest {
        repository.add("aaa", "ANY")
        repository.add("bbb", "ANY")
        repository.add("ccc", "ANY")

        repository.remove("bbb")

        val ordered = dao.observeOrdered().first()
        assertEquals(listOf("aaa", "ccc"), ordered.map { it.destinationHash })
        assertEquals(listOf(0, 1), ordered.map { it.priority })
        assertNull(dao.findByHash("bbb"))
    }

    @Test
    fun `setRestriction updates the stored restriction string`() = runTest {
        repository.add("aaa", "ANY")
        repository.setRestriction("aaa", "WIFI_ONLY")
        assertEquals("WIFI_ONLY", dao.findByHash("aaa")?.networkRestriction)
    }

    @Test
    fun `setEnabled toggles and observeEnabled filters disabled rows`() = runTest {
        repository.add("aaa", "ANY")
        repository.add("bbb", "ANY")
        repository.setEnabled("aaa", false)

        val enabled = dao.observeEnabled().first()
        assertEquals(listOf("bbb"), enabled.map { it.destinationHash })

        // observeOrdered still sees the disabled row.
        val all = dao.observeOrdered().first()
        assertEquals(listOf("aaa", "bbb"), all.map { it.destinationHash })
        assertFalse(all.first { it.destinationHash == "aaa" }.enabled)
    }

    @Test
    fun `findByHash and contains reflect membership`() = runTest {
        repository.add("aaa", "ANY")
        assertTrue(dao.contains("aaa"))
        assertFalse(dao.contains("zzz"))
        assertEquals("aaa", dao.findByHash("aaa")?.destinationHash)
        assertNull(dao.findByHash("zzz"))
    }

    @Test
    fun `recordFailure stores code, explanation and timestamp`() = runTest {
        repository.add("aaa", "ANY")
        repository.recordFailure("aaa", code = 0xf0, explanation = "No path to relay", timestamp = 1234L)

        val node = dao.findByHash("aaa")!!
        assertEquals(0xf0, node.lastFailureCode)
        assertEquals("No path to relay", node.lastFailureExplanation)
        assertEquals(1234L, node.lastFailureAt)
    }

    @Test
    fun `recordFailure is a no-op for a hash not in the list`() = runTest {
        // No rows added — recording on an absent hash must not create a row.
        repository.recordFailure("ghost", code = 8, explanation = "Relay unreachable", timestamp = 1L)
        assertEquals(0, dao.count())
        assertNull(dao.findByHash("ghost"))
    }

    @Test
    fun `recordFailure overwrites the prior record (latest wins)`() = runTest {
        repository.add("aaa", "ANY")
        repository.recordFailure("aaa", code = 8, explanation = "Relay unreachable", timestamp = 100L)
        repository.recordFailure("aaa", code = 0xf0, explanation = "No path to relay", timestamp = 200L)

        val node = dao.findByHash("aaa")!!
        assertEquals(0xf0, node.lastFailureCode)
        assertEquals("No path to relay", node.lastFailureExplanation)
        assertEquals(200L, node.lastFailureAt)
    }

    @Test
    fun `clearFailure nulls all three columns`() = runTest {
        repository.add("aaa", "ANY")
        repository.recordFailure("aaa", code = 9, explanation = "No path to relay", timestamp = 5L)
        repository.clearFailure("aaa")

        val node = dao.findByHash("aaa")!!
        assertNull(node.lastFailureCode)
        assertNull(node.lastFailureExplanation)
        assertNull(node.lastFailureAt)
    }

    @Test
    fun `isCurrentlyUnreachable is true inside the cooldown window only`() = runTest {
        repository.add("aaa", "ANY")
        val base = dao.findByHash("aaa")!!

        // Never failed → not unreachable.
        assertFalse(repository.isCurrentlyUnreachable(base, nowMillis = 1000L, cooldownMillis = 600_000L))

        repository.recordFailure("aaa", code = 9, explanation = "No path to relay", timestamp = 1000L)
        val failed = dao.findByHash("aaa")!!

        // Inside the window → unreachable.
        assertTrue(repository.isCurrentlyUnreachable(failed, nowMillis = 1000L + 300_000L, cooldownMillis = 600_000L))
        // Exactly at the window edge is still excluded.
        assertTrue(repository.isCurrentlyUnreachable(failed, nowMillis = 1000L + 600_000L, cooldownMillis = 600_000L))
        // Past the window → recovered (re-eligible).
        assertFalse(repository.isCurrentlyUnreachable(failed, nowMillis = 1000L + 600_001L, cooldownMillis = 600_000L))
    }
}
