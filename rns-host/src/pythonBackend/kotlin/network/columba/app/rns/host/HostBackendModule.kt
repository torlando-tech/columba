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
import network.columba.app.rns.host.ble.bridge.KotlinBLEBridge
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
        ChaquopyRnsBackend(appContext = context, callCoordinator = callCoordinator).also {
            // The bundled AndroidBLE custom interface (deployed by
            // event_bridge.deploy_bundled_interfaces) reaches into Kotlin via
            // event_bridge.get_ble_bridge() at driver-start time. Stash the
            // singleton here so PythonRnsRuntime.start can forward it before
            // Reticulum() is constructed. KotlinBLEBridge.getInstance is
            // process-singleton and idempotent — same instance the rest of
            // the host uses (BleCoordinator, BleStatusRepository).
            it.runtime.bleBridge = KotlinBLEBridge.getInstance(context)
        }

    @Provides
    @Singleton
    fun providePythonNetworkTransport(backend: ChaquopyRnsBackend): PythonNetworkTransport =
        PythonNetworkTransport(backend.runtime)

    /** Eager-constructed so the backend-READY observer in its init block is subscribed in time. */
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
            backend = backend,
            transport = transport,
            callCoordinator = callCoordinator,
        )

    @Provides
    @Singleton
    fun provideRnsBackend(
        backend: ChaquopyRnsBackend,
        // Force-construct PythonCallManager — side-effect: its init-time
        // backend-status observer subscribes before READY can fire.
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
