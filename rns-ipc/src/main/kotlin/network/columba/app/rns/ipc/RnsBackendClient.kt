package network.columba.app.rns.ipc

import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.ipc.callback.IRnsCapabilitiesCallback
import network.columba.app.rns.ipc.callback.IRnsCoreCallback
import network.columba.app.rns.ipc.callback.IRnsLxmfCallback
import network.columba.app.rns.ipc.callback.IRnsNomadnetCallback
import network.columba.app.rns.ipc.callback.IRnsTelemetryCallback
import network.columba.app.rns.ipc.callback.IRnsTelephonyCallback
import network.columba.app.rns.ipc.callback.IRnsTransportAdminCallback
import network.columba.app.rns.ipc.client.ClientRnsCore
import network.columba.app.rns.ipc.client.ClientRnsLxmf
import network.columba.app.rns.ipc.client.ClientRnsNomadnet
import network.columba.app.rns.ipc.client.ClientRnsTelemetry
import network.columba.app.rns.ipc.client.ClientRnsTelephony
import network.columba.app.rns.ipc.client.ClientRnsTransportAdmin
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * UI-process adapter that turns a connected [IRnsBackend] AIDL proxy into the
 * Kotlin [RnsBackend] contract callers expect.
 *
 * Construction is split from the bind orchestration deliberately: the
 * `:rns-host`-side `Intent` and `ServiceConnection` don't exist in `:rns-ipc`'s
 * compile classpath, so the host-side binder is delivered in via [connect]
 * once the caller's `ServiceConnection.onServiceConnected` fires. Tests
 * construct a `RnsBackendClient` directly off an in-process
 * [network.columba.app.rns.ipc.RnsBackendServer] stub without involving
 * binder at all.
 *
 * Sub-interface accessors hold the binders fetched at [connect] time. The
 * accessor AIDL methods on [IRnsBackend] are themselves `oneway` with typed
 * callbacks, so the bind path makes six round trips up-front and caches the
 * results for the lifetime of the binding. If the host crashes and the
 * service rebinds, the caller is expected to construct a fresh
 * `RnsBackendClient` — there's no in-place rebind path.
 */
class RnsBackendClient(
    private val scope: CoroutineScope,
) : RnsBackend {
    private val capabilitiesState = MutableStateFlow(BackendCapabilities.UNKNOWN)

    private var coreClient: ClientRnsCore? = null
    private var lxmfClient: ClientRnsLxmf? = null
    private var telephonyClient: ClientRnsTelephony? = null
    private var telemetryClient: ClientRnsTelemetry? = null
    private var nomadnetClient: ClientRnsNomadnet? = null
    private var transportAdminClient: ClientRnsTransportAdmin? = null

    /**
     * Bind the client to the supplied [remote]. Fetches the six sub-interface
     * binders + the initial capabilities snapshot via the AIDL accessor
     * callbacks, then registers the capabilities observer so runtime mutations
     * propagate. Suspends until all seven oneway-callback round trips have
     * settled; after this call, the [RnsBackend] sub-interface properties are
     * safe to read.
     */
    suspend fun connect(remote: IRnsBackend) {
        coreClient = ClientRnsCore(fetchCore(remote), scope)
        lxmfClient = ClientRnsLxmf(fetchLxmf(remote), scope)
        telephonyClient = ClientRnsTelephony(fetchTelephony(remote))
        telemetryClient = ClientRnsTelemetry(fetchTelemetry(remote), scope)
        nomadnetClient = ClientRnsNomadnet(fetchNomadnet(remote), scope)
        transportAdminClient = ClientRnsTransportAdmin(fetchTransportAdmin(remote), scope)

        // Seed capabilities synchronously so the first downstream read sees a
        // real snapshot, not the placeholder UNKNOWN default. Observer
        // registration follows so subsequent host-side mutations land into the
        // same StateFlow.
        runCatching { capabilitiesState.value = fetchCapabilities(remote) }
        scope.launch {
            runCatching {
                val observer = object : IRnsCapabilitiesCallback.Stub() {
                    override fun onCapabilities(caps: BackendCapabilities?) {
                        if (caps != null) capabilitiesState.value = caps
                    }
                }
                remote.registerCapabilitiesObserver(observer)
            }
        }
    }

    override val capabilities: StateFlow<BackendCapabilities>
        get() = capabilitiesState.asStateFlow()

    override val core: RnsCore
        get() = coreClient ?: error("RnsBackendClient.connect() not invoked")
    override val lxmf: RnsLxmf
        get() = lxmfClient ?: error("RnsBackendClient.connect() not invoked")
    override val telephony: RnsTelephony
        get() = telephonyClient ?: error("RnsBackendClient.connect() not invoked")
    override val telemetry: RnsTelemetry
        get() = telemetryClient ?: error("RnsBackendClient.connect() not invoked")
    override val nomadnet: RnsNomadnet
        get() = nomadnetClient ?: error("RnsBackendClient.connect() not invoked")
    override val transportAdmin: RnsTransportAdmin
        get() = transportAdminClient ?: error("RnsBackendClient.connect() not invoked")

    private suspend fun fetchCore(remote: IRnsBackend): IRnsCore =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsCoreCallback.Stub() {
                override fun onCore(service: IRnsCore) {
                    if (delivered.compareAndSet(false, true)) cont.resume(service)
                }
            }
            try { remote.getCore(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }

    private suspend fun fetchLxmf(remote: IRnsBackend): IRnsLxmf =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsLxmfCallback.Stub() {
                override fun onLxmf(service: IRnsLxmf) {
                    if (delivered.compareAndSet(false, true)) cont.resume(service)
                }
            }
            try { remote.getLxmf(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }

    private suspend fun fetchTelephony(remote: IRnsBackend): IRnsTelephony =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsTelephonyCallback.Stub() {
                override fun onTelephony(service: IRnsTelephony) {
                    if (delivered.compareAndSet(false, true)) cont.resume(service)
                }
            }
            try { remote.getTelephony(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }

    private suspend fun fetchTelemetry(remote: IRnsBackend): IRnsTelemetry =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsTelemetryCallback.Stub() {
                override fun onTelemetry(service: IRnsTelemetry) {
                    if (delivered.compareAndSet(false, true)) cont.resume(service)
                }
            }
            try { remote.getTelemetry(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }

    private suspend fun fetchNomadnet(remote: IRnsBackend): IRnsNomadnet =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsNomadnetCallback.Stub() {
                override fun onNomadnet(service: IRnsNomadnet) {
                    if (delivered.compareAndSet(false, true)) cont.resume(service)
                }
            }
            try { remote.getNomadnet(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }

    private suspend fun fetchTransportAdmin(remote: IRnsBackend): IRnsTransportAdmin =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsTransportAdminCallback.Stub() {
                override fun onTransportAdmin(service: IRnsTransportAdmin) {
                    if (delivered.compareAndSet(false, true)) cont.resume(service)
                }
            }
            try { remote.getTransportAdmin(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }

    private suspend fun fetchCapabilities(remote: IRnsBackend): BackendCapabilities =
        suspendCancellableCoroutine { cont ->
            val delivered = AtomicBoolean(false)
            val cb = object : IRnsCapabilitiesCallback.Stub() {
                override fun onCapabilities(caps: BackendCapabilities?) {
                    if (delivered.compareAndSet(false, true)) {
                        cont.resume(caps ?: BackendCapabilities.UNKNOWN)
                    }
                }
            }
            try { remote.getCapabilities(cb) } catch (e: RemoteException) {
                if (delivered.compareAndSet(false, true)) cont.resumeWith(Result.failure(e))
            }
        }
}
