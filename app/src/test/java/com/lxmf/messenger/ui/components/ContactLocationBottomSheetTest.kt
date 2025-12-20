package com.lxmf.messenger.ui.components

import android.app.Application
import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ContactLocationBottomSheet utility functions.
 *
 * Tests the pure utility functions:
 * - bearingToDirection: bearing angle to cardinal direction
 * - formatDistanceAndDirection: distance and direction formatting
 * - formatUpdatedTime: relative time formatting
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactLocationBottomSheetTest {

    // ========== bearingToDirection Tests ==========

    @Test
    fun `bearingToDirection returns north for 0 degrees`() {
        assertEquals("north", bearingToDirection(0f))
    }

    @Test
    fun `bearingToDirection returns north for 360 degrees`() {
        // 360 is same as 0
        assertEquals("north", bearingToDirection(360f))
    }

    @Test
    fun `bearingToDirection returns north for small positive angles`() {
        assertEquals("north", bearingToDirection(10f))
        assertEquals("north", bearingToDirection(22f))
    }

    @Test
    fun `bearingToDirection returns north for angles near 360`() {
        assertEquals("north", bearingToDirection(350f))
        assertEquals("north", bearingToDirection(338f))
    }

    @Test
    fun `bearingToDirection returns northeast for angles 22_5 to 67_5`() {
        assertEquals("northeast", bearingToDirection(23f))
        assertEquals("northeast", bearingToDirection(45f))
        assertEquals("northeast", bearingToDirection(67f))
    }

    @Test
    fun `bearingToDirection returns east for angles 67_5 to 112_5`() {
        assertEquals("east", bearingToDirection(68f))
        assertEquals("east", bearingToDirection(90f))
        assertEquals("east", bearingToDirection(112f))
    }

    @Test
    fun `bearingToDirection returns southeast for angles 112_5 to 157_5`() {
        assertEquals("southeast", bearingToDirection(113f))
        assertEquals("southeast", bearingToDirection(135f))
        assertEquals("southeast", bearingToDirection(157f))
    }

    @Test
    fun `bearingToDirection returns south for angles 157_5 to 202_5`() {
        assertEquals("south", bearingToDirection(158f))
        assertEquals("south", bearingToDirection(180f))
        assertEquals("south", bearingToDirection(202f))
    }

    @Test
    fun `bearingToDirection returns southwest for angles 202_5 to 247_5`() {
        assertEquals("southwest", bearingToDirection(203f))
        assertEquals("southwest", bearingToDirection(225f))
        assertEquals("southwest", bearingToDirection(247f))
    }

    @Test
    fun `bearingToDirection returns west for angles 247_5 to 292_5`() {
        assertEquals("west", bearingToDirection(248f))
        assertEquals("west", bearingToDirection(270f))
        assertEquals("west", bearingToDirection(292f))
    }

    @Test
    fun `bearingToDirection returns northwest for angles 292_5 to 337_5`() {
        assertEquals("northwest", bearingToDirection(293f))
        assertEquals("northwest", bearingToDirection(315f))
        assertEquals("northwest", bearingToDirection(337f))
    }

    @Test
    fun `bearingToDirection handles negative angles`() {
        // -90 should normalize to 270 (west)
        assertEquals("west", bearingToDirection(-90f))
        // -180 should normalize to 180 (south)
        assertEquals("south", bearingToDirection(-180f))
    }

    @Test
    fun `bearingToDirection handles angles greater than 360`() {
        // 450 should normalize to 90 (east)
        assertEquals("east", bearingToDirection(450f))
        // 720 should normalize to 0 (north)
        assertEquals("north", bearingToDirection(720f))
    }

    // ========== formatDistanceAndDirection Tests ==========

    @Test
    fun `formatDistanceAndDirection returns unknown when userLocation is null`() {
        val result = formatDistanceAndDirection(null, 37.7749, -122.4194)
        assertEquals("Location unknown", result)
    }

    @Test
    fun `formatDistanceAndDirection formats meters for short distances`() {
        // San Francisco coordinates
        val userLocation = createMockLocation(37.7749, -122.4194)

        // Location very close (~500m away)
        val result = formatDistanceAndDirection(userLocation, 37.7799, -122.4194)

        // Should contain "m" for meters
        assertTrue("Result should contain meters: $result", result.contains("m"))
        // Should contain a direction
        assertTrue(
            "Result should contain a direction: $result",
            result.contains("north") || result.contains("south") ||
                result.contains("east") || result.contains("west"),
        )
    }

    @Test
    fun `formatDistanceAndDirection formats kilometers for long distances`() {
        val userLocation = createMockLocation(37.7749, -122.4194) // San Francisco

        // Location ~10km away
        val result = formatDistanceAndDirection(userLocation, 37.8749, -122.4194)

        // Should contain "km" for kilometers
        assertTrue("Result should contain kilometers: $result", result.contains("km"))
    }

    @Test
    fun `formatDistanceAndDirection includes direction`() {
        val userLocation = createMockLocation(37.7749, -122.4194)

        // Location to the east
        val result = formatDistanceAndDirection(userLocation, 37.7749, -122.3194)

        // Should contain a direction word
        val directions = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        assertTrue(
            "Result should contain a direction: $result",
            directions.any { result.contains(it) },
        )
    }

    // ========== formatUpdatedTime Tests ==========

    @Test
    fun `formatUpdatedTime returns just now for recent timestamps`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 5_000) // 5 seconds ago

        assertEquals("Updated just now", result)
    }

    @Test
    fun `formatUpdatedTime returns seconds for timestamps under 1 minute`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 30_000) // 30 seconds ago

        assertTrue("Result should contain seconds: $result", result.contains("s ago"))
        assertTrue("Result should start with Updated: $result", result.startsWith("Updated"))
    }

    @Test
    fun `formatUpdatedTime returns minutes for timestamps under 1 hour`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 5 * 60_000) // 5 minutes ago

        assertTrue("Result should contain minutes: $result", result.contains("m ago"))
    }

    @Test
    fun `formatUpdatedTime returns hours for timestamps under 1 day`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 3 * 3600_000) // 3 hours ago

        assertTrue("Result should contain hours: $result", result.contains("h ago"))
    }

    @Test
    fun `formatUpdatedTime returns days for old timestamps`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 2L * 86400_000L) // 2 days ago

        assertTrue("Result should contain days: $result", result.contains("d ago"))
    }

    @Test
    fun `formatUpdatedTime handles very old timestamps`() {
        val now = System.currentTimeMillis()
        val result = formatUpdatedTime(now - 30L * 86400_000L) // 30 days ago

        assertTrue("Result should contain days: $result", result.contains("d ago"))
    }

    // ========== Helper Functions ==========

    private fun createMockLocation(lat: Double, lng: Double): Location {
        val location = mockk<Location>(relaxed = true)
        every { location.latitude } returns lat
        every { location.longitude } returns lng
        return location
    }
}
