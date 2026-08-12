/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package network.columba.app.rns.backend.kt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import network.columba.app.rns.api.call.AcceptedCallLifecycle
import network.columba.app.rns.api.call.CallAttemptDirection
import network.columba.app.rns.api.call.CallAttemptRequest
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.link.Link
import network.reticulum.transport.Transport
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.telephone.Telephone

/**
 * Native Reticulum mechanics for the inbound LXST destination and link handshake.
 *
 * Reduced version of the donor adapter: no transport session IDs, no rejected-link
 * history owners, no Telephone session machinery. Accepted incoming calls are made
 * durable through the shared [AcceptedCallLifecycle] owner BEFORE the ringing callback
 * / UI is exposed. Admission failures (e.g. another Columba attempt still owns
 * terminal processing, or a durable insert failure) tear the link down without
 * creating any history or ringing.
 */
internal class NativeInboundCallAdapter(
    private val deliveryIdentity: Identity,
    private val transport: NativeNetworkTransport,
    private val telephone: () -> Telephone,
    private val owner: AcceptedCallLifecycle,
    private val localIdentityHash: () -> String,
    private val scope: CoroutineScope,
    private val onCallerIdentified: (Link, Identity) -> Unit,
) {
    @Volatile
    private var destination: Destination? = null

    @Volatile
    var enabled: Boolean = true
        private set

    fun register() {
        try {
            val registered =
                Destination.create(
                    identity = deliveryIdentity,
                    direction = DestinationDirection.IN,
                    type = DestinationType.SINGLE,
                    appName = LXST_APP_NAME,
                    LXST_ASPECT,
                )
            registered.setLinkEstablishedCallback { candidate ->
                (candidate as? Link)?.let(::onLinkEstablished)
            }
            destination = registered
            Log.i(TAG, "lxst.telephony destination registered: ${registered.hexHash.take(16)}")
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
        runCatching { registered.announce(appData) }
            .onSuccess {
                Log.i(
                    TAG,
                    "Announced lxst.telephony ${registered.hexHash.take(16)}" +
                        if (appData != null) " (appData=${appData.size} bytes)" else "",
                )
            }.onFailure { Log.e(TAG, "Failed to announce lxst.telephony", it) }
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        destination?.let { registered ->
            runCatching { Transport.deregisterDestination(registered) }
                .onSuccess { Log.i(TAG, "lxst.telephony destination deregistered") }
                .onFailure { Log.w(TAG, "deregisterDestination failed", it) }
        }
        destination = null
    }

    fun enable() {
        if (enabled && destination != null) return
        enabled = true
        register()
        announce()
    }

    fun clear() {
        destination = null
    }

    /** Signal STATUS_BUSY to the caller before tearing the link down. */
    fun busy(link: Link) {
        runCatching { link.send(packSignal(Signalling.STATUS_BUSY)) }
            .onFailure { Log.w(TAG, "Failed to signal busy before rejecting inbound link", it) }
    }

    /** Tear down an inbound link without any rejection history owner. */
    fun teardown(link: Link) {
        link.teardown()
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
        link: Link,
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

    internal fun onLinkEstablished(link: Link) {
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
            link.setRemoteIdentifiedCallback { identifiedLink, identity ->
                onCallerIdentified(identifiedLink, identity)
            }
            link.setLinkClosedCallback { closed ->
                Log.d(TAG, "Inbound call link closed before identification: reason=${closed.teardownReason}")
            }
            link.send(packSignal(Signalling.STATUS_AVAILABLE))
        } catch (error: Exception) {
            Log.e(TAG, "Could not initialize inbound call callbacks", error)
            teardown(link)
        }
    }

    private fun packSignal(signal: Int): ByteArray {
        val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(FIELD_SIGNALLING)
        packer.packArrayHeader(1)
        packer.packInt(signal)
        return packer.toByteArray()
    }

    private companion object {
        const val TAG = "NativeInboundCallAdapter"
        const val LXST_APP_NAME = "lxst"
        const val LXST_ASPECT = "telephony"
        const val FIELD_SIGNALLING = 0x00
    }
}
