package network.columba.app.notifications

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.columba.app.rns.api.notify.MessageNotificationBridge

/**
 * Publishes "the UI is foregrounded right now" to the `:reticulum` process so it
 * knows whether this process owns received-message notifications.
 *
 * Written into [MessageNotificationBridge.CROSS_PROCESS_PREFS_NAME] as a
 * heartbeat timestamp. `0` and any timestamp older than
 * [MessageNotificationBridge.UI_FOREGROUND_STALE_AFTER_MS] mean "the UI is gone
 * or frozen, the service must notify".
 *
 * Why a heartbeat rather than a flag: the OS freezes cached processes without
 * notifying them, so a boolean written on `ON_STOP` would go stale only if the
 * write happened — and a process killed mid-lifecycle never writes it at all.
 * A frozen process stops ticking, which is exactly the signal needed.
 *
 * Not a Hilt binding: it needs only a Context, and it is started from exactly one
 * call site (`ColumbaApplication.onCreate`, main process only).
 */
@Suppress("DEPRECATION") // MODE_MULTI_PROCESS is the supported cross-process channel here
class UiPresencePublisher(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null

    @Volatile
    private var observing = false

    /** Register the lifecycle observer. Idempotent; safe to call once per process. */
    fun start() {
        if (observing) return
        observing = true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> onForeground()
                    Lifecycle.Event.ON_STOP -> onBackground()
                    else -> Unit
                }
            },
        )
        // The app is typically already started by the time onCreate returns on a
        // warm launch; make the first heartbeat immediate rather than up to one
        // tick late.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onForeground()
        }
    }

    /** Mark foreground and keep the heartbeat fresh. */
    @VisibleForTesting
    internal fun onForeground() {
        writeTick(System.currentTimeMillis())
        if (tickJob?.isActive == true) return
        tickJob =
            scope.launch {
                while (isActive) {
                    delay(MessageNotificationBridge.UI_FOREGROUND_TICK_MS)
                    writeTick(System.currentTimeMillis())
                }
            }
    }

    /** Stop ticking and immediately publish "not foregrounded". */
    @VisibleForTesting
    internal fun onBackground() {
        tickJob?.cancel()
        tickJob = null
        writeTick(0L)
    }

    private fun writeTick(at: Long) {
        context
            .getSharedPreferences(
                MessageNotificationBridge.CROSS_PROCESS_PREFS_NAME,
                Context.MODE_MULTI_PROCESS,
            ).edit()
            .putLong(MessageNotificationBridge.KEY_UI_FOREGROUND_AT, at)
            .apply()
    }
}
