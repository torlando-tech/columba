package network.columba.app.rns.host

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.backend.py.PyEventCallback
import network.columba.app.rns.backend.py.PyTwoArgCallback
import network.columba.app.rns.backend.py.PythonRnsRuntime
import tech.torlando.lxst.audio.Signalling
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
 * **Architectural choice** — v0.10.x had a single Python flavor and put
 * all of this logic in `python/lxst_modules/call_manager.py` (~800 LOC of
 * link state, identity verification, signalling, TX batching). The
 * dual-build's slim-Python rule (`rns-backend-py/CLAUDE.md`) explicitly
 * bans new `rns_*.py` facade files, so the v0.10.x behavior is ported
 * here in Kotlin instead. `event_bridge.py` carries only the thin
 * callback-bridge primitives needed to convert `RNS.Link` /
 * `RNS.Destination` Python callbacks into [PyEventCallback] /
 * [PyTwoArgCallback] invocations (same pattern as
 * `make_link_packet_handler` for inbound audio).
 *
 * Lifecycle: [setup] is called by `PythonRnsCore.initialize` after the
 * Python RNS stack is up and `wireEventBridge` has run — mirrors the
 * `NativeRnsBackendImpl.setupNativeTelephone` call site in `initialize`.
 */
class PythonCallManager(
    private val context: Context,
    private val runtime: PythonRnsRuntime,
    private val transport: PythonNetworkTransport,
    private val callCoordinator: CallCoordinator,
    /**
     * Backend status flow — we observe this to auto-trigger [setup] when
     * `PythonRnsCore.initialize` flips status to `READY`. That's the
     * earliest moment `runtime.localIdentity` is populated and the
     * `lxst.telephony` destination can be registered.
     */
    private val backendStatusFlow: StateFlow<NetworkStatus>,
) : CallController {
    private companion object {
        const val TAG = "PythonCallManager"
        const val LXST_APP_NAME = "lxst"
        const val LXST_ASPECT = "telephony"

        /**
         * LXST wire-protocol field IDs — match
         * [NativeCallManager.packSignal] + [PythonNetworkTransport]
         * (FIELD_SIGNALLING / FIELD_FRAMES). Frozen for Sideband interop.
         */
        const val FIELD_SIGNALLING = 0x00
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packetRouter: PacketRouter = PacketRouter.getInstance(context)
    private val audioBridge: AudioDevice = AudioDevice.getInstance(context)

    @Volatile
    private var setupRan: Boolean = false

    init {
        // Auto-run setup() when the backend reaches READY. Done from
        // here (not from `:rns-backend-py.PythonRnsCore.initialize` where
        // `NativeRnsBackendImpl.setupNativeTelephone` runs inline on the
        // kotlin flavor) because `:rns-backend-py` can't depend on
        // `:rns-host` — and PythonCallManager lives in `:rns-host`.
        // Observing the public `networkStatus` flow keeps the layering
        // clean and matches the eager setup behavior of the kotlin flavor.
        scope.launch {
            backendStatusFlow.filter { it == NetworkStatus.READY }.first()
            if (!setupRan) {
                runCatching { setup() }.onFailure {
                    Log.e(TAG, "Auto-setup on backend READY failed", it)
                }
            }
        }
    }

    /** The [Telephone], created in [setup]. */
    lateinit var telephone: Telephone
        private set

    /**
     * Inbound `lxst.telephony` `RNS.Destination` registered in [setup].
     * Held to prevent GC and to allow cleanup on [shutdown]. PyObject
     * because upstream `RNS.Destination` is a Python class.
     */
    @Volatile
    private var telephonyDestination: PyObject? = null

    /**
     * Wire the telephony stack and register the inbound-call destination.
     *
     * Call once after [PythonRnsRuntime.start] + `wireEventBridge` have
     * completed (the local identity and `RNS.Reticulum` are required).
     * Safe to call again after [shutdown].
     */
    fun setup() {
        if (setupRan) {
            Log.d(TAG, "setup() already ran — ignoring duplicate call")
            return
        }
        Log.i(TAG, "Setting up python-flavor telephony stack")

        val localIdentity = runtime.localIdentity
            ?: run {
                Log.e(TAG, "Cannot set up telephony: runtime.localIdentity is null (call after runtime.start)")
                return
            }
        setupRan = true

        // 1. Provide local identity to transport for proactive identify on outbound links.
        transport.setLocalIdentity(localIdentity)

        // 2. PacketRouter -> transport (outbound audio + signals from the audio pipeline).
        packetRouter.setPacketHandler(
            object : AudioPacketHandler {
                override fun receiveAudioPacket(packet: ByteArray) = transport.sendPacket(packet)

                override fun receiveSignal(signal: Int) = transport.sendSignal(signal)
            },
        )

        // 3. transport -> PacketRouter (inbound audio).
        transport.setPacketCallback { data -> packetRouter.onInboundPacket(data) }

        // 4. Telephone — its init block wires transport.signalCallback.
        telephone = Telephone(
            context = context,
            networkTransport = transport,
            audioBridge = audioBridge,
            networkPacketBridge = packetRouter,
            callBridge = callCoordinator,
        )

        // 5. Register as CallController so CallCoordinator relays UI actions here.
        callCoordinator.setCallManager(this)

        // 6. Register lxst.telephony IN destination so RNS routes incoming call links here.
        registerTelephonyDestination(localIdentity)

        // 7. Announce immediately so peers can resolve a path to our telephony destination
        //    even before the next LXMF auto-announce fires.
        announce()

        Log.i(TAG, "Python telephony stack ready")
    }

    /**
     * Build the IN `lxst.telephony` destination and hook the
     * link-established callback through `event_bridge.py`. Mirrors
     * `NativeCallManager.registerTelephonyDestination`.
     *
     * `set_proof_strategy(PROVE_NONE)` matches the v0.10.x Python flow
     * (`call_manager.py:235`) — link establishment doesn't need
     * cryptographic proof of identity; the per-call identify dance
     * (`set_remote_identified_callback`) handles peer auth.
     */
    private fun registerTelephonyDestination(localIdentity: PyObject) {
        try {
            val destClass = runtime.rnsModule["Destination"]
                ?: error("RNS.Destination not resolvable")
            val destination = runtime.rnsModule.callAttr(
                "Destination",
                localIdentity,
                destClass["IN"],
                destClass["SINGLE"],
                LXST_APP_NAME,
                LXST_ASPECT,
            )

            // Disable proof-of-identity-on-link — matches v0.10.x.
            destClass["PROVE_NONE"]?.let { proveNone ->
                runCatching { destination.callAttr("set_proof_strategy", proveNone) }
                    .onFailure { Log.w(TAG, "set_proof_strategy(PROVE_NONE) failed", it) }
            }

            // Wrap our Kotlin onInboundLinkEstablished as a Python closure
            // (set_link_established_callback expects `callback(link)`).
            val onEstablished = PyEventCallback { linkPy ->
                runCatching { onInboundLinkEstablished(linkPy) }
                    .onFailure { Log.e(TAG, "onInboundLinkEstablished threw", it) }
            }
            val handler = runtime.eventBridge.callAttr("make_link_established_handler", onEstablished)
            destination.callAttr("set_link_established_callback", handler)

            telephonyDestination = destination
            val hexHash = destination["hash"]
                ?.toJava(ByteArray::class.java)
                ?.joinToString("") { "%02x".format(it) }
                .orEmpty()
            Log.i(TAG, "lxst.telephony destination registered: ${hexHash.take(16)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register lxst.telephony destination", e)
        }
    }

    /**
     * Announce the local `lxst.telephony` destination so remote peers can
     * resolve a path to it (without a path, their `RNS.Transport.has_path`
     * fails and `establishLink` returns false). Public so the caller can
     * couple announces to LXMF reannounces.
     */
    fun announce(appData: ByteArray? = null) {
        val dest = telephonyDestination ?: run {
            Log.w(TAG, "Cannot announce lxst.telephony: destination not registered")
            return
        }
        try {
            if (appData != null) {
                val pyData = runtime.python.builtins.callAttr("bytes", appData)
                dest.callAttr("announce", pyData)
            } else {
                dest.callAttr("announce")
            }
            Log.i(TAG, "Announced lxst.telephony")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to announce lxst.telephony", e)
        }
    }

    // ===== Incoming call handling =====

    /**
     * RNS fires this when a remote peer opens a link to our
     * lxst.telephony destination. Mirrors
     * `NativeCallManager.onInboundLinkEstablished`.
     */
    private fun onInboundLinkEstablished(link: PyObject) {
        Log.i(TAG, "Inbound call link arrived")

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line busy — signalling busy and rejecting inbound link")
            sendSignalOnLink(link, Signalling.STATUS_BUSY)
            runCatching { link.callAttr("teardown") }
            return
        }

        // Install callbacks BEFORE signalling availability — caller can identify
        // immediately after STATUS_AVAILABLE and win the race against our
        // callback registration otherwise. Mirrors the kt-flavor comment.
        val onIdentified = PyTwoArgCallback { linkPy, identityPy ->
            runCatching { onCallerIdentified(linkPy, identityPy) }
                .onFailure { Log.e(TAG, "onCallerIdentified threw", it) }
        }
        val identifyHandler = runtime.eventBridge.callAttr(
            "make_remote_identified_handler",
            onIdentified,
        )
        runCatching { link.callAttr("set_remote_identified_callback", identifyHandler) }
            .onFailure { Log.w(TAG, "set_remote_identified_callback failed", it) }

        val onClosed = PyEventCallback { _ ->
            Log.d(TAG, "Inbound call link closed before identification")
        }
        val closedHandler = runtime.eventBridge.callAttr("make_link_closed_handler", onClosed)
        runCatching { link.callAttr("set_link_closed_callback", closedHandler) }
            .onFailure { Log.w(TAG, "set_link_closed_callback failed", it) }

        // Tell the caller we're reachable so they call link.identify().
        sendSignalOnLink(link, Signalling.STATUS_AVAILABLE)
    }

    /**
     * RNS fires this once the inbound caller has proved their identity.
     * Mirrors `NativeCallManager.onCallerIdentified` — accept the link
     * into transport, notify `Telephone`, and signal `RINGING`.
     */
    private fun onCallerIdentified(link: PyObject, identity: PyObject) {
        val identityHash = identity["hash"]
            ?.toJava(ByteArray::class.java)
            ?.joinToString("") { "%02x".format(it) }
            .orEmpty()
        Log.i(TAG, "Caller identified: ${identityHash.take(16)}")

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line became busy after identify — signalling busy")
            sendSignalOnLink(link, Signalling.STATUS_BUSY)
            runCatching { link.callAttr("teardown") }
            return
        }

        // Accept the link into transport so signals + audio flow.
        transport.acceptInboundLink(link)

        // Notify Telephone — sets isIncomingCall, activates ring tone,
        // updates CallCoordinator so the UI shows the incoming-call screen.
        telephone.onIncomingCall(identityHash)

        // Signal ringing to remote (dial tone trigger).
        transport.sendSignal(Signalling.STATUS_RINGING)
    }

    /**
     * Send a single LXST signalling byte directly on a specific link.
     * Used during the inbound handshake when the link is not yet
     * promoted to [PythonNetworkTransport.activeLink], so we can't go
     * through [PythonNetworkTransport.sendSignal].
     *
     * Wire format MUST match
     * [PythonNetworkTransport.sendSignal] / `NativeCallManager.packSignal`:
     * msgpack `{FIELD_SIGNALLING(0x00): [signal]}`.
     */
    private fun sendSignalOnLink(link: PyObject, signal: Int) {
        runCatching {
            val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(1)
            packer.packInt(FIELD_SIGNALLING)
            packer.packArrayHeader(1)
            packer.packInt(signal)
            val pyData = runtime.python.builtins.callAttr("bytes", packer.toByteArray())
            runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send")
        }.onFailure { Log.w(TAG, "sendSignalOnLink($signal) failed: ${it.message}") }
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
        telephonyDestination = null
        scope.cancel()
    }
}
