package network.columba.app.rns.host.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import network.columba.app.rns.host.ReticulumService
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor
/**
 * AutoInterface keepalive pulse for duty-cycle mode.
 *
 * AutoInterface peers drop a silent device after `PEERING_TIMEOUT`
 * (22 s x 1.25 Android = 27.5 s): the 1.6 s `peer_announce` token thread is
 * frozen while the CPU suspends, so a duty-cycled phone disappears from
 * every LAN peering table. Hooking the token into the traffic lock would
 * pin it (1.6 s << the 90 s traffic-lock timeout), so instead this manager
 * wakes the service on a fixed cadence strictly below PEERING_TIMEOUT and
 * holds [LockManager.acquireKeepalivePulseWakeLock] long enough for one
 * announce cycle plus the buffered RX drain.
 *
 * The pulse is only meaningful when all of:
 *  - the backend supports duty-cycle mode (python traffic signals),
 *  - duty-cycle mode is active (persistent wake lock off),
 *  - an AutoInterface is enabled,
 *  - the user enabled the setting (default off until measured).
 *
 * Exact alarms are throttled in deep doze for apps without the
 * battery-optimization exemption (and require SCHEDULE_EXACT_ALARM on S+);
 * without either the pulse degrades to Android's maintenance-window
 * cadence, which is far beyond PEERING_TIMEOUT — so scheduling is skipped
 * with a log rather than pretending to keep the device visible.
 */
class KeepalivePulseManager(
    private val context: Context,
    private val lockManager: LockManager,
    private val settingsAccessor: ServiceSettingsAccessor,
) {
    companion object {
        private const val TAG = "KeepalivePulse"

        /**
         * Pulse cadence. Interval + lock window (5 s) must stay under the
         * 27.5 s PEERING_TIMEOUT; 22 + 5 = 27 s leaves the token thread's
         * 1.6 s cycle inside the window with margin for alarm jitter.
         */
        const val PULSE_INTERVAL_MS = 22_000L

        // Distinct from the service's other intent codes; the alarm targets
        // ReticulumService.ACTION_KEEPALIVE_PULSE.
        private const val PULSE_ALARM_REQUEST_CODE = 0x4C50 // "LP"
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Volatile
    private var scheduled = false

    /**
     * Whether the current policy wants pulses. `onPulseFired` checks this so
     * an alarm intent already in flight when the user disables pulses (or
     * the service cancels the pulse) cannot restart the chain.
     */
    @Volatile
    private var wanted = false

    /**
     * Start or stop the pulse to match the current policy. Cheap and
     * idempotent; call after any input changes (setting toggle, wake-lock
     * mode toggle, interface config change, service start).
     */
    fun reevaluate(
        dutyCycleActive: Boolean,
        autoInterfaceEnabled: Boolean,
        pulseEnabledOverride: Boolean? = null,
    ) {
        // The override carries the just-toggled setting value: the UI pushes
        // this intent before the cross-process mirror write has necessarily
        // hit disk, and the mirror read here would see the old value.
        val wantedNow =
            dutyCycleActive &&
                autoInterfaceEnabled &&
                (pulseEnabledOverride ?: settingsAccessor.getKeepalivePulseEnabled())
        wanted = wantedNow
        if (wantedNow) scheduleNext() else cancel()
    }

    /**
     * The alarm fired: hold the CPU for one announce window and queue the
     * next pulse. Called from `ReticulumService.onStartCommand` for
     * ACTION_KEEPALIVE_PULSE.
     */
    fun onPulseFired() {
        if (!wanted) {
            // cancel() ran while this alarm intent was already in flight.
            return
        }
        lockManager.acquireKeepalivePulseWakeLock()
        // The firing alarm is consumed; allow scheduleNext to queue the
        // following pulse.
        scheduled = false
        scheduleNext()
    }

    fun cancel() {
        wanted = false
        if (!scheduled) return
        scheduled = false
        runCatching { alarmManager.cancel(pulsePendingIntent()) }
            .onFailure { Log.w(TAG, "Failed to cancel keepalive pulse alarm", it) }
        Log.i(TAG, "Keepalive pulse stopped")
    }

    private fun scheduleNext() {
        if (scheduled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(
                TAG,
                "Keepalive pulse not scheduled: exact alarms not allowed " +
                    "(grant SCHEDULE_EXACT_ALARM or the battery-optimization exemption)",
            )
            return
        }
        val triggerAt = SystemClock.elapsedRealtime() + PULSE_INTERVAL_MS
        val result =
            runCatching {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pulsePendingIntent(),
                )
            }
        if (result.isSuccess) {
            scheduled = true
            Log.i(TAG, "Keepalive pulse scheduled in ${PULSE_INTERVAL_MS / 1000}s")
        } else {
            Log.w(TAG, "Failed to schedule keepalive pulse alarm", result.exceptionOrNull())
        }
    }

    private fun pulsePendingIntent(): PendingIntent {
        val intent =
            Intent(context, ReticulumService::class.java).apply {
                action = ReticulumService.ACTION_KEEPALIVE_PULSE
            }
        return PendingIntent.getService(
            context,
            PULSE_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
