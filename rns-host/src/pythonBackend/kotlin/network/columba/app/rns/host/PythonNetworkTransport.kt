package network.columba.app.rns.host

import android.util.Log
import com.chaquo.python.PyObject
import network.columba.app.rns.backend.py.PythonRnsRuntime
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
    }

    @Volatile
    private var activeLink: PyObject? = null

    @Volatile
    private var packetCallback: ((ByteArray) -> Unit)? = null

    @Volatile
    private var signalCallback: ((Int) -> Unit)? = null

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
                // TODO(on-device): bridge link's inbound packet callback into
                // packetCallback / signalCallback. Needs a Python-callable that
                // re-enters Kotlin — verify the Chaquopy bridging on a device.
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
        runCatching { link.callAttr("teardown") }
            .onFailure { Log.w(TAG, "teardownLink failed", it) }
    }

    override fun sendPacket(encodedFrame: ByteArray) {
        val link = activeLink ?: return
        runCatching {
            val pyData = runtime.python.builtins.callAttr("bytes", encodedFrame)
            runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send")
        }.onFailure {
            // Fire-and-forget: real-time audio tolerates packet loss.
            Log.v(TAG, "sendPacket dropped: ${it.message}")
        }
    }

    override fun sendSignal(signal: Int) {
        val link = activeLink ?: return
        runCatching {
            // Signalling codes go on the wire as a single byte, same as
            // NativeNetworkTransport's packSignal().
            val pyData = runtime.python.builtins.callAttr("bytes", byteArrayOf(signal.toByte()))
            runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send")
        }.onFailure { Log.w(TAG, "sendSignal failed", it) }
    }

    override fun setPacketCallback(callback: (ByteArray) -> Unit) {
        packetCallback = callback
        // TODO(on-device): attach to the live RNS.Link's packet callback.
    }

    override fun setSignalCallback(callback: (Int) -> Unit) {
        signalCallback = callback
        // TODO(on-device): attach to the live RNS.Link's packet callback and
        // demux single-byte signalling frames from audio frames.
    }
}
