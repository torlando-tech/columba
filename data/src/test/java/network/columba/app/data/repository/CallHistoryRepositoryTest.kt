@file:Suppress("NoRelaxedMocks")

package network.columba.app.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.dao.CallHistoryDeletionDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.entity.PeerIconEntity
import network.columba.app.data.model.CallHistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CallHistoryRepository] icon enrichment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryRepositoryTest {
    private val callHistoryDao = mockk<CallHistoryDao>()
    private val callHistoryDeletionDao = mockk<CallHistoryDeletionDao>()
    private val localIdentityDao = mockk<LocalIdentityDao>()
    private val peerIconDao = mockk<PeerIconDao>()

    private val repository =
        CallHistoryRepository(
            callHistoryDao,
            callHistoryDeletionDao,
            localIdentityDao,
            peerIconDao,
        )

    private val deliveryHash = "db3ffd2575469a78bff6b7c8c183e32a"
    private val identityHash = "ef2f7fbc8ec20971cb9bc8f468984aaf"

    private fun record(
        remote: String,
        direction: String,
        currentDestinationHash: String?,
    ) = CallHistoryRecord(
        callAttemptId = "attempt-$remote",
        localIdentityHash = "cd".repeat(16),
        remoteIdentityHash = remote,
        direction = direction,
        peerDisplayNameSnapshot = null,
        codecProfileCode = null,
        attemptedAt = 100,
        ringingAt = null,
        connectedAt = null,
        endedAt = null,
        outcome = null,
        inferredEnding = false,
        failureReason = null,
        displayName = "Peer",
        currentDestinationHash = currentDestinationHash,
    )

    @Test
    fun `outgoing row resolves icon from dialed destination without an announce`() =
        runTest {
            // Outgoing rows have no currentDestinationHash (no announce maps
            // the dialed destination), but the dialed destination IS the
            // peer_icons key, so the stored icon must still be attached.
            every { callHistoryDao.observeHistory(any(), any()) } returns
                flowOf(listOf(record(deliveryHash, "OUTGOING", null)))
            every { peerIconDao.observeIcon(deliveryHash) } returns
                flowOf(PeerIconEntity(deliveryHash, "rocket-launch", "ffffff", "bb2bd4", 1L))

            val enriched = repository.observeHistory("local", "").first().single()

            assertEquals("rocket-launch", enriched.iconName)
            assertEquals("ffffff", enriched.iconForegroundColor)
            assertEquals("bb2bd4", enriched.iconBackgroundColor)
        }

    @Test
    fun `incoming row prefers announce-derived destination for icon lookup`() =
        runTest {
            every { callHistoryDao.observeHistory(any(), any()) } returns
                flowOf(listOf(record(identityHash, "INCOMING", deliveryHash)))
            every { peerIconDao.observeIcon(deliveryHash) } returns
                flowOf(PeerIconEntity(deliveryHash, "rocket-launch", "ffffff", "bb2bd4", 1L))
            every { peerIconDao.observeIcon(identityHash) } returns flowOf(null)

            val enriched = repository.observeHistory("local", "").first().single()

            assertEquals("rocket-launch", enriched.iconName)
        }

    @Test
    fun `row without any stored icon keeps null icon fields`() =
        runTest {
            every { callHistoryDao.observeHistory(any(), any()) } returns
                flowOf(listOf(record(identityHash, "INCOMING", deliveryHash)))
            every { peerIconDao.observeIcon(any()) } returns flowOf(null)

            val enriched = repository.observeHistory("local", "").first().single()

            assertEquals(null, enriched.iconName)
            assertEquals(null, enriched.iconForegroundColor)
            assertEquals(null, enriched.iconBackgroundColor)
        }
}
