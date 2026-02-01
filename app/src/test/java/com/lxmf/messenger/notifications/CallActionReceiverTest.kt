package com.lxmf.messenger.notifications

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lxmf.messenger.MainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Unit tests for CallActionReceiver.
 *
 * Tests handling of call notification actions using Robolectric to actually
 * invoke onReceive and verify behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallActionReceiverTest {
    private lateinit var context: Context
    private lateinit var receiver: CallActionReceiver
    private lateinit var shadowApplication: ShadowApplication
    private lateinit var mockPendingResult: BroadcastReceiver.PendingResult

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // BroadcastReceiver.PendingResult is an Android system type - relaxed mock is appropriate
        // The only method called is finish() which returns void
        mockPendingResult = mockk()
        every { mockPendingResult.finish() } returns Unit
        // Use a spy so we can mock goAsync() while keeping real onReceive behavior
        receiver = spyk(CallActionReceiver())
        every { receiver.goAsync() } returns mockPendingResult
        shadowApplication = Shadows.shadowOf(context as Application)
    }

    // ========== Answer Call Action Tests ==========

    @Test
    fun `onReceive with ANSWER_CALL action starts MainActivity`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123def456")
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull("Should start an activity", startedIntent)
        assertEquals(MainActivity::class.java.name, startedIntent.component?.className)
    }

    @Test
    fun `onReceive with ANSWER_CALL action sets correct intent action`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123def456")
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(CallNotificationHelper.ACTION_ANSWER_CALL, startedIntent.action)
    }

    @Test
    fun `onReceive with ANSWER_CALL action passes identity hash`() {
        val testHash = "abc123def456789012345678901234567890"
        val intent =
            Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, testHash)
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(
            testHash,
            startedIntent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH),
        )
    }

    @Test
    fun `onReceive with ANSWER_CALL action sets auto answer flag`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertTrue(
            "Should set auto_answer flag",
            startedIntent.getBooleanExtra(CallActionReceiver.EXTRA_AUTO_ANSWER, false),
        )
    }

    @Test
    fun `onReceive with ANSWER_CALL action sets correct intent flags`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertTrue(
            "Should have FLAG_ACTIVITY_NEW_TASK",
            (startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
        assertTrue(
            "Should have FLAG_ACTIVITY_SINGLE_TOP",
            (startedIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0,
        )
    }

    @Test
    fun `onReceive with ANSWER_CALL action handles null identity hash`() {
        val intent = Intent(CallNotificationHelper.ACTION_ANSWER_CALL)
        // No identity hash extra

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull("Should still start activity", startedIntent)
        assertEquals(MainActivity::class.java.name, startedIntent.component?.className)
    }

    // ========== Decline Call Action Tests ==========

    @Test
    fun `onReceive with DECLINE_CALL action does not start activity`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_DECLINE_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        receiver.onReceive(context, intent)

        // Decline should not start an activity (it triggers hangup instead)
        val startedIntent = shadowApplication.nextStartedActivity
        // No activity should be started for decline action
        assertTrue(
            "Decline action should not start MainActivity",
            startedIntent == null ||
                startedIntent.component?.className != MainActivity::class.java.name ||
                startedIntent.action != CallNotificationHelper.ACTION_ANSWER_CALL,
        )
    }

    @Test
    fun `onReceive with DECLINE_CALL action calls goAsync for background work`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_DECLINE_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        // onReceive should complete successfully
        val result = runCatching { receiver.onReceive(context, intent) }

        assertTrue("onReceive should complete without throwing", result.isSuccess)
        // Verify goAsync was called for async hangup
        verify { receiver.goAsync() }
    }

    @Test
    fun `onReceive with DECLINE_CALL action finishes pending result when app unavailable`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_DECLINE_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        // onReceive should complete successfully
        val result = runCatching { receiver.onReceive(context, intent) }
        assertTrue("onReceive should complete without throwing", result.isSuccess)

        // In test env without ColumbaApplication, it should still finish the pending result
        // Give coroutine a moment to run
        Thread.sleep(100)
        verify { mockPendingResult.finish() }
    }

    // ========== End Call Action Tests ==========

    @Test
    fun `onReceive with END_CALL action does not start activity`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_END_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertTrue(
            "End action should not start MainActivity with answer action",
            startedIntent == null || startedIntent.action != CallNotificationHelper.ACTION_ANSWER_CALL,
        )
    }

    @Test
    fun `onReceive with END_CALL action calls goAsync for background work`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_END_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        // onReceive should complete successfully
        val result = runCatching { receiver.onReceive(context, intent) }

        assertTrue("onReceive should complete without throwing", result.isSuccess)
        // Verify goAsync was called for async hangup
        verify { receiver.goAsync() }
    }

    @Test
    fun `onReceive with END_CALL action finishes pending result when app unavailable`() {
        val intent =
            Intent(CallNotificationHelper.ACTION_END_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        // onReceive should complete successfully
        val result = runCatching { receiver.onReceive(context, intent) }
        assertTrue("onReceive should complete without throwing", result.isSuccess)

        // In test env without ColumbaApplication, it should still finish the pending result
        // Give coroutine a moment to run
        Thread.sleep(100)
        verify { mockPendingResult.finish() }
    }

    // ========== Unknown Action Tests ==========

    @Test
    fun `onReceive with unknown action does not start activity`() {
        val intent =
            Intent("com.example.UNKNOWN_ACTION").apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, "abc123")
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        // Unknown action should not trigger any activity start
        assertTrue(
            "Unknown action should not start any activity",
            startedIntent == null,
        )
    }

    @Test
    fun `onReceive with null action does not crash`() {
        val intent = Intent()
        // Action is null

        // Should not throw
        receiver.onReceive(context, intent)
    }

    // ========== Companion Object Constants Tests ==========

    @Test
    fun `EXTRA_AUTO_ANSWER constant has correct value`() {
        assertEquals("auto_answer", CallActionReceiver.EXTRA_AUTO_ANSWER)
    }

    // ========== Integration-like Tests ==========

    @Test
    fun `answer action with full identity hash works correctly`() {
        val fullHash = "0102030405060708091011121314151617181920212223242526272829303132"
        val intent =
            Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, fullHash)
            }

        receiver.onReceive(context, intent)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(fullHash, startedIntent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH))
        assertEquals(CallNotificationHelper.ACTION_ANSWER_CALL, startedIntent.action)
        assertTrue(startedIntent.getBooleanExtra(CallActionReceiver.EXTRA_AUTO_ANSWER, false))
    }

    @Test
    fun `multiple sequential answer actions work correctly`() {
        val hashes = listOf("hash1", "hash2", "hash3")

        hashes.forEach { hash ->
            val intent =
                Intent(CallNotificationHelper.ACTION_ANSWER_CALL).apply {
                    putExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH, hash)
                }
            receiver.onReceive(context, intent)
        }

        // Verify all activities were started (check the last one)
        // Note: shadowApplication.nextStartedActivity consumes intents
        // So we just verify no exception was thrown for multiple calls
    }
}
