package com.lxmf.messenger.service.state

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized state holder for ReticulumService.
 *
 * This class provides thread-safe atomic access to all service state variables.
 * All managers share this state instance, ensuring consistent state visibility
 * across the service.
 *
 * Thread Safety:
 * - Atomic types (AtomicReference, AtomicInteger, AtomicBoolean) for primitive state
 * - @Volatile for reference types that need visibility guarantees
 * - Generation tracking prevents stale async operations from corrupting state
 */
class ServiceState {
    /**
     * Current network status.
     * Values: "SHUTDOWN", "INITIALIZING", "READY", "RESTARTING", or "ERROR:message"
     */
    val networkStatus = AtomicReference<String>("SHUTDOWN")

    /**
     * Initialization generation counter for race condition prevention.
     *
     * Incremented on each new initialization cycle. Async shutdown jobs check this
     * to avoid overwriting status if a new initialization started while they were running.
     */
    val initializationGeneration = AtomicInteger(0)

    /**
     * Whether a conversation screen is currently active.
     * Used for context-aware polling (1s when active vs 2-30s adaptive when not).
     */
    val isConversationActive = AtomicBoolean(false)

    /**
     * Kill switch to prevent calls during/after interpreter teardown.
     *
     * AtomicBoolean.get() compiles to a single volatile read (~1ns) — negligible
     * even on the audio hot path.
     *
     * NOT reset in [reset] — only cleared explicitly via [clearShutdownFlag] after
     * a new initialization cycle confirms the previous shutdown completed.
     */
    val isPythonShutdownStarted = AtomicBoolean(false)

    /**
     * Active announce polling coroutine job.
     */
    @Volatile
    var pollingJob: Job? = null

    /**
     * Active shutdown coroutine job.
     * Tracked so new initializations can wait for pending shutdowns.
     */
    @Volatile
    var shutdownJob: Job? = null

    /**
     * Increment generation counter and return the new value.
     * Call this at the start of each initialization cycle.
     */
    fun nextGeneration(): Int = initializationGeneration.incrementAndGet()

    /**
     * Check if the given generation matches current generation.
     * Used by async operations to verify they should still update state.
     */
    fun isCurrentGeneration(gen: Int): Boolean = initializationGeneration.get() == gen

    /**
     * Clear the shutdown flag after a previous shutdown has fully completed
     * and a new initialization cycle is beginning.
     */
    fun clearShutdownFlag() {
        isPythonShutdownStarted.set(false)
    }

    /**
     * Check if the service network is ready.
     */
    fun isInitialized(): Boolean = networkStatus.get() == "READY"

    /**
     * Reset all state to initial values.
     * Should only be called during service destruction.
     */
    fun reset() {
        networkStatus.set("SHUTDOWN")
        isConversationActive.set(false)
        pollingJob = null
        shutdownJob = null
        // Note: initializationGeneration is NOT reset to preserve race condition protection
        // Note: isPythonShutdownStarted is NOT reset here — only by clearShutdownFlag()
    }
}
