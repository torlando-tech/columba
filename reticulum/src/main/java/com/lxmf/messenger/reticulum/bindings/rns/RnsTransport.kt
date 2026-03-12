package com.lxmf.messenger.reticulum.bindings.rns

/**
 * Routing, path management, and announce handling.
 *
 * Mirrors reticulum-kt: `Transport` singleton — `hasPath()`, `requestPath()`,
 * `hopsTo()`, `registerAnnounceHandler()`, `registerDestination()`.
 * Interface (not singleton) for testability — Chaquopy impl is the singleton.
 */
interface RnsTransport {
    val identity: RnsIdentity?
    val transportEnabled: Boolean

    fun registerDestination(destination: RnsDestination)

    fun deregisterDestination(destination: RnsDestination)

    fun registerAnnounceHandler(handler: RnsAnnounceHandler)

    fun deregisterAnnounceHandler(handler: RnsAnnounceHandler)

    fun hasPath(destinationHash: ByteArray): Boolean

    fun requestPath(destinationHash: ByteArray)

    fun hopsTo(destinationHash: ByteArray): Int

    fun getInterfaces(): List<RnsInterfaceInfo>

    fun persistData()
}
