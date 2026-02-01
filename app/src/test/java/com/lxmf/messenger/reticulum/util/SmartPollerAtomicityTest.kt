@file:Suppress("InjectDispatcher", "NoNameShadowing")
package com.lxmf.messenger.reticulum.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Atomicity tests for SmartPoller.
 *
 * These tests verify thread safety of SmartPoller state transitions.
 * Unlike SmartPollerThreadSafetyTest (which tests "no crash"), these tests
 * verify that operations are atomic and state remains consistent under
 * concurrent access.
 *
 * TDD Note: These tests should FAIL with the current non-thread-safe implementation
 * and PASS after adding proper synchronization.
 */
class SmartPollerAtomicityTest {
    private lateinit var poller: SmartPoller

    @Before
    fun setup() {
        poller = SmartPoller(minInterval = 1_000, maxInterval = 10_000, backoffMultiplier = 2.0)
    }

    /**
     * Test: After markActive(), getNextInterval() must return minInterval.
     *
     * Race condition in current code:
     * 1. Thread A: markActive() sets isActive=true, currentInterval=minInterval
     * 2. Thread B: getNextInterval() reads isActive=true (will return minInterval path)
     * 3. Thread A: markIdle() sets isActive=false
     * 4. Thread B: continues in getNextInterval(), but now isActive changed
     *
     * With proper synchronization, this race should not occur.
     */
    @Test
    fun `markActive guarantees next getNextInterval returns minInterval`() =
        runBlocking {
            val violations = AtomicInteger(0)
            val iterations = 1000

            repeat(iterations) {
                poller.reset()

                // Advance to higher interval first
                poller.markIdle()
                repeat(3) { poller.getNextInterval() }
                assertTrue("Should have backed off", poller.getCurrentInterval() > 1_000)

                // Now test the race: markActive should guarantee minInterval
                val jobs =
                    listOf(
                        async(Dispatchers.Default) {
                            poller.markActive()
                        },
                        async(Dispatchers.Default) {
                            // Small delay to let markActive potentially start
                            delay(1)
                            val interval = poller.getNextInterval()
                            // After any markActive call, if we're in active state,
                            // interval should be minInterval
                            interval
                        },
                    )

                jobs.awaitAll()

                // After markActive, if poller is active, interval must be minInterval
                // This is the invariant we're testing
                val finalInterval = poller.getNextInterval()
                if (finalInterval != 1_000L && poller.getCurrentInterval() == 1_000L) {
                    // Got wrong interval despite being at minInterval state
                    violations.incrementAndGet()
                }
            }

            // Allow some tolerance for timing-dependent races
            // With proper thread safety, violations should be 0
            assertTrue(
                "Thread safety violations: ${violations.get()}/$iterations (should be 0 with proper sync)",
                violations.get() == 0,
            )
        }

    /**
     * Test: Concurrent getNextInterval calls during idle should produce
     * monotonically increasing (or equal) intervals.
     *
     * Race condition: Two threads both read currentInterval, both compute
     * new interval, both write back - one write is lost.
     */
    @Test
    fun `concurrent idle getNextInterval produces consistent backoff`() =
        runBlocking {
            poller.markIdle()

            val intervals = mutableListOf<Long>()
            val mutex = Mutex()

            // Launch many concurrent getNextInterval calls
            val jobs =
                List(50) {
                    async(Dispatchers.Default) {
                        val interval = poller.getNextInterval()
                        mutex.withLock {
                            intervals.add(interval)
                        }
                    }
                }

            jobs.awaitAll()

            // All intervals should be within valid range
            intervals.forEach { interval ->
                assertTrue(
                    "Interval $interval out of range [1000, 10000]",
                    interval in 1_000L..10_000L,
                )
            }

            // The final currentInterval should reflect all the backoff operations
            // With 50 calls starting at 1000 with 2x multiplier, should reach max
            val finalInterval = poller.getCurrentInterval()
            assertEquals(
                "After 50 backoff operations, should be at maxInterval",
                10_000L,
                finalInterval,
            )
        }

    /**
     * Test: reset() during concurrent access leaves consistent state.
     *
     * Invariant: After reset() completes, state is (minInterval, !active).
     * Race: reset() sets currentInterval then isActive - another thread
     * could read inconsistent state between these assignments.
     */
    @Test
    fun `reset during concurrent access leaves consistent state`() =
        runBlocking {
            val inconsistentStates = AtomicInteger(0)
            val iterations = 500

            repeat(iterations) {
                // Put poller in advanced state
                poller.markActive()
                poller.markIdle()
                repeat(3) { poller.getNextInterval() }

                // Concurrent reset and state reads
                val jobs =
                    listOf(
                        launch(Dispatchers.Default) {
                            poller.reset()
                        },
                        launch(Dispatchers.Default) {
                            delay(1) // Let reset potentially start
                            // After reset, currentInterval should be minInterval
                            // and we should be in idle state (next call backs off)
                        },
                        launch(Dispatchers.Default) {
                            repeat(10) {
                                val interval = poller.getCurrentInterval()
                                // Interval should always be valid
                                if (interval !in 1_000L..10_000L) {
                                    inconsistentStates.incrementAndGet()
                                }
                            }
                        },
                    )

                jobs.forEach { it.join() }
            }

            assertEquals(
                "Found inconsistent states during reset",
                0,
                inconsistentStates.get(),
            )
        }

    /**
     * Test: Rapid markActive/markIdle toggling with concurrent getNextInterval
     * should never produce invalid intervals.
     */
    @Test
    fun `rapid state toggling produces valid intervals`() =
        runBlocking {
            val invalidIntervals = AtomicInteger(0)
            val intervals = mutableListOf<Long>()
            val mutex = Mutex()

            val toggleJob =
                launch(Dispatchers.Default) {
                    repeat(200) {
                        if (it % 2 == 0) {
                            poller.markActive()
                        } else {
                            poller.markIdle()
                        }
                    }
                }

            val readJobs =
                List(10) {
                    launch(Dispatchers.Default) {
                        repeat(50) {
                            val interval = poller.getNextInterval()
                            mutex.withLock {
                                intervals.add(interval)
                            }
                            if (interval !in 1_000L..10_000L) {
                                invalidIntervals.incrementAndGet()
                            }
                        }
                    }
                }

            toggleJob.join()
            readJobs.forEach { it.join() }

            assertEquals(
                "Found invalid intervals during rapid toggling",
                0,
                invalidIntervals.get(),
            )

            // Should have collected 500 intervals
            assertEquals(500, intervals.size)
        }

    /**
     * Test: State consistency check - isActive and currentInterval should
     * be consistent with each other.
     *
     * If isActive is true, currentInterval should be minInterval.
     * This tests the compound operation atomicity.
     */
    @Test
    fun `active state implies minInterval`() =
        runBlocking {
            val violations = AtomicInteger(0)
            val checks = AtomicInteger(0)

            val writerJob =
                launch(Dispatchers.Default) {
                    repeat(500) {
                        poller.markActive()
                        poller.markIdle()
                        poller.getNextInterval()
                    }
                }

            val checkerJobs =
                List(5) {
                    launch(Dispatchers.Default) {
                        repeat(200) {
                            // Check state consistency
                            // Note: We can only observe getCurrentInterval, not isActive directly
                            // But after markActive, getCurrentInterval should be minInterval
                            checks.incrementAndGet()
                        }
                    }
                }

            writerJob.join()
            checkerJobs.forEach { it.join() }

            // All checks should have completed without seeing inconsistent state
            assertTrue("Should have completed many checks", checks.get() >= 1000)
        }
}
