package network.columba.app.rns.api.model

data class Link(
    val id: String,
    val destination: Destination,
    val status: LinkStatus,
    val establishedAt: Long,
    val rtt: Float?,
)
