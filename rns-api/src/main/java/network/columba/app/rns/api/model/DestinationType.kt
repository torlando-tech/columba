package network.columba.app.rns.api.model

sealed class DestinationType {
    object SINGLE : DestinationType()

    object GROUP : DestinationType()

    object PLAIN : DestinationType()
}
