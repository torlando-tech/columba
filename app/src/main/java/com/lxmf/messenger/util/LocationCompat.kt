package com.lxmf.messenger.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Provides Google Play Services availability checking and fallback location methods
 * using Android's built-in LocationManager.
 *
 * On devices without Google Play Services (custom ROMs, F-Droid builds), calling
 * FusedLocationProviderClient generates repeated warnings:
 *   W GooglePlayServicesUtil: com.lxmf.messenger required the Google Play Store, but it is missing.
 *   W GoogleApiManager: The service for com.google.android.gms.internal.location.k is not available
 *
 * This utility checks availability once, logs at Info level, and provides fallback
 * location methods so those GMS APIs are never called on unsupported devices.
 *
 * @see <a href="https://github.com/torlando-tech/columba/issues/456">Issue #456</a>
 */
object LocationCompat {
    private const val TAG = "LocationCompat"
    private const val SINGLE_LOCATION_TIMEOUT_MS = 10_000L

    @Volatile
    private var checked = false

    @Volatile
    private var available = false

    /**
     * Check if Google Play Services location is available on this device.
     * The result is cached after the first check. Logs availability once at Info level.
     */
    fun isPlayServicesAvailable(context: Context): Boolean {
        if (!checked) {
            synchronized(this) {
                if (!checked) {
                    val result =
                        GoogleApiAvailability
                            .getInstance()
                            .isGooglePlayServicesAvailable(context)
                    available = (result == ConnectionResult.SUCCESS)
                    checked = true

                    if (available) {
                        Log.i(TAG, "Google Play Services available, using FusedLocationProvider")
                    } else {
                        Log.i(
                            TAG,
                            "Google Play Services not available, using Android LocationManager for location",
                        )
                    }
                }
            }
        }
        return available
    }

    /**
     * Get the last known location from Android's LocationManager.
     * Tries GPS provider first, then network provider.
     *
     * @return The last known location, or null if none available
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Request a single current location from Android's LocationManager.
     * Uses GPS if available, otherwise falls back to network provider.
     *
     * @param context Application context
     * @param onResult Callback with the location, or null if unavailable
     */
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(
        context: Context,
        onResult: (Location?) -> Unit,
    ) {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val provider =
            when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> {
                    // No provider enabled, return last known
                    onResult(getLastKnownLocation(context))
                    return
                }
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                    provider,
                    null, // CancellationSignal
                    context.mainExecutor,
                ) { location ->
                    onResult(location)
                }
            } else {
                // For older APIs, request a single update with a safety timeout.
                // requestSingleUpdate may never invoke its callback if no GPS fix
                // is obtained (e.g., poor signal indoors), which would leave callers
                // stuck waiting forever.
                val handler = Handler(Looper.getMainLooper())
                var resultDelivered = false

                val listener =
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!resultDelivered) {
                                resultDelivered = true
                                handler.removeCallbacksAndMessages(this)
                                locationManager.removeUpdates(this)
                                onResult(location)
                            }
                        }

                        @Deprecated("Deprecated in API")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: android.os.Bundle?,
                        ) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) {
                            if (!resultDelivered) {
                                resultDelivered = true
                                handler.removeCallbacksAndMessages(this)
                                locationManager.removeUpdates(this)
                                onResult(getLastKnownLocation(context))
                            }
                        }
                    }

                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(
                    provider,
                    listener,
                    Looper.getMainLooper(),
                )

                // Safety timeout: fall back to last known location
                handler.postAtTime(
                    {
                        if (!resultDelivered) {
                            resultDelivered = true
                            locationManager.removeUpdates(listener)
                            Log.d(TAG, "Single location request timed out, falling back to last known")
                            onResult(getLastKnownLocation(context))
                        }
                    },
                    listener, // token for removeCallbacksAndMessages
                    SystemClock.uptimeMillis() + SINGLE_LOCATION_TIMEOUT_MS,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get current location from $provider", e)
            // Fall back to last known
            onResult(getLastKnownLocation(context))
        }
    }

    /**
     * Request continuous location updates from Android's LocationManager.
     * Returns the [LocationListener] which must be passed to [removeLocationUpdates] to stop.
     *
     * @param context Application context
     * @param intervalMs Minimum time between updates in milliseconds
     * @param onLocation Callback for each location update
     * @return The LocationListener handle, for use with [removeLocationUpdates]
     */
    @SuppressLint("MissingPermission")
    fun requestLocationUpdates(
        context: Context,
        intervalMs: Long,
        onLocation: (Location) -> Unit,
    ): LocationListener {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocation(location)
                }

                @Deprecated("Deprecated in API")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: android.os.Bundle?,
                ) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

        // Request from GPS if available
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }

        // Also request from network if available (provides faster initial fix)
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                intervalMs,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }

        return listener
    }

    /**
     * Remove location updates previously started with [requestLocationUpdates].
     *
     * @param context Application context
     * @param listener The LocationListener returned from [requestLocationUpdates]
     */
    fun removeLocationUpdates(
        context: Context,
        listener: LocationListener,
    ) {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.removeUpdates(listener)
    }
}
