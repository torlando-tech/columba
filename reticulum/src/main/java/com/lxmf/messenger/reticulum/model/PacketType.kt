package com.lxmf.messenger.reticulum.model

sealed class PacketType {
    object DATA : PacketType()

    object ANNOUNCE : PacketType()

    object LINKREQUEST : PacketType()

    object PROOF : PacketType()
}
