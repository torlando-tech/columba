package com.lxmf.messenger.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Manages notification permissions for Android 13+ (API 33).
 *
 * Starting with Android 13 (TIRAMISU), apps must request the POST_NOTIFICATIONS
 * permission to show notifications. This manager provides utilities to:
 * - Check if the permission is granted
 * - Determine if a permission request is needed
 * - Get the permission string for the launcher
 *
 * On Android 12 and below, no permission is needed and notifications work by default.
 */
object NotificationPermissionManager {
    /**
     * Check if notification permission is granted.
     *
     * @param context Application context
     * @return true if permission is granted OR if running on Android 12 or below
     *         (where no permission is needed)
     */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // No permission needed on Android 12 and below
            true
        }
    }

    /**
     * Check if a permission request is needed before enabling notifications.
     *
     * @param context Application context
     * @return true only if running Android 13+ AND permission is not granted
     */
    fun needsPermissionRequest(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        } else {
            // No permission needed on Android 12 and below
            false
        }
    }

    /**
     * Get the permission string required for notifications.
     *
     * @return POST_NOTIFICATIONS permission string on Android 13+, null otherwise
     */
    fun getRequiredPermission(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    }
}
