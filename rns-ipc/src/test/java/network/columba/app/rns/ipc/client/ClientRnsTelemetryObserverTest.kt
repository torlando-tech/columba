package network.columba.app.rns.ipc.client

import android.os.DeadObjectException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import network.columba.app.rns.ipc.IRnsTelemetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression guard for Sentry COLUMBA-B0 — the telemetry sibling of COLUMBA-AZ.
 *
 * [ClientRnsTelemetry]'s init block registers a telemetry observer via an AIDL
 * call inside a `callbackFlow`. If the `:reticulum` backend is dead at
 * registration the call throws [DeadObjectException]; unguarded it escapes the
 * producer and crashes the collecting coroutine. The shared
 * `registerObserverOrClose` guard must catch it and close the flow gracefully so
 * nothing reaches the scope's [CoroutineExceptionHandler]. This exercises that
 * shared guard through a real client site; it fails on the unguarded code and
 * passes once the registration is routed through the guard.
 */
@RunWith(RobolectricTestRunner::class)
class ClientRnsTelemetryObserverTest {

    @Test
    fun deadRemoteDuringObserverRegistration_isHandledNotCrashed() {
        val caught = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val handler = CoroutineExceptionHandler { _, t ->
            caught.set(t)
            latch.countDown()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)

        // Explicit stubs (no relaxed mock) for exactly the init calls: the
        // observer registration throws (the crash trigger); unregister is a no-op.
        val remote = mockk<IRnsTelemetry>()
        every { remote.registerTelemetryObserver(any()) } throws
            DeadObjectException("Transaction failed on small parcel; remote process probably died")
        every { remote.unregisterTelemetryObserver(any()) } just Runs

        // Construct the real production object — its init block registers the observer.
        ClientRnsTelemetry(remote, scope)

        // Block the full window for any (unexpected) escape to reach the handler.
        val escaped = latch.await(500, TimeUnit.MILLISECONDS)
        scope.cancel()

        assertFalse(
            "DeadObjectException must NOT escape to the coroutine scope (reproduces COLUMBA-B0)",
            escaped,
        )
        assertNull("No throwable should reach the coroutine exception handler", caught.get())
    }
}
