package network.columba.app.rns.ipc

import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.ipc.callback.IRnsCapabilitiesCallback
import network.columba.app.rns.ipc.callback.IRnsCoreCallback
import network.columba.app.rns.ipc.callback.IRnsLxmfCallback
import network.columba.app.rns.ipc.callback.IRnsNomadnetCallback
import network.columba.app.rns.ipc.callback.IRnsTelemetryCallback
import network.columba.app.rns.ipc.callback.IRnsTelephonyCallback
import network.columba.app.rns.ipc.callback.IRnsTransportAdminCallback
import network.columba.app.rns.ipc.server.ObserverHub
import network.columba.app.rns.ipc.server.ServerRnsCore
import network.columba.app.rns.ipc.server.ServerRnsLxmf
import network.columba.app.rns.ipc.server.ServerRnsNomadnet
import network.columba.app.rns.ipc.server.ServerRnsTelemetry
import network.columba.app.rns.ipc.server.ServerRnsTelephony
import network.columba.app.rns.ipc.server.ServerRnsTransportAdmin

/**
 * Host-process adapter that exposes a Kotlin [RnsBackend] implementation
 * across binder via the [IRnsBackend.Stub] AIDL contract.
 *
 * Owns the six per-sub-interface server adapters; each is constructed lazily
 * on the first accessor call so a UI that only consumes part of the surface
 * (e.g., a voice-only flow that never touches `RnsTransportAdmin`) doesn't
 * pay the construction cost of the others. Accessor methods are themselves
 * `oneway` with typed callbacks so the host can never block the binder
 * dispatch thread when handing back a binder reference.
 *
 * The [scope] passed in should match the host service's process lifetime —
 * cancelling it tears down all server adapters' upstream Flow collectors and
 * any in-flight `dispatch { ... }` coroutines.
 */
class RnsBackendServer(
    private val impl: RnsBackend,
    private val scope: CoroutineScope,
) : IRnsBackend.Stub() {
    // Lazy server-adapter construction. `synchronized` guards the double-check
    // pattern so two concurrent UI processes (binder dispatch is multi-thread)
    // can't both construct an adapter for the same sub-interface.
    @Volatile private var coreServer: ServerRnsCore? = null
    @Volatile private var lxmfServer: ServerRnsLxmf? = null
    @Volatile private var telephonyServer: ServerRnsTelephony? = null
    @Volatile private var telemetryServer: ServerRnsTelemetry? = null
    @Volatile private var nomadnetServer: ServerRnsNomadnet? = null
    @Volatile private var transportAdminServer: ServerRnsTransportAdmin? = null
    private val constructLock = Any()

    private val capabilitiesHub = ObserverHub(
        scope = scope,
        upstream = { impl.capabilities },
        callbackBinder = { cb: IRnsCapabilitiesCallback -> cb.asBinder() },
        emit = { cb, value -> cb.onCapabilities(value) },
    )

    override fun getCore(cb: IRnsCoreCallback) {
        val server = coreServer ?: synchronized(constructLock) {
            coreServer ?: ServerRnsCore(impl.core, scope).also { coreServer = it }
        }
        try { cb.onCore(server) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun getLxmf(cb: IRnsLxmfCallback) {
        val server = lxmfServer ?: synchronized(constructLock) {
            lxmfServer ?: ServerRnsLxmf(impl.lxmf, scope).also { lxmfServer = it }
        }
        try { cb.onLxmf(server) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun getTelephony(cb: IRnsTelephonyCallback) {
        val server = telephonyServer ?: synchronized(constructLock) {
            telephonyServer ?: ServerRnsTelephony(impl.telephony, scope).also { telephonyServer = it }
        }
        try { cb.onTelephony(server) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun getTelemetry(cb: IRnsTelemetryCallback) {
        val server = telemetryServer ?: synchronized(constructLock) {
            telemetryServer ?: ServerRnsTelemetry(impl.telemetry, scope).also { telemetryServer = it }
        }
        try { cb.onTelemetry(server) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun getNomadnet(cb: IRnsNomadnetCallback) {
        val server = nomadnetServer ?: synchronized(constructLock) {
            nomadnetServer ?: ServerRnsNomadnet(impl.nomadnet, scope).also { nomadnetServer = it }
        }
        try { cb.onNomadnet(server) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun getTransportAdmin(cb: IRnsTransportAdminCallback) {
        val server = transportAdminServer ?: synchronized(constructLock) {
            transportAdminServer
                ?: ServerRnsTransportAdmin(impl.transportAdmin, scope).also { transportAdminServer = it }
        }
        try { cb.onTransportAdmin(server) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun getCapabilities(cb: IRnsCapabilitiesCallback) {
        // Snapshot read from the StateFlow — no observer machinery for the
        // fire-once path; same pattern as IRnsCore.getCurrentNetworkStatus.
        try { cb.onCapabilities(impl.capabilities.value) } catch (_: RemoteException) { /* client dead */ }
    }

    override fun registerCapabilitiesObserver(cb: IRnsCapabilitiesCallback) =
        capabilitiesHub.registerObserver(cb)

    override fun unregisterCapabilitiesObserver(cb: IRnsCapabilitiesCallback) =
        capabilitiesHub.unregisterObserver(cb)
}
