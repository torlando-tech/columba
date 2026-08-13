package network.columba.app.rns.host.call

import app.cash.turbine.test
import java.util.UUID
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.columba.app.rns.api.call.CallFailureReason
import network.columba.app.rns.api.call.CallLifecycleEvent
import network.columba.app.rns.api.call.UnconnectedOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCallLifecycleTest {

    @Test
    fun `second admission cannot replace active durable ownership`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { 100L },
                )
            val request = request()

            lifecycle.acceptCallAttempt(request).getOrThrow()
            val duplicate = lifecycle.acceptCallAttempt(request)

            assertTrue(duplicate.isFailure)
            assertEquals("attempt-1", lifecycle.activeAttempt.value?.callAttemptId)
            assertEquals("attempt-1", dao.getByAttemptId("attempt-1")?.callAttemptId)
        }

    @Test
    fun `replacement admission stays closed until terminal persistence completes`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { 100L },
                )
            lifecycle.acceptCallAttempt(request()).getOrThrow()

            // Terminal not yet persisted -> replacement refused.
            val blocked = lifecycle.acceptCallAttempt(request())
            assertTrue(blocked.isFailure)

            // Finalize the owned attempt, then a replacement may be admitted.
            lifecycle.interruptCall("attempt-1").getOrThrow()
            assertNull(lifecycle.activeAttempt.value)
        }

    @Test
    fun `duplicate connected and finalize observations are idempotent`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val times = ArrayDeque(listOf(100L, 200L, 300L, 400L))
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { times.removeFirst() },
                )
            val request =
                CallAttemptRequest(
                    direction = CallAttemptDirection.OUTGOING,
                    localIdentityHash = "local",
                    remoteIdentityHash = "remote",
                    codecProfileCode = 2,
                )
            lifecycle.acceptCallAttempt(request).getOrThrow()
            lifecycle.recordCallConnected("attempt-1").getOrThrow()
            lifecycle.recordCallConnected("attempt-1").getOrThrow() // duplicate no-op
            lifecycle.completeConnectedCall("attempt-1").getOrThrow()
            lifecycle.completeConnectedCall("attempt-1").getOrThrow() // duplicate no-op

            val record = dao.getByAttemptId("attempt-1")
            assertEquals(200L, record?.connectedAt)
            assertEquals(300L, record?.endedAt)
            assertEquals("CONNECTED_ENDED", record?.outcome)
            assertNull(lifecycle.activeAttempt.value)
        }

    @Test
    fun `same peer replacement cannot receive the prior ended callback`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            var sequence = 0
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-${++sequence}" },
                    nowMillis = { 100L },
                )
            // First outgoing attempt to a peer completes.
            lifecycle.acceptCallAttempt(request()).getOrThrow()
            lifecycle.recordCallConnected("attempt-1").getOrThrow()
            lifecycle.completeConnectedCall("attempt-1").getOrThrow()

            // Second call to the SAME peer becomes active.
            lifecycle.acceptCallAttempt(request()).getOrThrow()

            // A late ended observation for the PRIOR attempt must be a no-op for the
            // new attempt: attempt-2 stays active and open regardless.
            val lateEnded = lifecycle.completeConnectedCall("attempt-1")
            assertTrue(lateEnded.isSuccess) // idempotent against the already-finalized prior row
            assertEquals("attempt-2", lifecycle.activeAttempt.value?.callAttemptId)
            assertNull(dao.getByAttemptId("attempt-2")?.endedAt)
            assertEquals("CONNECTED_ENDED", dao.getByAttemptId("attempt-1")?.outcome)
        }

    @Test
    fun `shutdown waits for the one owned durable finalization`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val times = ArrayDeque(listOf(100L, 500L))
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { times.removeFirst() },
                )
            lifecycle
                .acceptCallAttempt(
                    CallAttemptRequest(
                        direction = CallAttemptDirection.INCOMING,
                        localIdentityHash = "local",
                        remoteIdentityHash = "remote",
                        codecProfileCode = null,
                    ),
                ).getOrThrow()

            lifecycle.shutdown().getOrThrow()

            val record = dao.getByAttemptId("attempt-1")
            assertEquals("INTERRUPTED", record?.outcome)
            assertEquals(500L, record?.endedAt)
            assertNull(lifecycle.activeAttempt.value)
        }

    @Test
    fun `accepted attempt is durable before exposure`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    serviceInstanceId = "service-1",
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { 1_234L },
                )

            val snapshot =
                lifecycle
                    .acceptCallAttempt(
                        CallAttemptRequest(
                            direction = CallAttemptDirection.OUTGOING,
                            localIdentityHash = "LOCAL-HASH",
                            remoteIdentityHash = "REMOTE-HASH",
                            codecProfileCode = 2,
                        ),
                    ).getOrThrow()

            assertEquals("attempt-1", snapshot.callAttemptId)
            assertEquals(
                CallHistoryEntity(
                    callAttemptId = "attempt-1",
                    localIdentityHash = "local-hash",
                    remoteIdentityHash = "remote-hash",
                    direction = "OUTGOING",
                    peerDisplayNameSnapshot = null,
                    codecProfileCode = 2,
                    attemptedAt = 1_234L,
                    ringingAt = null,
                    connectedAt = null,
                    endedAt = null,
                    outcome = null,
                    inferredEnding = false,
                    failureReason = null,
                    serviceInstanceId = "service-1",
                ),
                dao.inserted.single(),
            )
        }

    @Test
    fun `accepted attempt publishes exact event and live snapshot`() =
        runTest {
            val lifecycle =
                newLifecycle(
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { 1_234L },
                )
            val request = request()

            lifecycle.events.test {
                val snapshot = lifecycle.acceptCallAttempt(request).getOrThrow()

                assertEquals("attempt-1", snapshot.callAttemptId)
                assertEquals(snapshot, lifecycle.activeAttempt.value)
                assertEquals(CallLifecycleEvent.AttemptAccepted(snapshot), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `incoming ringing and missed outcome persist ordered exact milestones`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val times = ArrayDeque(listOf(100L, 150L, 250L, 400L))
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { times.removeFirst() },
                )
            val request =
                CallAttemptRequest(
                    direction = CallAttemptDirection.INCOMING,
                    localIdentityHash = "local",
                    remoteIdentityHash = "remote",
                    codecProfileCode = null,
                )

            lifecycle.acceptCallAttempt(request).getOrThrow()
            lifecycle.recordCallRinging("attempt-1").getOrThrow()
            lifecycle.recordCallRinging("attempt-1").getOrThrow() // duplicate no-op
            assertTrue(lifecycle.finalizeUnconnectedCall("attempt-1", UnconnectedOutcome.BUSY_REMOTE).isFailure)
            lifecycle
                .finalizeUnconnectedCall("attempt-1", UnconnectedOutcome.MISSED_INCOMING)
                .getOrThrow()

            val record = dao.getByAttemptId("attempt-1")
            assertEquals(150L, record?.ringingAt)
            assertNull(record?.connectedAt)
            assertEquals(400L, record?.endedAt)
            assertEquals("MISSED_INCOMING", record?.outcome)
            assertNull(lifecycle.activeAttempt.value)
        }

    @Test
    fun `failCallAttempt persists Columba observed failure reason`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val times = ArrayDeque(listOf(100L, 200L))
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { times.removeFirst() },
                )
            lifecycle.acceptCallAttempt(request()).getOrThrow()
            lifecycle.failCallAttempt("attempt-1", CallFailureReason.MICROPHONE_PERMISSION_DENIED).getOrThrow()

            val record = dao.getByAttemptId("attempt-1")
            assertEquals("FAILED", record?.outcome)
            assertEquals("MICROPHONE_PERMISSION_DENIED", record?.failureReason)
            assertNull(lifecycle.activeAttempt.value)
        }

    @Test
    fun `process restart interrupts prior service ownership`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val prior =
                newLifecycle(
                    callHistoryDao = dao,
                    serviceInstanceId = "service-old",
                    callAttemptIdFactory = { "attempt-old" },
                    nowMillis = { 100L },
                )
            prior.acceptCallAttempt(request()).getOrThrow()
            val recovered =
                newLifecycle(
                    callHistoryDao = dao,
                    serviceInstanceId = "service-new",
                    nowMillis = { 500L },
                )

            assertEquals(1, recovered.reconcileOpenAttempts().getOrThrow())

            val row = dao.getByAttemptId("attempt-old")
            assertEquals("INTERRUPTED", row?.outcome)
            assertEquals(500L, row?.endedAt)
            assertTrue(row?.inferredEnding == true)
            assertNull(recovered.activeAttempt.value)
        }

    @Test
    fun `discard removes exact unexposed attempt`() =
        runTest {
            val dao = RecordingCallHistoryDao()
            val lifecycle =
                newLifecycle(
                    callHistoryDao = dao,
                    callAttemptIdFactory = { "attempt-1" },
                    nowMillis = { 1_234L },
                )
            lifecycle.acceptCallAttempt(request()).getOrThrow()
            lifecycle.discardCallAttempt("attempt-1").getOrThrow()

            assertNull(lifecycle.activeAttempt.value)
            assertNull(dao.getByAttemptId("attempt-1"))
            assertTrue(dao.wasDeleted("attempt-1"))
        }

    @Test
    fun `each accepted retry receives a distinct attempt id`() =
        runTest {
            var sequence = 0
            val lifecycle =
                newLifecycle(
                    callAttemptIdFactory = { "attempt-${++sequence}" },
                    nowMillis = { 1_234L },
                )
            val first = lifecycle.acceptCallAttempt(request()).getOrThrow()
            lifecycle.discardCallAttempt(first.callAttemptId).getOrThrow()
            val second = lifecycle.acceptCallAttempt(request()).getOrThrow()

            assertNotEquals(first.callAttemptId, second.callAttemptId)
            assertEquals("attempt-1", first.callAttemptId)
            assertEquals("attempt-2", second.callAttemptId)
        }

    private fun newLifecycle(
        callHistoryDao: CallHistoryDao = RecordingCallHistoryDao(),
        serviceInstanceId: String = "service-test",
        callAttemptIdFactory: () -> String = { UUID.randomUUID().toString() },
        nowMillis: () -> Long = System::currentTimeMillis,
        peerDisplayNameProvider: suspend (String) -> String? = { null },
    ): ServiceCallLifecycle =
        ServiceCallLifecycle(
            callHistoryDao = callHistoryDao,
            serviceInstanceId = serviceInstanceId,
            callAttemptIdFactory = callAttemptIdFactory,
            nowMillis = nowMillis,
            peerDisplayNameProvider = peerDisplayNameProvider,
        )

    private fun request() =
        CallAttemptRequest(
            direction = CallAttemptDirection.OUTGOING,
            localIdentityHash = "local-hash",
            remoteIdentityHash = "remote-hash",
            codecProfileCode = 2,
        )
}
