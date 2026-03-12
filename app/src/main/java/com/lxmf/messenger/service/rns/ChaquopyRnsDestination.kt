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
        // Callback bridging: Python calls this with a raw Link, we wrap it
        if (callback != null) {
            val pyCallback = createLinkCallback(callback)
            api
                .callAttr(
                    "destination_set_link_established_callback",
                    pyDestination,
                    pyCallback,
                )?.close()
        } else {
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
        // TODO: Bridge Python request handler callback to Kotlin lambda
        api
            .callAttr(
                "destination_register_request_handler",
                pyDestination,
                path,
                null as Any?, // Will wire callback in Phase 4
            )?.close()
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

    /**
     * Create a Python-callable wrapper that converts a raw Python Link to [RnsLink]
     * before forwarding to the Kotlin callback.
     */
    @Suppress("UnusedParameter") // callback wired in Phase 4
    private fun createLinkCallback(callback: (RnsLink) -> Unit): PyObject {
        // Use Chaquopy's proxy mechanism — the Python side receives a Java lambda
        // that wraps the incoming PyObject Link in ChaquopyRnsLink
        // This is wired in Phase 4 when we have the full callback bridge
        // For now, return a no-op proxy
        return api // placeholder — real wiring in Phase 4
    }

    override fun close() {
        pyDestination.close()
    }
}
