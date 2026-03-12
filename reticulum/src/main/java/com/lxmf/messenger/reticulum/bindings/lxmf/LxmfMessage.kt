package com.lxmf.messenger.reticulum.bindings.lxmf

import com.lxmf.messenger.reticulum.protocol.DeliveryMethod

/**
 * Live LXMF message object with delivery/failure callbacks.
 *
 * Wraps a live Python LXMF.LXMessage (now) or native LXMF-kt LXMessage (later).
 * Mutable properties ([desiredMethod], [tryPropagationOnFail]) can be set before
 * passing to [LxmfRouter.handleOutbound].
 */
interface LxmfMessage {
    val hash: ByteArray
    val state: LxmfMessageState
    val sourceHash: ByteArray
    val destinationHash: ByteArray
    val content: String
    val fields: Map<Int, Any>?
    val timestamp: Long
    var desiredMethod: DeliveryMethod
    var tryPropagationOnFail: Boolean

    fun registerDeliveryCallback(callback: (LxmfMessage) -> Unit)

    fun registerFailedCallback(callback: (LxmfMessage) -> Unit)
}
