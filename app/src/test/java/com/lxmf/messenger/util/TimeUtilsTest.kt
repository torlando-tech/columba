package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for TimeUtils functions.
 */
class TimeUtilsTest {
    companion object {
        private const val MINUTE_MILLIS = 60 * 1000L
        private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
        private const val DAY_MILLIS = 24 * HOUR_MILLIS
    }

    // ========== formatTimeSince Tests ==========

    @Test
    fun `formatTimeSince returns just now for less than 1 minute`() {
        val now = System.currentTimeMillis()

        assertEquals("just now", formatTimeSince(now, now))
        assertEquals("just now", formatTimeSince(now - 30_000, now)) // 30 seconds ago
        assertEquals("just now", formatTimeSince(now - 59_000, now)) // 59 seconds ago
    }

    @Test
    fun `formatTimeSince returns 1 minute ago for exactly 1 minute`() {
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - MINUTE_MILLIS

        assertEquals("1 minute ago", formatTimeSince(oneMinuteAgo, now))
    }

    @Test
    fun `formatTimeSince returns minutes ago for 2-59 minutes`() {
        val now = System.currentTimeMillis()

        assertEquals("2 minutes ago", formatTimeSince(now - 2 * MINUTE_MILLIS, now))
        assertEquals("30 minutes ago", formatTimeSince(now - 30 * MINUTE_MILLIS, now))
        assertEquals("59 minutes ago", formatTimeSince(now - 59 * MINUTE_MILLIS, now))
    }

    @Test
    fun `formatTimeSince returns 1 hour ago for exactly 1 hour`() {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - HOUR_MILLIS

        assertEquals("1 hour ago", formatTimeSince(oneHourAgo, now))
    }

    @Test
    fun `formatTimeSince returns hours ago for 2-23 hours`() {
        val now = System.currentTimeMillis()

        assertEquals("2 hours ago", formatTimeSince(now - 2 * HOUR_MILLIS, now))
        assertEquals("12 hours ago", formatTimeSince(now - 12 * HOUR_MILLIS, now))
        assertEquals("23 hours ago", formatTimeSince(now - 23 * HOUR_MILLIS, now))
    }

    @Test
    fun `formatTimeSince returns 1 day ago for exactly 1 day`() {
        val now = System.currentTimeMillis()
        val oneDayAgo = now - DAY_MILLIS

        assertEquals("1 day ago", formatTimeSince(oneDayAgo, now))
    }

    @Test
    fun `formatTimeSince returns days ago for multiple days`() {
        val now = System.currentTimeMillis()

        assertEquals("2 days ago", formatTimeSince(now - 2 * DAY_MILLIS, now))
        assertEquals("7 days ago", formatTimeSince(now - 7 * DAY_MILLIS, now))
        assertEquals("30 days ago", formatTimeSince(now - 30 * DAY_MILLIS, now))
    }

    @Test
    fun `formatTimeSince handles future timestamps gracefully`() {
        val now = System.currentTimeMillis()
        val future = now + HOUR_MILLIS

        // Future timestamps result in negative differences, which become "just now"
        assertEquals("just now", formatTimeSince(future, now))
    }

    // ========== Convenience overload tests ==========

    @Test
    fun `formatTimeSince without now parameter uses current time`() {
        val recentPast = System.currentTimeMillis() - 30_000 // 30 seconds ago
        val result = formatTimeSince(recentPast)

        assertEquals("just now", result)
    }
}
