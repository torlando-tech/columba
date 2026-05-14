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
 *   provides this as `@Singleton` for the `:reticulum`-process Hilt graph.
 * - The strangler-fig facade in `:reticulum/protocol/NativeReticulumProtocol`
 *   wraps this and forwards every `ReticulumProtocol` call to the matching
 *   sub-interface; A.10 deletes the facade.
 */
class NativeRnsBackend(
    appContext: Context? = null,
    rnodeHostBridge: RNodeHostBridge? = null,
) : RnsBackend {
    /**
     * Internal worker holding all shared state and the actual reticulum-kt /
     * lxmf-kt / lxst-kt calls. Public so the legacy facade in `:reticulum`
     * can reach individual methods that aren't in any [RnsBackend]
     * sub-interface (`getStatus`, version helpers) until A.10.
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
