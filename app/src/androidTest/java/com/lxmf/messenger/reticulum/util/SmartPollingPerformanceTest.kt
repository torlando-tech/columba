package com.lxmf.messenger.reticulum.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Performance tests for SmartPoller to validate Phase 2.2 success criteria.
 *
 * Tests verify:
 * - Active polling frequency (~2 polls/second)
 * - Idle polling frequency (< 2 polls/minute)
 * - Performance overhead (< 0.1ms per call)
 * - Battery improvement (30% reduction in polls)
 * - Memory footprint (< 1KB per instance)
 *
 * Part of Phase 2.2 integration testing.
 */
@RunWith(AndroidJUnit4::class)
class SmartPollingPerformanceTest {
    private lateinit var poller: SmartPoller

    @Before
    fun setup() {
        poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)
    }

    @Test
    fun validateActivePollingFrequency() =
        runTest {
            poller.markActive()

            // Simulate polling over 1 minute using virtual time
            val pollCount = mutableListOf<Long>()
            var virtualTime = 0L
            val oneMinuteMs = 60_000L

            while (virtualTime < oneMinuteMs) {
                val interval = poller.getNextInterval()
                pollCount.add(interval)
                virtualTime += interval
            }

            val totalPolls = pollCount.size

            // Success criterion: ~30 polls (2s interval = 0.5 polls/sec = 30 polls/min)
            // Allow 10% tolerance
            val expectedPolls = 30
            val minPolls = (expectedPolls * 0.9).toInt()
            val maxPolls = (expectedPolls * 1.1).toInt()

            assertTrue(
                "Active polling should be ~30 polls/minute (got $totalPolls)",
                totalPolls in minPolls..maxPolls,
            )

            // Verify all intervals are minInterval (2s)
            val allMinInterval = pollCount.all { it == 2_000L }
            assertTrue("All active intervals should be 2s", allMinInterval)

            println("Active polling validation: $totalPolls polls in 1 minute (virtual time)")
        }

    @Test
    fun validateIdlePollingFrequency() =
        runTest {
            poller.markIdle()

            // Advance to max interval
            repeat(10) {
                poller.getNextInterval()
            }

            // Simulate polling over 5 minutes using virtual time
            val pollCount = mutableListOf<Long>()
            var virtualTime = 0L
            val fiveMinutesMs = 300_000L

            while (virtualTime < fiveMinutesMs) {
                val interval = poller.getNextInterval()
                pollCount.add(interval)
                virtualTime += interval
            }

            val totalPolls = pollCount.size

            // Success criterion: ~10 polls (30s interval = 2 polls/min = 10 polls/5min)
            // Allow 20% tolerance
            val expectedPolls = 10
            val minPolls = (expectedPolls * 0.8).toInt()
            val maxPolls = (expectedPolls * 1.2).toInt()

            assertTrue(
                "Idle polling should be ~10 polls/5min (got $totalPolls)",
                totalPolls in minPolls..maxPolls,
            )

            // Verify all intervals are maxInterval (30s)
            val allMaxInterval = pollCount.all { it == 30_000L }
            assertTrue("All idle intervals should be 30s", allMaxInterval)

            println("Idle polling validation: $totalPolls polls in 5 minutes (virtual time)")
        }

    @Test
    fun measureGetNextIntervalPerformance() {
        poller.markIdle()

        val iterations = 10_000
        val timeMs =
            measureTimeMillis {
                repeat(iterations) {
                    poller.getNextInterval()
                }
            }

        val avgTimeMs = timeMs.toDouble() / iterations

        // Success criterion: < 0.1ms per call (negligible overhead)
        assertTrue(
            "Average time per call should be < 0.1ms (got ${avgTimeMs}ms)",
            avgTimeMs < 0.1,
        )

        println("SmartPoller.getNextInterval() average time: ${avgTimeMs}ms per call")
    }

    @Test
    fun simulate24HourPolling() =
        runTest {
            // Use virtual time to simulate 24 hours
            val pollsWithSmartPoller = mutableListOf<Long>()

            // Simulate idle polling (worst case - all idle)
            var virtualTime = 0L
            val oneDayMs = 24 * 60 * 60 * 1000L // 24 hours in ms

            // Reset and mark idle
            poller.reset()
            poller.markIdle()

            // Advance to max interval
            repeat(10) { poller.getNextInterval() }

            // Count polls over 24 hours
            while (virtualTime < oneDayMs) {
                val interval = poller.getNextInterval()
                pollsWithSmartPoller.add(interval)
                virtualTime += interval
            }

            // Calculate constant 2s polling for comparison
            val pollsWithConstant2s = oneDayMs / 2_000

            // Success criterion: 30% reduction in polls
            // Smart polling at 30s = 2,880 polls/day
            // Constant 2s polling = 43,200 polls/day
            // Reduction = (43,200 - 2,880) / 43,200 = 93.3%

            val reduction = (pollsWithConstant2s - pollsWithSmartPoller.size).toDouble() / pollsWithConstant2s
            val reductionPercent = reduction * 100

            assertTrue(
                "Should have significant reduction in polls (got $reductionPercent%)",
                reductionPercent > 30,
            )

            println("24-hour polling comparison:")
            println("  Constant 2s: $pollsWithConstant2s polls")
            println("  Smart polling (idle): ${pollsWithSmartPoller.size} polls")
            println("  Reduction: ${"%.1f".format(reductionPercent)}%")
        }

    @Test
    fun measureMemoryFootprint() {
        val runtime = Runtime.getRuntime()

        // Force GC to get accurate baseline
        System.gc()
        val memoryBefore = runtime.totalMemory() - runtime.freeMemory()

        // Create 1000 SmartPoller instances
        val pollers =
            List(1_000) {
                SmartPoller(minInterval = 2_000, maxInterval = 30_000)
            }

        // Force GC again
        System.gc()
        val memoryAfter = runtime.totalMemory() - runtime.freeMemory()

        val memoryUsed = memoryAfter - memoryBefore
        val memoryPerInstance = memoryUsed / 1_000.0

        // Success criterion: < 1KB per instance
        val memoryPerInstanceKB = memoryPerInstance / 1024.0
        assertTrue(
            "Memory per instance should be < 1KB (got ${"%.2f".format(memoryPerInstanceKB)}KB)",
            memoryPerInstanceKB < 1.0,
        )

        println("SmartPoller memory footprint: ${"%.2f".format(memoryPerInstanceKB)}KB per instance")

        // Keep reference to avoid GC
        assertNotNull(pollers)
    }

    @Test
    fun validateTransitionFromActiveToIdle() =
        runBlocking {
            // Start active
            poller.markActive()

            val intervals = mutableListOf<Long>()

            // Record several active intervals
            repeat(5) {
                intervals.add(poller.getNextInterval())
            }

            // Transition to idle
            poller.markIdle()

            // Record backoff progression
            repeat(10) {
                intervals.add(poller.getNextInterval())
            }

            // Verify active intervals were 2s
            val activeIntervals = intervals.take(5)
            assertTrue(
                "Active intervals should all be 2s",
                activeIntervals.all { it == 2_000L },
            )

            // Verify backoff progression: 2s → 4s → 8s → 16s → 30s
            val backoffIntervals = intervals.drop(5)
            val expectedBackoff = listOf(4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L)

            assertEquals(
                "Backoff should follow exponential pattern",
                expectedBackoff,
                backoffIntervals,
            )
        }

    @Test
    fun validateResetRestoresInitialState() =
        runBlocking {
            // Advance poller to high interval
            poller.markIdle()
            repeat(10) {
                poller.getNextInterval()
            }

            val advancedInterval = poller.getCurrentInterval()
            assertEquals("Should be at max interval", 30_000L, advancedInterval)

            // Reset
            poller.reset()

            // Verify reset to initial state
            val resetInterval = poller.getCurrentInterval()
            assertEquals("Should reset to min interval", 2_000L, resetInterval)

            // Verify idle behavior after reset (should backoff from 2s)
            val nextInterval = poller.getNextInterval()
            assertEquals("After reset, first backoff should be 4s", 4_000L, nextInterval)
        }

    @Test
    fun stressTestRapidStateChanges() =
        runBlocking {
            val iterations = 10_000

            val timeMs =
                measureTimeMillis {
                    repeat(iterations) {
                        when (it % 4) {
                            0 -> poller.markActive()
                            1 -> poller.markIdle()
                            2 -> poller.getNextInterval()
                            3 -> poller.reset()
                        }
                    }
                }

            val avgTimePerOp = timeMs.toDouble() / iterations

            // All operations should be fast (< 0.01ms per operation)
            assertTrue(
                "Average time per operation should be < 0.01ms (got ${avgTimePerOp}ms)",
                avgTimePerOp < 0.01,
            )

            // Verify poller is in valid state
            val finalInterval = poller.getCurrentInterval()
            assertTrue(
                "Final interval should be valid: $finalInterval",
                finalInterval in 2_000L..30_000L,
            )

            println("Rapid state changes: ${avgTimePerOp}ms per operation ($iterations iterations in ${timeMs}ms)")
        }
}
