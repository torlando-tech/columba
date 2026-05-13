package network.columba.app.rns.api.model

sealed class LinkStatus {
    object PENDING : LinkStatus()

    object ACTIVE : LinkStatus()

    object STALE : LinkStatus()

    object CLOSED : LinkStatus()
}
