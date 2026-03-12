package com.lxmf.messenger.reticulum.bindings.rns

import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction

/**
 * Factory for creating [RnsDestination] instances.
 *
 * Mirrors reticulum-kt: `Destination.create(identity, direction, type, appName, *aspects)`.
 */
interface RnsDestinationProvider {
    fun create(
        identity: RnsIdentity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        vararg aspects: String,
    ): RnsDestination
}
