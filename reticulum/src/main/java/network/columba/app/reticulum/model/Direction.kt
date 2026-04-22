package network.columba.app.reticulum.model

sealed class Direction {
    object IN : Direction()

    object OUT : Direction()
}
