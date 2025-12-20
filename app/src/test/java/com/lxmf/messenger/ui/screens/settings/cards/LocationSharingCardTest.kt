package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for LocationSharingCard utility functions.
 *
 * Tests the pure utility functions:
 * - formatTimeRemaining: formats remaining time until sharing ends
 * - getDurationDisplayText: converts duration enum name to display text
 * - getPrecisionRadiusDisplayText: converts radius meters to display text
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationSharingCardTest {

    // ========== formatTimeRemaining Tests ==========

    @Test
    fun `formatTimeRemaining returns until stopped for null endTime`() {
        val result = formatTimeRemaining(null)
        assertEquals("Until stopped", result)
    }

    @Test
    fun `formatTimeRemaining returns expiring for past times`() {
        val pastTime = System.currentTimeMillis() - 1000 // 1 second ago
        val result = formatTimeRemaining(pastTime)
        assertEquals("Expiring...", result)
    }

    @Test
    fun `formatTimeRemaining returns expiring for current time`() {
        val result = formatTimeRemaining(System.currentTimeMillis())
        assertEquals("Expiring...", result)
    }

    @Test
    fun `formatTimeRemaining returns minutes for short durations`() {
        val now = System.currentTimeMillis()
        val endTime = now + 5 * 60_000 // 5 minutes from now

        val result = formatTimeRemaining(endTime)

        assertTrue("Result should contain minutes: $result", result.contains("m remaining"))
        assertTrue("Result should show around 5 minutes: $result", result.contains("5m") || result.contains("4m"))
    }

    @Test
    fun `formatTimeRemaining returns minutes only for under 1 hour`() {
        val now = System.currentTimeMillis()
        val endTime = now + 30 * 60_000 // 30 minutes from now

        val result = formatTimeRemaining(endTime)

        assertTrue("Result should contain 'm remaining': $result", result.endsWith("m remaining"))
        // Should not contain hours
        assertTrue("Result should not contain hours: $result", !result.contains("h"))
    }

    @Test
    fun `formatTimeRemaining returns hours and minutes for longer durations`() {
        val now = System.currentTimeMillis()
        val endTime = now + 90 * 60_000 // 1 hour 30 minutes from now

        val result = formatTimeRemaining(endTime)

        assertTrue("Result should contain 'h' for hours: $result", result.contains("h"))
        assertTrue("Result should contain 'm' for minutes: $result", result.contains("m"))
        assertTrue("Result should contain 'remaining': $result", result.contains("remaining"))
    }

    @Test
    fun `formatTimeRemaining correctly formats 2 hours`() {
        val now = System.currentTimeMillis()
        val endTime = now + 2 * 60 * 60_000 // 2 hours from now

        val result = formatTimeRemaining(endTime)

        assertTrue("Result should show 2 hours: $result", result.contains("2h"))
        assertTrue("Result should show 0 minutes: $result", result.contains("0m"))
    }

    @Test
    fun `formatTimeRemaining correctly formats 4 hours 15 minutes`() {
        val now = System.currentTimeMillis()
        val endTime = now + (4 * 60 + 15) * 60_000 // 4 hours 15 minutes from now

        val result = formatTimeRemaining(endTime)

        assertTrue("Result should show 4 hours: $result", result.contains("4h"))
        assertTrue("Result should show 15 minutes: $result", result.contains("15m"))
    }

    // ========== getDurationDisplayText Tests ==========

    @Test
    fun `getDurationDisplayText returns correct text for FIFTEEN_MINUTES`() {
        val result = getDurationDisplayText("FIFTEEN_MINUTES")
        assertEquals("15 min", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for ONE_HOUR`() {
        val result = getDurationDisplayText("ONE_HOUR")
        assertEquals("1 hour", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for FOUR_HOURS`() {
        val result = getDurationDisplayText("FOUR_HOURS")
        assertEquals("4 hours", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for UNTIL_MIDNIGHT`() {
        val result = getDurationDisplayText("UNTIL_MIDNIGHT")
        assertEquals("Until midnight", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for INDEFINITE`() {
        val result = getDurationDisplayText("INDEFINITE")
        assertEquals("Until I stop", result)
    }

    @Test
    fun `getDurationDisplayText returns fallback for invalid duration`() {
        val result = getDurationDisplayText("INVALID_DURATION")
        assertEquals("1 hour", result)
    }

    @Test
    fun `getDurationDisplayText returns fallback for empty string`() {
        val result = getDurationDisplayText("")
        assertEquals("1 hour", result)
    }

    @Test
    fun `getDurationDisplayText returns fallback for lowercase name`() {
        // Enum valueOf is case-sensitive
        val result = getDurationDisplayText("one_hour")
        assertEquals("1 hour", result)
    }

    // ========== getPrecisionRadiusDisplayText Tests ==========

    @Test
    fun `getPrecisionRadiusDisplayText returns Precise for 0 meters`() {
        val result = getPrecisionRadiusDisplayText(0)
        assertEquals("Precise", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns Neighborhood for 1000 meters`() {
        val result = getPrecisionRadiusDisplayText(1000)
        assertEquals("Neighborhood (~1km)", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns City for 10000 meters`() {
        val result = getPrecisionRadiusDisplayText(10000)
        assertEquals("City (~10km)", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns Region for 100000 meters`() {
        val result = getPrecisionRadiusDisplayText(100000)
        assertEquals("Region (~100km)", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns km for custom large radius`() {
        val result = getPrecisionRadiusDisplayText(5000)
        assertEquals("5km", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns km for very large custom radius`() {
        val result = getPrecisionRadiusDisplayText(50000)
        assertEquals("50km", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns meters for small custom radius`() {
        val result = getPrecisionRadiusDisplayText(500)
        assertEquals("500m", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns meters for very small radius`() {
        val result = getPrecisionRadiusDisplayText(100)
        assertEquals("100m", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText handles edge case at 1000m boundary`() {
        // 999m should still show in meters
        val result999 = getPrecisionRadiusDisplayText(999)
        assertEquals("999m", result999)

        // 1001m should show in km (integer division: 1001/1000 = 1)
        val result1001 = getPrecisionRadiusDisplayText(1001)
        assertEquals("1km", result1001)
    }

    @Test
    fun `getPrecisionRadiusDisplayText handles minimum positive value`() {
        val result = getPrecisionRadiusDisplayText(1)
        assertEquals("1m", result)
    }
}
