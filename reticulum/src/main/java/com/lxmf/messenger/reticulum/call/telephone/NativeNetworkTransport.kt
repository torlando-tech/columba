/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.lxmf.messenger.reticulum.call.telephone

import android.util.Log
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.link.LinkConstants
import network.reticulum.transport.Transport
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.telephone.NetworkTransport

/**
 * Native Kotlin implementation of [NetworkTransport] for LXST telephony.
 *
 * Replaces [PythonNetworkTransport] by using reticulum-kt [Link] directly,
 * eliminating the Python GIL from the voice packet path. Audio packets are
 * sent/received over the Reticulum link without any Python intermediary.
 *
 * This is the critical change that fixes voice call latency caused by GIL
 * contention between the audio transmit loop and announce processing.
 */
class NativeNetworkTransport : NetworkTransport {
    companion object {
        private const val TAG = "NativeNetworkTransport"
        private const val LXST_APP_NAME = "lxst"
        private const val LXST_ASPECT = "telephony"
        private const val EXTENDED_SIGNAL_PREFIX: Byte = 0xFE.toByte()
    }

    private var activeLink: Link? = null
    private var packetCallback: ((ByteArray) -> Unit)? = null
    private var signalCallback: ((Int) -> Unit)? = null
    private var locallyClosingLink: Link? = null

    /**
     * Local identity used to identify ourselves to the remote peer.
     *
     * Must be set before any call is made. When STATUS_AVAILABLE is received from the
     * callee (outgoing call path), we call [Link.identify] to send our identity.
     * This mirrors Python call_manager.__packet_received's STATUS_AVAILABLE handler.
     */
    private var localIdentity: Identity? = null

    fun setLocalIdentity(identity: Identity) {
        localIdentity = identity
    }

    private fun handleLinkClosed(
        link: Link,
        reason: Int,
        logPrefix: String,
    ) {
        Log.i(TAG, "$logPrefix closed: reason=$reason")
        val wasLocalTeardown = locallyClosingLink === link
        if (wasLocalTeardown) {
            locallyClosingLink = null
        }
        if (activeLink === link) {
            activeLink = null
        }

        // Mirror the old Python path: remote link close notifies Telephone with
        // STATUS_AVAILABLE so the state machine tears down the call UI/audio.
        if (!wasLocalTeardown) {
            signalCallback?.invoke(Signalling.STATUS_AVAILABLE)
        }
    }

    private fun installLinkCallbacks(link: Link) {
        link.setPacketCallback { data, _ ->
            handleIncomingPacket(data)
        }
        link.setLinkClosedCallback { l ->
            handleLinkClosed(link, l.teardownReason, "Link")
        }
    }

    override val isLinkActive: Boolean
        get() = activeLink?.status == LinkConstants.ACTIVE

