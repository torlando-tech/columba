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

        // Refresh interval: 9 hours (before 10-hour wake lock timeout)
        internal const val REFRESH_INTERVAL_MS = 9 * 60 * 60 * 1000L
    }

    private var maintenanceJob: Job? = null

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
            Log.d(TAG, "Locks refreshed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh locks", e)
        }
    }
}
