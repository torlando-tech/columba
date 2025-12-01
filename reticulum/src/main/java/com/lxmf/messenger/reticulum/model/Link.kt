package com.lxmf.messenger.reticulum.model

data class Link(
    val id: String,
    val destination: Destination,
    val status: LinkStatus,
    val establishedAt: Long,
    val rtt: Float?,
)
