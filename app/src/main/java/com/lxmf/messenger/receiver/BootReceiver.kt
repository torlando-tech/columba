package com.lxmf.messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.lxmf.messenger.service.ReticulumService
import com.lxmf.messenger.service.SosTriggerService

/**
 * Starts Columba services after device boot.
 *
 * Launches:
 * 1. [ReticulumService] — mesh network (runs in :reticulum process)
 * 2. [SosTriggerService] — keeps the main process alive so that
 *    [com.lxmf.messenger.service.SosTriggerDetector.startObserving] (started in
 *    [com.lxmf.messenger.ColumbaApplication.onCreate]) can read SOS settings and
 *    decide whether to keep the service running. If SOS is disabled or mode is
 *    MANUAL, the observer stops the service automatically.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — starting Columba services")

        try {
            val serviceIntent =
                Intent(context, ReticulumService::class.java).apply {
                    action = ReticulumService.ACTION_START
                }
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.d(TAG, "ReticulumService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ReticulumService on boot: ${e.message}", e)
        }

        // Start SosTriggerService to keep the main process alive while
        // startObserving() reads DataStore. If SOS is not active, the observer
        // will stop this service within seconds.
        try {
            SosTriggerService.start(context)
            Log.d(TAG, "SosTriggerService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SosTriggerService on boot: ${e.message}", e)
        }
    }
}
