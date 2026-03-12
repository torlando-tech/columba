package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentityProvider

/**
 * Chaquopy implementation of [RnsIdentityProvider].
 * Calls `rns_api.RnsApi` factory methods that return live Python Identity objects.
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyRnsIdentityProvider(
    private val api: PyObject,
) : RnsIdentityProvider {
    override fun create(): RnsIdentity {
        val pyIdentity = api.callAttr("create_identity")
        return ChaquopyRnsIdentity(pyIdentity, api)
    }

    override fun load(path: String): RnsIdentity {
        val pyIdentity = api.callAttr("load_identity", path)
        return ChaquopyRnsIdentity(pyIdentity, api)
    }

    override fun fromBytes(privateKeyBytes: ByteArray): RnsIdentity {
        val pyIdentity = api.callAttr("identity_from_bytes", privateKeyBytes)
        return ChaquopyRnsIdentity(pyIdentity, api)
    }

    override fun recall(destinationHash: ByteArray): RnsIdentity? {
        val pyIdentity = api.callAttr("recall_identity", destinationHash)
        return if (pyIdentity != null && pyIdentity.toString() != "None") {
            ChaquopyRnsIdentity(pyIdentity, api)
        } else {
            pyIdentity?.close()
            null
        }
    }

    override fun recallAppData(destinationHash: ByteArray): ByteArray? {
        val result = api.callAttr("recall_app_data", destinationHash)
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

    override fun fullHash(data: ByteArray): ByteArray {
        val result = api.callAttr("full_hash", data)
        return try {
            result?.toJava(ByteArray::class.java) ?: ByteArray(0)
        } finally {
            result?.close()
        }
    }

    override fun truncatedHash(data: ByteArray): ByteArray {
        val result = api.callAttr("truncated_hash", data)
        return try {
            result?.toJava(ByteArray::class.java) ?: ByteArray(0)
        } finally {
            result?.close()
        }
    }
}
