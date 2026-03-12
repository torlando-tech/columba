package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.lxmf.messenger.reticulum.bindings.rns.RnsDestination
import com.lxmf.messenger.reticulum.bindings.rns.RnsDestinationProvider
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction

/**
 * Chaquopy implementation of [RnsDestinationProvider].
 * Calls `rns_api.RnsApi.create_destination()` which returns a live Python Destination.
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyRnsDestinationProvider(
    private val api: PyObject,
) : RnsDestinationProvider {
    override fun create(
        identity: RnsIdentity,
        direction: Direction,
        type: DestinationType,
        appName: String,
        vararg aspects: String,
    ): RnsDestination {
        val pyIdentity = (identity as ChaquopyRnsIdentity).pyIdentity
        val directionInt =
            when (direction) {
                Direction.IN -> 1 // RNS.Destination.IN
                Direction.OUT -> 2 // RNS.Destination.OUT
            }
        val typeInt =
            when (type) {
                DestinationType.SINGLE -> 1
                DestinationType.GROUP -> 2
                DestinationType.PLAIN -> 3
                DestinationType.LINK -> 4
            }
        // Convert Kotlin vararg to Python list
        val pyAspects =
            Python
                .getInstance()
                .builtins
                .callAttr("list", aspects.toList().toTypedArray())

        val pyDestination =
            api.callAttr(
                "create_destination",
                pyIdentity,
                directionInt,
                typeInt,
                appName,
                pyAspects,
            )
        return ChaquopyRnsDestination(
            pyDestination = pyDestination,
            rnsIdentity = identity,
            api = api,
            directionValue = direction,
            typeValue = type,
        )
    }
}
