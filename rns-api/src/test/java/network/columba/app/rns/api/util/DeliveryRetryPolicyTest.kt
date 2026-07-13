package network.columba.app.rns.api.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for the shared try-propagation-on-fail decision. Both backend
 * adapters route their failure callbacks through this predicate, so these
 * cases pin the retry semantics for the python and kotlin flavors at once.
 */
class DeliveryRetryPolicyTest {
    @Test
    fun `retries only when opted in, not already propagated, and a node is configured`() {
        // (tryPropagationOnFail, desiredMethodIsPropagated, propagationNodeConfigured) -> decision
        val cases =
            listOf(
                Triple(true, false, true) to true, // the one retry case
                Triple(true, false, false) to false, // no node -> plain failure
                Triple(true, true, true) to false, // already retried (method flipped) -> give up
                Triple(true, true, false) to false,
                Triple(false, false, true) to false, // caller never opted in
                Triple(false, false, false) to false,
                Triple(false, true, true) to false,
                Triple(false, true, false) to false,
            )
        for ((inputs, expected) in cases) {
            val (optIn, isPropagated, nodeConfigured) = inputs
            assertEquals(
                "shouldRetryViaPropagation($optIn, $isPropagated, $nodeConfigured)",
                expected,
                DeliveryRetryPolicy.shouldRetryViaPropagation(optIn, isPropagated, nodeConfigured),
            )
        }
    }
}
