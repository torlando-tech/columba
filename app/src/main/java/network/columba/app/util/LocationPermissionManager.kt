package network.columba.app.util

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
     * The location-precision radius (metres) that means "Precise" — exact GPS,
     * no coarsening. Mirrors the `PRECISE` preset in the Location Sharing
     * settings card. When the user has chosen this, the app needs
     * [Manifest.permission.ACCESS_FINE_LOCATION]; only-approximate access
     * yields positions kilometres off (issue #855).
     */
    const val PRECISE_PRECISION_RADIUS = 0

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
         * @param shouldShowRationale Whether we should show a rationale before requesting.
         *   Note: Permanent denial detection requires an Activity context
         *   (via shouldShowRequestPermissionRationale) and must be handled at the call site.
         */
        data class Denied(
            val shouldShowRationale: Boolean = false,
        ) : PermissionStatus()
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
     * Whether to prompt the user to upgrade to precise (fine) location.
     *
     * True only when location sharing is *enabled* AND its precision is set to
     * "Precise" ([PRECISE_PRECISION_RADIUS]) AND only approximate location is
     * currently granted — the state behind issue #855, where shared/telemetry
     * positions land kilometres away because the OS is withholding GPS.
     *
     * The [locationSharingEnabled] precondition fixes issue #991: precision
     * defaults to Precise (radius 0) for users who have never opened Location
     * Sharing settings, while sharing itself defaults to *off*. Without this
     * gate the prompt fired on the default state and re-armed every resume,
     * nagging users who never opted into sharing at all. When the user has
     * deliberately chosen an approximate radius (>0), or hasn't enabled sharing,
     * no upgrade is needed.
     *
     * @param locationSharingEnabled whether the user has turned on Location
     *   Sharing — precise access is pointless to request while it's off (#991)
     * @param precisionRadiusMeters persisted location-precision radius
     *   (0 = precise; >0 = coarsen to that radius)
     * @param hasFineLocation whether [Manifest.permission.ACCESS_FINE_LOCATION]
     *   is currently granted (see [hasFineLocationPermission])
     */
    fun needsPreciseLocationUpgrade(
        locationSharingEnabled: Boolean,
        precisionRadiusMeters: Int,
        hasFineLocation: Boolean,
    ): Boolean =
        locationSharingEnabled &&
            precisionRadiusMeters == PRECISE_PRECISION_RADIUS &&
            !hasFineLocation

    /**
     * Whether this Android version requires explicit background location permission.
     */
    fun requiresBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Check if explicit background location permission is granted.
     * On API levels below 29, this is treated as granted.
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        if (!requiresBackgroundLocationPermission()) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if permissions are sufficient for telemetry sending while app is inactive.
     */
    fun hasTelemetryBackgroundPermission(context: Context): Boolean =
        hasPermission(context) && hasBackgroundLocationPermission(context)

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
     * Rationale shown when prompting a user who has selected precise location
     * sharing but doesn't hold precise (fine) access — whether that's
     * approximate-only or no location permission at all (issue #855).
     */
    fun getPreciseLocationRationale(): String {
        return buildString {
            appendLine(
                "Location sharing is set to Precise, but Columba doesn't have precise " +
                    "location access.",
            )
            appendLine()
            appendLine(
                "Without it, a shared position is unavailable or only approximate — off by a " +
                    "kilometre or more. Enable precise location for accurate sharing, or set a " +
                    "coarser precision in Location Sharing settings if that's intended.",
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
