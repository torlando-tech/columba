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
import network.columba.app.rns.backend.py.ChaquopyRnsBackend
import network.columba.app.rns.backend.py.PyEventCallback
import network.columba.app.rns.backend.py.PyTwoArgCallback
import network.columba.app.rns.backend.py.PythonRnsRuntime
import network.columba.app.rns.host.persistence.CallsFromContactsGate
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.AudioPacketHandler
import tech.torlando.lxst.core.CallController
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.PacketRouter
import tech.torlando.lxst.telephone.Profile
import tech.torlando.lxst.telephone.Telephone

/**
 * Python-flavor LXST telephony — sibling of `NativeCallManager`. The
 * audio stack (LXST-kt) is identical across backends; only the network
 * transport differs ([PythonNetworkTransport] routes through Python RNS
 * via Chaquopy). All call-state logic lives here in Kotlin per the
 * slim-Python rule (`:rns-backend-py/CLAUDE.md`); `event_bridge.py`
 * carries only the per-callback bridge primitives.
 *
 * Setup auto-fires when the backend reaches READY — observer pattern
 * because `:rns-backend-py` can't call into `:rns-host` to do it inline.
 */
class PythonCallManager(
    private val context: Context,
    private val backend: ChaquopyRnsBackend,
    private val transport: PythonNetworkTransport,
    private val callCoordinator: CallCoordinator,
    private val settingsAccessor: ServiceSettingsAccessor,
    private val contactsGate: CallsFromContactsGate,
) : CallController {
    private val runtime: PythonRnsRuntime = backend.runtime
    private val backendStatusFlow: StateFlow<NetworkStatus> = backend.core.networkStatus
    private companion object {
        const val TAG = "PythonCallManager"
        const val LXST_APP_NAME = "lxst"
        const val LXST_ASPECT = "telephony"

        /** LXST signalling field id — must match NativeCallManager + PythonNetworkTransport. */
        const val FIELD_SIGNALLING = 0x00
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packetRouter: PacketRouter = PacketRouter.getInstance(context)
    private val audioBridge: AudioDevice = AudioDevice.getInstance(context)

    @Volatile
    private var setupRan: Boolean = false

    /**
     * Master incoming-calls toggle state. Mirrors `:rns-backend-kt`'s
     * sibling. Read by [onInboundLinkEstablished] as a TOCTOU guard
     * against a link that crossed the wire just before
     * [disableIncoming] ran; written only inside [incomingLock].
     */
    @Volatile
    private var incomingDisabled: Boolean = false

    /** Serialises [disableIncoming] / [enableIncoming] transitions. */
    private val incomingLock = Any()

    init {
        // Auto-run setup() at backend READY (:rns-backend-py can't reach
        // here, so observe its status flow instead of being called inline
        // like NativeRnsBackendImpl.setupNativeTelephone). Also install
        // the profile-aware call hook so PythonRnsTelephony.initiateCall
        // can pass the codec profile through (default CallCoordinator
        // path drops it), and the setIncomingEnabled hook so the master
        // AIDL toggle reaches this manager from the UI process.
        scope.launch {
            backendStatusFlow.filter { it == NetworkStatus.READY }.first()
            if (!setupRan) {
                runCatching { setup() }.onFailure {
                    Log.e(TAG, "Auto-setup on backend READY failed", it)
                }
            }
            backend.telephonyImpl.profileAwareCallHook = { destHex, profileCode ->
                call(destHex, profileCode)
            }
            backend.telephonyImpl.setIncomingEnabledHook = ::setIncomingEnabled
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

        transport.setLocalIdentity(localIdentity)
        packetRouter.setPacketHandler(
            object : AudioPacketHandler {
                override fun receiveAudioPacket(packet: ByteArray) = transport.sendPacket(packet)
                override fun receiveSignal(signal: Int) = transport.sendSignal(signal)
            },
        )
        transport.setPacketCallback { data -> packetRouter.onInboundPacket(data) }
        telephone = Telephone(
            context = context,
            networkTransport = transport,
            audioBridge = audioBridge,
            networkPacketBridge = packetRouter,
            callBridge = callCoordinator,
        )
        callCoordinator.setCallManager(this)
        registerTelephonyDestination(localIdentity)
        announce()

        // Cold-start application of the persisted master toggle. If the
        // user turned voice calls OFF before the last :reticulum tear-down
        // (or before this fresh-start), apply that now — we register +
        // immediately deregister rather than skipping registration, so the
        // re-enable path uses the same single helper. Marginally wasteful
        // (~ms of work) but correct + minimal-conditional.
        if (!settingsAccessor.getAllowVoiceCalls()) {
            Log.i(TAG, "Cold-start: Allow voice calls = false, deregistering destination")
            disableIncoming()
        }

        Log.i(TAG, "Python telephony stack ready")
    }

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

            // PROVE_NONE: per-call identify-callback handles peer auth, no
            // crypto proof on link establishment. Matches upstream LXST.
            destClass["PROVE_NONE"]?.let { proveNone ->
                runCatching { destination.callAttr("set_proof_strategy", proveNone) }
                    .onFailure { Log.w(TAG, "set_proof_strategy(PROVE_NONE) failed", it) }
            }

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

    /** Public so callers can couple lxst.telephony announces to LXMF reannounces. */
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

        // Defense-in-depth TOCTOU guard: an inbound link may have been
        // admitted by RNS in the brief window before
        // Transport.deregister_destination returned. Drop it silently —
        // STATUS_AVAILABLE has not yet been sent, so the caller sees the
        // same "remote went away" timeout as the toggle-off path below.
        if (incomingDisabled) {
            Log.d(TAG, "Inbound link but incoming disabled, silently tearing down")
            runCatching { link.callAttr("teardown") }
            return
        }

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line busy — signalling busy and rejecting inbound link")
            sendSignalOnLink(link, Signalling.STATUS_BUSY)
            runCatching { link.callAttr("teardown") }
            return
        }

        // Install identify + closed callbacks BEFORE STATUS_AVAILABLE —
        // caller can identify immediately on receipt and win the race
        // against our registration otherwise.
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

        sendSignalOnLink(link, Signalling.STATUS_AVAILABLE)
    }

    private fun onCallerIdentified(link: PyObject, identity: PyObject) {
        val identityHash = identity["hash"]
            ?.toJava(ByteArray::class.java)
            ?.joinToString("") { "%02x".format(it) }
            .orEmpty()
        Log.i(TAG, "Caller identified: ${identityHash.take(16)}")

        // Calls-from-contacts-only gate. Fires BEFORE STATUS_RINGING and
        // BEFORE Telephone.onIncomingCall, so the originator only sees a
        // wait-time timeout (no STATUS_BUSY / STATUS_REJECTED) and this
        // device shows no UI / no ringtone. Same fail-open semantics as
        // ServicePersistenceManager.shouldBlockUnknownSender on the
        // message side.
        if (contactsGate.shouldSilentlyDrop(identityHash)) {
            Log.i(TAG, "Calls-only-from-contacts: dropping ${identityHash.take(16)}")
            runCatching { link.callAttr("teardown") }
            return
        }

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line became busy after identify — signalling busy")
            sendSignalOnLink(link, Signalling.STATUS_BUSY)
            runCatching { link.callAttr("teardown") }
            return
        }

        transport.acceptInboundLink(link)
        telephone.onIncomingCall(identityHash)
        transport.sendSignal(Signalling.STATUS_RINGING)
    }

    /**
     * Send a signal directly on a specific link, bypassing
     * [PythonNetworkTransport.sendSignal] which targets `activeLink`.
     * Used during the inbound handshake before the link is accepted.
     * Wire format must match the transport's sendSignal.
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
        call(destinationHash, null)
    }

    /** Profile-aware overload — invoked from PythonRnsTelephony via the hook. */
    fun call(destinationHash: String, profileCode: Int?) {
        scope.launch {
            val destBytes = destinationHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val profile = profileCode?.let { code ->
                Profile.fromId(code).also {
                    if (it == null) Log.w(TAG, "Unknown LXST profile code 0x${code.toString(16)}, defaulting")
                }
            } ?: Profile.DEFAULT
            Log.i(TAG, "Calling with profile ${profile.abbreviation} (0x${profile.id.toString(16)})")
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

    // ===== Master incoming-calls toggle =====

    /**
     * Apply [setIncomingEnabled] for this manager.
     *
     * Invoked by [network.columba.app.rns.backend.py.PythonRnsTelephony]'s
     * `setIncomingEnabledHook` (wired in [init]) when the UI calls
     * `RnsTelephony.setIncomingEnabled(...)` across the AIDL boundary.
     *
     * Idempotent — applying the same state twice is a no-op.
     */
    fun setIncomingEnabled(enabled: Boolean) {
        if (enabled) enableIncoming() else disableIncoming()
    }

    private fun disableIncoming() = synchronized(incomingLock) {
        if (incomingDisabled) return@synchronized
        incomingDisabled = true
        telephonyDestination?.let { dest ->
            runCatching {
                runtime.rnsModule["Transport"]!!.callAttr("deregister_destination", dest)
                Log.i(TAG, "lxst.telephony destination deregistered")
            }.onFailure { Log.w(TAG, "deregister_destination failed", it) }
        }
        telephonyDestination = null
        if (::telephone.isInitialized && telephone.isCallActive()) {
            // Hang up active call so the remote sees a clean drop rather
            // than dead air. Hangup signal must travel BEFORE the
            // destination is gone, but we've already nulled it above —
            // that's fine because outgoing audio uses transport.activeLink
            // (set on accept) not the destination.
            runCatching { telephone.hangup() }
                .onFailure { Log.w(TAG, "Ignored hangup error during disableIncoming", it) }
        }
    }

    private fun enableIncoming() = synchronized(incomingLock) {
        if (!incomingDisabled && telephonyDestination != null) return@synchronized
        incomingDisabled = false
        val ident = runtime.localIdentity
        if (ident == null) {
            // setup() hasn't run yet (cold-start before backend READY).
            // The flag is now `false`; setup() will register normally.
            Log.d(TAG, "enableIncoming: localIdentity not ready, will register at setup")
            return@synchronized
        }
        registerTelephonyDestination(ident)
        announce()
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
