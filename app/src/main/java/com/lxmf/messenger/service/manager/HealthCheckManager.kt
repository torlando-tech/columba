package com.lxmf.messenger.service.manager

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors the health of the Python Reticulum process via heartbeat checking.
 *
 * The Python wrapper updates a heartbeat timestamp every 5 seconds when idle.
 * This manager periodically checks if that heartbeat is stale, indicating the
 * Python process may be hung or dead.
 *
 * If the heartbeat is stale for longer than STALE_THRESHOLD_MS, triggers a
 * service restart via the onStaleHeartbeat callback.
 *
 * Inspired by Sideband's service heartbeat pattern.
 */
class HealthCheckManager(
    private val wrapperManager: PythonWrapperManager,
    private val scope: CoroutineScope,
    private val onStaleHeartbeat: () -> Unit,
) {
    companion object {
        private const val TAG = "HealthCheckManager"

        // Check heartbeat every 30 seconds (reduced from 5s to cut IPC calls by 83%)
        internal const val CHECK_INTERVAL_MS = 30_000L

        // Heartbeat is stale if older than 60 seconds
        // Python updates every 5 seconds when idle, so 60 seconds means 12+ missed updates
        internal const val STALE_THRESHOLD_MS = 60_000L
    }

    private var healthCheckJob: Job? = null

    // Track consecutive stale checks to avoid false positives
    private var consecutiveStaleCount = 0
    private val staleCountThreshold = 2 // Require 2 consecutive stale checks before restart

    /**
     * Start the health check monitoring job.
     * Safe to call multiple times - previous job will be cancelled.
     */
    fun start() {
        healthCheckJob?.cancel()
        consecutiveStaleCount = 0
        healthCheckJob =
            scope.launch {
                Log.d(TAG, "Health check monitoring started")
                // Initial delay to let Python initialize
                delay(CHECK_INTERVAL_MS)

                while (isActive) {
                    checkHeartbeat()
                    delay(CHECK_INTERVAL_MS)
                }
            }
    }

    /**
     * Stop the health check monitoring job.
     * Safe to call multiple times or when not running.
     */
    fun stop() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        consecutiveStaleCount = 0
        Log.d(TAG, "Health check monitoring stopped")
    }

    /**
     * Check if the health check job is currently running.
     */
    fun isRunning(): Boolean = healthCheckJob?.isActive == true

    /**
     * Check the Python heartbeat and take action if stale.
     */
    private fun checkHeartbeat() {
        try {
            val heartbeat = wrapperManager.getHeartbeat()
            val now = System.currentTimeMillis() / 1000.0 // Convert to seconds to match Python
            val age = now - heartbeat

            // Convert age to milliseconds for comparison
            val ageMs = (age * 1000).toLong()

            if (heartbeat == 0.0) {
                // No heartbeat yet - Python may not be initialized
                Log.d(TAG, "No heartbeat available (wrapper not initialized)")
                consecutiveStaleCount = 0
                return
            }

            if (ageMs > STALE_THRESHOLD_MS) {
                consecutiveStaleCount++
                Log.w(
                    TAG,
                    "Stale heartbeat detected: age=${ageMs}ms, " +
                        "threshold=${STALE_THRESHOLD_MS}ms, " +
                        "consecutiveCount=$consecutiveStaleCount/$staleCountThreshold",
                )

                if (consecutiveStaleCount >= staleCountThreshold) {
                    Log.e(
                        TAG,
                        "Heartbeat stale for $consecutiveStaleCount consecutive checks - " +
                            "triggering service restart",
                    )
                    consecutiveStaleCount = 0
                    onStaleHeartbeat()
                }
            } else {
                if (consecutiveStaleCount > 0) {
                    Log.d(TAG, "Heartbeat recovered after $consecutiveStaleCount stale checks")
                }
                consecutiveStaleCount = 0
                Log.v(TAG, "Heartbeat OK: age=${ageMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking heartbeat", e)
            // Don't increment stale count on exceptions - could be transient
        }
    }
}
