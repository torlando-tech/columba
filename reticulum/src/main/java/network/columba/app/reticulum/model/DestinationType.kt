package network.columba.app.reticulum.model

sealed class DestinationType {
    object SINGLE : DestinationType()

    object GROUP : DestinationType()

    object PLAIN : DestinationType()
}
