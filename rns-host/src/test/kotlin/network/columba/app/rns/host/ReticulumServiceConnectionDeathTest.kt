package network.columba.app.rns.host

import android.content.ComponentName
import android.os.IBinder
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.ipc.IRnsBackend
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hardening for Sentry COLUMBA-AZ: the UI->host binding links a death recipient
 * to the `:reticulum` binder so process death is detected promptly (off a binder
 * thread) even when the main-thread [android.content.ServiceConnection] callbacks
 * lag — the situation on the low-end repro device. The recipient emits a `null`
 * backend (so `awaitBound()` callers suspend through the gap instead of invoking
 * the dead binder); the rebind itself stays owned by onServiceDisconnected/
 * onBindingDied to avoid a double-rebind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReticulumServiceConnectionDeathTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun binderDeath_linksRecipientAndEmitsNull() = runTest(UnconfinedTestDispatcher()) {
        val app = RuntimeEnvironment.getApplication()
        // Separate scope for the client-connect job so its (deliberately
        // never-resolving, relaxed-mock) fetch doesn't entangle the test scope.
        val bindScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

        ReticulumServiceConnection.bind(app, bindScope).test {
            val connection = shadowOf(app).boundServiceConnections.firstOrNull()
            assertNotNull("bind() should register a ServiceConnection via bindService", connection)

            val backend = mockk<IRnsBackend>(relaxed = true)
            val binder = mockk<IBinder>(relaxed = true)
            // asInterface() returns this local interface directly.
            every { binder.queryLocalInterface(any()) } returns backend
            val recipientSlot = slot<IBinder.DeathRecipient>()
            every { binder.linkToDeath(capture(recipientSlot), 0) } just Runs
            every { binder.unlinkToDeath(any(), 0) } returns true

            // Deliver a live binder, exactly as Android would.
            connection!!.onServiceConnected(ComponentName(app, "ReticulumService"), binder)

            // The fix wires a death recipient onto the delivered binder.
            verify { binder.linkToDeath(any(), 0) }
            assertTrue("a DeathRecipient should be linked on connect", recipientSlot.isCaptured)

            // Simulate the :reticulum process dying (binder thread callback).
            recipientSlot.captured.binderDied()

            // Death must surface as a null backend, not a crash or a stale live ref.
            assertNull("binderDied should emit a null backend", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        bindScope.cancel()
    }
}
