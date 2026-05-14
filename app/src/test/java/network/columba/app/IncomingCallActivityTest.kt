package network.columba.app

import android.app.Application
import android.content.Intent
import android.view.WindowManager
import dagger.hilt.android.EntryPointAccessors
import network.columba.app.di.RnsTelephonyEntryPoint
import network.columba.app.notifications.CallNotificationHelper
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for IncomingCallActivity.
 *
 * Tests lifecycle behavior, window configuration, intent handling,
 * and call state observation using Robolectric.
 *
 * IMPORTANT: We use controller.create().start() instead of controller.setup()
 * because setup() calls visible() which idles the Robolectric main looper.
 * The Compose UI contains rememberInfiniteTransition animations that cause
 * an infinite Choreographer frame loop when the looper drains, hanging the
 * test runner indefinitely.
 *
 * UnconfinedTestDispatcher overrides Dispatchers.Main so that lifecycleScope
 * coroutines (repeatOnLifecycle + StateFlow.collect) dispatch eagerly on the
 * current thread rather than posting to the looper.
 *
 * A.10 swapped the entry-point target from `CallCoordinatorEntryPoint` to
 * [RnsTelephonyEntryPoint] — the activity now observes call state through
 * the seam contract that survives the AIDL boundary instead of the
 * in-process LXST singleton.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncomingCallActivityTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Application
    private lateinit var callStateFlow: MutableStateFlow<CallState>
    private lateinit var mockTelephony: RnsTelephony

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()

        // Mock RnsTelephony so the activity sees Incoming state
        // (default Idle would auto-finish the activity). The activity reaches
        // the seam singleton via Hilt's RnsTelephonyEntryPoint, so the test
        // stubs the entry-point chain.
        callStateFlow = MutableStateFlow<CallState>(CallState.Incoming("abc123def456"))
        mockTelephony = mockk()
        every { mockTelephony.callState } returns callStateFlow
        coEvery { mockTelephony.answerCall() } returns Result.success(Unit)
        coEvery { mockTelephony.declineCall() } answers { }

        val mockEntryPoint = mockk<RnsTelephonyEntryPoint>()
        every { mockEntryPoint.telephony() } returns mockTelephony
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<android.content.Context>(), RnsTelephonyEntryPoint::class.java)
        } returns mockEntryPoint
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(EntryPointAccessors::class)
    }

    private fun buildCallIntent(
        identityHash: String? = "abc123def456",
        callerName: String? = "Test Caller",
    ): Intent =
        Intent(context, IncomingCallActivity::class.java).apply {
            action = CallNotificationHelper.ACTION_OPEN_CALL
            identityHash?.let { putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, it) }
            callerName?.let { putExtra(CallNotificationHelper.EXTRA_CALLER_NAME, it) }
        }

    // ========== Lifecycle Tests ==========

    @Test
    fun `finishes immediately when no identity hash provided`() {
        val intent = Intent(context, IncomingCallActivity::class.java)
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create()

        assertTrue("Activity should be finishing", controller.get().isFinishing)
    }

    @Test
    fun `does not finish when identity hash is provided`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create().start()

        assertTrue("Activity should not be finishing", !controller.get().isFinishing)
    }

    // ========== Window Configuration Tests ==========

    @Test
    fun `configures FLAG_KEEP_SCREEN_ON`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create()

        val flags =
            controller
                .get()
                .window.attributes.flags
        assertTrue(
            "Should have FLAG_KEEP_SCREEN_ON",
            flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
        )
    }

    // ========== onNewIntent Tests ==========

    @Test
    fun `onNewIntent updates identity hash`() {
        val intent = buildCallIntent(identityHash = "original_hash", callerName = "Original")
        callStateFlow.value = CallState.Incoming("original_hash")
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create()

        val newIntent = buildCallIntent(identityHash = "new_hash", callerName = "New Caller")
        controller.newIntent(newIntent)

        // Verify the intent was updated via setIntent
        assertEquals(
            "Intent should be updated with new hash",
            "new_hash",
            controller.get().intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH),
        )
        assertEquals(
            "Intent should be updated with new caller name",
            "New Caller",
            controller.get().intent.getStringExtra(CallNotificationHelper.EXTRA_CALLER_NAME),
        )
    }

    @Test
    fun `onNewIntent with null hash does not crash`() {
        val intent = buildCallIntent(identityHash = "original_hash", callerName = "Original")
        callStateFlow.value = CallState.Incoming("original_hash")
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create()

        // Send a new intent without identity hash — should not crash
        val newIntent = Intent(context, IncomingCallActivity::class.java)
        controller.newIntent(newIntent)

        assertTrue("Activity should still be running", !controller.get().isFinishing)
    }

    // ========== Destroy / Cleanup Tests ==========

    @Test
    fun `onDestroy does not crash`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create()

        // Should not throw
        controller.destroy()
    }

    @Test
    fun `full lifecycle does not crash`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create().start().resume()
        controller.pause().stop().destroy()

        // If we reach here, the full lifecycle completed without exceptions
        assertTrue("Full lifecycle completed", true)
    }

    // ========== Call State Transition Tests ==========

    @Test
    fun `activity finishes when call state becomes Idle`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create().start()

        // UnconfinedTestDispatcher ensures immediate dispatch to the collector
        callStateFlow.value = CallState.Idle

        assertTrue("Activity should finish on Idle", controller.get().isFinishing)
    }

    @Test
    fun `activity finishes when call state becomes Ended`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create().start()

        callStateFlow.value = CallState.Ended

        assertTrue("Activity should finish on Ended", controller.get().isFinishing)
    }

    @Test
    fun `activity finishes when call state becomes Rejected`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create().start()

        callStateFlow.value = CallState.Rejected

        assertTrue("Activity should finish on Rejected", controller.get().isFinishing)
    }

    @Test
    fun `activity finishes when call state becomes Busy`() {
        val intent = buildCallIntent()
        val controller = Robolectric.buildActivity(IncomingCallActivity::class.java, intent)
        controller.create().start()

        callStateFlow.value = CallState.Busy

        assertTrue("Activity should finish on Busy", controller.get().isFinishing)
    }
}
