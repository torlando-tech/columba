package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfMessage
import com.lxmf.messenger.reticulum.bindings.lxmf.LxmfMessageState
import com.lxmf.messenger.reticulum.protocol.DeliveryMethod

/**
 * Chaquopy implementation of [LxmfMessage].
 * Wraps a live Python `LXMF.LXMessage` object.
 *
 * @param pyMessage The live Python LXMF.LXMessage object
 * @param api The RnsApi instance for calling helper methods
 */
class ChaquopyLxmfMessage(
    internal val pyMessage: PyObject,
    private val api: PyObject,
) : LxmfMessage,
    AutoCloseable {
    override val hash: ByteArray
        get() {
            val result = api.callAttr("lxmf_message_get_hash", pyMessage)
            return try {
                result?.toJava(ByteArray::class.java) ?: ByteArray(0)
            } finally {
                result?.close()
            }
        }

    override val state: LxmfMessageState
        get() {
            val result = api.callAttr("lxmf_message_get_state", pyMessage)
            return try {
                when (result?.toInt()) {
                    0 -> LxmfMessageState.DRAFT
                    1 -> LxmfMessageState.OUTBOUND
                    2 -> LxmfMessageState.SENDING
                    3 -> LxmfMessageState.SENT
                    4 -> LxmfMessageState.DELIVERED
                    5 -> LxmfMessageState.FAILED
                    else -> LxmfMessageState.DRAFT
                }
            } finally {
                result?.close()
            }
        }

    override val sourceHash: ByteArray
        get() {
            val result = api.callAttr("lxmf_message_get_source_hash", pyMessage)
            return try {
                result?.toJava(ByteArray::class.java) ?: ByteArray(0)
            } finally {
                result?.close()
            }
        }

    override val destinationHash: ByteArray
        get() {
            val result = api.callAttr("lxmf_message_get_destination_hash", pyMessage)
            return try {
                result?.toJava(ByteArray::class.java) ?: ByteArray(0)
            } finally {
                result?.close()
            }
        }

    override val content: String
        get() {
            val result = api.callAttr("lxmf_message_get_content", pyMessage)
            return try {
                result?.toString() ?: ""
            } finally {
                result?.close()
            }
        }

    @Suppress("UNCHECKED_CAST")
    override val fields: Map<Int, Any>?
        get() {
            val result = api.callAttr("lxmf_message_get_fields", pyMessage)
            return try {
                if (result != null && result.toString() != "None") {
                    // Python dict with int keys — convert to Kotlin Map<Int, Any>
                    val pyDict = result.asMap() as Map<PyObject, PyObject>
                    pyDict.entries.associate { (k, v) ->
                        k.toInt() to (v.toJava(Any::class.java) as Any)
                    }
                } else {
                    null
                }
            } finally {
                result?.close()
            }
        }

    override val timestamp: Long
        get() {
            val result = api.callAttr("lxmf_message_get_timestamp", pyMessage)
            return try {
                result?.toLong() ?: 0L
            } finally {
                result?.close()
            }
        }

    // Mutable properties — set on the Python object before handleOutbound
    override var desiredMethod: DeliveryMethod = DeliveryMethod.DIRECT

    override var tryPropagationOnFail: Boolean = true

    override fun registerDeliveryCallback(callback: (LxmfMessage) -> Unit) {
        // TODO: Bridge Python callback to Kotlin in Phase 4
    }

    override fun registerFailedCallback(callback: (LxmfMessage) -> Unit) {
        // TODO: Bridge Python callback to Kotlin in Phase 4
    }

    override fun close() {
        pyMessage.close()
    }
}