    override suspend fun establishLink(destinationHash: ByteArray): Boolean =
        try {
            val identity = recallOrRequestIdentity(destinationHash)
            if (identity == null) {
                false
            } else {
                establishLinkToIdentity(identity, destinationHash)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing link", e)
            false
        }

    private suspend fun recallOrRequestIdentity(destinationHash: ByteArray): Identity? {
        val recalled =
            Identity.recall(destinationHash)
                ?: Identity.recallByIdentityHash(destinationHash)
        if (recalled != null) return recalled

        Log.w(TAG, "Cannot establish link: identity not known for ${destinationHash.toHex().take(16)}")
        Transport.requestPath(destinationHash)
        kotlinx.coroutines.delay(3000)
        val retried =
            Identity.recall(destinationHash)
                ?: Identity.recallByIdentityHash(destinationHash)
        if (retried == null) {
            Log.e(TAG, "Identity still not known after path request")
        }
        return retried
    }

    private suspend fun establishLinkToIdentity(
        identity: Identity,
        destHash: ByteArray,
    ): Boolean {
        val dest =
            Destination.create(
                identity,
                DestinationDirection.OUT,
                DestinationType.SINGLE,
                LXST_APP_NAME,
                LXST_ASPECT,
            )

        val link =
            Link.create(
                destination = dest,
                establishedCallback = { l ->
                    Log.i(TAG, "Link established: rtt=${l.rtt}ms")
                },
            )

        activeLink = link
        // Install callbacks immediately after Link.create(). The callee can send
        // STATUS_AVAILABLE as soon as the link comes up; if we wait until the
        // established callback to attach packet handling, that first byte can be lost
        // and the caller never identifies.
        installLinkCallbacks(link)

        // Wait for link establishment (up to 15s for low-bandwidth paths)
        val deadline = System.currentTimeMillis() + 15_000
        while (link.status != LinkConstants.ACTIVE &&
            link.status != LinkConstants.CLOSED &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(100)
        }

        return if (link.status == LinkConstants.ACTIVE) {
            Log.i(TAG, "Link active to ${destHash.toHex().take(16)}")

            // Identify proactively as soon as the link becomes active.
            // In theory the callee's STATUS_AVAILABLE should trigger this, but on real
            // devices that first 1-byte signal can race with callback installation on
            // either side. Sending LINKIDENTIFY immediately avoids that handshake race
            // while remaining protocol-correct: only the initiator may identify, and
            // Link.identify() already enforces ACTIVE status.
            val identity = localIdentity
            if (identity != null) {
                val identified = link.identify(identity)
                Log.i(TAG, "Proactive identify sent=$identified")
            } else {
                Log.w(TAG, "Link became active but localIdentity was null")
            }
            true
        } else {
            Log.w(TAG, "Link failed to establish (status=${link.status})")
            activeLink = null
            false
        }
    }

    override fun teardownLink() {
        activeLink?.let { link ->
            Log.i(TAG, "Tearing down link")
            locallyClosingLink = link
            link.teardown()
        }
        activeLink = null
    }

    override fun sendPacket(encodedFrame: ByteArray) {
        val link = activeLink ?: return
        if (link.status != LinkConstants.ACTIVE) return
        // Fire-and-forget: no proof needed for real-time audio
        link.send(encodedFrame)
    }

    override fun sendSignal(signal: Int) {
        val link = activeLink ?: return
        if (link.status != LinkConstants.ACTIVE) return

        val payload =
            if (signal <= 0xFF) {
                // Legacy one-byte control signal (status codes 0x00..0x06).
                byteArrayOf(signal.toByte())
            } else {
                // Extended signal framing for LXST profile negotiation.
                // Needed because profile signals are PREFERRED_PROFILE (0xFF) + profileId,
                // e.g. 0x10F for ULBW, which does not fit in a single byte.
                byteArrayOf(
                    EXTENDED_SIGNAL_PREFIX,
                    ((signal ushr 8) and 0xFF).toByte(),
                    (signal and 0xFF).toByte(),
                )
            }

        link.send(payload)
    }

    override fun setPacketCallback(callback: (ByteArray) -> Unit) {
        packetCallback = callback
    }

    override fun setSignalCallback(callback: (Int) -> Unit) {
        signalCallback = callback
    }

    /**
     * Accept an inbound link from an incoming caller.
     *
     * Called by [NativeCallManager] after the caller's identity has been verified via
     * [Link.setRemoteIdentifiedCallback]. Wires up packet and closed callbacks on the
     * link so audio and signals flow through this transport.
     *
     * @param link The fully-established inbound link from the caller
     */
    fun acceptInboundLink(link: Link) {
        Log.i(TAG, "Accepting inbound call link: ${link.linkId.toHex().take(16)}")
        activeLink = link
        link.setPacketCallback { data, _ ->
            handleIncomingPacket(data)
        }
        link.setLinkClosedCallback { l ->
            handleLinkClosed(link, l.teardownReason, "Inbound link")
        }
    }

    private fun handleIncomingPacket(data: ByteArray) {
        if (data.isEmpty()) return

        val signal =
            when {
                data.size == 1 -> data[0].toInt() and 0xFF
                data.size == 3 && data[0] == EXTENDED_SIGNAL_PREFIX -> {
                    ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                }
                else -> null
            }

        if (signal != null) {
            Log.d(TAG, "Inbound signal 0x${signal.toString(16)}")
            // STATUS_AVAILABLE (0x03) from callee means we should identify ourselves.
            // Mirrors Python call_manager.__packet_received STATUS_AVAILABLE handler.
            if (signal == 0x03 /* STATUS_AVAILABLE */) {
                val link = activeLink
                val identity = localIdentity
                if (link != null && identity != null) {
                    Log.d(TAG, "Remote available, identifying...")
                    link.identify(identity)
                } else {
                    Log.w(TAG, "Received STATUS_AVAILABLE but activeLink or localIdentity was null")
                }
            }
            signalCallback?.invoke(signal)
        } else {
            // Multi-byte packet = encoded audio
            packetCallback?.invoke(data)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
