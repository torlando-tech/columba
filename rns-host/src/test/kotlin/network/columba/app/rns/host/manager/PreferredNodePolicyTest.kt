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
    private val nAny = NetworkRestriction.ANY
    private val nWifi = NetworkRestriction.WIFI_ONLY
    private val nCell = NetworkRestriction.CELLULAR_ONLY

    @Test
    fun `empty list is always legacy auto regardless of transport`() {
        CurrentTransport.entries.forEach { t ->
            assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(emptyList(), t))
        }
    }

    @Test
    fun `single transport-specific node wins only on its matching transport`() {
        val wifi = listOf(PreferredNode("w", nWifi))
        val cell = listOf(PreferredNode("c", nCell))
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(wifi, CurrentTransport.WIFI_LIKE))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(wifi, CurrentTransport.CELLULAR))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(wifi, CurrentTransport.NONE))
        assertEquals(PropagationNodeSelection.FromList("c"), PreferredNodePolicy.resolve(cell, CurrentTransport.CELLULAR))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(cell, CurrentTransport.WIFI_LIKE))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(cell, CurrentTransport.NONE))
    }

    @Test
    fun `any node is the in-list fallback on every transport including none`() {
        val any = listOf(PreferredNode("a", nAny))
        CurrentTransport.entries.forEach { t ->
            assertEquals("ANY must match $t", PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(any, t))
        }
    }

    @Test
    fun `higher-ranked any beats a lower-ranked transport-specific node`() {
        // Priority is the primary axis: a higher-ranked ANY is reachable on wifi and wins,
        // so it beats the lower-ranked WIFI_ONLY node on wifi (the user's reported case).
        val list = listOf(PreferredNode("a", nAny), PreferredNode("w", nWifi))
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
        // ...and ANY also wins on a transport the WIFI_ONLY node does not match.
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.CELLULAR))
    }

    @Test
    fun `lower-ranked any is the fallback when a higher-ranked node does not match`() {
        // WIFI_ONLY@1 is not reachable on cellular, so the walk continues to ANY@2.
        val list = listOf(PreferredNode("w", nWifi), PreferredNode("a", nAny))
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.CELLULAR))
        // ...but on wifi the higher-ranked WIFI_ONLY node wins.
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
    }

    @Test
    fun `among matching transport-specific nodes the highest priority wins`() {
        val list = listOf(PreferredNode("w1", nWifi), PreferredNode("w2", nWifi))
        assertEquals(PropagationNodeSelection.FromList("w1"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
    }

    @Test
    fun `among any nodes the highest priority wins`() {
        val list = listOf(PreferredNode("a1", nAny), PreferredNode("a2", nAny))
        assertEquals(PropagationNodeSelection.FromList("a1"), PreferredNodePolicy.resolve(list, CurrentTransport.NONE))
    }

    @Test
    fun `sailboat scenario onboard wifi then ashore cellular`() {
        // Onboard node is wifi-only (preferred on boat wifi), internet node is ANY (ashore fallback).
        val list = listOf(PreferredNode("onboard", nWifi), PreferredNode("internet", nAny))
        assertEquals(PropagationNodeSelection.FromList("onboard"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
        assertEquals(PropagationNodeSelection.FromList("internet"), PreferredNodePolicy.resolve(list, CurrentTransport.CELLULAR))
        // A brief no-network blip stays on the stable ANY fallback, not legacy.
        assertEquals(PropagationNodeSelection.FromList("internet"), PreferredNodePolicy.resolve(list, CurrentTransport.NONE))
    }

    @Test
    fun `unknown transport has no transport-specific match so any is the fallback`() {
        val list = listOf(PreferredNode("w", nWifi), PreferredNode("a", nAny))
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.UNKNOWN))
    }

    @Test
    fun `list with only non-matching transport-specific nodes falls to legacy`() {
        val list = listOf(PreferredNode("w", nWifi), PreferredNode("c", nCell))
        // On a network with no default route, neither wifi nor cellular applies.
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(list, CurrentTransport.NONE))
        // On wifi, the wifi-specific one applies.
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
    }

    // ---- Unreachable-hash exclusion (failover) ----

    @Test
    fun `unreachable node is skipped and the next in priority order is chosen`() {
        val list = listOf(PreferredNode("a", nAny), PreferredNode("w", nWifi))
        // Highest-priority node "a" is currently unreachable → skipped; "w" is chosen on wifi.
        assertEquals(PropagationNodeSelection.FromList("w"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE, setOf("a")))
        // With no unreachable nodes, "a" wins as before.
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE))
    }

    @Test
    fun `all-unreachable list falls back to legacy auto`() {
        val list = listOf(PreferredNode("a", nAny), PreferredNode("b", nAny))
        assertEquals(PropagationNodeSelection.LegacyAuto, PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE, setOf("a", "b")))
        // A stale/irrelevant hash in the set that is not in the list does not affect resolution.
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE, setOf("ghost")))
    }

    @Test
    fun `unreachable lower-ranked node does not affect a higher-ranked reachable pick`() {
        val list = listOf(PreferredNode("a", nAny), PreferredNode("w", nWifi))
        // Lower-ranked "w" is unreachable, but higher-ranked "a" is reachable → "a" still wins.
        assertEquals(PropagationNodeSelection.FromList("a"), PreferredNodePolicy.resolve(list, CurrentTransport.WIFI_LIKE, setOf("w")))
    }
}
