package com.lxmf.messenger.reticulum.bindings.rns

import com.lxmf.messenger.reticulum.model.Identity

/**
 * Live identity object with cryptographic operations.
 *
 * Mirrors reticulum-kt: `Identity` class with sign(), encrypt(), validate(), decrypt().
 * Wraps a live Python RNS.Identity (now) or a native reticulum-kt Identity (later).
 *
 * For UI/persistence use cases that only need data, call [toSnapshot] to get an
 * [Identity] data class. Both layers coexist: live objects for business logic,
 * data classes for serialization.
 */
interface RnsIdentity {
    val hash: ByteArray
    val hexHash: String

    fun getPublicKey(): ByteArray

    fun getPrivateKey(): ByteArray?

    fun sign(message: ByteArray): ByteArray

    fun validate(
        signature: ByteArray,
        message: ByteArray,
    ): Boolean

    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray?

    /** Bridge to existing [Identity] data class for UI/persistence. */
    fun toSnapshot(): Identity
}
