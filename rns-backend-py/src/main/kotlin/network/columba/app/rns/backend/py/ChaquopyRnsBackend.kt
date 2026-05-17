package network.columba.app.rns.backend.py

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import tech.torlando.lxst.core.CallCoordinator

/**
 * Root [RnsBackend] implementation backed by **upstream Python RNS/LXMF**
 * via Chaquopy.
 *
 * The python sibling of `NativeRnsBackend`. Where the kotlin backend rolls
 * all six sub-interfaces into one `NativeRnsBackendImpl` (A.8 deviation #8),
 * the python flavor follows the plan's literal six-class split — each
 * `PythonRns*` is a focused adapter over upstream RNS/LXMF, sharing one
 * [PythonRnsRuntime] (PyObject registries + lifecycle) and one
 * [PythonEventBridge] (the upstream-callback → SharedFlow translation).
 *
 * Construction shape: the Hilt module in
 * `:rns-host/src/pythonBackend/.../HostBackendModule.kt` provides this as
 * `@Singleton` for the `:reticulum`-process graph, with per-sub-interface
 * `@Provides` mirroring the kotlin flavor. [callCoordinator] is passed in
 * by that module — `:rns-backend-py` is deliberately *not* on the
 * `NoCallCoordinatorGetInstanceOutsideHost` allowlist, so it never calls
 * `CallCoordinator.getInstance()` itself.
 *
 * The event bridge is wired in [PythonRnsCore.initialize] (it needs the live
 * `Reticulum` + `LXMRouter`, which only exist post-`runtime.start()`).
 */
class ChaquopyRnsBackend(
    appContext: Context,
    callCoordinator: CallCoordinator,
) : RnsBackend {
    /** Shared PyObject runtime — registries + Reticulum/LXMRouter lifecycle. */
    val runtime: PythonRnsRuntime = PythonRnsRuntime(appContext)

    /** Shared upstream-callback → SharedFlow translation layer. */
    val events: PythonEventBridge = PythonEventBridge()

    private val coreImpl = PythonRnsCore(runtime, events)
    private val lxmfImpl = PythonRnsLxmf(runtime, events)

    /** Concrete telephony impl — exposed so PythonCallManager can install its profile-aware hook. */
    val telephonyImpl = PythonRnsTelephony(callCoordinator)
    private val telemetryImpl = PythonRnsTelemetry(runtime, events)
    private val nomadnetImpl = PythonRnsNomadnet(runtime)
    private val transportAdminImpl = PythonRnsTransportAdmin(runtime, events)

    override val core: RnsCore = coreImpl
    override val lxmf: RnsLxmf = lxmfImpl
    override val telephony: RnsTelephony = telephonyImpl
    override val telemetry: RnsTelemetry = telemetryImpl
    override val nomadnet: RnsNomadnet = nomadnetImpl
    override val transportAdmin: RnsTransportAdmin = transportAdminImpl

    private val _capabilities: MutableStateFlow<BackendCapabilities> =
        MutableStateFlow(PYTHON_CAPABILITIES)
    override val capabilities: StateFlow<BackendCapabilities> = _capabilities.asStateFlow()
}
