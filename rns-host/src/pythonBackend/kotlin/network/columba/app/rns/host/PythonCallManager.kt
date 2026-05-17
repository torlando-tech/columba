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

    init {
        // Auto-run setup() at backend READY (:rns-backend-py can't reach
        // here, so observe its status flow instead of being called inline
        // like NativeRnsBackendImpl.setupNativeTelephone). Also install
        // the profile-aware call hook so PythonRnsTelephony.initiateCall
        // can pass the codec profile through (default CallCoordinator
        // path drops it).
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
