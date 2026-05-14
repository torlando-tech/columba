package network.columba.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tech.torlando.lxst.core.CallCoordinator

/**
 * Hilt entry point for retrieving the singleton [CallCoordinator] from
 * call sites that aren't part of the Hilt-injected object graph:
 *
 * - [network.columba.app.MainActivity]'s root Composable (`@Composable` functions
 *   can't `@Inject`, but `LocalContext.current` + `EntryPointAccessors` is the
 *   blessed escape hatch).
 * - [network.columba.app.IncomingCallActivity] is deliberately *not*
 *   `@AndroidEntryPoint` to keep its cold-start latency under the lock-screen
 *   ringing window; this entry point lets it reach the same singleton without
 *   joining Hilt's component graph.
 *
 * The underlying provider is
 * [network.columba.app.rns.host.HostBackendModule.provideCallCoordinator], which
 * is allowlisted by the `NoCallCoordinatorGetInstanceOutsideHost` Detekt rule.
 *
 * A.10 will swap this entry point's surface to `RnsTelephony` once the AIDL
 * binding lands; the consumer-side Composable / Activity refactors will then
 * inject the typed call-state Flows directly rather than reaching for the
 * `CallCoordinator` singleton through Hilt.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CallCoordinatorEntryPoint {
    fun callCoordinator(): CallCoordinator
}
