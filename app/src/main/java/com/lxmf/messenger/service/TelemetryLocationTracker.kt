package com.lxmf.messenger.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lxmf.messenger.util.LocationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages continuous location tracking for telemetry background sends.
 *
 * Keeps a recent cached location fix so that periodic telemetry sends
 * can obtain a position even when the app is in the background and no
 * other app is requesting location updates.
 */
internal class TelemetryLocationTracker(
    private val context: Context,
    private val useGms: Boolean,
    private val fusedLocationClient: FusedLocationProviderClient?,
) {
    companion object {
        private const val TAG = "TelemetryLocationTracker"
        private const val TRACKING_UPDATE_INTERVAL_MS = 30_000L
        private const val TRACKING_MIN_UPDATE_INTERVAL_MS = 15_000L
        private const val MAX_TRACKED_LOCATION_AGE_MS = 5 * 60 * 1000L
        private const val ONE_SHOT_LOCATION_TIMEOUT_MS = 20_000L
    }

    @Volatile private var locationTrackingActive = false

    @Volatile private var gmsLocationTrackingCallback: LocationCallback? = null

    @Volatile private var platformLocationTrackingListener: LocationListener? = null

    @Volatile private var latestTrackedLocation: Location? = null

    @Volatile private var latestTrackedLocationRecordedAtMs: Long? = null

    val isTracking: Boolean get() = locationTrackingActive

    /**
     * Start or stop tracking based on whether it [shouldTrack].
     */
    fun update(shouldTrack: Boolean) {
        if (shouldTrack && !locationTrackingActive) {
            start()
        } else if (!shouldTrack && locationTrackingActive) {
            stop()
        }
    }

    /**
     * Stop tracking and release resources.
     */
    fun stop() {
        if (!locationTrackingActive) return

        try {
            if (useGms) {
                gmsLocationTrackingCallback?.let { callback ->
                    fusedLocationClient?.removeLocationUpdates(callback)
                }
                gmsLocationTrackingCallback = null
            } else {
                platformLocationTrackingListener?.let { listener ->
                    LocationCompat.removeLocationUpdates(context, listener)
                }
                platformLocationTrackingListener = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping telemetry location tracking", e)
        }

        locationTrackingActive = false
        latestTrackedLocation = null
        latestTrackedLocationRecordedAtMs = null
        Log.d(TAG, "Location tracking stopped for telemetry")
    }

    /**
     * Return a recent valid location suitable for telemetry.
     *
     * Prefers the continuously tracked location if it is less than [MAX_TRACKED_LOCATION_AGE_MS]
     * old, otherwise falls back to a one-shot location request with a timeout.
     */
    suspend fun getTelemetryLocation(): Location? {
        val tracked = latestTrackedLocation
        if (tracked != null) {
            if (isLocationRecent(tracked)) {
                return tracked
            }
            Log.w(TAG, "Tracked location is stale (${getTrackedLocationAgeMs(tracked)} ms), refreshing")
        }

        val current =
            withTimeoutOrNull(ONE_SHOT_LOCATION_TIMEOUT_MS) {
                getCurrentLocation()
            }

        if (current == null) {
            Log.w(TAG, "Timed out waiting for one-shot location (${ONE_SHOT_LOCATION_TIMEOUT_MS}ms)")
            return null
        }

        cacheTrackedLocation(current)

        return if (isLocationRecent(current)) {
            current
        } else {
            Log.w(TAG, "Current location is stale (${getTrackedLocationAgeMs(current)} ms), rejecting")
            null
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    @Suppress("MissingPermission")
    private fun start() {
        if (locationTrackingActive) return

        try {
            if (useGms) {
                val callback =
                    object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            val location = result.lastLocation ?: return
                            cacheTrackedLocation(location)
                        }
                    }

                val request =
                    LocationRequest
                        .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, TRACKING_UPDATE_INTERVAL_MS)
                        .setMinUpdateIntervalMillis(TRACKING_MIN_UPDATE_INTERVAL_MS)
                        .build()

                fusedLocationClient!!.requestLocationUpdates(request, callback, context.mainLooper)
                gmsLocationTrackingCallback = callback
            } else {
                platformLocationTrackingListener =
                    LocationCompat.requestLocationUpdates(context, TRACKING_UPDATE_INTERVAL_MS) { location ->
                        cacheTrackedLocation(location)
                    }
            }

            locationTrackingActive = true
            Log.d(TAG, "Location tracking started for telemetry")
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to start telemetry location tracking (permission missing)", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start telemetry location tracking", e)
        }
    }

    private fun cacheTrackedLocation(location: Location) {
        latestTrackedLocation = location
        latestTrackedLocationRecordedAtMs = System.currentTimeMillis()
    }

    private fun getTrackedLocationAgeMs(location: Location): Long {
        val fixTimestamp = if (location.time > 0) location.time else 0L
        val recordedTimestamp = latestTrackedLocationRecordedAtMs ?: 0L
        val bestTimestamp = maxOf(fixTimestamp, recordedTimestamp)
        return if (bestTimestamp > 0L) {
            System.currentTimeMillis() - bestTimestamp
        } else {
            Long.MAX_VALUE
        }
    }

    private fun isLocationRecent(location: Location): Boolean = getTrackedLocationAgeMs(location) <= MAX_TRACKED_LOCATION_AGE_MS

    /**
     * Get the current device location via a one-shot request.
     */
    @Suppress("MissingPermission")
    private suspend fun getCurrentLocation(): Location? =
        suspendCancellableCoroutine { continuation ->
            if (useGms) {
                val cancellationTokenSource = CancellationTokenSource()

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }

                if (continuation.isActive) {
                    try {
                        fusedLocationClient!!
                            .getCurrentLocation(
                                Priority.PRIORITY_HIGH_ACCURACY,
                                cancellationTokenSource.token,
                            ).addOnSuccessListener { location ->
                                if (continuation.isActive) {
                                    continuation.resume(location)
                                }
                            }.addOnFailureListener { exception ->
                                Log.e(TAG, "Failed to get location", exception)
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Location request cancelled before it could start", e)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            } else {
                if (continuation.isActive) {
                    LocationCompat.getCurrentLocation(context) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }
            }
        }
}
