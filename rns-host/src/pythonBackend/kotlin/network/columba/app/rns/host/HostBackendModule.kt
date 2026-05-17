package network.columba.app.rns.host

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.backend.py.ChaquopyRnsBackend
import network.columba.app.rns.host.ble.bridge.KotlinBLEBridge
import network.columba.app.rns.host.di.LocalBackend
import tech.torlando.lxst.core.CallCoordinator
import javax.inject.Singleton

/**
 * Python-flavor backend wiring for `:rns-host`.
 *
 * Active when the `rnsImpl=pythonBackend` flavor resolves. Provides
 * [ChaquopyRnsBackend] under [LocalBackend] qualifier into the Hilt graph.
 *
 * The [CallCoordinator] is constructed here (this module *is* on the
 * `NoCallCoordinatorGetInstanceOutsideHost` Detekt allowlist) and passed into
 * [ChaquopyRnsBackend] — `:rns-backend-py` itself never calls `getInstance()`.
 *
 * [PythonNetworkTransport] + [PythonCallManager] are also provided so the LXST
 * voice path is compile-wired and has a clear injection point.
 *
 * A.10: this module no longer provides the unqualified
 * [network.columba.app.rns.api.RnsBackend] binding or the six sub-interface
 * extractors. Those moved to
 * [network.columba.app.rns.host.di.ProcessAwareBackendModule], which decides
 * per process whether to resolve this local backend (in `:reticulum`) or
 * return a [network.columba.app.rns.host.ipc.BoundRnsBackend] AIDL proxy
 * (in UI / test). Constructing `ChaquopyRnsBackend` is what loads CPython
 * and binds sockets — confining that work to `:reticulum` is the entire
 * point of the process split.
 *
 * Eager `PythonCallManager` construction is preserved via the
 * [eagerCallManager] parameter on [provideLocalRnsBackend]: the call-manager
 * init block subscribes to backend-status updates, and we want that
 * subscription wired before `runtime.start()` flips status to READY.
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

    /**
     * Flavor-local [RnsBackend] view of [ChaquopyRnsBackend]. [LocalBackend]
     * qualifier disambiguates from the process-aware unqualified
     * [RnsBackend] binding in
     * [network.columba.app.rns.host.di.ProcessAwareBackendModule].
     *
     * The [eagerCallManager] dependency is intentional — it forces
     * [PythonCallManager] construction (and its init-time backend-status
     * observer registration) BEFORE the backend completes initialization.
     */
    @Provides
    @Singleton
    @LocalBackend
    fun provideLocalRnsBackend(
        backend: ChaquopyRnsBackend,
        @Suppress("UNUSED_PARAMETER") eagerCallManager: PythonCallManager,
    ): RnsBackend = backend
}
