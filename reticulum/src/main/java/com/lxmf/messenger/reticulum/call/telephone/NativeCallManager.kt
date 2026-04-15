/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.lxmf.messenger.reticulum.call.telephone

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.AudioPacketHandler
import tech.torlando.lxst.core.CallController
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter
import tech.torlando.lxst.telephone.Profile
import tech.torlando.lxst.telephone.Telephone

/**
 * Wires the native LXST telephony stack for GIL-free voice calls.
 *
 * Responsibilities:
 * - Creates [Telephone] with [NativeNetworkTransport] (no Python in audio path)
 * - Bridges [PacketRouter] ↔ [NativeNetworkTransport] for audio packet routing
 * - Registers the `lxst.telephony` Reticulum destination for incoming calls
 * - Handles the Reticulum link identity protocol (STATUS_AVAILABLE → identify)
 * - Implements [CallController] so [CallCoordinator] can drive UI-initiated actions
 *
 * ## Packet routing diagram
 * ```
 * Outbound (mic → network):
 *   Packetizer → PacketRouter.sendPacket() → AudioPacketHandler → transport.sendPacket() → Link
 *
 * Inbound (network → speaker):
 *   Link → transport.packetCallback → PacketRouter.onInboundPacket() → LinkSource / Mixer
 * ```
 *
 * ## Incoming call identity protocol
 * 1. Remote caller establishes link to `lxst.telephony` destination
 * 2. We send STATUS_AVAILABLE (0x03) to prompt the caller to call `link.identify()`
 * 3. Caller's identity arrives via [Link.setRemoteIdentifiedCallback]
 * 4. We call [transport.acceptInboundLink] and [Telephone.onIncomingCall]
 *
 * ## Outgoing call identify protocol
 * 1. [NativeNetworkTransport.handleIncomingPacket] intercepts STATUS_AVAILABLE from callee
 * 2. Automatically calls `link.identify(localIdentity)` (mirrors Python call_manager)
 *
 * @param context Application context for [AudioDevice] and [PacketRouter]
 * @param deliveryIdentity Local Reticulum identity (used for telephony destination + identify)
 * @param transport [NativeNetworkTransport] instance shared with the [Telephone]
 */
