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
 * Pins singular/plural forms of the relative-time resources (issue #1191).
 *
 * Every `*_ago` resource used by a screen helper is a `<plurals>` entry, so a
 * count of 1 renders "1 hour ago" rather than "1 hours ago". Assertions read
 * the resources under Robolectric instead of duplicating English literals where
 * possible; the literal checks document the exact rendered copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelativeTimePluralsTest {
    private val resources
        get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun quantity(id: Int, count: Int): String =
        resources.getQuantityString(id, count, count)

    @Test
    fun `interface relative time singularizes`() {
        assertEquals("1 hour ago", quantity(R.plurals.iface_hours_ago, 1))
        assertEquals("2 hours ago", quantity(R.plurals.iface_hours_ago, 2))
        assertEquals("1 day ago", quantity(R.plurals.iface_days_ago, 1))
        assertEquals("3 days ago", quantity(R.plurals.iface_days_ago, 3))
    }

    @Test
    fun `map relative time singularizes`() {
        assertEquals("1 hour ago", quantity(R.plurals.map_time_hours_ago, 1))
        assertEquals("2 hours ago", quantity(R.plurals.map_time_hours_ago, 2))
        assertEquals("1 day ago", quantity(R.plurals.map_time_days_ago, 1))
        assertEquals("5 days ago", quantity(R.plurals.map_time_days_ago, 5))
    }

    @Test
    fun `identity relative time singularizes`() {
        assertEquals("1 minute ago", quantity(R.plurals.identity_time_minutes_ago, 1))
        assertEquals("5 minutes ago", quantity(R.plurals.identity_time_minutes_ago, 5))
        assertEquals("1 hour ago", quantity(R.plurals.identity_time_hours_ago, 1))
        assertEquals("2 hours ago", quantity(R.plurals.identity_time_hours_ago, 2))
        assertEquals("1 day ago", quantity(R.plurals.identity_time_days_ago, 1))
        assertEquals("4 days ago", quantity(R.plurals.identity_time_days_ago, 4))
    }

    @Test
    fun `delivery relative time singularizes`() {
        assertEquals("1 second ago", quantity(R.plurals.settings_delivery_seconds_ago, 1))
        assertEquals("30 seconds ago", quantity(R.plurals.settings_delivery_seconds_ago, 30))
        assertEquals("1 minute ago", quantity(R.plurals.settings_delivery_minutes_ago, 1))
        assertEquals("1 hour ago", quantity(R.plurals.settings_delivery_hours_ago, 1))
        assertEquals("1 day ago", quantity(R.plurals.settings_delivery_days_ago, 1))
        assertEquals("2 days ago", quantity(R.plurals.settings_delivery_days_ago, 2))
    }

    @Test
    fun `location relative time singularizes`() {
        assertEquals("1 second ago", quantity(R.plurals.settings_location_seconds_ago, 1))
        assertEquals("1 minute ago", quantity(R.plurals.settings_location_minutes_ago, 1))
        assertEquals("1 hour ago", quantity(R.plurals.settings_location_hours_ago, 1))
        assertEquals("1 day ago", quantity(R.plurals.settings_location_days_ago, 1))
        assertEquals("7 days ago", quantity(R.plurals.settings_location_days_ago, 7))
    }

    @Test
    fun `shared time resources singularize`() {
        assertEquals("1 minute ago", quantity(R.plurals.time_minutes_ago, 1))
        assertEquals("9 minutes ago", quantity(R.plurals.time_minutes_ago, 9))
        assertEquals("1 hour ago", quantity(R.plurals.time_hours_ago, 1))
        assertEquals("6 hours ago", quantity(R.plurals.time_hours_ago, 6))
        assertEquals("1 day ago", quantity(R.plurals.time_days_ago, 1))
        assertEquals("1 week ago", quantity(R.plurals.time_weeks_ago, 1))
        assertEquals("2 weeks ago", quantity(R.plurals.time_weeks_ago, 2))
    }
}
