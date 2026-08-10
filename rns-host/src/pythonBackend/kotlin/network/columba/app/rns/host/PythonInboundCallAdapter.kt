/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.host

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.columba.app.rns.api.call.AcceptedCallLifecycle
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.columba.app.rns.backend.py.PyEventCallback
import network.columba.app.rns.backend.py.PyTwoArgCallback
import network.columba.app.rns.backend.py.PythonRnsRuntime
import org.msgpack.core.MessagePack
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.telephone.Telephone

/**
 * Python/RNS mechanics for the inbound LXST destination and link handshake.
 *
 * Reduced version of the donor adapter: no transport session IDs, no rejected-link
 * history owners, no Telephone session machinery. Accepted incoming calls are made
 * durable through the shared [AcceptedCallLifecycle] owner BEFORE the ringing callback
 * / UI is exposed. Admission failures (another Columba attempt still owns terminal
 * processing, or a durable insert failure) tear the link down without creating any
 * history or ringing.
 */
internal class PythonInboundCallAdapter(
    private val runtime: PythonRnsRuntime,
    private val transport: PythonNetworkTransport,
    private val telephone: () -> Telephone,
    private val owner: AcceptedCallLifecycle,
    private val localIdentityHash: () -> String,
    private val scope: CoroutineScope,
    private val onCallerIdentified: (PyObject, PyObject) -> Unit,
) {
    @Volatile
    private var destination: PyObject? = null

    @Volatile
    var enabled: Boolean = true
        private set

    internal var initializeInboundLink: (PyObject) -> Unit = ::installInboundCallbacksAndAvailability

    fun register(localIdentity: PyObject) {
        try {
            val destinationClass = runtime.rnsModule["Destination"]
                ?: error("RNS.Destination not resolvable")
            val registered = runtime.rnsModule.callAttr(
                "Destination",
                localIdentity,
                destinationClass["IN"],
                destinationClass["SINGLE"],
                LXST_APP_NAME,
                LXST_ASPECT,
            )
            destinationClass["PROVE_NONE"]?.let { proveNone ->
                runCatching { registered.callAttr("set_proof_strategy", proveNone) }
                    .onFailure { Log.w(TAG, "set_proof_strategy(PROVE_NONE) failed", it) }
            }
            val callback = PyEventCallback { link ->
                runCatching { onLinkEstablished(link) }
                    .onFailure { Log.e(TAG, "onLinkEstablished threw", it) }
            }
            val handler = runtime.eventBridge.callAttr("make_link_established_handler", callback)
            registered.callAttr("set_link_established_callback", handler)
            destination = registered
            Log.i(TAG, "lxst.telephony destination registered")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to register lxst.telephony destination", error)
        }
    }

    fun announce(appData: ByteArray? = null) {
        val registered = destination
        if (registered == null) {
            if (enabled) Log.w(TAG, "Cannot announce lxst.telephony: destination not registered")
            return
        }
        runCatching {
            if (appData == null) {
                registered.callAttr("announce")
            } else {
                val pyData = runtime.python.builtins.callAttr("bytes", appData)
                registered.callAttr("announce", pyData)
            }
        }.onSuccess { Log.i(TAG, "Announced lxst.telephony") }
            .onFailure { Log.e(TAG, "Failed to announce lxst.telephony", it) }
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        destination?.let { registered ->
            runCatching { runtime.rnsModule["Transport"]!!.callAttr("deregister_destination", registered) }
                .onSuccess { Log.i(TAG, "lxst.telephony destination deregistered") }
                .onFailure { Log.w(TAG, "deregister_destination failed", it) }
        }
        destination = null
    }

    fun enable(localIdentity: PyObject) {
        if (enabled && destination != null) return
        enabled = true
        register(localIdentity)
        announce()
    }

    fun clear() {
        destination = null
    }

    /** Signal STATUS_BUSY to the caller before tearing the link down. */
    fun busy(link: PyObject) {
        runCatching { sendSignal(link, Signalling.STATUS_BUSY) }
            .onFailure { Log.w(TAG, "Failed to signal busy before rejecting inbound link", it) }
    }

    /** Tear down an inbound link without any rejection history owner. */
    fun teardown(link: PyObject) {
        runCatching { link.callAttr("teardown") }
            .onFailure { Log.w(TAG, "Inbound link teardown failed", it) }
    }

    /**
     * Persist the accepted incoming attempt (durably) before exposing ringing.
     *
     * Runs inside the shared owner's admission: the attempt is durably inserted and
     * ringing is persisted BEFORE the expose lambda accepts the link and rings. If
     * admission fails (another attempt owns terminal processing, or a durable insert /
     * ringing persist failure) the link is torn down and NO history remains.
     */
    fun expose(
        link: PyObject,
        identityHash: String,
    ) {
        scope.launch {
            val result =
                owner.admitIncoming(
                    CallAttemptRequest(
                        direction = CallAttemptDirection.INCOMING,
                        localIdentityHash = localIdentityHash(),
                        remoteIdentityHash = identityHash,
                        codecProfileCode = null,
                    ),
                ) { _ ->
                    transport.acceptInboundLink(link)
                    telephone().onIncomingCall(identityHash)
                    transport.sendSignal(Signalling.STATUS_RINGING)
                }
            if (result.isFailure) {
                Log.w(TAG, "Incoming admission rejected: ${result.exceptionOrNull()}")
                teardown(link)
            }
        }
    }

    internal fun onLinkEstablished(link: PyObject) {
        Log.i(TAG, "Inbound call link arrived")
        if (!enabled) {
            Log.d(TAG, "Inbound link but incoming disabled, silently tearing down")
            teardown(link)
            return
        }
        if (telephone().isCallActive()) {
            Log.w(TAG, "Line busy — signalling busy and rejecting inbound link")
            busy(link)
            teardown(link)
            return
        }
        try {
            initializeInboundLink(link)
        } catch (error: Exception) {
            Log.e(TAG, "Could not initialize inbound call callbacks", error)
            teardown(link)
        }
    }

    private fun installInboundCallbacksAndAvailability(link: PyObject) {
        val identified = PyTwoArgCallback { identifiedLink, identity ->
            runCatching { onCallerIdentified(identifiedLink, identity) }
                .onFailure { Log.e(TAG, "onCallerIdentified threw", it) }
        }
        val identifiedHandler = runtime.eventBridge.callAttr("make_remote_identified_handler", identified)
        link.callAttr("set_remote_identified_callback", identifiedHandler)
        val closed = PyEventCallback { Log.d(TAG, "Inbound call link closed before identification") }
        val closedHandler = runtime.eventBridge.callAttr("make_link_closed_handler", closed)
        link.callAttr("set_link_closed_callback", closedHandler)
        sendSignal(link, Signalling.STATUS_AVAILABLE)
    }

    private fun sendSignal(
        link: PyObject,
        signal: Int,
    ) {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(FIELD_SIGNALLING)
        packer.packArrayHeader(1)
        packer.packInt(signal)
        val pyData = runtime.python.builtins.callAttr("bytes", packer.toByteArray())
        // RNS.Packet.send() returns a PacketReceipt (or None/False), never a Java
        // Boolean true, so it must not be asserted as a Boolean success flag. Match
        // the legacy sendSignalOnLink behavior: send best-effort and log failures.
        runCatching { runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send") }
            .onFailure { Log.w(TAG, "sendSignal($signal) failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "PythonInboundCallAdapter"
        const val LXST_APP_NAME = "lxst"
        const val LXST_ASPECT = "telephony"
        const val FIELD_SIGNALLING = 0x00
    }
}
