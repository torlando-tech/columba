package com.lxmf.messenger.reticulum.model

sealed class NetworkStatus {
    object INITIALIZING : NetworkStatus()

    object CONNECTING : NetworkStatus()

    object READY : NetworkStatus()

    data class ERROR(val message: String) : NetworkStatus()

    object SHUTDOWN : NetworkStatus()
}
