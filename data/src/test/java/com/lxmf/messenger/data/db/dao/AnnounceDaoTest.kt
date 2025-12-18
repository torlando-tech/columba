package com.lxmf.messenger.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for AnnounceDao, focusing on the getTopPropagationNodes() query
 * which is used for the relay selection modal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnnounceDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var dao: AnnounceDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.announceDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestAnnounce(
        destinationHash: String = "dest_${System.nanoTime()}",
        peerName: String = "Test Peer",
        nodeType: String = "PEER",
        hops: Int = 1,
        lastSeenTimestamp: Long = System.currentTimeMillis(),
    ) = AnnounceEntity(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = ByteArray(32) { it.toByte() },
        appData = null,
        hops = hops,
        lastSeenTimestamp = lastSeenTimestamp,
        nodeType = nodeType,
        receivingInterface = null,
        aspect = when (nodeType) {
            "PROPAGATION_NODE" -> "lxmf.propagation"
            "PEER" -> "lxmf.delivery"
            else -> null
        },
        isFavorite = false,
        favoritedTimestamp = null,
        stampCost = null,
        stampCostFlexibility = null,
        peeringCost = null,
    )

    // ========== getTopPropagationNodes Tests ==========

    @Test
    fun getTopPropagationNodes_returnsEmptyWhenNoAnnounces() = runTest {
        // When/Then
        dao.getTopPropagationNodes(10).test {
            val nodes = awaitItem()
            assertTrue(nodes.isEmpty())
        }
    }

    @Test
    fun getTopPropagationNodes_returnsOnlyPropagationNodeType() = runTest {
        // Given - mix of node types
        dao.upsertAnnounce(createTestAnnounce(destinationHash = "peer1", nodeType = "PEER", hops = 1))
        dao.upsertAnnounce(createTestAnnounce(destinationHash = "node1", nodeType = "NODE", hops = 1))
        dao.upsertAnnounce(createTestAnnounce(destinationHash = "prop1", nodeType = "PROPAGATION_NODE", hops = 1))
        dao.upsertAnnounce(createTestAnnounce(destinationHash = "prop2", nodeType = "PROPAGATION_NODE", hops = 2))
        dao.upsertAnnounce(createTestAnnounce(destinationHash = "peer2", nodeType = "PEER", hops = 3))

        // When/Then
        dao.getTopPropagationNodes(10).test {
            val nodes = awaitItem()
            assertEquals(2, nodes.size)
            assertTrue(nodes.all { it.nodeType == "PROPAGATION_NODE" })
        }
    }

    @Test
    fun getTopPropagationNodes_respectsLimit() = runTest {
        // Given - 5 propagation nodes
        repeat(5) { i ->
            dao.upsertAnnounce(
                createTestAnnounce(
                    destinationHash = "prop$i",
                    nodeType = "PROPAGATION_NODE",
                    hops = i,
                ),
            )
        }

        // When/Then - request only 3
        dao.getTopPropagationNodes(3).test {
            val nodes = awaitItem()
            assertEquals(3, nodes.size)
        }
    }

    @Test
    fun getTopPropagationNodes_orderedByHopsAscending() = runTest {
        // Given - propagation nodes with different hop counts
        dao.upsertAnnounce(
            createTestAnnounce(destinationHash = "far", nodeType = "PROPAGATION_NODE", hops = 5),
        )
        dao.upsertAnnounce(
            createTestAnnounce(destinationHash = "near", nodeType = "PROPAGATION_NODE", hops = 1),
        )
        dao.upsertAnnounce(
            createTestAnnounce(destinationHash = "mid", nodeType = "PROPAGATION_NODE", hops = 3),
        )

        // When/Then
        dao.getTopPropagationNodes(10).test {
            val nodes = awaitItem()
            assertEquals(3, nodes.size)
            assertEquals("near", nodes[0].destinationHash) // 1 hop - nearest first
            assertEquals("mid", nodes[1].destinationHash) // 3 hops
            assertEquals("far", nodes[2].destinationHash) // 5 hops - farthest last
        }
    }

    @Test
    fun getTopPropagationNodes_emitsUpdatesWhenNodeAdded() = runTest {
        // Given/When/Then
        dao.getTopPropagationNodes(10).test {
            // Initially empty
            assertEquals(0, awaitItem().size)

            // Add a propagation node
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "new_prop", nodeType = "PROPAGATION_NODE"),
            )

            // Should emit update
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("new_prop", updated[0].destinationHash)
        }
    }

    @Test
    fun getTopPropagationNodes_doesNotEmitWhenNonPropagationNodeAdded() = runTest {
        // Given - one propagation node exists
        dao.upsertAnnounce(
            createTestAnnounce(destinationHash = "prop1", nodeType = "PROPAGATION_NODE"),
        )

        dao.getTopPropagationNodes(10).test {
            // Initial state
            assertEquals(1, awaitItem().size)

            // Add a PEER - should not trigger emission for this flow
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "peer1", nodeType = "PEER"),
            )

            // Room actually emits on any table change, so we might get an update
            // but the result should still be 1 propagation node
            // This test ensures filtering still works even with spurious emissions
            expectNoEvents() // If Room doesn't emit, that's fine
        }
    }

    // ========== getNodeTypeCounts Tests ==========

    @Test
    fun getNodeTypeCounts_returnsEmptyWhenNoAnnounces() = runTest {
        // When
        val counts = dao.getNodeTypeCounts()

        // Then
        assertTrue(counts.isEmpty())
    }

    @Test
    fun getNodeTypeCounts_returnsCorrectDistribution() = runTest {
        // Given
        repeat(3) { i ->
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "peer$i", nodeType = "PEER"))
        }
        repeat(2) { i ->
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "node$i", nodeType = "NODE"))
        }
        repeat(5) { i ->
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "prop$i", nodeType = "PROPAGATION_NODE"))
        }

        // When
        val counts = dao.getNodeTypeCounts()

        // Then
        assertEquals(3, counts.size)
        assertEquals(5, counts.find { it.nodeType == "PROPAGATION_NODE" }?.count)
        assertEquals(3, counts.find { it.nodeType == "PEER" }?.count)
        assertEquals(2, counts.find { it.nodeType == "NODE" }?.count)
    }
}
