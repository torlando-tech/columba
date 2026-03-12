package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsIdentity
import com.lxmf.messenger.reticulum.model.Identity

/**
 * Chaquopy implementation of [RnsIdentity].
 * Wraps a live Python `RNS.Identity` object, calling methods via `rns_api` static helpers.
 *
 * @param pyIdentity The live Python RNS.Identity object
 * @param api The RnsApi instance for calling static helper methods
 */
class ChaquopyRnsIdentity(
    internal val pyIdentity: PyObject,
    private val api: PyObject,
) : RnsIdentity,
    AutoCloseable {
    override val hash: ByteArray
        get() {
            val result = api.callAttr("identity_get_hash", pyIdentity)
            return try {
                result?.toJava(ByteArray::class.java) ?: ByteArray(0)
            } finally {
                result?.close()
            }
        }

    override val hexHash: String
        get() {
            val result = api.callAttr("identity_get_hex_hash", pyIdentity)
            return try {
                result?.toString() ?: ""
            } finally {
                result?.close()
            }
        }

    override fun getPublicKey(): ByteArray {
        val result = api.callAttr("identity_get_public_key", pyIdentity)
        return try {
            result?.toJava(ByteArray::class.java) ?: ByteArray(0)
        } finally {
            result?.close()
        }
    }

    override fun getPrivateKey(): ByteArray? {
        val result = api.callAttr("identity_get_private_key", pyIdentity)
        return try {
            result?.toJava(ByteArray::class.java)
        } finally {
            result?.close()
        }
    }

    override fun sign(message: ByteArray): ByteArray {
        val result = api.callAttr("identity_sign", pyIdentity, message)
        return try {
            result?.toJava(ByteArray::class.java) ?: ByteArray(0)
        } finally {
            result?.close()
        }
    }

    override fun validate(
        signature: ByteArray,
        message: ByteArray,
    ): Boolean {
        val result = api.callAttr("identity_validate", pyIdentity, signature, message)
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val result = api.callAttr("identity_encrypt", pyIdentity, plaintext)
        return try {
            result?.toJava(ByteArray::class.java) ?: ByteArray(0)
        } finally {
            result?.close()
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray? {
        val result = api.callAttr("identity_decrypt", pyIdentity, ciphertext)
        return try {
            result?.toJava(ByteArray::class.java)
        } finally {
            result?.close()
        }
    }

    override fun toSnapshot(): Identity =
        Identity(
            hash = hash,
            publicKey = getPublicKey(),
            privateKey = getPrivateKey(),
        )

    override fun close() {
        pyIdentity.close()
    }
}
