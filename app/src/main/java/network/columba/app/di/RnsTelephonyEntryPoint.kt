package network.columba.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.columba.app.rns.api.RnsTelephony

/**
 * Hilt entry point for retrieving the singleton [RnsTelephony] from call
 * sites that aren't part of the Hilt-injected object graph:
 *
 * - [network.columba.app.MainActivity]'s root Composable (`@Composable`
 *   functions can't `@Inject`, but `LocalContext.current` +
 *   `EntryPointAccessors` is the blessed escape hatch).
 * - [network.columba.app.IncomingCallActivity] is deliberately *not*
 *   `@AndroidEntryPoint` to keep its cold-start latency under the
 *   lock-screen ringing window; this entry point lets it reach the same
 *   singleton without joining Hilt's component graph.
 *
 * Replaces the A.9 `CallCoordinatorEntryPoint` once the call observable
 * surface lands on [RnsTelephony] (A.10). UI consumers now observe call
 * state via the seam contract rather than the in-process LXST singleton,
 * so the boundary survives the AIDL split.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RnsTelephonyEntryPoint {
    fun telephony(): RnsTelephony
}
