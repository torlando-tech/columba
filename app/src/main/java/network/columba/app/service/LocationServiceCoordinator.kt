package com.lxmf.messenger.service

import android.content.Context
import android.util.Log

/**
 * Coordinates [LocationForegroundService] lifecycle between multiple consumers
 * (location sharing and telemetry collection).
 *
 * The service is started when the first consumer acquires and stopped when the
 * last consumer releases. Thread-safe via synchronized.
 */
object LocationServiceCoordinator {
    private const val TAG = "LocationServiceCoord"

    const val REASON_SHARING = "location_sharing"
    const val REASON_TELEMETRY = "telemetry_collection"

    private val activeReasons = mutableSetOf<String>()

    // Set when the service fails to start (e.g. SecurityException on Android 14+).
    // Prevents observers from retrying acquire() in a loop.
    @Volatile
    private var serviceFailed = false

    fun isAcquired(reason: String): Boolean = synchronized(activeReasons) { reason in activeReasons }

    /** Call after the user re-grants location permission to allow re-acquiring the service. */
    fun resetFailedState() {
        serviceFailed = false
        Log.d(TAG, "serviceFailed flag cleared — permission likely re-granted")
    }

    fun acquire(context: Context, reason: String) {
        if (serviceFailed) {
            // Check if permission was re-granted since the failure
            if (com.lxmf.messenger.util.LocationPermissionManager.hasPermission(context)) {
                resetFailedState()
            } else {
                return
            }
        }
        synchronized(activeReasons) {
            if (serviceFailed) return // re-check under lock
            val wasEmpty = activeReasons.isEmpty()
            activeReasons.add(reason)
            val text = notificationText()
            if (wasEmpty) {
                Log.d(TAG, "Starting location foreground service (reason: $reason)")
                try {
                    LocationForegroundService.start(context, text)
                } catch (e: Exception) {
                    activeReasons.remove(reason)
                    Log.e(TAG, "Failed to start service, rolled back '$reason'", e)
                }
            } else {
                // Update notification text to reflect new reason
                LocationForegroundService.start(context, text)
                Log.d(TAG, "Location service updated, added reason: $reason (active: $activeReasons)")
            }
        }
    }

    /** Called by the service when it fails to start foreground and self-destructs. */
    fun clearAll() {
        synchronized(activeReasons) {
            Log.w(TAG, "Clearing all reasons due to service failure (was: $activeReasons)")
            activeReasons.clear()
            serviceFailed = true
        }
    }

    fun release(context: Context, reason: String) {
        synchronized(activeReasons) {
            if (!activeReasons.remove(reason)) {
                Log.d(TAG, "release() for '$reason' — not acquired, ignoring")
                return
            }
            if (activeReasons.isEmpty()) {
                Log.d(TAG, "Stopping location foreground service (released: $reason)")
                LocationForegroundService.stop(context)
            } else {
                // Update notification text to reflect remaining reasons
                LocationForegroundService.start(context, notificationText())
                Log.d(TAG, "Location service updated (released: $reason, remaining: $activeReasons)")
            }
        }
    }

    private fun notificationText(): String = when {
        REASON_SHARING in activeReasons && REASON_TELEMETRY in activeReasons ->
            "Location sharing & telemetry active"
        REASON_SHARING in activeReasons -> "Location sharing active"
        REASON_TELEMETRY in activeReasons -> "Telemetry collection active"
        else -> "Location active"
    }
}
