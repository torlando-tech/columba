package com.lxmf.messenger.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Manages location permissions for the Map feature.
 *
 * Requires ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION for:
 * - Displaying user's current location on the map
 * - Future: Sharing location with contacts
 *
 * Note: On Android 12+, both permissions must be requested together
 * if requesting FINE location.
 */
object LocationPermissionManager {
    /**
     * Result of permission check.
     */
    sealed class PermissionStatus {
        /**
         * Location permission is granted.
         */
        object Granted : PermissionStatus()

        /**
         * Location permission is denied.
         * @param shouldShowRationale Whether we should show a rationale before requesting
         */
        data class Denied(
            val shouldShowRationale: Boolean = false,
        ) : PermissionStatus()

        /**
         * Permission was permanently denied (user selected "Don't ask again").
         * User must be directed to settings.
         */
        object PermanentlyDenied : PermissionStatus()
    }

    /**
     * Get the required location permissions based on API level.
     *
     * On Android 12+, requesting FINE_LOCATION also requires COARSE_LOCATION.
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires both FINE and COARSE when requesting FINE
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            // Pre-Android 12, only FINE_LOCATION is needed
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * Check if location permission is granted.
     *
     * Returns true if either FINE or COARSE location is granted.
     * Prefers FINE location but accepts COARSE for degraded functionality.
     */
    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if fine (precise) location permission is granted.
     */
    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check permission status and return detailed information.
     *
     * @param context Application context
     * @return PermissionStatus indicating current permission state
     */
    fun checkPermissionStatus(context: Context): PermissionStatus {
        val hasFineLocation =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        return if (hasFineLocation || hasCoarseLocation) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied()
        }
    }

    /**
     * Get a human-readable description of why location permission is needed.
     * This should be shown to users before requesting permissions.
     */
    fun getPermissionRationale(): String {
        return buildString {
            appendLine("Columba needs location access to:")
            appendLine()
            appendLine("Share your location with chosen contacts")
            appendLine("Help friends find you at events")
            appendLine("Calculate distance to contacts")
            appendLine()
            appendLine(
                "You control who can see your location and for how long. " +
                    "Location data is only shared peer-to-peer with contacts you choose. " +
                    "Location data, like all data on Columba, is encrypted end to end, " +
                    "never stored on a central server, and is only readable by the contacts you send it to.",
            )
            appendLine()
            appendLine(
                "Columba will not and can not share your location with anyone until you actively " +
                    "send it to someone. Your location is always encrypted safely over Reticulum.",
            )
        }
    }

    /**
     * Check if location services are available on this device.
     */
    fun isLocationSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
    }
}
