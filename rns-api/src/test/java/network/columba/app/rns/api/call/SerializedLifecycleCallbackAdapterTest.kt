/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.api.call

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Symmetric tests for [SerializedLifecycleCallbackAdapter] — the single callback
 * contract shared across the native and Python backends.
 *
 * Confirms each LXST event routes into the shared serialized owner, and that
 * callback identity mismatches fail closed WITHOUT mutating history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerializedLifecycleCallbackAdapterTest {

    private fun request(direction: CallAttemptDirection = CallAttemptDirection.OUTGOING) =
        CallAttemptRequest(
            direction = direction,
            localIdentityHash = "local",
            remoteIdentityHash = "remote",
            codecProfileCode = 2,
        )

    private fun TestScope.newLifecycle(
        recorder: CallLifecycleRecorder,
    ): AcceptedCallLifecycle {
        return AcceptedCallLifecycle(
            recorder = recorder,
            scope = backgroundScope,
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            retryDelayMillis = 1L,
        )
    }

    private fun TestScope.newAdapter(
        recorder: RecordingLifecycleRecorder,
    ): Pair<AcceptedCallLifecycle, SerializedLifecycleCallbackAdapter> {
        val lifecycle = newLifecycle(recorder)
        return lifecycle to SerializedLifecycleCallbackAdapter(lifecycle)
    }

    // ---------- established ----------

    @Test
    fun `established routes to connected for the owned remote identity`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onEstablished("remote")

            runCurrent()
            assertTrue(recorder.connectedAttempts.contains("attempt-1"))
        }

    @Test
    fun `established with mismatched identity fails closed`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onEstablished("someone-else")

            runCurrent()
            assertFalse(recorder.operations.contains("connected"))
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    // ---------- generic ended ----------

    @Test
    fun `generic ended completes a connected call`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }
            adapter.onEstablished("remote")
            runCurrent()

            adapter.onGenericEnded("remote")

            runCurrent()
            assertTrue(recorder.completedAttempts.contains("attempt-1"))
        }

    @Test
    fun `generic ended with mismatched identity fails closed`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onGenericEnded("someone-else")

            runCurrent()
            assertFalse(recorder.operations.contains("complete"))
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    // ---------- busy ----------

    @Test
    fun `busy on outgoing pre-connect finalizes as busy remote`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onBusy("remote")

            runCurrent()
            assertTrue(recorder.finalizedOutcomes.contains(UnconnectedOutcome.BUSY_REMOTE))
        }

    @Test
    fun `busy with mismatched identity fails closed`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onBusy("someone-else")

            runCurrent()
            assertFalse(recorder.operations.contains("finalize"))
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    @Test
    fun `busy on incoming attempt is ignored`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitIncoming(request(CallAttemptDirection.INCOMING)) { }

            adapter.onBusy("remote")

            runCurrent()
            assertFalse(recorder.operations.contains("finalize"))
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    // ---------- rejected ----------

    @Test
    fun `rejected on outgoing pre-connect finalizes as rejected remote`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onRejected("remote")

            runCurrent()
            assertTrue(recorder.finalizedOutcomes.contains(UnconnectedOutcome.REJECTED_REMOTE))
        }

    @Test
    fun `rejected with mismatched identity fails closed`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onRejected("someone-else")

            runCurrent()
            assertFalse(recorder.operations.contains("finalize"))
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
        }

    // ---------- local decline / cancel ----------

    @Test
    fun `local decline on incoming finalizes as declined local`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitIncoming(request(CallAttemptDirection.INCOMING)) { }

            adapter.onLocalDecline()

            runCurrent()
            assertTrue(recorder.finalizedOutcomes.contains(UnconnectedOutcome.DECLINED_LOCAL))
        }

    @Test
    fun `local cancel on outgoing finalizes as cancelled local`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onLocalCancel()

            runCurrent()
            assertTrue(recorder.finalizedOutcomes.contains(UnconnectedOutcome.CANCELLED_LOCAL))
        }

    // ---------- ringing is informational ----------

    @Test
    fun `ringing does not mutate history`() =
        runTest {
            val recorder = RecordingLifecycleRecorder()
            val (lifecycle, adapter) = newAdapter(recorder)
            lifecycle.admitOutgoing(request()) { }

            adapter.onRinging("remote")

            runCurrent()
            assertEquals("attempt-1", lifecycle.activeAttempt?.callAttemptId)
            assertFalse(recorder.operations.any { it != "accept" })
        }
}
