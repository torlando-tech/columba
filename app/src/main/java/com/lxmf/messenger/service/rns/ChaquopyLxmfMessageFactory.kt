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
@Suppress("UnusedPrivateProperty") // api will be used in Phase 4 when source/dest wiring is complete
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
        // Phase 4 will resolve destinationHash to live Python Destination objects.
        // LXMF.LXMessage requires non-null source and destination Destination objects;
        // passing null would crash the Python constructor immediately.
        throw UnsupportedOperationException(
            "LxmfMessageFactory.create() requires live Python Destination objects. " +
                "This will be wired in Phase 4 when the LXMF router provides source/dest.",
        )
    }
}
