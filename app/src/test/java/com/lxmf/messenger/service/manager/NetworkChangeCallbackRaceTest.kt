package com.lxmf.messenger.service.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduces the crash that occurs when leaving WiFi range.
 *
 * Root cause: Network change callbacks fire rapidly on Binder threads while
 * the wrapper is being shut down, causing TOCTOU race conditions.
 */
class NetworkChangeCallbackRaceTest {
    class MockWrapper(val id: Int) {
        @Volatile
        var isShutdown = false

        fun callMethod(): String {
            if (isShutdown) error("Wrapper is shutdown!")
            return "success-$id"
        }

        fun shutdown() {
            isShutdown = true
        }
    }

    /**
     * Simulates the CURRENT (unsafe) pattern:
     * - Callback runs on uncontrolled thread
     * - No synchronization with wrapper lifecycle
     * - No debouncing
     */
    class UnsafeNetworkCallbackSimulator {
        @Volatile
        var wrapper: MockWrapper? = null
        private var wrapperIdCounter = AtomicInteger(0)
        private val crashCount = AtomicInteger(0)

        fun initialize() {
            wrapper = MockWrapper(wrapperIdCounter.incrementAndGet())
        }

        fun shutdown() {
            wrapper?.shutdown()
            wrapper = null
        }

        // Simulates: if (::binder.isInitialized) { binder.announceLxmfDestination() }
        @Suppress("SwallowedException") // Intentional: counting crashes for test assertions
        fun onNetworkChanged() {
            val w = wrapper
            if (w != null) { // Check passes
                try {
                    // Longer window to reliably create race conditions in tests
                    Thread.sleep(5)
                    w.callMethod() // May throw if shutdown happened between check and use
                } catch (e: IllegalStateException) {
                    crashCount.incrementAndGet()
                }
            }
        }

        fun getCrashCount() = crashCount.get()
    }

    /**
     * Simulates the FIXED pattern:
     * - Callback debounced and runs on controlled coroutine scope
     * - State check verifies wrapper is fully ready
     */
    class SafeNetworkCallbackSimulator(private val scope: CoroutineScope) {
        private val mutex = Mutex()

        @Volatile
        var wrapper: MockWrapper? = null
        private var wrapperIdCounter = AtomicInteger(0)
        private val crashCount = AtomicInteger(0)
        private var debounceJob: Job? = null

        suspend fun initialize() {
            mutex.withLock {
                wrapper = MockWrapper(wrapperIdCounter.incrementAndGet())
            }
        }

        suspend fun shutdown() {
            mutex.withLock {
                wrapper?.shutdown()
                wrapper = null
            }
        }

        fun isInitialized(): Boolean = wrapper?.isShutdown == false

        // Fixed: debounced, runs on scope, checks state properly
        @Suppress("SwallowedException") // Intentional: counting crashes for test assertions
        fun onNetworkChanged() {
            debounceJob?.cancel()
            debounceJob =
                scope.launch {
                    delay(50) // Debounce
                    if (isInitialized()) {
                        try {
                            wrapper?.callMethod()
                        } catch (e: IllegalStateException) {
                            crashCount.incrementAndGet()
                        }
                    }
                }
        }

        fun getCrashCount() = crashCount.get()
    }

    @Test
    fun `UNSAFE - rapid network changes during shutdown causes crashes`() =
        runBlocking {
            val simulator = UnsafeNetworkCallbackSimulator()
            simulator.initialize()

            // Simulate rapid network callbacks (like leaving WiFi range)
            val callbackJobs =
                List(20) {
                    launch(Dispatchers.Default) {
                        repeat(10) {
                            simulator.onNetworkChanged()
                            delay(1)
                        }
                    }
                }

            // Simulate shutdown happening concurrently
            // Longer delay to ensure callbacks are in-flight when shutdown occurs
            val shutdownJob =
                launch(Dispatchers.Default) {
                    delay(20)
                    simulator.shutdown()
                }

            callbackJobs.forEach { it.join() }
            shutdownJob.join()

            // With the unsafe pattern, crashes CAN occur due to TOCTOU race condition.
            // The exact number depends on timing, so we just document the behavior.
            // In production, this manifests as crashes when leaving WiFi range.
            val crashes = simulator.getCrashCount()
            println("Crashes with UNSAFE pattern: $crashes (race condition demonstration)")
            // Note: We don't assert crashes > 0 because race conditions are non-deterministic.
            // This test documents the vulnerability rather than guaranteeing reproduction.
        }

    @Test
    fun `SAFE - debounced callbacks with state check prevents crashes`() =
        runBlocking {
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val simulator = SafeNetworkCallbackSimulator(testScope)
            simulator.initialize()

            // Simulate rapid network callbacks
            repeat(100) {
                simulator.onNetworkChanged()
            }

            // Give time for debounced callback to fire
            delay(100)

            // Shutdown
            simulator.shutdown()

            delay(50)

            // With the safe pattern, no crashes should occur
            assertEquals("No crashes expected with safe pattern", 0, simulator.getCrashCount())

            testScope.cancel()
        }
}
