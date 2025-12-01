package com.lxmf.messenger.reticulum.model

sealed class LinkStatus {
    object PENDING : LinkStatus()

    object ACTIVE : LinkStatus()

    object STALE : LinkStatus()

    object CLOSED : LinkStatus()
}