class NativeCallManager(
    private val context: Context,
    private val deliveryIdentity: Identity,
    val transport: NativeNetworkTransport,
) : CallController {
    companion object {
        private const val TAG = "NativeCallManager"
        private const val LXST_APP_NAME = "lxst"
        private const val LXST_ASPECT = "telephony"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packetRouter: PacketRouter = PacketRouter.getInstance(context)
    private val audioBridge: AudioDevice = AudioDevice.getInstance(context)
    private val callCoordinator: CallCoordinator = CallCoordinator.getInstance()

    /**
     * The [Telephone] instance, created during [setup].
     * Exposed so [NativeReticulumProtocol] can query call status.
     */
    lateinit var telephone: Telephone
        private set

    /**
     * Inbound `lxst.telephony` destination (registered in Transport for incoming calls).
     * Held to prevent GC and to allow cleanup on [shutdown].
     */
    private var telephonyDestination: Destination? = null

    // ===== Initialization =====

    /**
     * Wire the telephony stack and register the incoming-call destination.
     *
     * Call this once after Reticulum is initialized and [deliveryIdentity] is available.
     * Safe to call again after [shutdown] to re-initialize.
     */
    fun setup() {
        Log.i(TAG, "Setting up native telephony stack")

        // 1. Provide local identity to transport for STATUS_AVAILABLE → identify flow
        transport.setLocalIdentity(deliveryIdentity)

        // 2. Wire PacketRouter → transport (outbound audio and signals from audio pipeline)
        packetRouter.setPacketHandler(
            object : AudioPacketHandler {
                override fun receiveAudioPacket(packet: ByteArray) = transport.sendPacket(packet)

                override fun receiveSignal(signal: Int) = transport.sendSignal(signal)
            },
        )

        // 3. Wire transport → PacketRouter (inbound audio from remote peer).
        //    Signal routing is set by Telephone.init (see step 4).
        transport.setPacketCallback { data -> packetRouter.onInboundPacket(data) }

        // 4. Create Telephone — its init block sets transport.signalCallback to onSignalReceived
        telephone =
            Telephone(
                context = context,
                networkTransport = transport,
                audioBridge = audioBridge,
                networkPacketBridge = packetRouter,
                callBridge = callCoordinator,
            )

        // 5. Register as CallController so CallCoordinator can relay UI actions back to us
        callCoordinator.setCallManager(this)

        // 6. Register lxst.telephony destination so Transport routes incoming call links here
        registerTelephonyDestination()

        // Announce immediately so peers can resolve a path to our telephony destination
        // even before the next coupled LXMF auto-announce fires.
        announce()

        Log.i(TAG, "Native telephony stack ready")
    }

    private fun registerTelephonyDestination() {
        try {
            val dest =
                Destination.create(
                    identity = deliveryIdentity,
                    direction = DestinationDirection.IN,
                    type = DestinationType.SINGLE,
                    appName = LXST_APP_NAME,
                    LXST_ASPECT,
                )
            // Fire when a remote peer opens a link to our telephony destination
            dest.setLinkEstablishedCallback { anyLink ->
                (anyLink as? Link)?.let { onInboundLinkEstablished(it) }
            }
            telephonyDestination = dest
            Log.i(TAG, "lxst.telephony destination registered: ${dest.hexHash.take(16)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register lxst.telephony destination", e)
        }
    }

    /**
     * Announce the local `lxst.telephony` destination.
     *
     * Kept public so [NativeReticulumProtocol] can couple telephony announces to every
     * `lxmf.delivery` announce/reannounce. This mirrors the Python call manager's explicit
     * telephony announce while keeping the native path in sync with delivery announces.
     */
    fun announce(appData: ByteArray? = null) {
        val dest = telephonyDestination
        if (dest == null) {
            Log.w(TAG, "Cannot announce lxst.telephony: destination not registered")
            return
        }

        try {
            dest.announce(appData)
            Log.i(
                TAG,
                "Announced lxst.telephony ${dest.hexHash.take(16)}" +
                    if (appData != null) " (appData=${appData.size} bytes)" else "",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to announce lxst.telephony", e)
        }
    }

    // ===== Incoming Call Handling =====

    /**
     * Handle a new inbound link request on the `lxst.telephony` destination.
     *
     * Sends STATUS_AVAILABLE to prompt the caller to identify themselves,
     * then waits for [Link.setRemoteIdentifiedCallback] before accepting the call.
     * If the line is already busy, sends STATUS_BUSY and tears down the link.
     */
    private fun onInboundLinkEstablished(link: Link) {
        Log.i(TAG, "Inbound call link arrived")

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line busy — signalling busy and rejecting inbound link")
            link.send(packSignal(Signalling.STATUS_BUSY))
            link.teardown()
            return
        }

        // Install callbacks BEFORE signalling availability. Otherwise the caller can
        // identify immediately after receiving STATUS_AVAILABLE and win the race against
        // our callback registration, leaving the call stuck at "connecting".
        link.setRemoteIdentifiedCallback { identifiedLink, identity ->
            onCallerIdentified(identifiedLink, identity)
        }

        link.setLinkClosedCallback { l ->
            Log.d(TAG, "Inbound call link closed before identification: reason=${l.teardownReason}")
        }

        // Tell the caller we're reachable so they call link.identify().
        link.send(packSignal(Signalling.STATUS_AVAILABLE))
    }

    /**
     * Pack a signal as msgpack {FIELD_SIGNALLING(0): [signal]} for Python LXST interop.
     * Python sends signals via Channel with this wire format; raw bytes are not recognized.
     */
    private fun packSignal(signal: Int): ByteArray {
        val packer =
            org.msgpack.core.MessagePack
                .newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(0x00) // FIELD_SIGNALLING
        packer.packArrayHeader(1)
        packer.packInt(signal)
        return packer.toByteArray()
    }

    /**
     * Called when the incoming caller has sent their Reticulum identity.
     *
     * Wires the link into [NativeNetworkTransport], notifies [Telephone] of the
     * incoming call, and sends STATUS_RINGING to the remote caller.
     */
    private fun onCallerIdentified(
        link: Link,
        identity: Identity,
    ) {
        val identityHash = identity.hexHash
        Log.i(TAG, "Caller identified: ${identityHash.take(16)}")

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line became busy after identify — signalling busy")
            link.send(packSignal(Signalling.STATUS_BUSY))
            link.teardown()
            return
        }

        // Accept the link into transport so signals and audio can flow
        transport.acceptInboundLink(link)

        // Notify Telephone — this sets isIncomingCall, activates ring tone, and
        // updates CallCoordinator so the UI shows the incoming call screen
        telephone.onIncomingCall(identityHash)

        // Signal ringing to remote caller (they need this to activate dial tone)
        transport.sendSignal(Signalling.STATUS_RINGING)
    }

    // ===== CallController Implementation =====
    // These are invoked by CallCoordinator when the UI (or a test) triggers an action.

    override fun call(destinationHash: String) {
        call(destinationHash, null)
    }

    fun call(
        destinationHash: String,
        profileCode: Int?,
    ) {
        scope.launch {
            val destBytes = destinationHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val profile =
                profileCode
                    ?.let { code ->
                        Profile.fromId(code).also {
                            if (it == null) {
                                Log.w(TAG, "Unknown LXST profile code 0x${code.toString(16)}, falling back to default")
                            }
                        }
                    } ?: Profile.DEFAULT

            Log.i(TAG, "Starting call with profile ${profile.abbreviation} (0x${profile.id.toString(16)})")
            telephone.call(destBytes, profile)
        }
    }

    override fun answer() {
        telephone.answer()
    }

    override fun hangup() {
        telephone.hangup()
    }

    override fun muteMicrophone(muted: Boolean) {
        telephone.muteTransmit(muted)
    }

    override fun setSpeaker(enabled: Boolean) {
        audioBridge.setSpeakerphoneOn(enabled)
    }

    // ===== Lifecycle =====

    fun shutdown() {
        Log.i(TAG, "Shutting down NativeCallManager")
        // Hang up first, while the link is still alive, so Telephone can run
        // its hangup signalling and release audio hardware (mic/speaker, ring
        // tones, mixers). Tearing down the transport link before hangup would
        // suppress STATUS_HANGUP and leave audio resources held until the next
        // setup(), causing mic/speaker conflicts on identity switch or config
        // change.
        if (::telephone.isInitialized && telephone.isCallActive()) {
            try {
                telephone.hangup()
            } catch (e: Exception) {
                Log.w(TAG, "Ignored error hanging up active call on shutdown: ${e.message}")
            }
        }
        callCoordinator.setCallManager(null)
        telephonyDestination = null
        // Tear down any active call link so NativeNetworkTransport.activeLink is
        // cleared — otherwise a subsequent setup() would see a stale closed link.
        try {
            transport.teardownLink()
        } catch (e: Exception) {
            Log.w(TAG, "Ignored error tearing down call transport on shutdown: ${e.message}")
        }
        scope.cancel()
    }
}
