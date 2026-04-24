package network.columba.app.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import network.columba.app.util.LocationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Provides location fixes for telemetry background sends.
 *
 * Uses low-power continuous tracking (BALANCED_POWER_ACCURACY = WiFi/cell)
 * at an interval derived from the send interval to keep a cached position
 * available. Falls back to a one-shot HIGH_ACCURACY fix when the cache is stale.
 *
 * This avoids the ~29 mAh/h cost of continuous HIGH_ACCURACY GPS while
 * still working reliably in Doze (one-shot cold GPS can timeout in Doze).
 */
internal class TelemetryLocationTracker(
    private val context: Context,
    private val useGms: Boolean,
    private val fusedLocationClient: FusedLocationProviderClient?,
) {
    companion object {
        private const val TAG = "TelemetryLocationTracker"
        private const val TRACKING_LEAD_TIME_MS = 30_000L // start tracking 30s before send
        private const val TRACKING_MIN_INTERVAL_MS = 60_000L // 1 min floor
        private const val MAX_ONE_SHOT_FIX_AGE_MS = 5 * 60 * 1000L // reject OS-cached fixes older than 5 min
        private const val ONE_SHOT_LOCATION_TIMEOUT_MS = 30_000L // cold GPS lock can take 20–30s
        // Reject background-cycle fallback fixes coarser than this; better to send nothing
        // periodically than to spam recipients with a position several km off. Manual sends
        // bypass this — when the user explicitly hits "send", they want SOMETHING.
        // 500 m fits typical outdoor WiFi/cell fixes; pure indoor cell-only is often 1–5 km.
        private const val MAX_PERIODIC_FALLBACK_ACCURACY_M = 500f
    }

    @Volatile private var locationTrackingActive = false
    @Volatile private var currentTrackingIntervalMs = 0L
    @Volatile private var gmsLocationTrackingCallback: LocationCallback? = null
    @Volatile private var platformLocationTrackingListener: LocationListener? = null
    @Volatile private var latestTrackedLocation: Location? = null
    @Volatile private var latestTrackedLocationRecordedAtMs: Long? = null

    val isTracking: Boolean get() = locationTrackingActive

    /** Set the active flag without starting continuous tracking. For tests only. */
    @androidx.annotation.VisibleForTesting
    internal fun activateForTest() {
        locationTrackingActive = true
    }

    /**
     * Start or stop tracking based on whether it [shouldTrack].
     * @param sendIntervalMs the telemetry send interval — tracking interval is derived from it
     */
    fun update(shouldTrack: Boolean, sendIntervalMs: Long = 0L) {
        val desiredInterval = maxOf(sendIntervalMs - TRACKING_LEAD_TIME_MS, TRACKING_MIN_INTERVAL_MS)
        if (shouldTrack && !locationTrackingActive) {
            currentTrackingIntervalMs = desiredInterval
            start()
        } else if (shouldTrack && locationTrackingActive && desiredInterval != currentTrackingIntervalMs) {
            // Interval changed — restart with new interval
            stop()
            currentTrackingIntervalMs = desiredInterval
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
     * Tries a one-shot HIGH_ACCURACY GPS fix first (per send → ~10s GPS hot,
     * negligible battery cost vs. continuous tracking). Falls back to the
     * BALANCED_POWER (WiFi/cell) cache if the one-shot times out — typical in
     * Doze with cold GPS or indoors.
     *
     * @param allowAnyAccuracy when true, accept the tracker fallback regardless
     *   of accuracy. Pass true for user-initiated sends ("send now"); pass false
     *   for periodic background cycles where a 5 km fix would just spam peers.
     */
    suspend fun getTelemetryLocation(allowAnyAccuracy: Boolean = false): Location? {
        if (!locationTrackingActive) return null

        val current =
            withTimeoutOrNull(ONE_SHOT_LOCATION_TIMEOUT_MS) {
                getCurrentLocation()
            }

        if (current != null && isFixFresh(current)) {
            Log.d(
                TAG,
                "One-shot fix accepted: provider=${current.provider} acc=${current.accuracy}m " +
                    "age=${System.currentTimeMillis() - current.time}ms",
            )
            cacheTrackedLocation(current)
            return current
        }

        if (current == null) {
            Log.w(TAG, "One-shot HIGH_ACCURACY timed out (${ONE_SHOT_LOCATION_TIMEOUT_MS}ms), falling back to tracker cache")
        } else {
            Log.w(TAG, "One-shot fix stale (age=${System.currentTimeMillis() - current.time}ms), falling back to tracker cache")
        }

        val tracked = latestTrackedLocation
        if (tracked == null || !isLocationRecent(tracked)) return null

        if (!allowAnyAccuracy && tracked.accuracy > MAX_PERIODIC_FALLBACK_ACCURACY_M) {
            Log.w(
                TAG,
                "Periodic send: tracker cache rejected (acc=${tracked.accuracy}m > " +
                    "${MAX_PERIODIC_FALLBACK_ACCURACY_M}m); skipping rather than spamming peers " +
                    "with a position several km off (manual send would override)",
            )
            return null
        }

        Log.d(
            TAG,
            "Tracker cache fallback accepted (allowAnyAccuracy=$allowAnyAccuracy): " +
                "provider=${tracked.provider} acc=${tracked.accuracy}m " +
                "age=${System.currentTimeMillis() - tracked.time}ms",
        )
        return tracked
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
                            result.lastLocation?.let { cacheTrackedLocation(it) }
                        }
                    }

                val request =
                    LocationRequest
                        .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, currentTrackingIntervalMs)
                        .setMinUpdateIntervalMillis(TRACKING_MIN_INTERVAL_MS)
                        .build()

                fusedLocationClient!!.requestLocationUpdates(request, callback, context.mainLooper)
                gmsLocationTrackingCallback = callback
            } else {
                platformLocationTrackingListener =
                    LocationCompat.requestLocationUpdates(context, currentTrackingIntervalMs) { location ->
                        cacheTrackedLocation(location)
                    }
            }

            locationTrackingActive = true
            Log.d(TAG, "Location tracking started (BALANCED_POWER, interval=${currentTrackingIntervalMs}ms)")
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

    /** Check if a fix from the OS is recent enough based on its capture time. */
    private fun isFixFresh(location: Location): Boolean {
        // location.time == 0 means the provider didn't set a timestamp (common in test/mock);
        // treat as fresh since we just received it from the OS
        if (location.time <= 0) return true
        return System.currentTimeMillis() - location.time <= MAX_ONE_SHOT_FIX_AGE_MS
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

    private fun isLocationRecent(location: Location): Boolean {
        // Cache TTL = tracking interval + buffer (roughly matches the send interval)
        val maxAge = currentTrackingIntervalMs + TRACKING_LEAD_TIME_MS + TRACKING_LEAD_TIME_MS
        return getTrackedLocationAgeMs(location) <= maxAge
    }

    /**
     * Get the current device location via a one-shot request (HIGH_ACCURACY fallback).
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
                        // maxUpdateAgeMillis=0 forces a fresh fix and rejects the OS location
                        // cache. Without it, GMS can return a 5–10s-old network fix labelled
                        // as HIGH_ACCURACY — which indoors can be several km off while still
                        // reporting a plausible accuracy value.
                        val request =
                            CurrentLocationRequest
                                .Builder()
                                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                                .setMaxUpdateAgeMillis(0L)
                                .setDurationMillis(ONE_SHOT_LOCATION_TIMEOUT_MS)
                                .setGranularity(Granularity.GRANULARITY_FINE)
                                .build()
                        fusedLocationClient!!
                            .getCurrentLocation(
                                request,
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
                val signal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }

                if (continuation.isActive) {
                    LocationCompat.getCurrentLocation(context, signal) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }
            }
        }
}
