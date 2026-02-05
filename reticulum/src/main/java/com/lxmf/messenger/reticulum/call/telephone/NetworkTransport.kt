package com.lxmf.messenger.reticulum.call.telephone

/**
 * Network transport abstraction for telephony.
 *
 * This interface abstracts the network layer from the Telephone class,
 * enabling future migration from Python+Chaquopy to pure Kotlin Reticulum.
 *
 * Current implementation: PythonNetworkTransport (wraps Chaquopy bridge)
 * Future implementation: KotlinNetworkTransport (pure Kotlin Reticulum)
 *
 * Design decisions (from 11-CONTEXT.md):
 * - Telephone class should not import or reference PyObject directly
 * - Keep signalling protocol clean - it should work over any transport
 * - Minimize Python-specific assumptions in call flow
 */
interface NetworkTransport {

    /**
     * Establish a link to the destination for call setup.
     *
     * This is a suspending function because link establishment may take
     * significant time (path requests, link setup handshake).
     *
     * @param destinationHash 16-byte Reticulum destination hash
     * @return true if link established successfully, false otherwise
     */
    suspend fun establishLink(destinationHash: ByteArray): Boolean

    /**
     * Tear down the active link.
     *
     * Called during hangup or call cleanup. Safe to call if no link active.
     */
    fun teardownLink()

    /**
     * Send encoded audio packet to remote peer.
     *
     * Fire-and-forget semantics - packet loss is acceptable for real-time audio.
     * Caller is responsible for encoding the frame before sending.
     *
     * @param encodedFrame Codec-encoded audio frame (includes header)
     */
    fun sendPacket(encodedFrame: ByteArray)

    /**
     * Send signalling message to remote peer.
     *
     * Signalling values from Signalling object:
     * - STATUS_BUSY (0x00) through STATUS_ESTABLISHED (0x06)
     * - PREFERRED_PROFILE (0xFF) + profile.id for profile negotiation
     *
     * @param signal Signalling code to send
     */
    fun sendSignal(signal: Int)

    /**
     * Register callback for incoming audio packets.
     *
     * Callback is invoked on IO thread - implementations should not block.
     * Packet includes codec header byte.
     *
     * @param callback Function receiving encoded audio packets
     */
    fun setPacketCallback(callback: (ByteArray) -> Unit)

    /**
     * Register callback for incoming signalling messages.
     *
     * Callback is invoked on IO thread - implementations should not block.
     *
     * @param callback Function receiving signalling codes
     */
    fun setSignalCallback(callback: (Int) -> Unit)

    /**
     * Check if link is currently active.
     *
     * Link may become inactive due to timeout, remote hangup, or network failure.
     */
    val isLinkActive: Boolean
}
