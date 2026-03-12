package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfMessage
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfRouter
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.protocol.PropagationState

/**
 * Chaquopy implementation of [LxmfRouter].
 * Wraps `rns_api.RnsApi` LXMF router methods.
 *
 * @param api The live Python `RnsApi` instance
 */
@Suppress("UNCHECKED_CAST")
class ChaquopyLxmfRouter(
    private val api: PyObject,
) : LxmfRouter {
    override fun registerDeliveryIdentity(identity: RnsIdentity) {
        val pyIdentity = (identity as ChaquopyRnsIdentity).pyIdentity
        api.callAttr("lxmf_register_delivery_identity", pyIdentity)?.close()
    }

    override fun registerDeliveryCallback(callback: (LxmfMessage) -> Unit) {
        // TODO: Bridge Python delivery callback to Kotlin in Phase 4
        // The Python callback receives a raw LXMF.LXMessage which needs wrapping
    }

    override fun handleOutbound(message: LxmfMessage) {
        val pyMessage = (message as ChaquopyLxmfMessage).pyMessage
        api.callAttr("lxmf_handle_outbound", pyMessage)?.close()
    }

    override fun setOutboundPropagationNode(destinationHash: ByteArray?) {
        if (destinationHash != null) {
            api.callAttr("lxmf_set_outbound_propagation_node", destinationHash)?.close()
        } else {
            api.callAttr("lxmf_set_outbound_propagation_node", null as Any?)?.close()
        }
    }

    override fun getOutboundPropagationNode(): ByteArray? {
        val result = api.callAttr("lxmf_get_outbound_propagation_node")
        return try {
            if (result != null && result.toString() != "None") {
                result.toJava(ByteArray::class.java)
            } else {
                null
            }
        } finally {
            result?.close()
        }
    }

    override fun requestMessagesFromPropagationNode(
        identity: RnsIdentity?,
        maxMessages: Int,
    ) {
        val pyIdentity = (identity as? ChaquopyRnsIdentity)?.pyIdentity
        api
            .callAttr(
                "lxmf_request_messages_from_propagation_node",
                pyIdentity,
                maxMessages,
            )?.close()
    }

    override fun getPropagationState(): PropagationState {
        val result = api.callAttr("lxmf_get_propagation_state")
        return try {
            val dict = result?.asMap() as? Map<PyObject, PyObject>
            if (dict != null) parsePropagationDict(dict) else PropagationState.IDLE
        } finally {
            result?.close()
        }
    }

    private fun parsePropagationDict(dict: Map<PyObject, PyObject>): PropagationState {
        var state = 0
        var stateName = "idle"
        var progress = 0f
        var messagesReceived = 0
        for ((k, v) in dict) {
            try {
                when (k.toString()) {
                    "state" -> state = v.toInt()
                    "state_name" -> stateName = v.toString()
                    "progress" -> progress = v.toFloat()
                    "messages_received" -> messagesReceived = v.toInt()
                }
            } finally {
                k.close()
                v.close()
            }
        }
        return PropagationState(
            state = state,
            stateName = stateName,
            progress = progress,
            messagesReceived = messagesReceived,
        )
    }

    override fun getVersion(): String? {
        val result = api.callAttr("lxmf_get_version")
        return try {
            result?.toString()
        } finally {
            result?.close()
        }
    }
}
