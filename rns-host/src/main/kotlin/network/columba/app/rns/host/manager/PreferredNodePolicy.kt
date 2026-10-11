package network.columba.app.rns.host.manager

import network.columba.app.rns.api.model.NetworkRestriction

/**
 * A single preferred propagation node as the selection policy sees it.
 *
 * The caller passes an enabled-only, priority-ascending list (index 0 = highest
 * priority) so the policy can be a pure function over an ordered list. `restriction`
 * is the `NetworkRestriction` the user set for the node; it is the wifi/cellular
 * layer that sits on top of the user's priority ordering.
 */
data class PreferredNode(
    val destinationHash: String,
    val restriction: NetworkRestriction,
)

/**
 * Outcome of resolving which propagation node to use for the current transport.
 */
sealed class PropagationNodeSelection {
    /**
     * A node from the preferred list won. Set by the caller onto the contact flagged
     * [network.columba.app.data.db.entity.ContactEntity.isMyRelay].
     */
    data class FromList(val destinationHash: String) : PropagationNodeSelection()

    /**
     * No list node applied to the current transport; fall back to the existing
     * nearest-by-hops auto-selection (today's behavior).
     */
    data object LegacyAuto : PropagationNodeSelection()
}

/**
 * Pure selection policy for the prioritized preferred-propagation-node list.
 *
 * This is a *selection* problem, not a concurrency one: the LXMF router holds a single
 * outbound propagation node, so exactly one list entry becomes active at a time. The
 * wifi/cellular layer ("layer the wifi/cellular on top of the list") is implemented as a
 * two-pass ladder over the user's priority order:
 *
 *  1. **Transport-specific, priority order** — the highest-priority node whose restriction
 *     matches the current transport ([NetworkRestriction.WIFI_ONLY] on
 *     [CurrentTransport.WIFI_LIKE], [NetworkRestriction.CELLULAR_ONLY] on
 *     [CurrentTransport.CELLULAR]). A generic `ANY` node ranked above a transport-specific
 *     node does NOT win here — the transport-specific layer sits on top of the ordering.
 *  2. **`ANY`, priority order** — if no transport-specific node applies, the highest-priority
 *     `ANY` node. `ANY` is the in-list universal fallback: it matches every transport
 *     including [CurrentTransport.NONE], so a brief network blip does not flap the router's
 *     destination.
 *  3. **Legacy** — if neither applies (e.g. the list is empty, or it contains only
 *     transport-specific nodes that don't match), fall back to the existing
 *     nearest-by-hops auto-selection.
 *
 * Deliberately independent of [InterfaceTransportFilter.passesTransport]: that truth table
 * disables ALL IP interfaces on [CurrentTransport.NONE] (a blip drops every IP interface).
 * For node *selection* we want the opposite — a stable `ANY` fallback — so the two tables
 * intentionally diverge at the `ANY`/`NONE` cell. See the `preferred-node-priority` spec.
 */
object PreferredNodePolicy {
    /**
     * Resolve the active propagation node for [currentTransport] from an enabled-only,
     * priority-ascending [nodes] list.
     */
    fun resolve(
        nodes: List<PreferredNode>,
        currentTransport: CurrentTransport,
    ): PropagationNodeSelection {
        // Pass 1: transport-specific, priority order. `ANY` is excluded here so it cannot
        // shadow a lower-ranked but transport-matched node.
        val transportSpecific = nodes.firstOrNull { it.restriction matchesTransport currentTransport }
        if (transportSpecific != null) {
            return PropagationNodeSelection.FromList(transportSpecific.destinationHash)
        }
        // Pass 2: the universal in-list fallback.
        val any = nodes.firstOrNull { it.restriction == NetworkRestriction.ANY }
        if (any != null) {
            return PropagationNodeSelection.FromList(any.destinationHash)
        }
        // Pass 3: nothing applied — fall back to nearest-by-hops auto-selection.
        return PropagationNodeSelection.LegacyAuto
    }

    /**
     * Whether a transport-specific [NetworkRestriction] matches [transport]. `ANY` is
     * intentionally `false` here — it is the pass-2 catch-all, not a transport match.
     */
    private infix fun NetworkRestriction.matchesTransport(transport: CurrentTransport): Boolean =
        when (this) {
            NetworkRestriction.WIFI_ONLY -> transport == CurrentTransport.WIFI_LIKE
            NetworkRestriction.CELLULAR_ONLY -> transport == CurrentTransport.CELLULAR
            NetworkRestriction.ANY -> false
        }
}
