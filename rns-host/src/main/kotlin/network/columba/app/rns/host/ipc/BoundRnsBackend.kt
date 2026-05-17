package network.columba.app.rns.host.ipc

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.host.ReticulumServiceConnection

/**
 * UI-process [RnsBackend] implementation that proxies to the live
 * `:reticulum`-process backend via AIDL.
 *
 * Construction is cheap — the underlying [ReticulumServiceConnection.bind]
 * call kicks off `bindService` with `BIND_AUTO_CREATE` so the `:reticulum`
 * FGS spawns (or is reused) the moment a `BoundRnsBackend` is provided by
 * Hilt. The six sub-interface wrappers ([BoundRnsCore], [BoundRnsLxmf], …)
 * each hold a [StateFlow] reference to the same in-flight binding and
 * republish their Flow/StateFlow accessors via `flatMapLatest` so a
 * binder-death + rebind cycle (START_STICKY recovery) surfaces as a brief
 * pause in observable streams rather than a terminal completion.
 *
 * Eager [SharingStarted.Eagerly] sharing is intentional: lazy sharing would
 * mean the first observer pays the bind latency; eager keeps the connection
 * in-flight as soon as Hilt resolves the singleton at app start, so UI code
 * that subscribes mid-launch sees the binding ready.
 */
class BoundRnsBackend(
    context: Context,
    scope: CoroutineScope,
) : RnsBackend {
    /**
     * Live binding to the `:reticulum` backend, or `null` between
     * `onServiceDisconnected` and the follow-up `onServiceConnected`. Sub-
     * wrappers await `filterNotNull().first()` on suspend calls and
     * republish on Flow/StateFlow accessors.
     */
    private val connectionFlow: StateFlow<RnsBackend?> =
        ReticulumServiceConnection
            .bind(context, scope)
            .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    override val core: RnsCore = BoundRnsCore(connectionFlow, scope)
    override val lxmf: RnsLxmf = BoundRnsLxmf(connectionFlow, scope)
    override val telephony: RnsTelephony = BoundRnsTelephony(connectionFlow, scope)
    override val telemetry: RnsTelemetry = BoundRnsTelemetry(connectionFlow, scope)
    override val nomadnet: RnsNomadnet = BoundRnsNomadnet(connectionFlow, scope)
    override val transportAdmin: RnsTransportAdmin = BoundRnsTransportAdmin(connectionFlow, scope)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val capabilities: StateFlow<BackendCapabilities> =
        connectionFlow
            .filterNotNull()
            .flatMapLatest { it.capabilities }
            .stateIn(scope, SharingStarted.Eagerly, BackendCapabilities.UNKNOWN)
}
