package network.columba.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import network.columba.app.R

/**
 * Manages camera permissions for QR code scanning.
 *
 * Requires CAMERA permission across all Android versions.
 */
object CameraPermissionManager {
    /**
     * Result of permission check.
     */
    sealed class PermissionStatus {
        /**
         * Camera permission is granted.
         */
        object Granted : PermissionStatus()

        /**
         * Camera permission is denied.
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
     * Get the camera permission.
     */
    fun getRequiredPermission(): String {
        return Manifest.permission.CAMERA
    }

    /**
     * Check if camera permission is granted.
     */
    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check permission status and return detailed information.
     *
     * @param context Application context
     * @return PermissionStatus indicating current permission state
     */
    fun checkPermissionStatus(context: Context): PermissionStatus {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

        return if (hasPermission) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Denied()
        }
    }

    /**
     * Resource id of a human-readable description of why camera permission is
     * needed. This should be shown to users before requesting permissions.
     */
    @StringRes
    fun getPermissionRationale(): Int = R.string.camera_permission_rationale

    /**
     * Check if camera hardware is available on this device.
     */
    fun isCameraSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}
