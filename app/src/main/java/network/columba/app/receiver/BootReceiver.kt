package network.columba.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the Reticulum service at device boot so that location sharing
 * and telemetry collection can resume without requiring the user to open the app.
 *
 * Registered in AndroidManifest.xml with BOOT_COMPLETED intent filter.
 * Requires RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — starting Reticulum service")
        val serviceIntent = Intent(context, network.columba.app.service.ReticulumService::class.java)
        try {
            context.startForegroundService(serviceIntent)
            Log.d(TAG, "Reticulum service start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Reticulum service at boot", e)
        }
    }
}
