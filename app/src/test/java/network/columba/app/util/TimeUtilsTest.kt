package network.columba.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for TimeUtils functions.
 *
 * Runs under Robolectric so the localized relative-time resources can be
 * resolved; assertions compare against the resource ids/plurals rather than
 * hardcoded English text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimeUtilsTest {
    private val context: Context by lazy { ApplicationProvider.getApplicationContext() }

    private fun minutesAgo(minutes: Int): String =
        context.resources.getQuantityString(R.plurals.time_minutes_ago, minutes, minutes)

    private fun hoursAgo(hours: Int): String =
        context.resources.getQuantityString(R.plurals.time_hours_ago, hours, hours)

    private fun daysAgo(days: Int): String =
        context.resources.getQuantityString(R.plurals.time_days_ago, days, days)

    private fun weeksAgo(weeks: Int): String =
        context.resources.getQuantityString(R.plurals.time_weeks_ago, weeks, weeks)

    private val justNow: String get() = context.getString(R.string.time_since_just_now)

    // ========== formatTimeSince Tests ==========

    @Test
    fun `formatTimeSince returns just now for less than 1 minute`() {
        val now = System.currentTimeMillis()

        assertEquals(justNow, formatTimeSince(context, now, now))
        assertEquals(justNow, formatTimeSince(context, now - 30_000, now)) // 30 seconds ago
        assertEquals(justNow, formatTimeSince(context, now - 59_000, now)) // 59 seconds ago
    }

    @Test
    fun `formatTimeSince returns 1 minute ago for exactly 1 minute`() {
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - MINUTE_MILLIS

        assertEquals(minutesAgo(1), formatTimeSince(context, oneMinuteAgo, now))
    }

    @Test
    fun `formatTimeSince returns minutes ago for 2-59 minutes`() {
        val now = System.currentTimeMillis()

        assertEquals(minutesAgo(2), formatTimeSince(context, now - 2 * MINUTE_MILLIS, now))
        assertEquals(minutesAgo(30), formatTimeSince(context, now - 30 * MINUTE_MILLIS, now))
        assertEquals(minutesAgo(59), formatTimeSince(context, now - 59 * MINUTE_MILLIS, now))
    }

    @Test
    fun `formatTimeSince returns 1 hour ago for exactly 1 hour`() {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - HOUR_MILLIS

        assertEquals(hoursAgo(1), formatTimeSince(context, oneHourAgo, now))
    }

    @Test
    fun `formatTimeSince returns hours ago for 2-23 hours`() {
        val now = System.currentTimeMillis()

        assertEquals(hoursAgo(2), formatTimeSince(context, now - 2 * HOUR_MILLIS, now))
        assertEquals(hoursAgo(12), formatTimeSince(context, now - 12 * HOUR_MILLIS, now))
        assertEquals(hoursAgo(23), formatTimeSince(context, now - 23 * HOUR_MILLIS, now))
    }

    @Test
    fun `formatTimeSince returns 1 day ago for exactly 1 day`() {
        val now = System.currentTimeMillis()
        val oneDayAgo = now - DAY_MILLIS

        assertEquals(daysAgo(1), formatTimeSince(context, oneDayAgo, now))
    }

    @Test
    fun `formatTimeSince returns days ago for multiple days`() {
        val now = System.currentTimeMillis()

        assertEquals(daysAgo(2), formatTimeSince(context, now - 2 * DAY_MILLIS, now))
        assertEquals(daysAgo(7), formatTimeSince(context, now - 7 * DAY_MILLIS, now))
        assertEquals(daysAgo(30), formatTimeSince(context, now - 30 * DAY_MILLIS, now))
    }

    @Test
    fun `formatTimeSince handles future timestamps gracefully`() {
        val now = System.currentTimeMillis()
        val future = now + HOUR_MILLIS

        // Future timestamps result in negative differences, which become "just now"
        assertEquals(justNow, formatTimeSince(context, future, now))
    }

    @Test
    fun `formatTimeSince without now parameter uses current time`() {
        val recentPast = System.currentTimeMillis() - 30_000 // 30 seconds ago
        val result = formatTimeSince(context, recentPast)

        assertEquals(justNow, result)
    }

    // ========== formatRelativeTime Tests ==========

    @Test
    fun `formatRelativeTime returns just now for less than 1 minute`() {
        val now = System.currentTimeMillis()

        assertEquals(
            context.getString(R.string.time_relative_just_now),
            formatRelativeTime(context, now),
        )
    }

    @Test
    fun `formatRelativeTime returns minutes hours days and weeks ago`() {
        val now = System.currentTimeMillis()

        assertEquals(minutesAgo(5), formatRelativeTime(context, now - 5 * MINUTE_MILLIS))
        assertEquals(hoursAgo(2), formatRelativeTime(context, now - 2 * HOUR_MILLIS))
        assertEquals(daysAgo(3), formatRelativeTime(context, now - 3 * DAY_MILLIS))
        assertEquals(weeksAgo(2), formatRelativeTime(context, now - 15 * DAY_MILLIS))
    }

    companion object {
        private const val MINUTE_MILLIS = 60 * 1000L
        private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
        private const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}
