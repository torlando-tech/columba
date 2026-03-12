package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsDestination
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.bindings.rns.RnsLink
import com.lxmf.messenger.reticulum.model.Destination
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction

/**
 * Chaquopy implementation of [RnsDestination].
 * Wraps a live Python `RNS.Destination` object.
 *
 * @param pyDestination The live Python RNS.Destination object
 * @param rnsIdentity The [RnsIdentity] that owns this destination
 * @param api The RnsApi instance for calling helper methods
 * @param directionValue The [Direction] of this destination
 * @param typeValue The [DestinationType] of this destination
 */
class ChaquopyRnsDestination(
    internal val pyDestination: PyObject,
    private val rnsIdentity: RnsIdentity,
    private val api: PyObject,
    private val directionValue: Direction,
    private val typeValue: DestinationType,
) : RnsDestination,
    AutoCloseable {
    override val hash: ByteArray
        get() {
            val result = api.callAttr("destination_get_hash", pyDestination)
            return try {
                result?.toJava(ByteArray::class.java) ?: ByteArray(0)
            } finally {
                result?.close()
            }
        }

    override val hexHash: String
        get() {
            val result = api.callAttr("destination_get_hex_hash", pyDestination)
            return try {
                result?.toString() ?: ""
            } finally {
                result?.close()
            }
        }

    override val identity: RnsIdentity get() = rnsIdentity

    override val direction: Direction get() = directionValue

    override val type: DestinationType get() = typeValue

    override fun announce(appData: ByteArray?) {
        if (appData != null) {
            api.callAttr("destination_announce", pyDestination, appData)?.close()
        } else {
            api.callAttr("destination_announce", pyDestination)?.close()
        }
    }

    override fun setLinkEstablishedCallback(callback: ((RnsLink) -> Unit)?) {
        if (callback != null) {
            // TODO: Phase 4 — bridge Kotlin callback to Python-callable proxy.
            // Cannot register yet; doing so would pass a non-callable placeholder
            // that crashes when RNS fires the link-established event.
        } else {
            // Clear the callback on the Python side
            api
                .callAttr(
                    "destination_set_link_established_callback",
                    pyDestination,
                    null as Any?,
                )?.close()
        }
    }

    override fun registerRequestHandler(
        path: String,
        responseGenerator: (path: String, data: ByteArray?, requestId: ByteArray) -> ByteArray?,
    ) {
        // TODO: Phase 4 — bridge Kotlin responseGenerator lambda to Python callable.
        // Cannot register yet; RNS validates that response_generator is callable
        // and raises ValueError for None.
    }

    override fun deregisterRequestHandler(path: String) {
        api.callAttr("destination_deregister_request_handler", pyDestination, path)?.close()
    }

    override fun toSnapshot(): Destination =
        Destination(
            hash = hash,
            hexHash = hexHash,
            identity = rnsIdentity.toSnapshot(),
            direction = directionValue,
            type = typeValue,
            appName = "", // Not cached — retrieve from Python if needed
            aspects = emptyList(),
        )

    override fun close() {
        pyDestination.close()
    }
}
