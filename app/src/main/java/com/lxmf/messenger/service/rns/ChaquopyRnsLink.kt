package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsDestination
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.bindings.rns.RnsLink
import com.lxmf.messenger.reticulum.model.LinkStatus

/**
 * Chaquopy implementation of [RnsLink].
 * Wraps a live Python `RNS.Link` object.
 *
 * @param pyLink The live Python RNS.Link object
 * @param api The RnsApi instance for calling helper methods
 */
class ChaquopyRnsLink(
    internal val pyLink: PyObject,
    private val api: PyObject,
) : RnsLink,
    AutoCloseable {
    override val linkId: ByteArray
        get() {
            val result = api.callAttr("link_get_id", pyLink)
            return try {
                result?.toJava(ByteArray::class.java) ?: ByteArray(0)
            } finally {
                result?.close()
            }
        }

    override val status: LinkStatus
        get() {
            val result = api.callAttr("link_get_status", pyLink)
            return try {
                when (result?.toInt()) {
                    0x00 -> LinkStatus.PENDING // RNS PENDING
                    0x01 -> LinkStatus.ACTIVE // RNS ACTIVE
                    0x02 -> LinkStatus.STALE // RNS STALE
                    0x04 -> LinkStatus.CLOSED // RNS CLOSED
                    else -> LinkStatus.CLOSED
                }
            } finally {
                result?.close()
            }
        }

    override val destination: RnsDestination?
        get() = null // Destination tracking deferred to Phase 4

    override val isInitiator: Boolean
        get() {
            val result = api.callAttr("link_get_is_initiator", pyLink)
            return try {
                result?.toBoolean() ?: false
            } finally {
                result?.close()
            }
        }

    override val mtu: Int
        get() {
            val result = api.callAttr("link_get_mtu", pyLink)
            return try {
                result?.toInt() ?: 0
            } finally {
                result?.close()
            }
        }

    override val rtt: Long?
        get() {
            val result = api.callAttr("link_get_rtt", pyLink)
            return try {
                if (result != null && result.toString() != "None") {
                    // Python returns RTT in seconds as float, convert to millis
                    (result.toDouble() * 1000).toLong()
                } else {
                    null
                }
            } finally {
                result?.close()
            }
        }

    override fun send(data: ByteArray): Boolean {
        val result = api.callAttr("link_send", pyLink, data)
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun teardown(reason: Int) {
        api.callAttr("link_teardown", pyLink, reason)?.close()
    }

    override fun identify(identity: RnsIdentity): Boolean {
        val pyIdentity = (identity as ChaquopyRnsIdentity).pyIdentity
        val result = api.callAttr("link_identify", pyLink, pyIdentity)
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun getRemoteIdentity(): RnsIdentity? {
        val pyIdentity = api.callAttr("link_get_remote_identity", pyLink)
        return if (pyIdentity != null && pyIdentity.toString() != "None") {
            ChaquopyRnsIdentity(pyIdentity, api)
        } else {
            pyIdentity?.close()
            null
        }
    }

    override fun getEstablishmentRate(): Long? {
        val result = api.callAttr("link_get_establishment_rate", pyLink)
        return try {
            if (result != null && result.toString() != "None") {
                result.toLong()
            } else {
                null
            }
        } finally {
            result?.close()
        }
    }

    override fun getExpectedRate(): Long? {
        val result = api.callAttr("link_get_expected_rate", pyLink)
        return try {
            if (result != null && result.toString() != "None") {
                result.toLong()
            } else {
                null
            }
        } finally {
            result?.close()
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val result = api.callAttr("link_encrypt", pyLink, plaintext)
        return try {
            result?.toJava(ByteArray::class.java) ?: ByteArray(0)
        } finally {
            result?.close()
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray? {
        val result = api.callAttr("link_decrypt", pyLink, ciphertext)
        return try {
            result?.toJava(ByteArray::class.java)
        } finally {
            result?.close()
        }
    }

    override fun setClosedCallback(callback: ((RnsLink) -> Unit)?) {
        if (callback != null) {
            // TODO: Phase 4 — bridge Kotlin callback to Python-callable proxy.
        } else {
            api.callAttr("link_set_closed_callback", pyLink, null as Any?)?.close()
        }
    }

    override fun setPacketCallback(callback: ((ByteArray, RnsLink) -> Unit)?) {
        if (callback != null) {
            // TODO: Phase 4 — bridge Kotlin callback to Python-callable proxy.
        } else {
            api.callAttr("link_set_packet_callback", pyLink, null as Any?)?.close()
        }
    }

    override fun close() {
        pyLink.close()
    }
}
