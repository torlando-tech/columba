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
 * The user's priority ordering is the primary axis: the wifi/cellular reachability check is
 * a single pass over the list in priority order, and `ANY` is reachable on every transport, so
 * a higher-ranked `ANY` always beats a lower-ranked transport-specific node.
 *
 *  1. **Highest-priority reachable node, priority order** — walk the list in priority order
 *     and return the first node whose restriction is reachable on the current transport.
 *     [NetworkRestriction.WIFI_ONLY] is reachable only on [CurrentTransport.WIFI_LIKE],
 *     [NetworkRestriction.CELLULAR_ONLY] only on [CurrentTransport.CELLULAR], and
 *     [NetworkRestriction.ANY] on every transport. Because the walk is strictly by priority,
 *     a higher-ranked `ANY` beats a lower-ranked transport-specific node: priority-1 `ANY`
 *     plus priority-2 `WIFI_ONLY` on wifi resolves to the `ANY` node. `ANY` also matches
 *     [CurrentTransport.NONE] and [CurrentTransport.UNKNOWN], so it is the in-list universal
 *     fallback and a brief network blip does not flap the router's destination.
 *  2. **Legacy** — if no node is reachable on the current transport (e.g. the list is empty,
 *     or it contains only transport-specific nodes that don't match), fall back to the
 *     existing nearest-by-hops auto-selection.
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
        // Single pass in priority order: the first node reachable on the current transport
        // wins. `ANY` is reachable on every transport, so a higher-ranked `ANY` beats a
        // lower-ranked transport-specific node (the user's ordering is the primary axis).
        val reachable = nodes.firstOrNull { it.restriction reachableOn currentTransport }
        if (reachable != null) {
            return PropagationNodeSelection.FromList(reachable.destinationHash)
        }
        // No node is reachable on this transport — fall back to nearest-by-hops auto-selection.
        return PropagationNodeSelection.LegacyAuto
    }

    /**
     * Whether [NetworkRestriction] is reachable on [transport]. `ANY` is reachable on every
     * transport (it is the in-list universal fallback, so a no-network blip keeps the relay
     * stable instead of flapping to nearest-by-hops). `WIFI_ONLY`/`CELLULAR_ONLY` are
     * reachable only on their matching transport.
     */
    private infix fun NetworkRestriction.reachableOn(transport: CurrentTransport): Boolean =
        when (this) {
            NetworkRestriction.WIFI_ONLY -> transport == CurrentTransport.WIFI_LIKE
            NetworkRestriction.CELLULAR_ONLY -> transport == CurrentTransport.CELLULAR
            NetworkRestriction.ANY -> true
        }
}
