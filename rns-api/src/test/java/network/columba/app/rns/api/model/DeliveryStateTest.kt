package network.columba.app.rns.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [DeliveryState] codec and its terminal-success policy.
 *
 * The encoded strings are a persistence contract: Room databases created
 * before the sealed type existed contain exactly these values, and the
 * maestro logcat harness greps their uppercased forms. Changing any encoded
 * value is a breaking change — these tests pin them.
 */
class DeliveryStateTest {
    private val allStates =
        listOf(
            DeliveryState.Pending,
            DeliveryState.Sent,
            DeliveryState.Delivered,
            DeliveryState.Propagated,
            DeliveryState.RetryingViaPropagation,
            DeliveryState.Failed,
        )

    @Test
    fun `encode produces the legacy persistence strings`() {
        assertEquals("pending", DeliveryState.Pending.encode())
        assertEquals("sent", DeliveryState.Sent.encode())
        assertEquals("delivered", DeliveryState.Delivered.encode())
        assertEquals("propagated", DeliveryState.Propagated.encode())
        assertEquals("retrying_propagated", DeliveryState.RetryingViaPropagation.encode())
        assertEquals("failed", DeliveryState.Failed.encode())
    }

    @Test
    fun `decode inverts encode for every state`() {
        for (state in allStates) {
            assertEquals(state, DeliveryState.decode(state.encode()))
        }
    }

    @Test
    fun `encoded forms are pairwise distinct`() {
        assertEquals(allStates.size, allStates.map { it.encode() }.toSet().size)
    }

    @Test
    fun `decode returns null for unrecognized legacy values`() {
        assertNull(DeliveryState.decode("unknown"))
        assertNull(DeliveryState.decode(""))
        assertNull(DeliveryState.decode("received"))
        // Decoding is exact; case variants are not valid persisted forms.
        assertNull(DeliveryState.decode("Delivered"))
    }

    @Test
    fun `terminal success covers sent, propagated, delivered (Issue 257)`() {
        assertTrue(DeliveryState.Sent.isTerminalSuccess)
        assertTrue(DeliveryState.Propagated.isTerminalSuccess)
        assertTrue(DeliveryState.Delivered.isTerminalSuccess)
        assertFalse(DeliveryState.Pending.isTerminalSuccess)
        assertFalse(DeliveryState.RetryingViaPropagation.isTerminalSuccess)
        assertFalse(DeliveryState.Failed.isTerminalSuccess)
    }
}
