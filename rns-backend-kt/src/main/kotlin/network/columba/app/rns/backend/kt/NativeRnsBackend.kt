package network.columba.app.rns.backend.kt

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

/**
 * Root [RnsBackend] implementation backed by reticulum-kt + lxmf-kt + lxst-kt.
 *
 * Holds a single [NativeRnsBackendImpl] worker (which implements all 6
 * sub-interfaces directly) and exposes it through the six [RnsBackend]
 * accessors. The sub-interface fields are the *same instance* of
 * [NativeRnsBackendImpl] cast to the target sub-interface type — there's no
 * dispatch overhead, the cast is statically resolvable since
 * [NativeRnsBackendImpl] implements every sub-interface.
 *
 * Construction shape:
 * - Hilt module in `:rns-host/src/kotlinBackend/.../HostBackendModule.kt`
 *   provides this as `@Singleton` for the `:reticulum`-process Hilt graph,
 *   along with per-sub-interface `@Provides` so the UI process injects
 *   `RnsCore` / `RnsLxmf` / `RnsTelephony` / etc. directly. The A.10
 *   strangler-fig facade and the whole `:reticulum` module were removed in
 *   A.10c / A.12 once every consumer had moved onto the sub-interfaces.
 */
class NativeRnsBackend(
    appContext: Context? = null,
    rnodeHostBridge: RNodeHostBridge? = null,
) : RnsBackend {
    init {
        // Defense-in-depth A.10 assertion: this constructor MUST only run in
        // the `:reticulum` FGS process. Constructing it elsewhere means the
        // process-aware Hilt module
        // (`network.columba.app.rns.host.di.ProcessAwareBackendModule`)
        // resolved the local backend in the UI process, which would cause
        // RNS interface threads to bind sockets in the wrong pid. Debug-only
        // — release builds skip the check so a defensive bug never crashes
        // the FGS. Tolerates null processName (unit tests have no
        // Application context).
        if (BuildConfig.DEBUG && appContext != null) {
            val processName = if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                null
            }
            check(processName == null || processName.contains(":reticulum")) {
                "NativeRnsBackend constructed in wrong process: pid=" +
                    "${android.os.Process.myPid()} processName=$processName — " +
                    "expected `:reticulum`. Check ProcessAwareBackendModule wiring."
            }
        }
    }

    /**
     * Internal worker holding all shared state and the actual reticulum-kt /
     * lxmf-kt / lxst-kt calls. Public for in-process callers that need the
     * few methods not surfaced on any [RnsBackend] sub-interface (legacy
     * `getStatus` / version helpers).
     */
    val impl: NativeRnsBackendImpl = NativeRnsBackendImpl(
        appContext = appContext,
        rnodeHostBridge = rnodeHostBridge,
    )

    override val core: RnsCore = impl
    override val lxmf: RnsLxmf = impl
    override val telephony: RnsTelephony = impl
    override val telemetry: RnsTelemetry = impl
    override val nomadnet: RnsNomadnet = impl
    override val transportAdmin: RnsTransportAdmin = impl

    /**
     * Native-backend capability snapshot. Held in a StateFlow rather than a
     * static val to match the [RnsBackend.capabilities] contract — the UI
     * subscribes for runtime-mutable downgrades (e.g., LXST jar absence in a
     * stripped-down test build). [NATIVE_CAPABILITIES] is the default value;
     * Phase B's encoder parity test will mutate it through this StateFlow.
     */
    private val _capabilities: MutableStateFlow<BackendCapabilities> = MutableStateFlow(NATIVE_CAPABILITIES)
    override val capabilities: StateFlow<BackendCapabilities> = _capabilities.asStateFlow()
}
