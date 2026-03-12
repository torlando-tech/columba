package com.lxmf.messenger.reticulum.model

sealed class DestinationType {
    object SINGLE : DestinationType()

    object GROUP : DestinationType()

    object PLAIN : DestinationType()

    /** Used for link-based destinations. Mirrors reticulum-kt DestinationType.LINK. */
    object LINK : DestinationType()
}
