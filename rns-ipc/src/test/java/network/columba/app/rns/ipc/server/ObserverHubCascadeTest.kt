package network.columba.app.rns.ipc.server

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.TransactionTooLargeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for the inbound-message cascade: a single oversized payload
 * (which the real Binder rejects with [TransactionTooLargeException], a
 * subclass of [android.os.RemoteException]) must NOT detach the observer.
 *
 * Before the fix, [ObserverHub] caught any `RemoteException` as "client died"
 * and detached the observer. Since the UI subscribes exactly once (a
 * `callbackFlow` whose `awaitClose` only fires on cancel) it was never told it
 * had been dropped and never re-registered — so one too-large inbound image
 * silently killed delivery of EVERY subsequent message, text included, for the
 * rest of the process's life. See `ServerFlowBridge.ObserverHub`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ObserverHubCascadeTest {

    /** Minimal stand-in for an AIDL callback carrying a stable binder identity. */
    private class FakeCallback(val binder: IBinder)

    @Test
    fun `oversized emit keeps observer subscribed so later messages still deliver`() = runTest {
        val hubScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val upstream = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val delivered = mutableListOf<String>()
        val cb = FakeCallback(Binder())

        val hub = ObserverHub<String, FakeCallback>(
            scope = hubScope,
            upstream = { upstream.asSharedFlow() as Flow<String> },
            callbackBinder = { it.binder },
            emit = { _, value ->
                // Simulate the real Binder behaviour: a too-large payload throws
                // TransactionTooLargeException; everything else delivers.
                if (value == "OVERSIZED") throw TransactionTooLargeException()
                delivered += value
            },
        )

        hub.registerObserver(cb)
        advanceUntilIdle()

        upstream.emit("text-before")
        advanceUntilIdle()
        upstream.emit("OVERSIZED") // image too big for the Binder transaction
        advanceUntilIdle()
        upstream.emit("text-after") // must still arrive — observer must survive
        advanceUntilIdle()

        assertEquals(listOf("text-before", "text-after"), delivered)

        hubScope.cancel()
    }

    @Test
    fun `dead client still detaches so a genuinely gone observer stops delivery`() = runTest {
        val hubScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val upstream = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val delivered = mutableListOf<String>()
        val cb = FakeCallback(Binder())

        val hub = ObserverHub<String, FakeCallback>(
            scope = hubScope,
            upstream = { upstream.asSharedFlow() as Flow<String> },
            callbackBinder = { it.binder },
            emit = { _, value ->
                // A genuinely dead client throws DeadObjectException — that one
                // SHOULD detach.
                if (value == "DEAD") throw DeadObjectException()
                delivered += value
            },
        )

        hub.registerObserver(cb)
        advanceUntilIdle()

        upstream.emit("one")
        advanceUntilIdle()
        upstream.emit("DEAD") // client process gone → detach
        advanceUntilIdle()
        upstream.emit("two") // observer detached → not delivered
        advanceUntilIdle()

        assertEquals(listOf("one"), delivered)

        hubScope.cancel()
    }
}
