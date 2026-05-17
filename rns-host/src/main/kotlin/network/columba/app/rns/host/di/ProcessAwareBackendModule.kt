package network.columba.app.rns.host.di

import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.RnsTelemetry
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.host.ipc.BoundRnsBackend
import network.columba.app.rns.host.process.ProcessDetector
import network.columba.app.rns.host.process.ProcessType

/**
 * The single canonical [RnsBackend] / sub-interface binding for the whole
 * Columba app.
 *
 * Both flavor `HostBackendModule`s contribute a [LocalBackend]-qualified
 * concrete impl (`ChaquopyRnsBackend` / `NativeRnsBackend`). This module sits
 * one level above and decides, per process:
 *
 * - In `:reticulum` (the FGS process) → return the flavor-local backend.
 *   That's the process that hosts the live RNS stack; constructing the local
 *   backend resolves the `Lazy`, which is intentional — it pre-warms the
 *   backend ahead of the first UI `initialize(config)` call.
 * - In the UI process (`network.columba.app[.debug]`) → return
 *   [BoundRnsBackend], an AIDL proxy that delegates every call to the
 *   `:reticulum`-process backend via the existing
 *   [network.columba.app.rns.host.ReticulumServiceConnection] +
 *   [network.columba.app.rns.ipc.RnsBackendClient] surface. The `Lazy` is
 *   never resolved on this side, so `ChaquopyRnsBackend.<init>` never runs in
 *   the UI pid and Python is never loaded there.
 * - In test environments → same as UI (the AIDL surface is exercised against
 *   in-process fakes by instrumented tests; unit tests use `BoundRns*` with
 *   direct flows).
 *
 * Hilt's `SingletonComponent` is per-process, so this provider runs once per
 * process. The branch is evaluated at provider time, never re-evaluated.
 *
 * Every Hilt consumer of `RnsCore` / `RnsLxmf` / `RnsTelephony` /
 * `RnsTelemetry` / `RnsNomadnet` / `RnsTransportAdmin` resolves through the
 * extractors below — same shape consumers had before, just now process-aware
 * underneath.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProcessAwareBackendModule {
    private const val TAG = "ProcessAwareBackendModule"

    @Provides
    @Singleton
    fun provideProcessType(@ApplicationContext context: Context): ProcessType =
        ProcessDetector.detect(context)

    @Provides
    @Singleton
    fun provideRnsBackend(
        processType: ProcessType,
        @ApplicationContext context: Context,
        @LocalBackend localBackend: Lazy<RnsBackend>,
    ): RnsBackend {
        val processName = ProcessDetector.currentProcessName(context)
        return when (processType) {
            ProcessType.RETICULUM -> localBackend.get().also {
                Log.i(
                    TAG,
                    "Resolved RnsBackend in :reticulum pid=${android.os.Process.myPid()} " +
                        "process=$processName -> ${it::class.simpleName} (local)",
                )
            }
            ProcessType.UI, ProcessType.TEST -> BoundRnsBackend(
                context = context,
                // Lifetime-of-process scope. Tied to the singleton; never cancelled
                // because Hilt singletons have no destroy hook (the process dies
                // before this scope would).
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also {
                Log.i(
                    TAG,
                    "Resolved RnsBackend in $processType pid=${android.os.Process.myPid()} " +
                        "process=$processName -> ${it::class.simpleName} (bound via AIDL)",
                )
            }
        }
    }

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
