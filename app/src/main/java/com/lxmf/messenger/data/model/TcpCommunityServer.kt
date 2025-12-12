package com.lxmf.messenger.data.model

/**
 * Represents a community TCP server for Reticulum networking.
 */
data class TcpCommunityServer(
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * List of known community TCP servers for Reticulum.
 */
object TcpCommunityServers {
    val servers: List<TcpCommunityServer> =
        listOf(
            TcpCommunityServer("Beleth RNS Hub", "rns.beleth.net", 4242),
            TcpCommunityServer("FireZen", "firezen.com", 4242),
            TcpCommunityServer("g00n.cloud Hub", "dfw.us.g00n.cloud", 6969),
            TcpCommunityServer("interloper node", "intr.cx", 4242),
            TcpCommunityServer("Jon's Node", "rns.jlamothe.net", 4242),
            TcpCommunityServer("noDNS1", "202.61.243.41", 4965),
            TcpCommunityServer("noDNS2", "193.26.158.230", 4965),
            TcpCommunityServer("NomadNode SEAsia TCP", "rns.jaykayenn.net", 4242),
            TcpCommunityServer("0rbit-Net", "93.95.227.8", 49952),
            TcpCommunityServer("Quad4 TCP Node 1", "rns.quad4.io", 4242),
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
}
