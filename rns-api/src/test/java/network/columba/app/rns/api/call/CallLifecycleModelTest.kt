package network.columba.app.rns.api.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Model tests for the reduced call-history domain model.
 *
 * Verifies the reduced outcome set (no UNANSWERED/DROPPED/media causes), the
 * restricted failure-reason set, validation, and single-shot finalization.
 */
class CallLifecycleModelTest {
    // ===== Reduced terminal outcome set =====

    @Test
    fun `CallFinalOutcome contains exactly the nine reduced outcomes`() {
        val expected = setOf(
            CallFinalOutcome.CONNECTED_ENDED,
            CallFinalOutcome.MISSED_INCOMING,
            CallFinalOutcome.DECLINED_LOCAL,
            CallFinalOutcome.REJECTED_REMOTE,
            CallFinalOutcome.BUSY_REMOTE,
            CallFinalOutcome.CANCELLED_LOCAL,
            CallFinalOutcome.NOT_CONNECTED,
            CallFinalOutcome.FAILED,
            CallFinalOutcome.INTERRUPTED,
        )
        assertEquals(expected, CallFinalOutcome.values().toSet())
    }

    @Test
    fun `CallFinalOutcome cannot express UNANSWERED DROPPED or MEDIA_PIPELINE_ERROR`() {
        val names = CallFinalOutcome.values().map { it.name }
        assertFalse("UNANSWERED must not be a persisted outcome", names.contains("UNANSWERED"))
        assertFalse("DROPPED must not be a persisted outcome", names.contains("DROPPED"))
        assertFalse("MEDIA_PIPELINE_ERROR must not be a persisted outcome", names.contains("MEDIA_PIPELINE_ERROR"))
    }

    @Test
    fun `connected-only outcomes are not expressible on unconnected finalization`() {
        val unconnected = UnconnectedOutcome.values().map { it.toCallFinalOutcome() }.toSet()
        assertFalse(unconnected.contains(CallFinalOutcome.CONNECTED_ENDED))
        assertFalse(unconnected.contains(CallFinalOutcome.FAILED))
        assertFalse(unconnected.contains(CallFinalOutcome.INTERRUPTED))
    }

    @Test
    fun `UnconnectedOutcome maps to the expected persisted outcomes`() {
        val map = UnconnectedOutcome.values().associateWith { it.toCallFinalOutcome() }
        assertEquals(CallFinalOutcome.MISSED_INCOMING, map[UnconnectedOutcome.MISSED_INCOMING])
        assertEquals(CallFinalOutcome.DECLINED_LOCAL, map[UnconnectedOutcome.DECLINED_LOCAL])
        assertEquals(CallFinalOutcome.REJECTED_REMOTE, map[UnconnectedOutcome.REJECTED_REMOTE])
        assertEquals(CallFinalOutcome.BUSY_REMOTE, map[UnconnectedOutcome.BUSY_REMOTE])
        assertEquals(CallFinalOutcome.CANCELLED_LOCAL, map[UnconnectedOutcome.CANCELLED_LOCAL])
        assertEquals(CallFinalOutcome.NOT_CONNECTED, map[UnconnectedOutcome.NOT_CONNECTED])
    }

    // ===== Restricted failure reasons =====

    @Test
    fun `CallFailureReason contains exactly the Columba-observed prerequisite causes`() {
        val expected = setOf(
            CallFailureReason.NETWORK_UNAVAILABLE,
            CallFailureReason.MICROPHONE_PERMISSION_DENIED,
            CallFailureReason.LOCAL_IDENTITY_UNAVAILABLE,
            CallFailureReason.ANOTHER_CALL_ACTIVE,
            CallFailureReason.INVALID_PEER_IDENTITY,
            CallFailureReason.SERVICE_STARTUP_FAILURE,
            CallFailureReason.UNKNOWN_PREREQUISITE_FAILURE,
        )
        assertEquals(expected, CallFailureReason.values().toSet())
    }

    @Test
    fun `CallFailureReason has no transport or media causes`() {
        val names = CallFailureReason.values().map { it.name }
        assertFalse(names.any { it.startsWith("TRANSPORT") })
        assertFalse(names.any { it.contains("MEDIA") })
    }

    // ===== Attempt request validation =====

    @Test
    fun `CallAttemptRequest rejects blank local identity hash`() {
        try {
            request(direction = CallAttemptDirection.INCOMING, local = "   ")
            fail("Expected IllegalArgumentException for blank local identity")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `CallAttemptRequest rejects blank remote identity hash`() {
        try {
            request(direction = CallAttemptDirection.OUTGOING, remote = "")
            fail("Expected IllegalArgumentException for blank remote identity")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `CallAttemptRequest accepts a valid request`() {
        val r = request(direction = CallAttemptDirection.OUTGOING)
        assertEquals(CallAttemptDirection.OUTGOING, r.direction)
        assertEquals("local-hash", r.localIdentityHash)
        assertEquals("remote-hash", r.remoteIdentityHash)
        assertNull(r.codecProfileCode)
    }

    // ===== connectedAt is the established-callback observation time =====

    @Test
    fun `AttemptConnected carries the established-callback observation time`() {
        val event = CallLifecycleEvent.AttemptConnected(callAttemptId = "a-1", connectedAt = 1234L)
        assertEquals(1234L, event.connectedAt)
    }

    // ===== Single-shot finalization (no transition between terminal outcomes) =====

    @Test
    fun `AttemptFinalized is immutable and cannot transition to another outcome`() {
        val finalized = CallLifecycleEvent.AttemptFinalized(
            callAttemptId = "a-1",
            outcome = CallFinalOutcome.CONNECTED_ENDED,
            endedAt = 99L,
        )
        // A copy with a different outcome is a different value; the original is unchanged.
        val mutated = finalized.copy(outcome = CallFinalOutcome.INTERRUPTED)
        assertNotEquals(finalized, mutated)
        assertEquals(CallFinalOutcome.CONNECTED_ENDED, finalized.outcome)
    }

    @Test
    fun `matchesAcceptedRemote accepts null or matching snapshot identity`() {
        val snapshot = snapshot(remote = "REMOTE-HASH")
        assertTrue(matchesAcceptedRemote(snapshot, null))
        assertTrue(matchesAcceptedRemote(snapshot, "remote-hash"))
        assertFalse(matchesAcceptedRemote(snapshot, "other-hash"))
    }

    // ===== Helpers =====

    private fun request(
        direction: CallAttemptDirection,
        local: String = "local-hash",
        remote: String = "remote-hash",
    ) = CallAttemptRequest(
        direction = direction,
        localIdentityHash = local,
        remoteIdentityHash = remote,
        codecProfileCode = null,
    )

    private fun snapshot(remote: String) = CallAttemptSnapshot(
        callAttemptId = "a-1",
        direction = CallAttemptDirection.INCOMING,
        localIdentityHash = "local-hash",
        remoteIdentityHash = remote,
        codecProfileCode = null,
        attemptedAt = 1L,
    )
}
