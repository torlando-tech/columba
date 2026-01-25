package com.lxmf.messenger.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Utility for managing battery optimization exemption.
 *
 * Columba requires battery optimization exemption to prevent Android from
 * killing the background ReticulumService during Deep Doze mode, which causes
 * 6+ hour gaps in message delivery.
 */
object BatteryOptimizationManager {
    private const val TAG = "BatteryOptimizationMgr"
    private const val PREFS_KEY_LAST_PROMPT = "last_battery_prompt_time"
    private const val PROMPT_INTERVAL_DAYS = 7

    /**
     * Check if the app is currently ignoring battery optimizations.
     * Returns true if exempted, false if restricted.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // No Doze mode before Android 6.0
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

        Log.d(TAG, "Battery optimization status: ${if (isIgnoring) "EXEMPTED" else "RESTRICTED"}")
        return isIgnoring
    }

    /**
     * Create an intent to request battery optimization exemption.
     * This takes the user to a system dialog where they can grant exemption.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun createRequestExemptionIntent(context: Context): Intent {
        return Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Create an intent to open the battery optimization settings screen.
     * This shows all apps and their battery optimization status.
     */
    fun createBatterySettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Safely request battery optimization exemption with fallback for OEM devices.
     *
     * Some OEM devices (MEIZU, OnePlus, etc.) don't implement the
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent, causing crashes.
     * This method tries the direct exemption request first, then falls back
     * to the general battery settings screen if unavailable.
     *
     * @param context The context to launch the intent from
     * @return true if an intent was successfully launched, false otherwise
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun requestBatteryOptimizationExemption(context: Context): Boolean {
        return try {
            // Try the direct exemption request first
            val exemptionIntent = createRequestExemptionIntent(context)

            // Check if an activity can handle this intent
            val packageManager = context.packageManager
            if (exemptionIntent.resolveActivity(packageManager) != null) {
                context.startActivity(exemptionIntent)
                Log.d(TAG, "Launched direct battery exemption request")
                true
            } else {
                // OEM doesn't support direct exemption - fall back to settings
                Log.w(TAG, "Device doesn't support REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, falling back to settings")
                val settingsIntent = createBatterySettingsIntent()
                context.startActivity(settingsIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch battery optimization intent", e)
            false
        }
    }

    /**
     * Check if we should prompt the user for battery exemption.
     * Returns true if:
     * - Android 6.0+ (Doze mode exists)
     * - App is currently restricted
     * - User hasn't been prompted recently (avoid spam)
     */
    fun shouldPromptForExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false // No Doze mode
        }

        // Check if already exempted
        if (isIgnoringBatteryOptimizations(context)) {
            return false // Already exempted, no need to prompt
        }

        // Check when we last prompted (avoid prompting every time)
        val prefs = context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
        val lastPromptTime = prefs.getLong(PREFS_KEY_LAST_PROMPT, 0)
        val daysSinceLastPrompt = (System.currentTimeMillis() - lastPromptTime) / (1000 * 60 * 60 * 24)

        if (daysSinceLastPrompt < PROMPT_INTERVAL_DAYS) {
            Log.d(TAG, "Skipping battery prompt - prompted $daysSinceLastPrompt days ago")
            return false // Don't prompt more than once per week
        }

        return true
    }

    /**
     * Record that we prompted the user for battery exemption.
     */
    fun recordPromptShown(context: Context) {
        val prefs = context.getSharedPreferences("columba_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong(PREFS_KEY_LAST_PROMPT, System.currentTimeMillis()).apply()
        Log.d(TAG, "Recorded battery exemption prompt shown")
    }
}
