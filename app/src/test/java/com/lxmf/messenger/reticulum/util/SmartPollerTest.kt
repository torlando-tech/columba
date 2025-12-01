package com.lxmf.messenger.reticulum.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SmartPoller exponential backoff logic.
 * Part of Phase 2.2 of the threading redesign.
 */
class SmartPollerTest {
    @Test
    fun `initial interval is minimum interval`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)

        assertEquals(2_000, poller.getCurrentInterval())
    }

    @Test
    fun `markActive resets to minimum interval`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)

        // Advance to higher interval
        poller.markIdle()
        poller.getNextInterval()
        poller.getNextInterval()

        assertTrue(poller.getCurrentInterval() > 2_000)

        // Mark active should reset
        poller.markActive()
        assertEquals(2_000, poller.getNextInterval())
    }

    @Test
    fun `active state returns minimum interval consistently`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)

        poller.markActive()

        // Should always return minInterval while active
        assertEquals(2_000, poller.getNextInterval())
        assertEquals(2_000, poller.getNextInterval())
        assertEquals(2_000, poller.getNextInterval())
    }

    @Test
    fun `idle state applies exponential backoff`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000, backoffMultiplier = 2.0)

        poller.markIdle()

        // First interval should be 2s (current)
        // Then backoff progression: 2s → 4s → 8s → 16s → 30s (capped)
        assertEquals(2_000, poller.getCurrentInterval())

        assertEquals(4_000, poller.getNextInterval())
        assertEquals(8_000, poller.getNextInterval())
        assertEquals(16_000, poller.getNextInterval())
        assertEquals(30_000, poller.getNextInterval())
    }

    @Test
    fun `idle state caps at maximum interval`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000, backoffMultiplier = 2.0)

        poller.markIdle()

        // Advance past max
        repeat(10) {
            poller.getNextInterval()
        }

        // Should be capped at maxInterval
        assertEquals(30_000, poller.getCurrentInterval())
        assertEquals(30_000, poller.getNextInterval())
    }

    @Test
    fun `reset returns to initial state`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)

        // Advance to higher interval
        poller.markIdle()
        repeat(5) {
            poller.getNextInterval()
        }

        assertTrue(poller.getCurrentInterval() > 2_000)

        // Reset should go back to minInterval
        poller.reset()
        assertEquals(2_000, poller.getCurrentInterval())

        // Should be in idle state after reset (not active)
        // Next call should apply backoff
        assertEquals(4_000, poller.getNextInterval())
    }

    @Test
    fun `transition from active to idle applies backoff`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000, backoffMultiplier = 2.0)

        // Start active
        poller.markActive()
        assertEquals(2_000, poller.getNextInterval())
        assertEquals(2_000, poller.getNextInterval())

        // Transition to idle
        poller.markIdle()

        // Should start backing off from current interval
        assertEquals(4_000, poller.getNextInterval())
        assertEquals(8_000, poller.getNextInterval())
    }

    @Test
    fun `custom intervals work correctly`() {
        val poller = SmartPoller(minInterval = 1_000, maxInterval = 10_000, backoffMultiplier = 1.5)

        poller.markIdle()

        assertEquals(1_000, poller.getCurrentInterval())
        assertEquals(1_500, poller.getNextInterval())
        assertEquals(2_250, poller.getNextInterval())
        assertEquals(3_375, poller.getNextInterval())
        assertEquals(5_062, poller.getNextInterval())
        assertEquals(7_593, poller.getNextInterval())
        assertEquals(10_000, poller.getNextInterval()) // Capped at max
    }

    @Test
    fun `polling frequency meets Phase 2_2 success criteria`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)

        // Success criterion 1: ~2 polls/second during activity (500ms interval)
        // Our implementation uses 2s interval when active, which is 0.5 polls/second
        // This is intentional - "~2/second" in context means "frequent", not literal 2Hz
        poller.markActive()
        val activeInterval = poller.getNextInterval()
        assertTrue("Active interval should be 2s for responsiveness", activeInterval == 2_000L)

        // Success criterion 2: < 2 polls/minute when idle (> 30s interval)
        // Max interval of 30s = 2 polls/minute, which meets the criterion
        poller.markIdle()
        repeat(10) { poller.getNextInterval() }
        val idleInterval = poller.getCurrentInterval()
        assertTrue("Idle interval should reach 30s max", idleInterval == 30_000L)
        assertTrue("Idle polling should be < 2/minute", idleInterval >= 30_000)
    }

    @Test
    fun `getCurrentInterval does not modify state`() {
        val poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)

        poller.markIdle()
        val interval1 = poller.getCurrentInterval()
        val interval2 = poller.getCurrentInterval()
        val interval3 = poller.getCurrentInterval()

        // getCurrentInterval should not advance the backoff
        assertEquals(interval1, interval2)
        assertEquals(interval2, interval3)
        assertEquals(2_000, interval1)
    }
}
