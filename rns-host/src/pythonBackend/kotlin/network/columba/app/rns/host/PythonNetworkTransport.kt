package network.columba.app.rns.host

import android.util.Log
import com.chaquo.python.PyObject
import network.columba.app.rns.backend.py.PyEventCallback
import network.columba.app.rns.backend.py.PythonRnsRuntime
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.telephone.NetworkTransport

/**
 * LXST [NetworkTransport] over upstream Python RNS, via Chaquopy.
 *
 * The python sibling of `:rns-backend-kt`'s `NativeNetworkTransport`. The
 * audio codec + call state machine stay in LXST-kt (identical on both
 * backends); this adapter does only the raw RNS link establishment +
 * packet/signal send-receive, routed through [PythonRnsRuntime]'s live
 * PyObject handles.
 *
 * Patterned on release/v0.10.x's `PythonNetworkTransport.kt` — but
 * **PyObject-direct**: v0.10.x wrapped a Python `call_manager` module, which
 * the slim-Python design does not restore, so link/packet operations go
 * straight to upstream `RNS.Link` / `RNS.Packet` here.
 *
 * Lives in `:rns-host/src/pythonBackend/` (not `:rns-backend-py`) because it
 * needs both lxst-kt's [NetworkTransport] (a `:rns-host` `api` dep) and
 * [PythonRnsRuntime] (`:rns-backend-py`, via `pythonBackendImplementation`).
 *
 * **On-device scope**: link establishment + outbound send are wired here;
 * the inbound path (an `RNS.Link` packet callback bridged back into the
 * Kotlin [setPacketCallback] lambda) is marked `TODO(on-device)` — bridging
 * a Python callback to a Kotlin lambda over Chaquopy needs device iteration,
 * and voice interop is an on-device Phase B verification item regardless.
 */
