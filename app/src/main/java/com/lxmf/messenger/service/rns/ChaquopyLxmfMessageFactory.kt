package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfMessage
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfMessageFactory
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.protocol.DeliveryMethod

/**
 * Chaquopy implementation of [LxmfMessageFactory].
 * Calls `rns_api.RnsApi.create_lxmf_message()` which returns a live Python LXMessage.
 *
 * Note: This factory needs source/destination as live Python Destination objects.
 * The full wiring (resolving destinationHash to a Python Destination) happens in Phase 4.
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyLxmfMessageFactory(
    private val api: PyObject,
) : LxmfMessageFactory {
    override fun create(
        sourceIdentity: RnsIdentity,
        destinationHash: ByteArray,
        content: String,
        fields: Map<Int, Any>?,
        desiredMethod: DeliveryMethod,
        tryPropagationOnFail: Boolean,
    ): LxmfMessage {
        // Map DeliveryMethod enum to LXMF Python constants
        val methodInt =
            when (desiredMethod) {
                DeliveryMethod.OPPORTUNISTIC -> 0
                DeliveryMethod.DIRECT -> 1
                DeliveryMethod.PROPAGATED -> 2
            }

        // TODO: Phase 4 — resolve destinationHash to live Python Destination objects
        // For now, we pass primitives and let the Python side handle destination lookup.
        // The full wiring requires the LXMF router's delivery destination for source,
        // and a recalled/created destination for the recipient.
        val pyMessage =
            api.callAttr(
                "create_lxmf_message",
                null as Any?, // source_destination — wired in Phase 4
                null as Any?, // dest_destination — wired in Phase 4
                content,
                fields,
                methodInt,
                tryPropagationOnFail,
            )

        return ChaquopyLxmfMessage(pyMessage, api).also {
            it.desiredMethod = desiredMethod
            it.tryPropagationOnFail = tryPropagationOnFail
        }
    }
}
