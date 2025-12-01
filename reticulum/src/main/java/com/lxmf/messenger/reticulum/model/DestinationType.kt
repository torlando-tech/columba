package com.lxmf.messenger.reticulum.model

sealed class DestinationType {
    object SINGLE : DestinationType()

    object GROUP : DestinationType()

    object PLAIN : DestinationType()
}
