package network.columba.app.rns.host

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.backend.py.ChaquopyRnsBackend
import tech.torlando.lxst.core.CallCoordinator
import javax.inject.Singleton

/**
 * Python-flavor backend wiring for `:rns-host`.
 *
 * Active when the `rnsImpl=pythonBackend` flavor resolves. Provides
 * [ChaquopyRnsBackend] (and its [RnsBackend] view + the six per-sub-interface
 * `@Provides`) into the `:reticulum`-process Hilt graph — the python sibling
 * of the kotlinBackend `HostBackendModule`.
 *
 * The [CallCoordinator] is constructed here (this module *is* on the
 * `NoCallCoordinatorGetInstanceOutsideHost` Detekt allowlist) and passed into
 * [ChaquopyRnsBackend] — `:rns-backend-py` itself never calls `getInstance()`.
 *
 * [PythonNetworkTransport] + [PythonCallManager] are also provided so the LXST
 * voice path is compile-wired and has a clear injection point; calling
 * `PythonCallManager.setup()` at the right moment in the backend lifecycle is
 * on-device follow-up (see PythonCallManager's kdoc).
 *
 * Single-source-binding rule (A.8 deviation #15): every `RnsBackend` /
 * sub-interface `@Provides` lives here, not in `:app`, to avoid Hilt's
 * build-wide duplicate-binding detection.
 */
@Module
@InstallIn(SingletonComponent::class)
object HostBackendModule {
    @Provides
    @Singleton
    fun provideCallCoordinator(): CallCoordinator = CallCoordinator.getInstance()

    @Provides
    @Singleton
    fun provideChaquopyRnsBackend(
        @ApplicationContext context: Context,
        callCoordinator: CallCoordinator,
    ): ChaquopyRnsBackend =
        ChaquopyRnsBackend(appContext = context, callCoordinator = callCoordinator)

    @Provides
    @Singleton
    fun providePythonNetworkTransport(backend: ChaquopyRnsBackend): PythonNetworkTransport =
        PythonNetworkTransport(backend.runtime)

    /**
     * Eagerly-constructed `PythonCallManager` — its `init` block
     * subscribes to `backend.core.networkStatus` and auto-runs
     * [PythonCallManager.setup] once the backend reaches `READY`,
     * which is when `PythonRnsRuntime.localIdentity` is populated.
     * Mirrors the kotlin flavor's `NativeRnsBackendImpl.setupNativeTelephone`
     * invocation point.
     */
    @Provides
    @Singleton
    fun providePythonCallManager(
        @ApplicationContext context: Context,
        backend: ChaquopyRnsBackend,
        transport: PythonNetworkTransport,
        callCoordinator: CallCoordinator,
    ): PythonCallManager =
        PythonCallManager(
            context = context,
            runtime = backend.runtime,
            transport = transport,
            callCoordinator = callCoordinator,
            backendStatusFlow = backend.core.networkStatus,
        )

    @Provides
    @Singleton
    fun provideRnsBackend(
        backend: ChaquopyRnsBackend,
        // Force-construct PythonCallManager eagerly so its init-time
        // backend-status observer is subscribed before `PythonRnsCore.initialize`
        // can flip status to READY. Without an injection edge here, Hilt
        // would lazily skip @Singleton construction — and a late-subscribing
        // observer would miss the READY event, leaving telephony un-set-up.
        // No code reads this param; the side effect is the construction.
        @Suppress("UNUSED_PARAMETER") eagerCallManager: PythonCallManager,
    ): RnsBackend = backend

    // Per-sub-interface providers — same single-source-binding rule + shape as
    // the kotlinBackend HostBackendModule.

    @Provides
    @Singleton
    fun provideRnsCore(rnsBackend: RnsBackend): RnsCore = rnsBackend.core

    @Provides
    @Singleton
    fun provideRnsLxmf(rnsBackend: RnsBackend): RnsLxmf = rnsBackend.lxmf

    @Provides
    @Singleton
    fun provideRnsTelephony(rnsBackend: RnsBackend): RnsTelephony = rnsBackend.telephony

    @Provides
    @Singleton
    fun provideRnsTelemetry(rnsBackend: RnsBackend): RnsTelemetry = rnsBackend.telemetry

    @Provides
    @Singleton
    fun provideRnsNomadnet(rnsBackend: RnsBackend): RnsNomadnet = rnsBackend.nomadnet

    @Provides
    @Singleton
    fun provideRnsTransportAdmin(rnsBackend: RnsBackend): RnsTransportAdmin =
        rnsBackend.transportAdmin
}
