package network.columba.app.rns.api.util

/**
 * Announce aspect strings Columba tracks.
 *
 * Reticulum destinations identify themselves by an aspect (e.g.
 * `"lxmf.delivery"`) — the same string is the source of truth for routing,
 * destination construction, `Transport.registerKnownAspect` calls, and the
 * announce-handler aspect filter on both backends. Previously each backend
 * + the shared `AppDataParser` / `NodeType.fromAspect` listed the literals
 * independently; this object centralises them so the protocol-leaf strings
 * cannot drift.
 *
 * Python-side, `event_bridge.py._KNOWN_ASPECTS` is a parallel tuple of the
 * same strings — kept in sync by hand because Chaquopy can't read kotlin
 * constants from Python. The strings here are the canonical reference.
 */
object Aspects {
    /** Peer-to-peer LXMF messaging destination (`<identity>.lxmf.delivery`). */
    const val LXMF_DELIVERY = "lxmf.delivery"

    /** LXMF propagation/store-and-forward node. */
    const val LXMF_PROPAGATION = "lxmf.propagation"

    /** NomadNet content node (Sites, pages, files). */
    const val NOMADNET_NODE = "nomadnetwork.node"

    /** LXST telephony / voice call destination. */
    const val LXST_TELEPHONY = "lxst.telephony"

    /** Every aspect Columba tracks — handy for set-membership tests. */
    val ALL: Set<String> = setOf(LXMF_DELIVERY, LXMF_PROPAGATION, NOMADNET_NODE, LXST_TELEPHONY)
}
