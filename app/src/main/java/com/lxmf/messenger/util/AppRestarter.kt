package com.lxmf.messenger.util

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

private const val TAG = "AppRestarter"

/**
 * Utility object for restarting the application.
 * Used when switching identities to reload with the new active identity.
 */
object AppRestarter {
    /**
     * Restart the application.
     *
     * This starts a new instance of the app and then kills the current process.
     * The app will be relaunched with a fresh state.
     *
     * @param context Application context
     */
    fun restartApp(context: Context) {
        Log.d(TAG, "restartApp: Initiating app restart...")

        // Get the launch intent for the app
        val intent =
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?: throw IllegalStateException("Unable to get launch intent for app")

        // Clear the task stack and create a new task
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)

        // Start the new activity first, then kill the process
        Log.d(TAG, "restartApp: Starting new activity...")
        context.startActivity(intent)

        // Give the system a moment to start the new activity
        Log.d(TAG, "restartApp: Exiting current process...")
        exitProcess(0)
    }

    /**
     * Check if the app needs to restart (e.g., after identity switch).
     *
     * This can be used to show a dialog or take other actions before restarting.
     *
     * @return true if the app should restart, false otherwise
     */
    fun shouldRestart(context: Context): Boolean {
        // Check if there's a pending restart flag in SharedPreferences
        val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        return prefs.getBoolean("pending_restart", false)
    }

    /**
     * Mark that the app should restart on next launch.
     *
     * @param context Application context
     */
    fun setPendingRestart(
        context: Context,
        pending: Boolean,
    ) {
        val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pending_restart", pending).apply()
    }
}
