package network.columba.app.reticulum.model

sealed class LinkStatus {
    object PENDING : LinkStatus()

    object ACTIVE : LinkStatus()

    object STALE : LinkStatus()

    object CLOSED : LinkStatus()
}
