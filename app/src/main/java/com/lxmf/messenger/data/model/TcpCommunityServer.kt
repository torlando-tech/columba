package com.lxmf.messenger.data.model

/**
 * Represents a community TCP server for Reticulum networking.
 *
 * @param name User-friendly name for the server
 * @param host Hostname or IP address
 * @param port TCP port number
 * @param isBootstrap When true, this server is recommended as a bootstrap interface.
 *                    Bootstrap interfaces auto-detach once sufficient discovered
 *                    interfaces are connected (RNS 1.1.0+ feature).
 */
data class TcpCommunityServer(
    val name: String,
    val host: String,
    val port: Int,
    val isBootstrap: Boolean = false,
)

/**
 * List of known community TCP servers for Reticulum.
 *
 * Selected servers are marked as bootstrap candidates based on:
 * - Reputation in the community
 * - Long-term reliability
 * - Geographic distribution
 */
object TcpCommunityServers {
    val servers: List<TcpCommunityServer> =
        listOf(
            // Bootstrap servers: well-established, reliable nodes for initial network discovery
            TcpCommunityServer("Beleth RNS Hub", "rns.beleth.net", 4242, isBootstrap = true),
            TcpCommunityServer("Quad4 TCP Node 1", "rns.quad4.io", 4242, isBootstrap = true),
            TcpCommunityServer("FireZen", "firezen.com", 4242, isBootstrap = true),
            // Regular community servers
            TcpCommunityServer("g00n.cloud Hub", "dfw.us.g00n.cloud", 6969),
            TcpCommunityServer("interloper node", "intr.cx", 4242),
            TcpCommunityServer(
                "interloper node (Tor)",
                "intrcxv4fa72e5ovler5dpfwsiyuo34tkcwfy5snzstxkhec75okowqd.onion",
                4242,
            ),
            TcpCommunityServer("Jon's Node", "rns.jlamothe.net", 4242),
            TcpCommunityServer("noDNS1", "202.61.243.41", 4965),
            TcpCommunityServer("noDNS2", "193.26.158.230", 4965),
            TcpCommunityServer("NomadNode SEAsia TCP", "rns.jaykayenn.net", 4242),
            TcpCommunityServer("0rbit-Net", "93.95.227.8", 49952),
            TcpCommunityServer("Quad4 TCP Node 2", "rns2.quad4.io", 4242),
            TcpCommunityServer("Quortal TCP Node", "reticulum.qortal.link", 4242),
            TcpCommunityServer("R-Net TCP", "istanbul.reserve.network", 9034),
            TcpCommunityServer("RNS bnZ-NODE01", "node01.rns.bnz.se", 4242),
            TcpCommunityServer("RNS COMSEC-RD", "80.78.23.249", 4242),
            TcpCommunityServer("RNS HAM RADIO", "135.125.238.229", 4242),
            TcpCommunityServer("RNS Testnet StoppedCold", "rns.stoppedcold.com", 4242),
            TcpCommunityServer("RNS_Transport_US-East", "45.77.109.86", 4965),
            TcpCommunityServer("SparkN0de", "aspark.uber.space", 44860),
            TcpCommunityServer("Tidudanka.com", "reticulum.tidudanka.com", 37500),
        )

    /**
     * Get only servers marked as bootstrap candidates.
     */
    val bootstrapServers: List<TcpCommunityServer>
        get() = servers.filter { it.isBootstrap }
}
