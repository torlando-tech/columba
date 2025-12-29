package com.lxmf.messenger.service.manager

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages periodic maintenance tasks for the Reticulum service.
 *
 * Primary responsibility: Refresh wake locks before Android's 10-hour timeout expires.
 * This ensures the service maintains its wake lock during long-running sessions.
 */
class MaintenanceManager(
    private val lockManager: LockManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MaintenanceManager"

        // Refresh interval: 5 minutes (Sideband uses 2 minutes, we use 5 for efficiency)
        // Changed from 9 hours to match Sideband's more aggressive lock renewal pattern.
        // This ensures locks are maintained even if Android aggressively reclaims resources.
        internal const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    private var maintenanceJob: Job? = null
    private var lastRefreshTimeMs: Long = 0L

    /**
     * Start the periodic maintenance job.
     * Safe to call multiple times - previous job will be cancelled.
     */
    fun start() {
        maintenanceJob?.cancel()
        maintenanceJob =
            scope.launch {
                Log.d(TAG, "Maintenance job started")
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    refreshLocks()
                }
            }
    }

    /**
     * Stop the maintenance job.
     * Safe to call multiple times or when not running.
     */
    fun stop() {
        maintenanceJob?.cancel()
        maintenanceJob = null
        Log.d(TAG, "Maintenance job stopped")
    }

    /**
     * Check if the maintenance job is currently running.
     */
    fun isRunning(): Boolean = maintenanceJob?.isActive == true

    /**
     * Refresh all locks. Called periodically by the maintenance job.
     */
    internal fun refreshLocks() {
        try {
            lockManager.acquireAll()
            lastRefreshTimeMs = System.currentTimeMillis()
            Log.d(TAG, "Locks refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh locks", e)
        }
    }

    /**
     * Get the time in seconds since the last lock refresh.
     * Returns -1 if no refresh has occurred yet.
     */
    fun getLastRefreshAgeSeconds(): Long {
        return if (lastRefreshTimeMs > 0) {
            (System.currentTimeMillis() - lastRefreshTimeMs) / 1000
        } else {
            -1L
        }
    }
}
