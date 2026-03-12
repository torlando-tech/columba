package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsAnnounceHandler
import com.lxmf.messenger.reticulum.bindings.rns.RnsDestination
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.bindings.rns.RnsInterfaceInfo
import com.lxmf.messenger.reticulum.bindings.rns.RnsTransport

/**
 * Chaquopy implementation of [RnsTransport].
 * Wraps `rns_api.RnsApi` transport-level methods (path, announce, interface management).
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyRnsTransport(
    private val api: PyObject,
) : RnsTransport {
    override val identity: RnsIdentity?
        get() = null // Transport identity managed at higher level

    override val transportEnabled: Boolean
        get() {
            val result = api.callAttr("is_transport_enabled")
            return try {
                result?.toBoolean() ?: false
            } finally {
                result?.close()
            }
        }

    override fun registerDestination(destination: RnsDestination) {
        val pyDest = (destination as ChaquopyRnsDestination).pyDestination
        api.callAttr("transport_register_destination", pyDest)?.close()
    }

    override fun deregisterDestination(destination: RnsDestination) {
        val pyDest = (destination as ChaquopyRnsDestination).pyDestination
        api.callAttr("transport_deregister_destination", pyDest)?.close()
    }

    override fun registerAnnounceHandler(handler: RnsAnnounceHandler) {
        // TODO: Bridge Kotlin AnnounceHandler to Python handler in Phase 4
    }

    override fun deregisterAnnounceHandler(handler: RnsAnnounceHandler) {
        // TODO: Bridge in Phase 4
    }

    override fun hasPath(destinationHash: ByteArray): Boolean {
        val result = api.callAttr("transport_has_path", destinationHash)
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun requestPath(destinationHash: ByteArray) {
        api.callAttr("transport_request_path", destinationHash)?.close()
    }

    override fun hopsTo(destinationHash: ByteArray): Int {
        val result = api.callAttr("transport_hops_to", destinationHash)
        return try {
            result?.toInt() ?: -1
        } finally {
            result?.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getInterfaces(): List<RnsInterfaceInfo> {
        val result = api.callAttr("transport_get_interfaces")
        return try {
            val pyList = result?.asList() ?: return emptyList()
            pyList.map { item ->
                val dict = item.asMap() as Map<PyObject, PyObject>
                RnsInterfaceInfo(
                    name =
                        dict.entries
                            .find { it.key.toString() == "name" }
                            ?.value
                            ?.toString() ?: "",
                    online =
                        dict.entries
                            .find { it.key.toString() == "online" }
                            ?.value
                            ?.toBoolean() ?: false,
                    type =
                        dict.entries
                            .find { it.key.toString() == "type" }
                            ?.value
                            ?.toString() ?: "",
                    rxBytes =
                        dict.entries
                            .find { it.key.toString() == "rxb" }
                            ?.value
                            ?.toLong() ?: 0L,
                    txBytes =
                        dict.entries
                            .find { it.key.toString() == "txb" }
                            ?.value
                            ?.toLong() ?: 0L,
                )
            }
        } finally {
            result?.close()
        }
    }

    override fun persistData() {
        api.callAttr("transport_persist_data")?.close()
    }
}
