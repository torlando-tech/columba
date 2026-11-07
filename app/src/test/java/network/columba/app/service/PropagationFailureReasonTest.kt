package network.columba.app.service

import network.columba.app.rns.api.model.PropagationState
import network.columba.app.service.PropagationFailureReason.Explanation
import network.columba.app.service.PropagationFailureReason.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PropagationFailureReason] — the cross-backend mapping from a
 * [PropagationState] to the "why did this relay go unreachable" classification.
 *
 * The `stateName` axis is the stable one (both RNS backends emit the same failure names),
 * while `state` differs by backend (native = enum ordinal 8/9/10, python = 0xf0+).
 */
class PropagationFailureReasonTest {
    private fun state(name: String, code: Int) = PropagationState(code, name, 0f, 0)

    // ---- isUnreachable (the cross-backend predicate) ----

    @Test
    fun `failed no_path and no_link are unreachable`() {
        assertTrue(PropagationFailureReason.isUnreachable(state("failed", 0xff)))
        assertTrue(PropagationFailureReason.isUnreachable(state("no_path", 9)))
        assertTrue(PropagationFailureReason.isUnreachable(state("no_link", 10)))
    }

    @Test
    fun `in-flight and success states are not unreachable`() {
        assertFalse(PropagationFailureReason.isUnreachable(state("idle", 0)))
        assertFalse(PropagationFailureReason.isUnreachable(state("path_requested", 1)))
        assertFalse(PropagationFailureReason.isUnreachable(state("link_establishing", 2)))
        assertFalse(PropagationFailureReason.isUnreachable(state("complete", 7)))
        assertFalse(PropagationFailureReason.isUnreachable(state("receiving", 5)))
        // Native "listing_messages" / "requesting_messages" are in-flight, not failure.
        assertFalse(PropagationFailureReason.isUnreachable(state("listing_messages", 4)))
        assertFalse(PropagationFailureReason.isUnreachable(state("requesting_messages", 5)))
    }

    // ---- explanation (human-readable reason) ----

    @Test
    fun `specific state names map to specific reasons`() {
        assertEquals(Explanation(Reason.NO_PATH), PropagationFailureReason.explanation(state("no_path", 9)))
        assertEquals(Explanation(Reason.NO_LINK), PropagationFailureReason.explanation(state("no_link", 10)))
    }

    @Test
    fun `generic failed is disambiguated by the raw code across both backend code spaces`() {
        // Python 0xf0+ terminals.
        assertEquals(Explanation(Reason.NO_PATH), PropagationFailureReason.explanation(state("failed", 0xf0)))
        assertEquals(Explanation(Reason.CONNECTION_FAILED), PropagationFailureReason.explanation(state("failed", 0xf1)))
        assertEquals(Explanation(Reason.TRANSFER_FAILED), PropagationFailureReason.explanation(state("failed", 0xf2)))
        assertEquals(Explanation(Reason.IDENTITY_NOT_RECEIVED), PropagationFailureReason.explanation(state("failed", 0xf3)))
        assertEquals(Explanation(Reason.ACCESS_DENIED), PropagationFailureReason.explanation(state("failed", 0xf4)))
        // Native LXMRouter$PropagationTransferState ordinals: FAILED(8), NO_PATH(9), NO_LINK(10).
        assertEquals(Explanation(Reason.RELAY_UNREACHABLE), PropagationFailureReason.explanation(state("failed", 8)))
        assertEquals(Explanation(Reason.NO_PATH), PropagationFailureReason.explanation(state("failed", 9)))
        assertEquals(Explanation(Reason.NO_LINK), PropagationFailureReason.explanation(state("failed", 10)))
    }

    @Test
    fun `unknown failed code falls back to a message that preserves the code`() {
        assertEquals(Explanation(Reason.RELAY_UNREACHABLE_CODE, listOf("123")), PropagationFailureReason.explanation(state("failed", 0x123)))
    }

    @Test
    fun `unknown non-failure state name yields a generic sync-failed message`() {
        assertEquals(Explanation(Reason.SYNC_FAILED, listOf("weird")), PropagationFailureReason.explanation(state("weird", 0)))
    }
}
