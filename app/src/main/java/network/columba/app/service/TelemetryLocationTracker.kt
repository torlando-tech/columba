package network.columba.app.service

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import network.columba.app.util.LocationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Provides on-demand location fixes for telemetry background sends.
 *
 * Instead of keeping a continuous GPS subscription (which drains ~29 mAh/h),
 * uses one-shot fixes only when a telemetry send actually needs a position.
 * Each fix takes ~10-20s and is cached for [MAX_TRACKED_LOCATION_AGE_MS] to
 * avoid redundant GPS activations if called again quickly.
 */
internal class TelemetryLocationTracker(
    private val context: Context,
    private val useGms: Boolean,
    private val fusedLocationClient: FusedLocationProviderClient?,
) {
    companion object {
        private const val TAG = "TelemetryLocationTracker"
        private const val MAX_TRACKED_LOCATION_AGE_MS = 30_000L // 30s dedup window
        private const val ONE_SHOT_LOCATION_TIMEOUT_MS = 20_000L
    }

    @Volatile private var locationTrackingActive = false
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
        locationTrackingActive = false
        latestTrackedLocation = null
        latestTrackedLocationRecordedAtMs = null
        Log.d(TAG, "Location tracking stopped for telemetry")
    }

    /**
     * Return a recent valid location suitable for telemetry.
     *
     * Returns the cached location if it is less than [MAX_TRACKED_LOCATION_AGE_MS] old,
     * otherwise performs a one-shot GPS fix (typically ~10-20s).
     */
    suspend fun getTelemetryLocation(): Location? {
        if (!locationTrackingActive) return null

        val tracked = latestTrackedLocation
        if (tracked != null && isLocationRecent(tracked)) {
            return tracked
        }

        val current =
            withTimeoutOrNull(ONE_SHOT_LOCATION_TIMEOUT_MS) {
                getCurrentLocation()
            }

        if (current == null) {
            Log.w(TAG, "Timed out waiting for one-shot location (${ONE_SHOT_LOCATION_TIMEOUT_MS}ms)")
        } else {
            cacheTrackedLocation(current)
        }
        return current
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun start() {
        if (locationTrackingActive) return
        locationTrackingActive = true
        Log.d(TAG, "Location tracking enabled (on-demand one-shot mode)")
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

    private fun isLocationRecent(location: Location): Boolean =
        getTrackedLocationAgeMs(location) <= MAX_TRACKED_LOCATION_AGE_MS

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
