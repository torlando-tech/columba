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
                Direction.IN -> 0x11 // RNS.Destination.IN (17)
                Direction.OUT -> 0x12 // RNS.Destination.OUT (18)
            }
        val typeInt =
            when (type) {
                DestinationType.SINGLE -> 0 // RNS.Destination.SINGLE
                DestinationType.GROUP -> 1 // RNS.Destination.GROUP
                DestinationType.PLAIN -> 2 // RNS.Destination.PLAIN
                DestinationType.LINK -> 3 // RNS.Destination.LINK
            }
        // Convert Kotlin vararg to Python list
        val pyAspects =
            Python
                .getInstance()
                .builtins
                .callAttr("list", aspects.toList().toTypedArray())

        val pyDestination =
            try {
                api.callAttr(
                    "create_destination",
                    pyIdentity,
                    directionInt,
                    typeInt,
                    appName,
                    pyAspects,
                )
            } finally {
                pyAspects.close()
            }
        return ChaquopyRnsDestination(
            pyDestination = pyDestination,
            rnsIdentity = identity,
            api = api,
            directionValue = direction,
            typeValue = type,
        )
    }
}
