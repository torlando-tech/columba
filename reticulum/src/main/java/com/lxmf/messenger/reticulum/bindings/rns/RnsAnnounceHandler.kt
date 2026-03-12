package com.lxmf.messenger.reticulum.bindings.rns

/**
 * Callback interface for receiving announce events.
 *
 * Mirrors reticulum-kt: `AnnounceHandler` functional interface.
 * Register with [RnsTransport.registerAnnounceHandler].
 */
interface RnsAnnounceHandler {
    val aspect: String

    fun receivedAnnounce(
        destinationHash: ByteArray,
        announcedIdentity: RnsIdentity,
        appData: ByteArray?,
        hops: Int,
    )
}
