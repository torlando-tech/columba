package network.columba.app.rns.host.manager

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Unit tests for KeepalivePulseManager: the pulse schedules only when every
 * precondition holds (duty-cycle active, AutoInterface enabled, setting on,
 * exact alarms allowed), fires the pulse lock, and self-cancels otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeepalivePulseManagerTest {
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var lockManager: LockManager
    private lateinit var settingsAccessor: ServiceSettingsAccessor
    private lateinit var manager: KeepalivePulseManager

    @Before
    fun setup() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
        lockManager = mockk(relaxed = true)
        settingsAccessor = mockk()
        everyPulseSetting(true)
        manager = KeepalivePulseManager(context, lockManager, settingsAccessor)
    }

    @After
    fun tearDown() {
        unmockkAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun everyPulseSetting(enabled: Boolean) {
        every { settingsAccessor.getKeepalivePulseEnabled() } returns enabled
    }

    @Test
    fun `reevaluate schedules the pulse when all preconditions hold`() {
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)

        val alarm = shadowAlarmManager.nextScheduledAlarm
        assertNotNull("expected a scheduled alarm", alarm)
        assertEquals(
            SystemClock.elapsedRealtime() + KeepalivePulseManager.PULSE_INTERVAL_MS,
            alarm!!.triggerAtTime,
        )
    }

    @Test
    fun `pulse interval plus lock window stays under the 27500ms peering timeout`() {
        // PEERING_TIMEOUT = 22s x 1.25 Android. The window is the 5s pulse
        // wake lock in LockManager; if either constant drifts, LAN peers
        // start dropping the device between pulses and the feature silently
        // stops working.
        assertEquals(22_000L, KeepalivePulseManager.PULSE_INTERVAL_MS)
        assertTrue(
            "interval + 5s window must stay under PEERING_TIMEOUT",
            KeepalivePulseManager.PULSE_INTERVAL_MS + 5_000L <= 27_500L,
        )
    }

    @Test
    fun `reevaluate does not schedule when persistent lock is held`() {
        manager.reevaluate(dutyCycleActive = false, autoInterfaceEnabled = true)

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `reevaluate does not schedule without AutoInterface`() {
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = false)

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `reevaluate does not schedule when the setting is off`() {
        everyPulseSetting(false)

        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `reevaluate cancels a running pulse when a precondition goes away`() {
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)

        manager.reevaluate(dutyCycleActive = false, autoInterfaceEnabled = true)

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `reevaluate is idempotent`() {
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `onPulseFired holds the pulse lock and queues the next pulse`() {
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)
        val firstTrigger = shadowAlarmManager.nextScheduledAlarm!!.triggerAtTime
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofMillis(KeepalivePulseManager.PULSE_INTERVAL_MS),
        )
        manager.onPulseFired()

        verify(exactly = 1) { lockManager.acquireKeepalivePulseWakeLock() }
        // The shadow replaces alarms sharing a PendingIntent (as the real
        // AlarmManager does); the replacement must be the NEXT pulse.
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(
            firstTrigger + KeepalivePulseManager.PULSE_INTERVAL_MS,
            shadowAlarmManager.nextScheduledAlarm!!.triggerAtTime,
        )
    }

    @Test
    fun `onPulseFired after cancel is a no-op`() {
        // A disable (or any cancel) racing an alarm intent already in flight
        // must not restart the pulse chain.
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)
        manager.cancel()

        manager.onPulseFired()

        verify(exactly = 0) { lockManager.acquireKeepalivePulseWakeLock() }
        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `pulseEnabledOverride schedules despite the mirror still reading off`() {
        // The UI pushes the toggle value before the mirror write has
        // necessarily hit disk; the intent value wins for this reevaluation.
        every { settingsAccessor.getKeepalivePulseEnabled() } returns false

        manager.reevaluate(
            dutyCycleActive = true,
            autoInterfaceEnabled = true,
            pulseEnabledOverride = true,
        )

        assertNotNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `pulseEnabledOverride false cancels despite the mirror reading on`() {
        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)

        manager.reevaluate(
            dutyCycleActive = true,
            autoInterfaceEnabled = true,
            pulseEnabledOverride = false,
        )

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `schedule is skipped when exact alarms are not allowed`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        manager.reevaluate(dutyCycleActive = true, autoInterfaceEnabled = true)

        assertNull(shadowAlarmManager.nextScheduledAlarm)
    }
}
