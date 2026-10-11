package network.columba.app.rns.host.manager

import network.columba.app.rns.api.model.NetworkRestriction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive truth table for [PreferredNodePolicy]. Covers the two-pass ladder
 * (transport-specific, then ANY, then legacy) across the full
 * `NetworkRestriction × CurrentTransport` matrix and priority-ordering edge cases.
 */
class PreferredNodePolicyTest {
    private val ANY = NetworkRestriction.ANY
    private val WIFI = NetworkRestriction.WIFI_ONLY
    private val CELL = NetworkRestriction.CELLULAR_ONLY

    @Test
    fun `empty list is always legacy auto regardless of transport`() {
        CurrentTransport.entries.forEach { t ->
            assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(emptyList(), t))
        }
    }

    @Test
    fun `single transport-specific node wins only on its matching transport`() {
        val wifi = listOf(PreferredNode("w", WIFI))
        val cell = listOf(PreferredNode("c", CELL))
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(wifi, CurrentTransport.WIFI_LIKE))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(wifi, CurrentTransport.CELLULAR))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(wifi, CurrentTransport.NONE))
        assertEquals(PropagationNodeSelection.FromList("c"), PreferredNodePolicy.resolve(cell, CurrentTransport.CELLULAR))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(cell, CurrentTransport.WIFI_LIKE))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(cell, CurrentTransport.NONE))
    }

    @Test
    fun `any node is the in-list fallback on every transport including none`() {
        val any = listOf(PreferredNode("a", ANY))
        CurrentTransport.entries.forEach { t ->
            assertEquals("ANY must match $t", PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(any, t))
        }
    }

    @Test
    fun `transport-specific node beats a higher-ranked any node`() {
        // The wifi/cellular layer sits ON TOP of the user's ordering.
        val list = listOf(PreferredNode("a", ANY), PreferredNode("w", WIFI))
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
        // ...but on a non-matching transport the ANY node is the fallback.
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.CELLULAR))
    }

    @Test
    fun `any node is preferred over a lower-ranked non-matching transport-specific node`() {
        // On cellular, a WIFI_ONLY node does not apply; the lower-ranked ANY is the fallback.
        val list = listOf(PreferredNode("w", WIFI), PreferredNode("a", ANY))
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.CELLULAR))
    }

    @Test
    fun `among matching transport-specific nodes the highest priority wins`() {
        val list = listOf(PreferredNode("w1", WIFI), PreferredNode("w2", WIFI))
        assertEquals(PropagationNodeSelection.FromList("w1"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
    }

    @Test
    fun `among any nodes the highest priority wins`() {
        val list = listOf(PreferredNode("a1", ANY), PreferredNode("a2", ANY))
        assertEquals(PropagationNodeSelection.FromList("a1"), PreferredNodePolicy.resolve(list, CurrentTransport.NONE))
    }

    @Test
    fun `sailboat scenario onboard wifi then ashore cellular`() {
        // Onboard node is wifi-only (preferred on boat wifi), internet node is ANY (ashore fallback).
        val list = listOf(PreferredNode("onboard", WIFI), PreferredNode("internet", ANY))
        assertEquals(PropagationNodeSelection.FromList("onboard"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
        assertEquals(PropagationNodeSelection.FromList("internet"), PreferredNodePolicy.resolve(list, CurrentTransport.CELLULAR))
        // A brief no-network blip stays on the stable ANY fallback, not legacy.
        assertEquals(PropagationNodeSelection.FromList("internet"), PreferredNodePolicy.resolve(list, CurrentTransport.NONE))
    }

    @Test
    fun `unknown transport has no transport-specific match so any is the fallback`() {
        val list = listOf(PreferredNode("w", WIFI), PreferredNode("a", ANY))
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.UNKNOWN))
    }

    @Test
    fun `list with only non-matching transport-specific nodes falls to legacy`() {
        val list = listOf(PreferredNode("w", WIFI), PreferredNode("c", CELL))
        // On a network with no default route, neither wifi nor cellular applies.
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(list, CurrentTransport.NONE))
        // On wifi, the wifi-specific one applies.
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
    }
}
