package com.lxmf.messenger.reticulum.bindings.rns

import com.lxmf.messenger.reticulum.model.LinkStatus

/**
 * Live link object with send/receive and crypto operations.
 *
 * Mirrors reticulum-kt: `Link` class — live object with `send()`, `teardown()`,
 * callbacks. Wraps a live Python RNS.Link (now) or native reticulum-kt Link (later).
 */
interface RnsLink {
    val linkId: ByteArray
    val status: LinkStatus
    val destination: RnsDestination?
    val isInitiator: Boolean
    val mtu: Int

    /** Round-trip time in milliseconds, or null if not yet measured. */
    val rtt: Long?

    fun send(data: ByteArray): Boolean

    fun teardown(reason: Int = 0)

    fun identify(identity: RnsIdentity): Boolean

    fun getRemoteIdentity(): RnsIdentity?

    /** Link establishment rate in bits per second. */
    fun getEstablishmentRate(): Long?

    /** Expected throughput in bits per second (from prior transfers). */
    fun getExpectedRate(): Long?

    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray?

    fun setClosedCallback(callback: ((RnsLink) -> Unit)?)

    fun setPacketCallback(callback: ((ByteArray, RnsLink) -> Unit)?)
}
