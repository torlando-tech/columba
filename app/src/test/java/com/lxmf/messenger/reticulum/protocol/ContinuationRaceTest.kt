@file:Suppress("InjectDispatcher")
package com.lxmf.messenger.reticulum.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Tests for continuation race condition patterns used in ServiceReticulumProtocol.
 *
 * The pattern under test:
 * 1. suspendCancellableCoroutine stores continuation in a field
 * 2. A callback (from different thread) reads the field and resumes it
 *
 * Race condition: callback fires before continuation is stored, or
 * callback reads null while another thread is storing.
 *
 * This test class extracts the pattern and tests it in isolation.
 */
class ContinuationRaceTest {
    /**
     * Simulates the UNSAFE pattern currently in ServiceReticulumProtocol.
     * This class has the race condition.
     */
    class UnsafeContinuationHolder {
        // No synchronization - this is the buggy pattern
        private var continuation: Continuation<Unit>? = null
        private var callbackFired = false

        suspend fun waitForCallback() {
            suspendCancellableCoroutine<Unit> { cont ->
                continuation = cont

                cont.invokeOnCancellation {
                    continuation = null
                }
            }
        }

        fun fireCallback() {
            callbackFired = true
            continuation?.resume(Unit)
            continuation = null
        }

        fun fireCallbackWithDelay() {
            // Simulates callback arriving before continuation is set
            callbackFired = true
            val cont = continuation
            cont?.resume(Unit)
            if (cont != null) {
                continuation = null
            }
        }
    }

    /**
     * Simulates the SAFE pattern that should be used.
     * Uses synchronized blocks to protect continuation access.
     */
    class SafeContinuationHolder {
        private val lock = Any()
        private var continuation: Continuation<Unit>? = null
        private var callbackFiredBeforeWait = false

        suspend fun waitForCallback() {
            suspendCancellableCoroutine<Unit> { cont ->
                synchronized(lock) {
                    if (callbackFiredBeforeWait) {
                        // Callback already fired before we started waiting
                        callbackFiredBeforeWait = false
                        cont.resume(Unit)
                    } else {
                        continuation = cont
                    }
                }

                cont.invokeOnCancellation {
                    synchronized(lock) {
                        continuation = null
                    }
                }
            }
        }

        fun fireCallback() {
            synchronized(lock) {
                val cont = continuation
                if (cont != null) {
                    cont.resume(Unit)
                    continuation = null
                } else {
                    // Callback fired before waitForCallback was called
                    callbackFiredBeforeWait = true
                }
            }
        }
    }

    /**
     * Test: Unsafe pattern can lose wake-ups when callback fires before
     * continuation is set.
     */
    @Test
    fun `unsafe pattern can lose wakeup when callback fires first`() =
        runBlocking {
            var lostWakeups = 0
            val iterations = 100

            repeat(iterations) {
                val holder = UnsafeContinuationHolder()

                val result =
                    withTimeoutOrNull(100) {
                        // Fire callback immediately in another coroutine
                        launch(Dispatchers.Default) {
                            // No delay - fire immediately
                            holder.fireCallback()
                        }

                        // Small delay to let callback potentially fire first
                        delay(1)

                        // Now try to wait
                        holder.waitForCallback()
                        true
                    }

                if (result == null) {
                    // Timeout = lost wakeup
                    lostWakeups++
                }
            }

            // Note: This test may be flaky with the unsafe implementation
            // The point is to demonstrate the race exists
            // With proper synchronization, lostWakeups should be 0
            println("Lost wakeups (unsafe): $lostWakeups/$iterations")
        }

    /**
     * Test: Safe pattern never loses wake-ups even when callback fires first.
     */
    @Test
    fun `safe pattern never loses wakeup`() =
        runBlocking {
            var lostWakeups = 0
            val iterations = 100

            repeat(iterations) {
                val holder = SafeContinuationHolder()

                val result =
                    withTimeoutOrNull(100) {
                        // Fire callback immediately in another coroutine
                        launch(Dispatchers.Default) {
                            holder.fireCallback()
                        }

                        // Small delay to let callback potentially fire first
                        delay(1)

                        // Now try to wait - should complete immediately if callback already fired
                        holder.waitForCallback()
                        true
                    }

                if (result == null) {
                    lostWakeups++
                }
            }

            assertEquals(
                "Safe pattern should never lose wakeups",
                0,
                lostWakeups,
            )
        }

    /**
     * Test: Safe pattern handles rapid fire-wait cycles.
     */
    @Test
    fun `safe pattern handles rapid cycles`() =
        runBlocking {
            val holder = SafeContinuationHolder()
            val completedCycles = AtomicInteger(0)

            val iterations = 50

            repeat(iterations) {
                val jobs =
                    listOf(
                        async(Dispatchers.Default) {
                            holder.waitForCallback()
                            completedCycles.incrementAndGet()
                        },
                        async(Dispatchers.Default) {
                            delay(5) // Small delay
                            holder.fireCallback()
                        },
                    )

                withTimeoutOrNull(200) {
                    jobs.forEach { it.await() }
                }
            }

            assertEquals(
                "All cycles should complete",
                iterations,
                completedCycles.get(),
            )
        }

    /**
     * Test: Safe pattern handles concurrent access from multiple threads.
     */
    @Test
    fun `safe pattern is thread safe under concurrent access`() =
        runBlocking {
            val successCount = AtomicInteger(0)
            val iterations = 20

            repeat(iterations) {
                val holder = SafeContinuationHolder()

                val waiterJob =
                    async(Dispatchers.Default) {
                        holder.waitForCallback()
                        successCount.incrementAndGet()
                    }

                val firerJob =
                    async(Dispatchers.IO) {
                        delay(10)
                        holder.fireCallback()
                    }

                withTimeoutOrNull(200) {
                    waiterJob.await()
                    firerJob.await()
                }
            }

            assertEquals(
                "All waits should succeed",
                iterations,
                successCount.get(),
            )
        }

    /**
     * Test: Double resume doesn't cause crash with safe pattern.
     */
    @Test
    fun `safe pattern handles double callback safely`() =
        runBlocking {
            val holder = SafeContinuationHolder()
            var crashed = false

            try {
                val waiterJob =
                    async(Dispatchers.Default) {
                        holder.waitForCallback()
                    }

                // Fire callback twice
                launch(Dispatchers.IO) {
                    delay(5)
                    holder.fireCallback()
                }

                launch(Dispatchers.IO) {
                    delay(10)
                    holder.fireCallback() // Second fire should be no-op
                }

                withTimeoutOrNull(200) {
                    waiterJob.await()
                }
            } catch (e: IllegalStateException) {
                // Double resume would throw IllegalStateException
                println("Crash from double resume: ${e.message}")
                crashed = true
            }

            assertTrue("Should not crash on double callback", !crashed)
        }
}
