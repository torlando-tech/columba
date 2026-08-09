package network.columba.app.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
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
class CallHistoryDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var callHistoryDao: CallHistoryDao
    private lateinit var callHistoryDeletionDao: CallHistoryDeletionDao
    private lateinit var identityDao: LocalIdentityDao

    companion object {
        private const val IDENTITY = "identity_hash_12345678901234567"
        private const val IDENTITY_2 = "identity_hash_22345678901234567"
        private const val REMOTE = "remote_hash_1234567890123456789012"
        private const val REMOTE_2 = "remote_hash_2234567890123456789012"
        private const val ATTEMPT = "call_attempt_1"
        private const val ATTEMPT_2 = "call_attempt_2"
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
        callHistoryDao = database.callHistoryDao()
        callHistoryDeletionDao = database.callHistoryDeletionDao()
        identityDao = database.localIdentityDao()

        runTest {
            identityDao.insert(createIdentity(IDENTITY))
            identityDao.insert(createIdentity(IDENTITY_2, isActive = false))
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createIdentity(
        identityHash: String,
        isActive: Boolean = true,
    ) = LocalIdentityEntity(
        identityHash = identityHash,
        displayName = "Test",
        destinationHash = "dest_$identityHash",
        filePath = "/test/$identityHash.key",
        keyData = null,
        createdTimestamp = 1L,
        lastUsedTimestamp = 2L,
        isActive = isActive,
    )

    private fun attempt(
        callAttemptId: String = ATTEMPT,
        identityHash: String = IDENTITY,
        remote: String = REMOTE,
        direction: String = "OUTGOING",
        serviceInstanceId: String = "svc-1",
    ) = CallHistoryEntity(
        callAttemptId = callAttemptId,
        localIdentityHash = identityHash,
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
        serviceInstanceId = serviceInstanceId,
    )

    // ===== Insert & duplicate observation =====

    @Test
    fun `insertInitial inserts and duplicate insert is ignored`() = runTest {
        assertTrue(callHistoryDao.insertInitial(attempt()) != -1L)
        val again = callHistoryDao.insertInitial(attempt())
        assertEquals(-1L, again) // duplicate PK ignored
        assertEquals(1, callHistoryDao.getForExport(IDENTITY).size)
    }

    @Test
    fun `recordConnected and completeConnectedCall finalize to CONNECTED_ENDED`() = runTest {
        callHistoryDao.insertInitial(attempt())
        assertTrue(callHistoryDao.recordConnected(ATTEMPT, 200))
        // duplicate observation is idempotent
        assertTrue(callHistoryDao.recordConnected(ATTEMPT, 200))
        assertTrue(callHistoryDao.completeConnectedCall(ATTEMPT, 300))

        val row = callHistoryDao.getByAttemptId(ATTEMPT)!!
        assertEquals("CONNECTED_ENDED", row.outcome)
        assertEquals(300L, row.endedAt)
        assertEquals(false, row.inferredEnding)
    }

    @Test
    fun `immutable finalization - second finalize is rejected`() = runTest {
        callHistoryDao.insertInitial(attempt())
        callHistoryDao.recordConnected(ATTEMPT, 200)
        assertTrue(callHistoryDao.completeConnectedCall(ATTEMPT, 300))
        // second finalize cannot change the terminal outcome
        assertFalse(callHistoryDao.completeConnectedCall(ATTEMPT, 400))
        val row = callHistoryDao.getByAttemptId(ATTEMPT)!!
        assertEquals(300L, row.endedAt)
        assertEquals("CONNECTED_ENDED", row.outcome)
    }

    @Test
    fun `finalizeUnconnectedCall accepts reduced outgoing outcomes`() = runTest {
        callHistoryDao.insertInitial(attempt(direction = "OUTGOING"))
        assertTrue(callHistoryDao.finalizeUnconnectedCall(ATTEMPT, 150, "REJECTED_REMOTE"))
        assertEquals("REJECTED_REMOTE", callHistoryDao.getByAttemptId(ATTEMPT)!!.outcome)
    }

    @Test
    fun `finalizeUnconnectedCall rejects removed outcome`() = runTest {
        callHistoryDao.insertInitial(attempt(direction = "OUTGOING"))
        // UNANSWERED / DROPPED are not in the reduced guard lists -> no row updated
        assertFalse(callHistoryDao.finalizeUnconnectedCall(ATTEMPT, 150, "UNANSWERED"))
        assertNull(callHistoryDao.getByAttemptId(ATTEMPT)!!.outcome)
    }

    @Test
    fun `finalizeUnconnectedCall rejects outcome for wrong direction`() = runTest {
        callHistoryDao.insertInitial(attempt(direction = "INCOMING"))
        // REJECTED_REMOTE is outgoing-only
        assertFalse(callHistoryDao.finalizeUnconnectedCall(ATTEMPT, 150, "REJECTED_REMOTE"))
        assertNull(callHistoryDao.getByAttemptId(ATTEMPT)!!.outcome)
    }

    @Test
    fun `failCallAttempt persists Columba-observed reason`() = runTest {
        callHistoryDao.insertInitial(attempt(direction = "OUTGOING"))
        assertTrue(callHistoryDao.failCallAttempt(ATTEMPT, 150, "NETWORK_UNAVAILABLE"))
        val row = callHistoryDao.getByAttemptId(ATTEMPT)!!
        assertEquals("FAILED", row.outcome)
        assertEquals("NETWORK_UNAVAILABLE", row.failureReason)
    }

    // ===== Identity scoping =====

    @Test
    fun `history is identity-scoped`() = runTest {
        callHistoryDao.insertInitial(attempt(identityHash = IDENTITY))
        assertEquals(1, callHistoryDao.getForExport(IDENTITY).size)
        assertEquals(0, callHistoryDao.getForExport(IDENTITY_2).size)
        assertEquals(emptyList<network.columba.app.data.model.CallHistoryRecord>(), callHistoryDao.observeHistory(IDENTITY_2, "").first())
    }

    // ===== Deletion authority & non-resurrection =====

    @Test
    fun `deleteFinalized records deletion authority and removes row`() = runTest {
        callHistoryDao.insertInitial(attempt())
        callHistoryDao.recordConnected(ATTEMPT, 200)
        callHistoryDao.completeConnectedCall(ATTEMPT, 300)

        assertEquals(1, callHistoryDeletionDao.deleteFinalized(ATTEMPT, IDENTITY, 400))
        assertNull(callHistoryDao.getByAttemptId(ATTEMPT))
        assertTrue(callHistoryDao.wasDeleted(ATTEMPT))
    }

    @Test
    fun `deleted attempt cannot be resurrected`() = runTest {
        callHistoryDao.insertInitial(attempt())
        callHistoryDao.recordConnected(ATTEMPT, 200)
        callHistoryDao.completeConnectedCall(ATTEMPT, 300)
        callHistoryDeletionDao.deleteFinalized(ATTEMPT, IDENTITY, 400)

        // a late generic callback tries to recreate the attempt -> refused
        assertEquals(-1L, callHistoryDao.insertInitial(attempt()))
        assertNull(callHistoryDao.getByAttemptId(ATTEMPT))
    }

    @Test
    fun `clearFinalized removes all finalized rows for identity`() = runTest {
        callHistoryDao.insertInitial(attempt(ATTEMPT))
        callHistoryDao.recordConnected(ATTEMPT, 200)
        callHistoryDao.completeConnectedCall(ATTEMPT, 300)
        callHistoryDao.insertInitial(attempt(ATTEMPT_2))
        callHistoryDao.recordConnected(ATTEMPT_2, 250)
        callHistoryDao.completeConnectedCall(ATTEMPT_2, 350)

        val cleared = callHistoryDeletionDao.clearFinalized(IDENTITY, 400)
        assertEquals(2, cleared)
        assertEquals(0, callHistoryDao.getForExport(IDENTITY).size)
    }

    @Test
    fun `deleteFinalized rejects unfinished call`() = runTest {
        callHistoryDao.insertInitial(attempt())
        // still open (no connected / no ended)
        assertEquals(0, callHistoryDeletionDao.deleteFinalized(ATTEMPT, IDENTITY, 400))
        assertFalse(callHistoryDao.wasDeleted(ATTEMPT))
    }

    // ===== Service reconcile =====

    @Test
    fun `reconcileOpenAttempts interrupts stale service instance`() = runTest {
        callHistoryDao.insertInitial(attempt(serviceInstanceId = "svc-old"))
        // keep svc-old open but reconcile with a different current instance
        callHistoryDao.reconcileOpenAttempts(currentServiceInstanceId = "svc-new", retainedCallAttemptId = null, endedAt = 500)
        val row = callHistoryDao.getByAttemptId(ATTEMPT)!!
        assertEquals("INTERRUPTED", row.outcome)
        assertTrue(row.inferredEnding)
    }

    @Test
    fun `getOpenAttemptForService only returns matching open instance`() = runTest {
        callHistoryDao.insertInitial(attempt(serviceInstanceId = "svc-a"))
        assertTrue(callHistoryDao.getOpenAttemptForService(ATTEMPT, "svc-a") != null)
        assertNull(callHistoryDao.getOpenAttemptForService(ATTEMPT, "svc-b"))
    }
}
