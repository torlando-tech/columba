package network.columba.app.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.cash.turbine.test
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlockedPeerDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var blockedPeerDao: BlockedPeerDao
    private lateinit var identityDao: LocalIdentityDao

    companion object {
        private const val IDENTITY_HASH = "identity_hash_12345678901234567"
        private const val IDENTITY_HASH_2 = "identity_hash_22345678901234567"
        private const val PEER_HASH = "peer_hash_1234567890123456789012"
        private const val PEER_HASH_2 = "peer_hash_2234567890123456789012"
        private const val PEER_IDENTITY_HASH = "peer_id_hash_123456789012345678"
    }

    @Before
    fun setup() {
        val context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        blockedPeerDao = database.blockedPeerDao()
        identityDao = database.localIdentityDao()

        runTest {
            identityDao.insert(createTestIdentity(IDENTITY_HASH))
            identityDao.insert(createTestIdentity(IDENTITY_HASH_2, isActive = false))
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createTestIdentity(
        identityHash: String,
        isActive: Boolean = true,
    ) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = "Test Identity",
        destinationHash = "dest_$identityHash",
        filePath = "/test/$identityHash.key",
        keyData = null,
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = isActive,
    )

    private fun createBlockedPeer(
        peerHash: String = PEER_HASH,
        identityHash: String = IDENTITY_HASH,
        peerIdentityHash: String? = PEER_IDENTITY_HASH,
        displayName: String? = "Blocked User",
        blackholeEnabled: Boolean = false,
    ) = BlockedPeerEntity(
        peerHash = peerHash,
        identityHash = identityHash,
        peerIdentityHash = peerIdentityHash,
        displayName = displayName,
        blockedTimestamp = System.currentTimeMillis(),
        isBlackholeEnabled = blackholeEnabled,
    )

    // ========== Insert & Query Tests ==========

    @Test
    fun `insertBlockedPeer and isBlocked returns true`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer())

            assertTrue(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))
        }

    @Test
    fun `isBlocked returns false for non-blocked peer`() =
        runTest {
            assertFalse(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))
        }

    @Test
    fun `isBlocked is identity-scoped`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(identityHash = IDENTITY_HASH))

            assertTrue(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))
            assertFalse(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH_2))
        }

    // ========== Delete Tests ==========

    @Test
    fun `deleteBlockedPeer removes block`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer())
            assertTrue(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))

            blockedPeerDao.deleteBlockedPeer(PEER_HASH, IDENTITY_HASH)
            assertFalse(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))
        }

    @Test
    fun `FK cascade deletes blocked peers when identity is deleted`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer())
            assertTrue(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))

            identityDao.delete(IDENTITY_HASH)
            assertFalse(blockedPeerDao.isBlocked(PEER_HASH, IDENTITY_HASH))
        }

    // ========== Flow Tests ==========

    @Test
    fun `isBlockedFlow emits updates reactively`() =
        runTest {
            blockedPeerDao.isBlockedFlow(PEER_HASH, IDENTITY_HASH).test {
                assertFalse(awaitItem())

                blockedPeerDao.insertBlockedPeer(createBlockedPeer())
                assertTrue(awaitItem())

                blockedPeerDao.deleteBlockedPeer(PEER_HASH, IDENTITY_HASH)
                assertFalse(awaitItem())
            }
        }

    @Test
    fun `getBlockedPeers returns all blocked for identity`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH))
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH_2))

            blockedPeerDao.getBlockedPeers(IDENTITY_HASH).test {
                val peers = awaitItem()
                assertEquals(2, peers.size)
            }
        }

    @Test
    fun `getBlockedPeerCount emits correct count`() =
        runTest {
            blockedPeerDao.getBlockedPeerCount(IDENTITY_HASH).test {
                assertEquals(0, awaitItem())

                blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH))
                assertEquals(1, awaitItem())

                blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH_2))
                assertEquals(2, awaitItem())

                blockedPeerDao.deleteBlockedPeer(PEER_HASH, IDENTITY_HASH)
                assertEquals(1, awaitItem())
            }
        }

    // ========== Hash List Tests ==========

    @Test
    fun `getBlockedPeerHashes returns all peer hashes for identity`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH))
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH_2))

            val hashes = blockedPeerDao.getBlockedPeerHashes(IDENTITY_HASH)
            assertEquals(2, hashes.size)
            assertTrue(hashes.contains(PEER_HASH))
            assertTrue(hashes.contains(PEER_HASH_2))
        }

    @Test
    fun `getBlockedPeerHashes is identity-scoped`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH, identityHash = IDENTITY_HASH))
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(peerHash = PEER_HASH_2, identityHash = IDENTITY_HASH_2))

            val hashes = blockedPeerDao.getBlockedPeerHashes(IDENTITY_HASH)
            assertEquals(1, hashes.size)
            assertEquals(PEER_HASH, hashes[0])
        }

    // ========== Blackhole Tests ==========

    @Test
    fun `getBlackholedPeerIdentityHashes returns only blackholed peers`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(
                createBlockedPeer(peerHash = PEER_HASH, blackholeEnabled = true),
            )
            blockedPeerDao.insertBlockedPeer(
                createBlockedPeer(peerHash = PEER_HASH_2, blackholeEnabled = false),
            )

            val hashes = blockedPeerDao.getBlackholedPeerIdentityHashes(IDENTITY_HASH)
            assertEquals(1, hashes.size)
            assertEquals(PEER_IDENTITY_HASH, hashes[0])
        }

    @Test
    fun `getBlackholedPeerIdentityHashes excludes null identity hashes`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(
                createBlockedPeer(peerHash = PEER_HASH, peerIdentityHash = null, blackholeEnabled = true),
            )

            val hashes = blockedPeerDao.getBlackholedPeerIdentityHashes(IDENTITY_HASH)
            assertTrue(hashes.isEmpty())
        }

    @Test
    fun `updateBlackholeEnabled toggles blackhole status`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(createBlockedPeer(blackholeEnabled = false))

            var hashes = blockedPeerDao.getBlackholedPeerIdentityHashes(IDENTITY_HASH)
            assertTrue(hashes.isEmpty())

            blockedPeerDao.updateBlackholeEnabled(PEER_HASH, IDENTITY_HASH, true)
            hashes = blockedPeerDao.getBlackholedPeerIdentityHashes(IDENTITY_HASH)
            assertEquals(1, hashes.size)

            blockedPeerDao.updateBlackholeEnabled(PEER_HASH, IDENTITY_HASH, false)
            hashes = blockedPeerDao.getBlackholedPeerIdentityHashes(IDENTITY_HASH)
            assertTrue(hashes.isEmpty())
        }

    // ========== Upsert (REPLACE) Tests ==========

    @Test
    fun `insertBlockedPeer replaces on conflict`() =
        runTest {
            blockedPeerDao.insertBlockedPeer(
                createBlockedPeer(displayName = "Original Name", blackholeEnabled = false),
            )

            blockedPeerDao.insertBlockedPeer(
                createBlockedPeer(displayName = "Updated Name", blackholeEnabled = true),
            )

            blockedPeerDao.getBlockedPeers(IDENTITY_HASH).test {
                val peers = awaitItem()
                assertEquals(1, peers.size)
                assertEquals("Updated Name", peers[0].displayName)
                assertTrue(peers[0].isBlackholeEnabled)
            }
        }
}
