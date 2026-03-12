package com.lxmf.messenger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.lxmf.messenger.service.ReticulumService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that starts the Reticulum foreground service after device boot,
 * if the user has enabled the "Auto-start on boot" setting.
 *
 * Listens for:
 * - BOOT_COMPLETED: Standard Android boot broadcast
 * - QUICKBOOT_POWERON: HTC/some OEM fast-boot broadcast
 *
 * Uses a dedicated SharedPreferences file ("columba_boot_prefs") to read the preference,
 * since DataStore requires coroutine initialization which is too heavy for a boot receiver.
 * The SettingsRepository keeps this SharedPreferences file in sync with the DataStore value.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootCompletedReceiver"
        const val PREFS_NAME = "columba_boot_prefs"
        const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.d(TAG, "Boot completed broadcast received")

        val enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

        if (!enabled) {
            Log.d(TAG, "Auto-start disabled - skipping service start")
            return
        }

        Log.i(TAG, "Auto-start enabled - starting ReticulumService")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val startIntent = Intent(context, ReticulumService::class.java).apply {
                    action = ReticulumService.ACTION_START
                }
                ContextCompat.startForegroundService(context, startIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
