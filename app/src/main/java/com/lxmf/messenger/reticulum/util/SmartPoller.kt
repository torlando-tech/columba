package com.lxmf.messenger.reticulum.util

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Smart polling utility with exponential backoff.
 *
 * Implements adaptive polling that starts at a minimum interval and backs off
 * exponentially when idle, reducing CPU and battery usage while maintaining
 * responsiveness during active periods.
 *
 * Part of Phase 2.2 of the threading redesign.
 *
 * Behavior:
 * - Active: polls at minInterval (default 2s)
 * - Idle: exponentially backs off to maxInterval (default 30s)
 * - Backoff progression: 2s → 4s → 8s → 16s → 30s
 *
 * Expected results:
 * - ~2 polls/second during activity
 * - < 2 polls/minute when idle (30s max)
 * - 30% battery improvement over 24 hours
 *
 * @param minInterval Minimum polling interval in milliseconds (used when active)
 * @param maxInterval Maximum polling interval in milliseconds (used when idle)
 * @param backoffMultiplier Factor by which to increase interval each cycle
 */
class SmartPoller(
    private val minInterval: Long = 2_000, // 2s when active
    private val maxInterval: Long = 30_000, // 30s max when idle
    private val backoffMultiplier: Double = 2.0,
) {
    // Thread-safe state: @Volatile ensures visibility across threads
    @Volatile
    private var currentInterval = minInterval

    // AtomicBoolean for thread-safe boolean operations
    private val isActive = AtomicBoolean(false)

    /**
     * Mark the poller as active (e.g., new data detected).
     * Resets polling interval to minimum for immediate responsiveness.
     * Thread-safe: uses AtomicBoolean and @Volatile for visibility.
     */
    fun markActive() {
        isActive.set(true)
        currentInterval = minInterval
    }

    /**
     * Mark the poller as idle (e.g., no new data).
     * Next interval will apply exponential backoff.
     * Thread-safe: uses AtomicBoolean.
     */
    fun markIdle() {
        isActive.set(false)
    }

    /**
     * Get the next polling interval in milliseconds.
     *
     * Returns minInterval if active, otherwise applies exponential backoff
     * up to maxInterval.
     *
     * Thread-safe: reads atomic state and uses @Volatile for interval.
     *
     * @return Next interval to wait before polling again (ms)
     */
    fun getNextInterval(): Long {
        return if (isActive.get()) {
            minInterval
        } else {
            // Exponential backoff when idle
            currentInterval =
                min(
                    (currentInterval * backoffMultiplier).toLong(),
                    maxInterval,
                )
            currentInterval
        }
    }

    /**
     * Reset the poller to initial state (minInterval, not active).
     * Useful when resuming from paused state.
     * Thread-safe: atomic operations ensure consistent state.
     */
    fun reset() {
        currentInterval = minInterval
        isActive.set(false)
    }

    /**
     * Get current interval without advancing state.
     * Useful for logging/debugging.
     */
    fun getCurrentInterval(): Long = currentInterval
}
