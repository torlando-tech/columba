package com.lxmf.messenger.reticulum.bindings.rns

/**
 * Factory and recall operations for [RnsIdentity].
 *
 * Mirrors reticulum-kt: `Identity.create()`, `Identity.load()`, `Identity.fromBytes()`,
 * `Identity.recall()`, `Identity.recallAppData()`.
 */
interface RnsIdentityProvider {
    fun create(): RnsIdentity

    fun load(path: String): RnsIdentity

    fun fromBytes(privateKeyBytes: ByteArray): RnsIdentity

    fun recall(destinationHash: ByteArray): RnsIdentity?

    fun recallAppData(destinationHash: ByteArray): ByteArray?

    fun fullHash(data: ByteArray): ByteArray

    fun truncatedHash(data: ByteArray): ByteArray
}
