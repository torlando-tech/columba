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
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.ipc.IRnsCore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression guard for Sentry COLUMBA-AZ.
 *
 * The `:reticulum` backend process can die in the window between `onServiceConnected`
 * handing [ClientRnsCore] a proxy and its init block registering the network-status
 * observer. The registration is the one remote call in this client that races process
 * death; an unguarded `remote.registerNetworkStatusObserver()` throws [DeadObjectException]
 * (a `RemoteException`) that escapes the `callbackFlow` producer and crashes the
 * collecting coroutine.
 *
 * Reproduced first against the unguarded code (the exception reached the scope's
 * [CoroutineExceptionHandler]); this asserts the FIXED behavior: the failure is caught,
 * nothing escapes to the handler, and `networkStatus` surfaces ERROR instead of crashing.
 * This test fails on the pre-fix code and passes once the registration is guarded.
 */
@RunWith(RobolectricTestRunner::class)
class ClientRnsCoreNetworkObserverTest {

    @Test
    fun deadRemoteDuringObserverRegistration_isHandledNotCrashed() {
        val caught = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val handler = CoroutineExceptionHandler { _, t ->
            caught.set(t)
            latch.countDown()
        }
        // SupervisorJob so the snapshot coroutine isn't cancelled by the observer
        // coroutine; Unconfined so init's launches run eagerly/inline.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)

        // Explicit stubs (no relaxed mock) for exactly the calls ClientRnsCore.init
        // makes: the observer registration throws (the crash trigger); the snapshot
        // read and unregister are no-ops.
        val remote = mockk<IRnsCore>()
        every { remote.registerNetworkStatusObserver(any()) } throws
            DeadObjectException("Transaction failed on small parcel; remote process probably died")
        every { remote.unregisterNetworkStatusObserver(any()) } just Runs
        every { remote.getCurrentNetworkStatus(any()) } just Runs

        // Construct the real production object — its init block performs the registration.
        val core = ClientRnsCore(remote, scope)

        // Block the full window for any (unexpected) escape to surface on the
        // handler. This also lets the Unconfined callbackFlow settle to its
        // terminal ERROR emission, so the value read below is deterministic.
        val escaped = latch.await(500, TimeUnit.MILLISECONDS)
        scope.cancel()

        assertFalse(
            "DeadObjectException must NOT escape to the coroutine scope after the fix " +
                "(escaped throwable: ${caught.get()})",
            escaped,
        )
        assertNull("No throwable should reach the coroutine exception handler", caught.get())
        assertTrue(
            "networkStatus should surface ERROR when the backend is unavailable, was ${core.networkStatus.value}",
            core.networkStatus.value is NetworkStatus.ERROR,
        )
    }
}
