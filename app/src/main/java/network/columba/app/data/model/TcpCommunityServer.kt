package network.columba.app.data.model

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
 */
object TcpCommunityServers {
    val servers: List<TcpCommunityServer> =
        listOf(
            TcpCommunityServer("rns.kin.earth", "rns.kin.earth", 4242),
            TcpCommunityServer("RNS Sofia", "193.193.182.147", 4242),
            TcpCommunityServer("noDNS1", "202.61.243.41", 4965),
            TcpCommunityServer("noDNS2", "193.26.158.230", 4965),
            TcpCommunityServer("interloper node", "intr.cx", 4242),
            TcpCommunityServer("Jon's Node", "rns.jlamothe.net", 4242),
            TcpCommunityServer("R-Net TCP", "istanbul.reserve.network", 9034),
            TcpCommunityServer("RNS bnZ-NODE01", "node01.rns.bnz.se", 4242),
            TcpCommunityServer("RNS_Transport_US-East", "45.77.109.86", 4965),
            TcpCommunityServer("SparkN0de", "aspark.uber.space", 44860),
            TcpCommunityServer("Beleth RNS Hub", "rns.beleth.net", 4242),
            TcpCommunityServer("g00n.cloud Hub", "dfw.us.g00n.cloud", 6969)
        )

    /**
     * Pick a random server from the list (uniformly, independently).
     *
     * @return A random community server.
     * @throws NoSuchElementException if the list is empty.
     */
    fun randomServer(): TcpCommunityServer = servers.random()
}
