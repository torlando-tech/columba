package network.columba.app.rns.api.model

sealed class Direction {
    object IN : Direction()

    object OUT : Direction()
}
