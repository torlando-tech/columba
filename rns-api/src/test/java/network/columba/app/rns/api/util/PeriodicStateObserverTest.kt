package network.columba.app.rns.api.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [PeriodicStateObserver] — the bounded poller that mirrors a
 * plain LXST-kt `Telephone` field (activeProfile / activeMode) with no
 * reactive hook as a [kotlinx.coroutines.flow.StateFlow] while a call is in
 * progress.
 *
 * The poller is a `while (true)` loop that reads + `delay`s, so it never
 * reaches quiescence. Each test therefore:
 *  - runs it on its own [UnconfinedTestDispatcher] (fresh Job sharing the
 *    `runTest` virtual-time [kotlinx.coroutines.test.TestCoroutineScheduler]),
 *    so it is NOT a child of the test job and cancellation is isolated;
 *  - drives each poll cycle with [advanceTimeBy] (bounded) rather than
 *    `advanceUntilIdle`, which would advance virtual time forever against a
 *    never-idling loop;
 *  - cancels the poller scope in `finally` so the infinite loop does not leak
 *    into the test's teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicStateObserverTest {
    @Test
    fun `emits null when no call is in progress`() = runTest {
        var callInProgress = false
        var profile = "HQ"

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val observer =
                PeriodicStateObserver<String?>(
                    scope = scope,
                    pollIntervalMs = 500L,
                    isCallInProgress = { callInProgress },
                    reader = { profile },
                )

            // One poll tick with no active call -> parked at null.
            advanceTimeBy(500L)
            assertNull(observer.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `emits the profile while a call is in progress`() = runTest {
        var callInProgress = true
        var profile = "MQ"

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val observer =
                PeriodicStateObserver<String?>(
                    scope = scope,
                    pollIntervalMs = 500L,
                    isCallInProgress = { callInProgress },
                    reader = { profile },
                )

            advanceTimeBy(500L)
            assertEquals("MQ", observer.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `tracks a value change and parks to null when the call ends`() = runTest {
        var callInProgress = true
        var profile = "MQ"

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val observer =
                PeriodicStateObserver<String?>(
                    scope = scope,
                    pollIntervalMs = 500L,
                    isCallInProgress = { callInProgress },
                    reader = { profile },
                )

            // First poll: call active, profile MQ.
            advanceTimeBy(500L)
            assertEquals("MQ", observer.state.value)

            // Local (or remote) value switch mid-call: next poll sees SHQ.
            profile = "SHQ"
            advanceTimeBy(500L)
            assertEquals("SHQ", observer.state.value)

            // Call ends: next poll parks the observable at null.
            callInProgress = false
            advanceTimeBy(500L)
            assertNull(observer.state.value)
        } finally {
            scope.cancel()
        }
    }
}
