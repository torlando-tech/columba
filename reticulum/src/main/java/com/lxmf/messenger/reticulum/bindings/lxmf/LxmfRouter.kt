package com.lxmf.messenger.reticulum.bindings.lxmf

import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.protocol.PropagationState

/**
 * LXMF message routing — delivery registration, outbound handling, propagation sync.
 *
 * Mirrors future LXMF-kt: `LXMRouter` class. Separated from RNS layer to match
 * the RNS/LXMF library boundary — when LXMF-kt arrives, only implementations
 * in this package change.
 */
interface LxmfRouter {
    fun registerDeliveryIdentity(identity: RnsIdentity)

    fun registerDeliveryCallback(callback: (LxmfMessage) -> Unit)

    fun handleOutbound(message: LxmfMessage)

    fun setOutboundPropagationNode(destinationHash: ByteArray?)

    fun getOutboundPropagationNode(): ByteArray?

    fun requestMessagesFromPropagationNode(
        identity: RnsIdentity? = null,
        maxMessages: Int = 256,
    )

    fun getPropagationState(): PropagationState

    fun getVersion(): String?
}
