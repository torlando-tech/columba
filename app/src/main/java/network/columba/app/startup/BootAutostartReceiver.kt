package network.columba.app.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.columba.app.rns.host.ReticulumService

/**
 * Starts the Reticulum foreground service after a reboot (or after the app is
 * updated) so messages keep arriving without the user opening Columba — the
 * behaviour requested in issue #617.
 *
 * Runs in the main app process; [BootAutostartPolicy] does the deciding, this
 * is only the adapter that sends [ReticulumService.ACTION_START]. The service
 * foregrounds itself immediately in `onCreate` and self-initializes from the
 * config snapshot, so no UI is involved.
 *
 * Not `directBootAware`: the config snapshot and credential-encrypted settings
 * live in credential-protected storage, and the identity key (Keystore) is
 * unreachable before the first unlock anyway.
 */
class BootAutostartReceiver : BroadcastReceiver() {
    internal companion object {
        private const val TAG = "BootAutostartReceiver"

        /**
         * Swappable only so tests can observe the start without a bound service.
         * Mirrors the `NotificationHelper.isAppInForeground` test-seam pattern.
         */
        @VisibleForTesting
        var startForegroundService: (Context, Intent) -> Unit = { context, intent ->
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Dispatcher for the policy read and the start. `onReceive` runs on the main
         * thread and [BootAutostartPolicy] reads the config snapshot from disk, so the
         * work is handed to [Dispatchers.IO] under [goAsync]; tests swap in an
         * unconfined dispatcher to observe the result synchronously.
         */
        @VisibleForTesting
        var dispatcher: CoroutineDispatcher = Dispatchers.IO
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        // Finish the broadcast only after the service is requested, so the process is not
        // reaped between returning from onReceive and the start being issued.
        // null when invoked directly (unit tests): the framework sets mPendingResult only
        // for a broadcast it dispatched itself.
        val pendingResult = goAsync()
        CoroutineScope(dispatcher).launch {
            try {
                startIfAllowed(context, action)
            } catch (e: Exception) {
                Log.w(TAG, "Boot autostart failed: ${e.message}")
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun startIfAllowed(
        context: Context,
        action: String,
    ) {
        if (!BootAutostartPolicy.shouldStart(context, action)) return

        val startIntent =
            Intent(context, ReticulumService::class.java)
                .setAction(ReticulumService.ACTION_START)
        try {
            startForegroundService(context, startIntent)
            Log.i(TAG, "Autostarted Reticulum service after $action")
        } catch (e: Exception) {
            // BOOT_COMPLETED is on the foreground-service exemption list, but some OEM
            // builds still refuse (or the system is mid-shutdown). A device that just
            // rebooted must not get a crash dialog; the next app launch recovers.
            Log.w(TAG, "Could not autostart Reticulum service after $action: ${e.message}")
        }
    }
}
