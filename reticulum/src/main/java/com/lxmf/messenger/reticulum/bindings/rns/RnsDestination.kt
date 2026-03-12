package com.lxmf.messenger.reticulum.bindings.rns

import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction

/**
 * Live destination object with announce and request handler registration.
 *
 * Mirrors reticulum-kt: `Destination` class with `announce()`,
 * `setLinkEstablishedCallback()`, `registerRequestHandler()`.
 *
 * For UI/persistence, call [toSnapshot] to get a [Destination] data class.
 */
interface RnsDestination {
    val hash: ByteArray
    val hexHash: String
    val identity: RnsIdentity
    val direction: Direction
    val type: DestinationType

    fun announce(appData: ByteArray? = null)

    fun setLinkEstablishedCallback(callback: ((RnsLink) -> Unit)?)

    fun registerRequestHandler(
        path: String,
        responseGenerator: (path: String, data: ByteArray?, requestId: ByteArray) -> ByteArray?,
    )

    fun deregisterRequestHandler(path: String)

    /** Bridge to existing [Destination] data class for UI/persistence. */
    fun toSnapshot(): Destination
}
