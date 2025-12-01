package com.lxmf.messenger.reticulum.ble.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Manages BLE permissions across different Android versions.
 *
 * Android 11 and below: Requires BLUETOOTH, BLUETOOTH_ADMIN, and ACCESS_FINE_LOCATION
 * Android 12+: Requires BLUETOOTH_SCAN, BLUETOOTH_CONNECT, and BLUETOOTH_ADVERTISE
 *
 * The neverForLocation flag in manifest allows scanning without location permission on Android 12+.
 */
object BlePermissionManager {
    /**
     * Result of permission check.
     */
    sealed class PermissionStatus {
        /**
         * All required permissions are granted.
         */
        object Granted : PermissionStatus()

        /**
         * Some required permissions are denied.
         * @param missingPermissions List of permissions that need to be requested
         * @param shouldShowRationale Whether we should show a rationale before requesting
         */
        data class Denied(
            val missingPermissions: List<String>,
            val shouldShowRationale: Boolean = false,
        ) : PermissionStatus()

        /**
         * Permissions were permanently denied (user selected "Don't ask again").
         * User must be directed to settings.
         */
        data class PermanentlyDenied(
            val missingPermissions: List<String>,
        ) : PermissionStatus()
    }

    /**
     * Get the list of BLE permissions required for the current Android version.
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+)
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            // Android 11 and below
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    /**
     * Check if all required BLE permissions are granted.
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check permission status and return detailed information.
     *
     * @param context Application context
     * @return PermissionStatus indicating current permission state
     */
    fun checkPermissionStatus(context: Context): PermissionStatus {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions =
            requiredPermissions.filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
            }

        return when {
            missingPermissions.isEmpty() -> PermissionStatus.Granted
            else -> PermissionStatus.Denied(missingPermissions)
        }
    }

    /**
     * Get a human-readable description of why BLE permissions are needed.
     * This should be shown to users before requesting permissions.
     */
    fun getPermissionRationale(): String {
        return buildString {
            appendLine("Columba needs Bluetooth permissions to:")
            appendLine("• Discover and connect to nearby Reticulum nodes")
            appendLine("• Create a mesh network for decentralized messaging")
            appendLine("• Send and receive encrypted messages over BLE")
            appendLine()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("Note: Location access is NOT required. BLE is used only for mesh networking.")
            } else {
                appendLine("Note: Location permission is required by Android for BLE scanning, but Columba does not use your location.")
            }
        }
    }

    /**
     * Check if BLE hardware is available on this device.
     */
    fun isBleSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    /**
     * Check if the device supports peripheral (advertising) mode.
     * This requires Android 5.0+ and hardware support.
     */
    fun isPeripheralModeSupported(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
}
