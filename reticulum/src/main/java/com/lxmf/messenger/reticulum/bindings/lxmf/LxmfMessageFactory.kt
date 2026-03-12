package com.lxmf.messenger.reticulum.bindings.lxmf

import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.protocol.DeliveryMethod

/**
 * Factory for constructing [LxmfMessage] instances.
 *
 * Mirrors future LXMF-kt: `LXMessage(source, destination, content, ...)`.
 */
interface LxmfMessageFactory {
    fun create(
        sourceIdentity: RnsIdentity,
        destinationHash: ByteArray,
        content: String,
        fields: Map<Int, Any>? = null,
        desiredMethod: DeliveryMethod = DeliveryMethod.DIRECT,
        tryPropagationOnFail: Boolean = true,
    ): LxmfMessage
}