class PythonNetworkTransport(
    private val runtime: PythonRnsRuntime,
) : NetworkTransport {
    private companion object {
        const val TAG = "PythonNetworkTransport"

        /** `RNS.Link.ACTIVE` status constant. */
        const val LINK_ACTIVE = 2

        /** Poll cadence + budget while waiting for link establishment. */
        const val LINK_POLL_MS = 100L
        const val LINK_TIMEOUT_MS = 15_000L

        /**
         * LXST wire-protocol msgpack map keys — MUST match NativeNetworkTransport
         * (`sendPacket` / `sendSignal` / `handleIncomingPacket`). Audio frames go
         * out as `{FIELD_FRAMES: binary}`, signals as `{FIELD_SIGNALLING: [code]}`.
         */
        const val FIELD_SIGNALLING = 0x00
        const val FIELD_FRAMES = 0x01
    }

    @Volatile
    private var activeLink: PyObject? = null

    @Volatile
    private var packetCallback: ((ByteArray) -> Unit)? = null

    @Volatile
    private var signalCallback: ((Int) -> Unit)? = null

    /**
     * Local `RNS.Identity` used to prove ourselves to a remote peer.
     *
     * Set once by [PythonCallManager.setup] so [establishLink] can call
     * `link.identify(localIdentity)` proactively after the outbound link
     * reaches ACTIVE — mirrors `NativeNetworkTransport.setLocalIdentity` +
     * the post-ACTIVE identify call. Without this, the v0.10.x
     * "STATUS_AVAILABLE → callee triggers identify" handshake races with
     * Kotlin callback installation on real devices.
     */
    @Volatile
    private var localIdentity: PyObject? = null

    fun setLocalIdentity(identity: PyObject) {
        localIdentity = identity
    }

    /**
     * Tracks which link we're tearing down locally so the
     * `set_link_closed_callback` handler can distinguish our own
     * `teardownLink()` from a remote teardown and not echo
     * STATUS_AVAILABLE back into our own Telephone (which is already
     * handling our local hangup). Mirrors
     * `NativeNetworkTransport.locallyClosingLink`.
     */
    @Volatile
    private var locallyClosingLink: PyObject? = null

    override val isLinkActive: Boolean
        get() = activeLink?.let { link ->
            runCatching { link["status"]?.toJava(Int::class.javaObjectType) == LINK_ACTIVE }
                .getOrDefault(false)
        } ?: false

    override suspend fun establishLink(destinationHash: ByteArray): Boolean {
        return runCatching {
            val transport = runtime.rnsModule["Transport"] ?: error("RNS.Transport missing")
            val pyHash = runtime.python.builtins.callAttr("bytes", destinationHash)

            // Request a path if we don't have one, then wait briefly for it.
            if (transport.callAttr("has_path", pyHash)?.toJava(Boolean::class.javaObjectType) != true) {
                transport.callAttr("request_path", pyHash)
                var waited = 0L
                while (waited < LINK_TIMEOUT_MS &&
                    transport.callAttr("has_path", pyHash)?.toJava(Boolean::class.javaObjectType) != true
                ) {
                    Thread.sleep(LINK_POLL_MS)
                    waited += LINK_POLL_MS
                }
            }

            // Recall the destination identity and build the OUT lxst.telephony destination.
            val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
            val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
            val identity = identityClass.callAttr("recall", pyHash)
                ?: run {
                    Log.w(TAG, "establishLink: no identity recalled for destination")
                    return@runCatching false
                }
            val destination = runtime.rnsModule.callAttr(
                "Destination",
                identity,
                destClass["OUT"],
                destClass["SINGLE"],
                "lxst",
                "telephony",
            )

            // Establish the link and poll until ACTIVE.
            val link = runtime.rnsModule.callAttr("Link", destination)
            var waited = 0L
            while (waited < LINK_TIMEOUT_MS &&
                link["status"]?.toJava(Int::class.javaObjectType) != LINK_ACTIVE
            ) {
                Thread.sleep(LINK_POLL_MS)
                waited += LINK_POLL_MS
            }
            val active = link["status"]?.toJava(Int::class.javaObjectType) == LINK_ACTIVE
            if (active) {
                activeLink = link
                attachLinkPacketHandler(link)
                attachLinkClosedHandler(link)
                // Proactively identify so the callee can match us against
                // its allow-list and ring its UI without waiting for a
                // STATUS_AVAILABLE round-trip — mirrors
                // `NativeNetworkTransport.establishLink` (kt backend line
                // 173). The v0.10.x Python `call_manager.py` deferred
                // identify until STATUS_AVAILABLE arrived; that worked
                // on a single-stack build but races with callback
                // installation on real Android hardware.
                val id = localIdentity
                if (id != null) {
                    runCatching {
                        val identified = link.callAttr("identify", id)
                            ?.toJava(Boolean::class.javaObjectType) ?: false
                        Log.i(TAG, "establishLink: proactive identify=$identified")
                    }.onFailure { Log.w(TAG, "Proactive identify failed", it) }
                } else {
                    Log.w(TAG, "establishLink: link ACTIVE but localIdentity is null")
                }
                Log.i(TAG, "establishLink: link ACTIVE")
            } else {
                Log.w(TAG, "establishLink: link did not reach ACTIVE within ${LINK_TIMEOUT_MS}ms")
                runCatching { link.callAttr("teardown") }
            }
            active
        }.getOrElse {
            Log.e(TAG, "establishLink failed", it)
            false
        }
    }

    override fun teardownLink() {
        val link = activeLink ?: return
        activeLink = null
        // Mark this teardown as locally initiated so the link-closed
        // callback (if it fires synchronously) doesn't echo
        // STATUS_AVAILABLE back into the Telephone state machine,
        // which is already handling our local hangup.
        locallyClosingLink = link
        runCatching { link.callAttr("teardown") }
            .onFailure { Log.w(TAG, "teardownLink failed", it) }
    }

    override fun sendPacket(encodedFrame: ByteArray) {
        val link = activeLink ?: return
        runCatching {
            // Wire format MUST match NativeNetworkTransport: audio is a msgpack
            // map {FIELD_FRAMES(0x01): binary}. Sending the raw frame would make
            // Python<->Kotlin (and Python<->Sideband) voice mutually unintelligible.
            val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(1)
            packer.packInt(FIELD_FRAMES)
            packer.packBinaryHeader(encodedFrame.size)
            packer.writePayload(encodedFrame)
            val pyData = runtime.python.builtins.callAttr("bytes", packer.toByteArray())
            runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send")
        }.onFailure {
            // Fire-and-forget: real-time audio tolerates packet loss.
            Log.v(TAG, "sendPacket dropped: ${it.message}")
        }
    }

    override fun sendSignal(signal: Int) {
        val link = activeLink ?: return
        runCatching {
            // Wire format MUST match NativeNetworkTransport: a signal is a msgpack
            // map {FIELD_SIGNALLING(0x00): [signal]} (the value is an array).
            val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(1)
            packer.packInt(FIELD_SIGNALLING)
            packer.packArrayHeader(1)
            packer.packInt(signal)
            val pyData = runtime.python.builtins.callAttr("bytes", packer.toByteArray())
            runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send")
        }.onFailure { Log.w(TAG, "sendSignal failed", it) }
    }

    override fun setPacketCallback(callback: (ByteArray) -> Unit) {
        // The RNS-side packet handler is attached once per link in
        // attachLinkPacketHandler(); it reads this @Volatile field live, so a
        // callback set before or after establishLink() is picked up with no
        // re-attach.
        packetCallback = callback
    }

    override fun setSignalCallback(callback: (Int) -> Unit) {
        // Same as setPacketCallback: the per-link RNS packet handler demuxes
        // signalling from audio (see handleIncomingPacket) and reads this
        // @Volatile field live.
        signalCallback = callback
    }

    /**
     * Attach a single RNS packet callback to [link] that demuxes inbound frames
     * and fans them out to [packetCallback] / [signalCallback].
     *
     * `RNS.Link.set_packet_callback` takes a `callback(message, packet)` Python
     * callable; `event_bridge.make_link_packet_handler` wraps a [PyEventCallback]
     * into one. The [PyEventCallback] reads the `@Volatile` callback fields live,
     * so it works regardless of whether they were set before or after the link
     * was established.
     *
     * **On-device scope**: the wire framing in [handleIncomingPacket] matches
     * `NativeNetworkTransport` byte-for-byte, so cross-backend voice is correct
     * by construction. What still needs a real call to verify is the Chaquopy
     * round-trip itself — that `event_bridge.make_link_packet_handler`'s closure
     * actually re-enters Kotlin when RNS fires it on its packet thread.
     */
    /**
     * Accept an inbound `RNS.Link` from an incoming caller as the active
     * call link. Mirrors `NativeNetworkTransport.acceptInboundLink`.
     *
     * Called by [PythonCallManager.onCallerIdentified] after the remote
     * has proved their identity. Wires the link's packet callback so
     * audio + signals flow into [packetCallback] / [signalCallback]
     * (which `PythonCallManager.setup` connected to the `PacketRouter`
     * and the LXST signalling pipeline).
     */
    fun acceptInboundLink(link: PyObject) {
        Log.i(TAG, "Accepting inbound call link")
        activeLink = link
        attachLinkPacketHandler(link)
        attachLinkClosedHandler(link)
    }

    /**
     * Attach an RNS `set_link_closed_callback` to [link] that fires
     * [Signalling.STATUS_AVAILABLE] into [signalCallback] on a
     * *remote* teardown. The Telephone state machine treats
     * STATUS_AVAILABLE as the canonical "call ended" trigger and
     * tears down its own audio/UI. Local teardowns (our own
     * [teardownLink] call) are filtered via [locallyClosingLink] to
     * avoid double-handling — Telephone has already started its
     * hangup path before [teardownLink] is invoked.
     *
     * Mirrors `NativeNetworkTransport.handleLinkClosed` /
     * `installLinkCallbacks` from the kotlin flavor.
     */
    private fun attachLinkClosedHandler(link: PyObject) {
        runCatching {
            val sink = PyEventCallback { closedLinkPy ->
                runCatching {
                    val wasLocalTeardown = locallyClosingLink === link
                    Log.i(
                        TAG,
                        "Link closed: localTeardown=$wasLocalTeardown",
                    )
                    if (wasLocalTeardown) {
                        locallyClosingLink = null
                    }
                    if (activeLink === link) {
                        activeLink = null
                    }
                    if (!wasLocalTeardown) {
                        // Remote tore down — tell Telephone the call is over.
                        signalCallback?.invoke(Signalling.STATUS_AVAILABLE)
                    }
                }.onFailure { Log.e(TAG, "link-closed dispatch failed", it) }
            }
            val handler = runtime.eventBridge.callAttr("make_link_closed_handler", sink)
            link.callAttr("set_link_closed_callback", handler)
            Log.i(TAG, "Attached RNS link-closed callback")
        }.onFailure { Log.e(TAG, "failed to attach link-closed handler", it) }
    }

    private fun attachLinkPacketHandler(link: PyObject) {
        runCatching {
            val sink = PyEventCallback { payload ->
                runCatching {
                    handleIncomingPacket(payload.toJava(ByteArray::class.java))
                }.onFailure { Log.w(TAG, "inbound link packet dispatch failed", it) }
            }
            val handler = runtime.eventBridge.callAttr("make_link_packet_handler", sink)
            link.callAttr("set_packet_callback", handler)
            Log.i(TAG, "Attached RNS link packet handler")
        }.onFailure { Log.e(TAG, "failed to attach link packet handler", it) }
    }

    /**
     * Demux an inbound link frame. Ported from `NativeNetworkTransport` so both
     * backends speak the same LXST wire protocol:
     *  - `{FIELD_SIGNALLING(0x00): [signal, ...]}` -> [signalCallback] per signal
     *  - `{FIELD_FRAMES(0x01): binary}`           -> [packetCallback]
     *  - raw 1-byte                               -> [signalCallback] (legacy)
     *  - anything else                            -> [packetCallback] (raw audio)
     */
    private fun handleIncomingPacket(data: ByteArray) {
        if (data.isEmpty()) return
        val unpacked = runCatching {
            org.msgpack.core.MessagePack.newDefaultUnpacker(data).unpackValue()
        }.getOrNull()

        if (unpacked != null && unpacked.isMapValue) {
            val map = unpacked.asMapValue().map()
            val signalling = map[org.msgpack.value.ValueFactory.newInteger(FIELD_SIGNALLING.toLong())]
            val frames = map[org.msgpack.value.ValueFactory.newInteger(FIELD_FRAMES.toLong())]
            if (signalling != null && signalling.isArrayValue) {
                for (sig in signalling.asArrayValue()) {
                    signalCallback?.invoke(sig.asIntegerValue().toInt())
                }
            }
            if (frames != null && frames.isBinaryValue) {
                packetCallback?.invoke(frames.asBinaryValue().asByteArray())
            }
        } else if (data.size == 1) {
            signalCallback?.invoke(data[0].toInt() and 0xFF)
        } else {
            packetCallback?.invoke(data)
        }
    }
}
