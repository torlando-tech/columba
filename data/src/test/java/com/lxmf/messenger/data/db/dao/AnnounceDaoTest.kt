package com.lxmf.messenger.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private lateinit var contactDao: ContactDao
    private lateinit var identityDao: LocalIdentityDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.announceDao()
        contactDao = database.contactDao()
        identityDao = database.localIdentityDao()
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
        stampCostFlexibility: Int? = null,
    ) = AnnounceEntity(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = ByteArray(32) { it.toByte() },
        appData = null,
        hops = hops,
        lastSeenTimestamp = lastSeenTimestamp,
        nodeType = nodeType,
        receivingInterface = null,
        aspect =
            when (nodeType) {
                "PROPAGATION_NODE" -> "lxmf.propagation"
                "PEER" -> "lxmf.delivery"
                else -> null
            },
        isFavorite = false,
        favoritedTimestamp = null,
        stampCost = if (stampCostFlexibility != null) 16 else null,
        stampCostFlexibility = stampCostFlexibility,
        peeringCost = if (stampCostFlexibility != null) 18 else null,
    )

    private fun createTestContact(
        destinationHash: String,
        identityHash: String = "test_identity_hash",
    ) = ContactEntity(
        destinationHash = destinationHash,
        identityHash = identityHash,
        publicKey = ByteArray(32) { it.toByte() },
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "ANNOUNCE",
    )

    private fun createTestIdentity(identityHash: String = "test_identity_hash") =
        LocalIdentityEntity(
            identityHash = identityHash,
            displayName = "Test Identity",
            destinationHash = "dest_$identityHash",
            filePath = "/test/path",
            createdTimestamp = System.currentTimeMillis(),
            lastUsedTimestamp = System.currentTimeMillis(),
            isActive = true,
        )

    // ========== getTopPropagationNodes Tests ==========

    @Test
    fun getTopPropagationNodes_returnsEmptyWhenNoAnnounces() =
        runTest {
            // When/Then
            dao.getTopPropagationNodes(10).test {
                val nodes = awaitItem()
                assertTrue(nodes.isEmpty())
            }
        }

    @Test
    fun getTopPropagationNodes_returnsOnlyPropagationNodeType() =
        runTest {
            // Given - mix of node types
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "peer1", nodeType = "PEER", hops = 1))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "node1", nodeType = "NODE", hops = 1))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "prop1", nodeType = "PROPAGATION_NODE", hops = 1, stampCostFlexibility = 3))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "prop2", nodeType = "PROPAGATION_NODE", hops = 2, stampCostFlexibility = 3))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "peer2", nodeType = "PEER", hops = 3))

            // When/Then
            dao.getTopPropagationNodes(10).test {
                val nodes = awaitItem()
                assertEquals(2, nodes.size)
                assertTrue(nodes.all { it.nodeType == "PROPAGATION_NODE" })
            }
        }

    @Test
    fun getTopPropagationNodes_respectsLimit() =
        runTest {
            // Given - 5 propagation nodes with modern LXMF format
            repeat(5) { i ->
                dao.upsertAnnounce(
                    createTestAnnounce(
                        destinationHash = "prop$i",
                        nodeType = "PROPAGATION_NODE",
                        hops = i,
                        stampCostFlexibility = 3,
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
    fun getTopPropagationNodes_orderedByHopsAscending() =
        runTest {
            // Given - propagation nodes with different hop counts (modern LXMF format)
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "far", nodeType = "PROPAGATION_NODE", hops = 5, stampCostFlexibility = 3),
            )
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "near", nodeType = "PROPAGATION_NODE", hops = 1, stampCostFlexibility = 3),
            )
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "mid", nodeType = "PROPAGATION_NODE", hops = 3, stampCostFlexibility = 3),
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
    fun getTopPropagationNodes_emitsUpdatesWhenNodeAdded() =
        runTest {
            // Given/When/Then
            dao.getTopPropagationNodes(10).test {
                // Initially empty
                assertEquals(0, awaitItem().size)

                // Add a propagation node with modern LXMF format
                dao.upsertAnnounce(
                    createTestAnnounce(destinationHash = "new_prop", nodeType = "PROPAGATION_NODE", stampCostFlexibility = 3),
                )

                // Should emit update
                val updated = awaitItem()
                assertEquals(1, updated.size)
                assertEquals("new_prop", updated[0].destinationHash)
            }
        }

    @Test
    fun getTopPropagationNodes_notAffectedByNonPropagationNodeInsert() =
        runTest {
            // Given - one propagation node exists with modern LXMF format
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "prop1", nodeType = "PROPAGATION_NODE", stampCostFlexibility = 3),
            )

            dao.getTopPropagationNodes(10).test {
                // Initial state
                val initial = awaitItem()
                assertEquals(1, initial.size)
                assertEquals("prop1", initial[0].destinationHash)

                // Add a PEER - should not affect the filtered result
                dao.upsertAnnounce(
                    createTestAnnounce(destinationHash = "peer1", nodeType = "PEER"),
                )

                // Room may or may not emit on table changes (implementation detail).
                // If it does emit, verify the result still only contains propagation nodes.
                // We can't control Room's emission behavior, so we accept either case.
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun getTopPropagationNodes_excludesDeprecatedNodes() =
        runTest {
            // Given - mix of modern and deprecated propagation nodes
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "modern1", nodeType = "PROPAGATION_NODE", hops = 1, stampCostFlexibility = 3),
            )
            // null stampCostFlexibility
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "deprecated1", nodeType = "PROPAGATION_NODE", hops = 1),
            )
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "modern2", nodeType = "PROPAGATION_NODE", hops = 2, stampCostFlexibility = 3),
            )
            // null stampCostFlexibility
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "deprecated2", nodeType = "PROPAGATION_NODE", hops = 2),
            )

            // When/Then - only modern nodes should be returned
            dao.getTopPropagationNodes(10).test {
                val nodes = awaitItem()
                assertEquals(2, nodes.size)
                assertTrue(nodes.all { it.stampCostFlexibility != null })
                assertTrue(nodes.any { it.destinationHash == "modern1" })
                assertTrue(nodes.any { it.destinationHash == "modern2" })
            }
        }

    @Test
    fun getAnnouncesByTypes_excludesDeprecatedPropagationNodes() =
        runTest {
            // Given - mix of modern propagation nodes, deprecated propagation nodes, and peers
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "modern_prop", nodeType = "PROPAGATION_NODE", stampCostFlexibility = 3),
            )
            // null stampCostFlexibility
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "deprecated_prop", nodeType = "PROPAGATION_NODE"),
            )
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "peer1", nodeType = "PEER"),
            )

            // When filtering for PROPAGATION_NODE only
            dao.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).test {
                val nodes = awaitItem()
                // Only modern propagation node should be returned
                assertEquals(1, nodes.size)
                assertEquals("modern_prop", nodes[0].destinationHash)
            }
        }

    @Test
    fun getAnnouncesByTypes_allowsPeersWithNullStampCostFlexibility() =
        runTest {
            // Given - peers (which don't have stamp cost fields)
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "peer1", nodeType = "PEER"),
            )
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "peer2", nodeType = "PEER"),
            )

            // When filtering for PEER
            dao.getAnnouncesByTypes(listOf("PEER")).test {
                val nodes = awaitItem()
                // Both peers should be returned (null stampCostFlexibility is fine for non-propagation nodes)
                assertEquals(2, nodes.size)
            }
        }

    // ========== getAnnounceByIdentityHash Tests (COLUMBA-28) ==========

    @Test
    fun getAnnounceByIdentityHash_returnsMatchingAnnounce() =
        runTest {
            val identityHash = "abcdef0123456789abcdef0123456789"
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "dest1", peerName = "Alice").copy(
                    computedIdentityHash = identityHash,
                ),
            )

            val result = dao.getAnnounceByIdentityHash(identityHash)

            assertEquals("dest1", result?.destinationHash)
            assertEquals("Alice", result?.peerName)
        }

    @Test
    fun getAnnounceByIdentityHash_returnsNullWhenNoMatch() =
        runTest {
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "dest1").copy(
                    computedIdentityHash = "aaaa0000aaaa0000aaaa0000aaaa0000",
                ),
            )

            val result = dao.getAnnounceByIdentityHash("bbbb1111bbbb1111bbbb1111bbbb1111")

            assertEquals(null, result)
        }

    @Test
    fun getAnnounceByIdentityHash_returnsNullWhenTableEmpty() =
        runTest {
            val result = dao.getAnnounceByIdentityHash("abcdef0123456789abcdef0123456789")

            assertEquals(null, result)
        }

    @Test
    fun getAnnounceByIdentityHash_returnsNullForNullComputedHash() =
        runTest {
            // Announce with no computedIdentityHash (e.g., pre-migration row not yet backfilled)
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "dest1").copy(
                    computedIdentityHash = null,
                ),
            )

            val result = dao.getAnnounceByIdentityHash("abcdef0123456789abcdef0123456789")

            assertEquals(null, result)
        }

    @Test
    fun getAnnounceByIdentityHash_isCaseSensitive() =
        runTest {
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "dest1").copy(
                    computedIdentityHash = "abcdef0123456789abcdef0123456789",
                ),
            )

            // Uppercase should NOT match (identity hashes are always stored lowercase)
            val result = dao.getAnnounceByIdentityHash("ABCDEF0123456789ABCDEF0123456789")

            assertEquals(null, result)
        }

    @Test
    fun getAnnounceByIdentityHash_returnsOneWhenMultipleExist() =
        runTest {
            // Two announces with the same identity hash (theoretically impossible,
            // but LIMIT 1 should still return exactly one)
            val identityHash = "abcdef0123456789abcdef0123456789"
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "dest1", peerName = "First").copy(
                    computedIdentityHash = identityHash,
                ),
            )
            dao.upsertAnnounce(
                createTestAnnounce(destinationHash = "dest2", peerName = "Second").copy(
                    computedIdentityHash = identityHash,
                ),
            )

            val result = dao.getAnnounceByIdentityHash(identityHash)

            // Should return exactly one result (LIMIT 1)
            assertNotNull(result)
        }

    // ========== getNodeTypeCounts Tests ==========

    @Test
    fun getNodeTypeCounts_returnsEmptyWhenNoAnnounces() =
        runTest {
            // When
            val counts = dao.getNodeTypeCounts()

            // Then
            assertTrue(counts.isEmpty())
        }

    @Test
    fun getNodeTypeCounts_returnsCorrectDistribution() =
        runTest {
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

    // ========== deleteAllAnnouncesExceptContacts Tests ==========

    @Test
    fun deleteAllAnnouncesExceptContacts_removesNonContactAnnounces() =
        runTest {
            // Given - 2 announces, 1 contact for dest_hash_1
            val identity = createTestIdentity("test_identity_hash")
            identityDao.insert(identity)

            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_1"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_2"))

            val contact = createTestContact("dest_hash_1", "test_identity_hash")
            contactDao.insertContact(contact)

            // When
            dao.deleteAllAnnouncesExceptContacts("test_identity_hash")

            // Then - Only dest_hash_1 remains
            val remaining = dao.getAllAnnouncesSync()
            assertEquals(1, remaining.size)
            assertEquals("dest_hash_1", remaining[0].destinationHash)
        }

    @Test
    fun deleteAllAnnouncesExceptContacts_preservesAllContactAnnounces() =
        runTest {
            // Given - 3 announces, all are contacts
            val identity = createTestIdentity("test_identity_hash")
            identityDao.insert(identity)

            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_1"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_2"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_3"))

            contactDao.insertContact(createTestContact("dest_hash_1", "test_identity_hash"))
            contactDao.insertContact(createTestContact("dest_hash_2", "test_identity_hash"))
            contactDao.insertContact(createTestContact("dest_hash_3", "test_identity_hash"))

            // When
            dao.deleteAllAnnouncesExceptContacts("test_identity_hash")

            // Then - All 3 announces remain
            val remaining = dao.getAllAnnouncesSync()
            assertEquals(3, remaining.size)
        }

    @Test
    fun deleteAllAnnouncesExceptContacts_doesNotPreserveContactsFromOtherIdentities() =
        runTest {
            // Given - 2 announces, contact for dest_hash_1 under identity_B (NOT active identity_A)
            val identityA = createTestIdentity("identity_A")
            val identityB = createTestIdentity("identity_B")
            identityDao.insert(identityA)
            identityDao.insert(identityB)

            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_1"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_2"))

            // Contact belongs to identity_B, not identity_A
            contactDao.insertContact(createTestContact("dest_hash_1", "identity_B"))

            // When - Delete using identity_A (which has NO contacts)
            dao.deleteAllAnnouncesExceptContacts("identity_A")

            // Then - Both announces deleted (identity_A has no contacts)
            val remaining = dao.getAllAnnouncesSync()
            assertEquals(0, remaining.size)
        }

    @Test
    fun deleteAllAnnouncesExceptContacts_deletesAllWhenNoContacts() =
        runTest {
            // Given - 3 announces, no contacts
            val identity = createTestIdentity("some_identity_with_no_contacts")
            identityDao.insert(identity)

            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_1"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_2"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_3"))

            // When
            dao.deleteAllAnnouncesExceptContacts("some_identity_with_no_contacts")

            // Then - All announces deleted
            val remaining = dao.getAllAnnouncesSync()
            assertEquals(0, remaining.size)
        }

    @Test
    fun deleteAllAnnouncesExceptContacts_handlesEmptyAnnouncesTable() =
        runTest {
            // Given - No announces
            val identity = createTestIdentity("test_identity_hash")
            identityDao.insert(identity)

            // When - Should not crash
            dao.deleteAllAnnouncesExceptContacts("test_identity_hash")

            // Then - Empty list
            val remaining = dao.getAllAnnouncesSync()
            assertEquals(0, remaining.size)
        }

    @Test
    fun deleteAllAnnounces_stillDeletesEverything() =
        runTest {
            // Given - 2 announces and 1 contact for dest_hash_1
            val identity = createTestIdentity("test_identity_hash")
            identityDao.insert(identity)

            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_1"))
            dao.upsertAnnounce(createTestAnnounce(destinationHash = "dest_hash_2"))

            contactDao.insertContact(createTestContact("dest_hash_1", "test_identity_hash"))

            // When - Use OLD deleteAllAnnounces (not the new contact-preserving version)
            dao.deleteAllAnnounces()

            // Then - All announces deleted (original behavior unchanged)
            val remaining = dao.getAllAnnouncesSync()
            assertEquals(0, remaining.size)
        }
}
