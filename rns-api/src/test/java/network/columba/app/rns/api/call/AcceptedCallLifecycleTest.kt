package network.columba.app.rns.api.call

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AcceptedCallLifecycleTest {

    private fun request(direction: CallAttemptDirection = CallAttemptDirection.OUTGOING) =
        CallAttemptRequest(
            direction = direction,
            localIdentityHash = "local",
            remoteIdentityHash = "remote",
            codecProfileCode = 2,
        )

    private fun TestScope.newLifecycle(
        recorder: CallLifecycleRecorder,
    ): Pair<AcceptedCallLifecycle, CallLifecycleRecorder> {
        val lifecycle =
            AcceptedCallLifecycle(
                recorder = recorder,
                scope = backgroundScope,
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                retryDelayMillis = 1L,
            )
        return lifecycle to recorder
    }

    @Test
    fun `incoming call is durable and ringing before adapter exposure`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            var exposed: CallAttemptSnapshot? = null

            val result =
                lifecycle.admitIncoming(request(CallAttemptDirection.INCOMING)) {
                    exposed = it
                }

            assertTrue(result.isSuccess)
            assertTrue(recorder.operations.contains("ringing"))
            assertTrue(recorder.operations.indexOf("ringing") < recorder.operations.indexOf("expose") || !recorder.operations.contains("expose"))
            assertEquals("attempt-1", exposed?.callAttemptId)
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    @Test
    fun `duplicate connected observations are idempotent`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            lifecycle.observeConnected("attempt-1", "remote")
            runCurrent()
            lifecycle.observeConnected("attempt-1", "remote")
            runCurrent()

            assertEquals(1, recorder.connectedAttempts.count { it == "attempt-1" })
        }

    @Test
    fun `replacement admission rejected until terminal persistence completes`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            val blocked = lifecycle.admitOutgoing(request()) {}
            assertTrue(blocked.isFailure)

            lifecycle.observeEnded("attempt-1", "remote")
            runCurrent()

            // After terminal finalization the owner is released and a replacement may be admitted.
            lifecycle.admitOutgoing(request()) {}
            assertTrue(lifecycle.activeAttempt?.callAttemptId == "attempt-2")
        }

    @Test
    fun `same peer replacement cannot receive the prior ended callback`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()
            lifecycle.observeConnected("attempt-1", "remote")
            runCurrent()
            lifecycle.observeEnded("attempt-1", "remote")
            runCurrent()
            assertNull(lifecycle.activeAttempt)

            lifecycle.admitOutgoing(request()) {}.getOrThrow()
            assertEquals("attempt-2", lifecycle.activeAttempt?.callAttemptId)

            // A late ended callback for the PRIOR attempt must not finalize attempt-2.
            lifecycle.observeEnded("attempt-1", "remote")
            runCurrent()
            assertEquals("attempt-2", lifecycle.activeAttempt?.callAttemptId)
            // attempt-2 was never finalized by the stale attempt-1 ended callback.
            assertEquals(0, recorder.completedAttempts.count { it == "attempt-2" })
        }

    @Test
    fun `shutdown finalizes the one owned attempt`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            val result = lifecycle.shutdown()

            assertTrue(result.isSuccess)
            assertNull(lifecycle.activeAttempt)
            assertEquals(1, recorder.interruptAttempts.size)
        }

    @Test
    fun `generic ended maps connected call to connected ended`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            lifecycle.observeConnected("attempt-1", "remote")
            runCurrent()
            lifecycle.observeEnded("attempt-1", "remote")
            runCurrent()

            assertEquals(1, recorder.completedAttempts.size)
            assertNull(lifecycle.activeAttempt)
        }

    @Test
    fun `generic ended maps unconnected incoming to missed and outgoing to not connected`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (incoming, _) = newLifecycle(recorder)
            incoming.admitIncoming(request(CallAttemptDirection.INCOMING)) {}.getOrThrow()
            incoming.observeEnded("attempt-1", "remote")
            runCurrent()
            assertNull(incoming.activeAttempt)
            assertTrue(recorder.finalizedOutcomes.contains(UnconnectedOutcome.MISSED_INCOMING))

            // Outgoing generic pre-connection ending -> NOT_CONNECTED.
            val recorder2 = RecordingLifecycleRecorder()
            val (outgoing, _) = newLifecycle(recorder2)
            outgoing.admitOutgoing(request()) {}.getOrThrow()
            outgoing.observeEnded("attempt-1", "remote")
            runCurrent()
            assertNull(outgoing.activeAttempt)
            assertTrue(recorder2.finalizedOutcomes.contains(UnconnectedOutcome.NOT_CONNECTED))
        }

    @Test
    fun `busy and rejected apply only to owned outgoing pre-connection attempt`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()
            lifecycle.observeBusy("attempt-1")
            runCurrent()
            assertEquals(listOf(UnconnectedOutcome.BUSY_REMOTE), recorder.finalizedOutcomes)
            assertNull(lifecycle.activeAttempt)

            // Busy for a mismatched id is ignored.
            val recorder2 = RecordingLifecycleRecorder()
            val (lifecycle2, _) = newLifecycle(recorder2)
            lifecycle2.admitIncoming(request(CallAttemptDirection.INCOMING)) {}.getOrThrow()
            lifecycle2.observeBusy("attempt-1") // incoming busy is not allowed
            runCurrent()
            assertTrue(recorder2.finalizedOutcomes.isEmpty())
            assertEquals("attempt-1", lifecycle2.activeAttempt?.callAttemptId)
        }

    @Test
    fun `local cancel finalizes unconnected outgoing call`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            lifecycle.recordLocalEndIntent()
            runCurrent()

            assertTrue(recorder.finalizedOutcomes.contains(UnconnectedOutcome.CANCELLED_LOCAL))
            assertNull(lifecycle.activeAttempt)
        }

    @Test
    fun `remote identity mismatch observation is ignored`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            lifecycle.observeConnected("attempt-1", "different-remote")
            runCurrent()

            assertTrue(recorder.connectedAttempts.isEmpty())
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    @Test
    fun `failCallAttempt persists Columba observed reason`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, _) = newLifecycle(recorder)
            lifecycle.admitOutgoing(request()) {}.getOrThrow()

            assertTrue(lifecycle.failCallAttempt("attempt-1", CallFailureReason.NETWORK_UNAVAILABLE))
            runCurrent()

            assertTrue(recorder.failed.contains(CallFailureReason.NETWORK_UNAVAILABLE))
            assertNull(lifecycle.activeAttempt)
        }
}
