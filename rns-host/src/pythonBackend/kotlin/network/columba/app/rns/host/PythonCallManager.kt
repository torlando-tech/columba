package network.columba.app.rns.host

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.AudioPacketHandler
import tech.torlando.lxst.core.CallController
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter
import tech.torlando.lxst.telephone.Profile
import tech.torlando.lxst.telephone.Telephone

/**
 * Python-flavor LXST telephony wiring — the [CallController] that lets
 * [CallCoordinator] drive a [Telephone] over [PythonNetworkTransport].
 *
 * Structural sibling of `:rns-backend-kt`'s `NativeCallManager`. The audio
 * stack ([Telephone] / [PacketRouter] / [AudioDevice]) is LXST-kt and
 * identical to the kotlin flavor — only the network transport differs
 * ([PythonNetworkTransport] routes through upstream Python RNS instead of
 * reticulum-kt). This is the "voice runs on LXST-kt regardless of backend"
 * decision from the dual-build plan.
 *
 * **On-device scope**: [setup] wires the audio↔transport bridges, constructs
 * the [Telephone], and registers as the [CallCoordinator]'s [CallController]
 * — so UI-initiated `call`/`answer`/`hangup` reach the audio stack. The
 * incoming-call path (registering an `lxst.telephony` `RNS.Destination` and
 * handling the inbound link + identity protocol) is `TODO(on-device)`: it
 * needs the RNS link-callback → Kotlin bridge that [PythonNetworkTransport]
 * also defers, and voice interop is an on-device Phase B verification item.
 *
 * Lifecycle note: [setup] is *not* called at Hilt-graph construction time —
 * mirroring `NativeCallManager`, which `NativeRnsBackendImpl` sets up during
 * `initialize()`. Wiring [setup] into the python backend's `initialize()`
 * lifecycle is on-device follow-up (see the module handoff doc).
 */
class PythonCallManager(
    private val context: Context,
    private val transport: PythonNetworkTransport,
    private val callCoordinator: CallCoordinator,
) : CallController {
    private companion object {
        const val TAG = "PythonCallManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packetRouter: PacketRouter = PacketRouter.getInstance(context)
    private val audioBridge: AudioDevice = AudioDevice.getInstance(context)

    /** The [Telephone], created in [setup]. */
    lateinit var telephone: Telephone
        private set

    /**
     * Wire the telephony stack. Call once after the python RNS stack is
     * initialized. Safe to call again after [shutdown].
     */
    fun setup() {
        Log.i(TAG, "Setting up python-flavor telephony stack")

        // PacketRouter -> transport (outbound audio + signals from the audio pipeline).
        packetRouter.setPacketHandler(
            object : AudioPacketHandler {
                override fun receiveAudioPacket(packet: ByteArray) = transport.sendPacket(packet)

                override fun receiveSignal(signal: Int) = transport.sendSignal(signal)
            },
        )

        // transport -> PacketRouter (inbound audio). PythonNetworkTransport
        // attaches this to the live RNS.Link's packet callback in
        // attachLinkPacketHandler() and demuxes the LXST msgpack wire format
        // ({FIELD_FRAMES: bin} / {FIELD_SIGNALLING: [code]}), so inbound audio
        // reaches the PacketRouter once a link is active. Set here so the
        // @Volatile field is populated before any link comes up.
        transport.setPacketCallback { data -> packetRouter.onInboundPacket(data) }

        // Telephone — its init block wires transport.signalCallback.
        telephone = Telephone(
            context = context,
            networkTransport = transport,
            audioBridge = audioBridge,
            networkPacketBridge = packetRouter,
            callBridge = callCoordinator,
        )

        // Register as CallController so CallCoordinator relays UI actions here.
        callCoordinator.setCallManager(this)

        // TODO(on-device): inbound-call accept path. The outbound path, the LXST
        // msgpack wire protocol, and the inbound-packet bridge are all wired now
        // (PythonNetworkTransport.attachLinkPacketHandler / handleIncomingPacket).
        // What remains is the incoming-call accept flow — port NativeCallManager's:
        //   1. registerTelephonyDestination(): build an IN RNS.Destination
        //      (localIdentity, IN, SINGLE, "lxst", "telephony") with a
        //      set_link_established_callback.
        //   2. on link-established: set_remote_identified_callback on the link;
        //      hold the link pending identity verification.
        //   3. on remote-identified: attach the packet handler to the inbound
        //      link + mark it active, then Telephone.onIncomingCall(...) so
        //      CallCoordinator rings the UI.
        // Each callback crosses Chaquopy the same way make_link_packet_handler
        // does; verifying the accept flow needs a real inbound call from a peer.
        Log.i(TAG, "Python telephony stack ready (outbound + LXST wire protocol wired; inbound-call accept is on-device follow-up)")
    }

    // ===== CallController — invoked by CallCoordinator on UI actions =====

    override fun call(destinationHash: String) {
        scope.launch {
            val destBytes = destinationHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            telephone.call(destBytes, Profile.DEFAULT)
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

    /** Tear down the telephony stack. Mirrors `NativeCallManager.shutdown()`. */
    fun shutdown() {
        Log.i(TAG, "Shutting down PythonCallManager")
        if (::telephone.isInitialized && telephone.isCallActive()) {
            runCatching { telephone.hangup() }
                .onFailure { Log.w(TAG, "Ignored error hanging up on shutdown", it) }
        }
        callCoordinator.setCallManager(null)
        runCatching { transport.teardownLink() }
            .onFailure { Log.w(TAG, "Ignored error tearing down transport on shutdown", it) }
        scope.cancel()
    }
}
