/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.backend.kt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import network.columba.app.rns.api.call.AcceptedCallLifecycle
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.columba.app.rns.api.call.CallCallbackAdapter
import network.columba.app.rns.api.call.CallLifecycleRecorder
import network.columba.app.rns.api.call.SerializedLifecycleCallbackAdapter
import network.columba.app.rns.api.util.hexToBytes
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.Transport
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
    private val recorder: CallLifecycleRecorder,
    private val callPrivacyBridge: CallPrivacyBridge? = null,
) : CallController {
    companion object {
        private const val TAG = "NativeCallManager"
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
     * Shared serialized call-lifecycle owner: durable admission, at-most-one owned
     * attempt, and single-shot finalization for every accepted attempt.
     */
    private val acceptedCallLifecycle =
        AcceptedCallLifecycle(
            recorder = recorder,
            scope = scope,
            retainedAttempt = null,
            onDeferredShutdownCleanupComplete = { scope.cancel() },
        )

    /** Reduced inbound adapter: destination registration + link handshake + durable admission. */
    private val inboundCalls =
        NativeInboundCallAdapter(
            deliveryIdentity = deliveryIdentity,
            transport = transport,
            telephone = { telephone },
            owner = acceptedCallLifecycle,
            localIdentityHash = { deliveryIdentity.hexHash },
            scope = scope,
            onCallerIdentified = ::onCallerIdentified,
        )

    /** Symmetric callback adapter: routes LXST events into the shared serialized owner. */
    private val callbackAdapter: CallCallbackAdapter =
        SerializedLifecycleCallbackAdapter(acceptedCallLifecycle)

    /** Serialises [disableIncoming] / [enableIncoming] transitions. */
    private val incomingLock = Any()

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

        // 5b. Route LXST call-state callbacks into the shared serialized lifecycle.
        callCoordinator.setCallStateChangedListener { state, identityHash ->
            when (state) {
                "ringing" -> identityHash?.let(callbackAdapter::onRinging)
                "established" -> identityHash?.let(callbackAdapter::onEstablished)
                "busy" -> callbackAdapter.onBusy(identityHash)
                "rejected" -> callbackAdapter.onRejected(identityHash)
            }
        }
        callCoordinator.setCallEndedListener(callbackAdapter::onGenericEnded)

        // 6. Register lxst.telephony destination so Transport routes incoming call links here
        inboundCalls.register()

        // Announce immediately so peers can resolve a path to our telephony destination
        // even before the next coupled LXMF auto-announce fires.
        inboundCalls.announce()

        // Cold-start application of the persisted master toggle. If the user
        // turned voice calls OFF before the last process tear-down (or before
        // this fresh-start), apply that now — register+immediately-deregister
        // is marginally wasteful but lets re-enable share one helper.
        if (callPrivacyBridge?.getAllowVoiceCalls() == false) {
            Log.i(TAG, "Cold-start: Allow voice calls = false, deregistering destination")
            inboundCalls.disable()
        }

        Log.i(TAG, "Native telephony stack ready")
    }

    /**
     * Announce the local `lxst.telephony` destination.
     *
     * Kept public so [NativeReticulumProtocol] can couple telephony announces to every
     * `lxmf.delivery` announce/reannounce. Delegates to the reduced inbound adapter.
     */
    fun announce(appData: ByteArray? = null) {
        inboundCalls.announce(appData)
    }

    // ===== Incoming Call Handling =====

    /**
     * Called when the incoming caller has sent their Reticulum identity.
     *
     * Applies the calls-from-contacts policy gate and the busy-line check, then
     * routes the accepted incoming attempt through the shared serialized owner's durable
     * admission. Admission failures (another owned attempt awaiting finalization, or a
     * durable insert failure) tear the link down without ringing or creating history.
     */
    private fun onCallerIdentified(
        link: Link,
        identity: Identity,
    ) {
        val identityHash = identity.hexHash
        Log.i(TAG, "Caller identified: ${identityHash.take(16)}")

        // Calls-from-contacts-only gate. Fires BEFORE STATUS_RINGING and
        // BEFORE Telephone.onIncomingCall, so the originator only sees a
        // wait-time timeout (no STATUS_BUSY / STATUS_REJECTED) and this
        // device shows no UI / no ringtone. Sibling of the same gate in
        // PythonCallManager; both share the same CallsFromContactsGate
        // singleton via the CallPrivacyBridge adapter.
        if (callPrivacyBridge?.shouldSilentlyDrop(identityHash) == true) {
            Log.i(TAG, "Calls-only-from-contacts: dropping ${identityHash.take(16)}")
            link.teardown()
            return
        }

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line became busy after identify — signalling busy")
            link.send(packSignal(Signalling.STATUS_BUSY))
            link.teardown()
            return
        }

        // Durable admission + ringing persistence happen inside the owner BEFORE the
        // expose lambda accepts the link and rings. A rejection tears the link down.
        inboundCalls.expose(link, identityHash)
    }

    /** Pack a signal as msgpack {FIELD_SIGNALLING(0): [signal]} for Python LXST interop. */
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
            val destBytes = destinationHash.hexToBytes()
            val profile =
                profileCode
                    ?.let { code ->
                        Profile.fromId(code).also {
                            if (it == null) {
                                Log.w(TAG, "Unknown LXST profile code 0x${code.toString(16)}, falling back to default")
                            }
                        }
                    } ?: Profile.DEFAULT

            // Durable admission happens BEFORE outbound signalling: the attempt row is
            // created and the lifecycle owner latches it before telephone.call() launches.
            // If admission fails, the call is not placed and no history is recorded.
            val request =
                CallAttemptRequest(
                    direction = CallAttemptDirection.OUTGOING,
                    localIdentityHash = deliveryIdentity.hexHash,
                    remoteIdentityHash = destinationHash,
                    codecProfileCode = profileCode,
                )
            val result =
                acceptedCallLifecycle.admitOutgoing(request) { _ ->
                    Log.i(TAG, "Starting call with profile ${profile.abbreviation} (0x${profile.id.toString(16)})")
                    telephone.call(destBytes, profile)
                }
            if (result.isFailure) {
                Log.w(TAG, "Outgoing call not admitted: ${result.exceptionOrNull()}")
            }
        }
    }

    override fun answer() {
        telephone.answer()
    }

    override fun hangup() {
        // Local decline (incoming pre-answer) / cancel (outgoing pre-connect) intent
        // is latched BEFORE the telephone hangs up; the owner decides the exact outcome
        // from the active attempt's direction. A connected call is unaffected here — the
        // subsequent generic-ended callback finalizes it.
        when (acceptedCallLifecycle.activeAttempt?.direction) {
            CallAttemptDirection.INCOMING -> callbackAdapter.onLocalDecline()
            CallAttemptDirection.OUTGOING -> callbackAdapter.onLocalCancel()
            null -> Unit
        }
        telephone.hangup()
    }

    override fun muteMicrophone(muted: Boolean) {
        telephone.muteTransmit(muted)
    }

    override fun setSpeaker(enabled: Boolean) {
        audioBridge.setSpeakerphoneOn(enabled)
    }

    // ===== Master incoming-calls toggle =====

    /**
     * Apply [setIncomingEnabled] for this manager.
     *
     * Invoked by [NativeRnsBackendImpl.setIncomingEnabledHook] (wired in
     * [NativeRnsBackendImpl.setupNativeTelephone] once this manager is
     * constructed) when the UI calls `RnsTelephony.setIncomingEnabled(...)`
     * across the AIDL boundary.
     *
     * Idempotent — applying the same state twice is a no-op.
     */
    fun setIncomingEnabled(enabled: Boolean) {
        if (enabled) enableIncoming() else disableIncoming()
    }

    private fun disableIncoming() = synchronized(incomingLock) {
        inboundCalls.disable()
        if (::telephone.isInitialized && telephone.isCallActive()) {
            // Hang up before the link's transport state goes away so the
            // remote sees a clean drop (hangup signal sent over the active
            // call link, not the destination).
            try {
                telephone.hangup()
            } catch (e: Exception) {
                Log.w(TAG, "Ignored hangup error during disableIncoming: ${e.message}")
            }
        }
    }

    private fun enableIncoming() = synchronized(incomingLock) {
        inboundCalls.enable()
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
        inboundCalls.clear()
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
