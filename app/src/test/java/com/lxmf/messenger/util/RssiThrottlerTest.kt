@file:Suppress("VarCouldBeVal")

package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssiThrottlerTest {
    // ==================== Basic Throttling Tests ====================

    @Test
    fun `shouldUpdate returns true for first update`() {
        val throttler = RssiThrottler(intervalMs = 3000)
        val result = throttler.shouldUpdate("AA:BB:CC:DD:EE:FF")
        assertTrue(result)
    }

    @Test
    fun `shouldUpdate returns false for second update within interval`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        // First update at t=1000
        assertTrue(throttler.shouldUpdate("AA:BB:CC:DD:EE:FF"))

        // Second update at t=2000 (only 1000ms later, interval is 3000ms)
        currentTime = 2000L
        assertFalse(throttler.shouldUpdate("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `shouldUpdate returns true after interval has elapsed`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        // First update at t=1000
        assertTrue(throttler.shouldUpdate("AA:BB:CC:DD:EE:FF"))

        // Second update at t=4000 (3000ms later, exactly at interval)
        currentTime = 4000L
        assertTrue(throttler.shouldUpdate("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `shouldUpdate returns false just before interval elapses`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        // First update at t=1000
        assertTrue(throttler.shouldUpdate("AA:BB:CC:DD:EE:FF"))

        // Second update at t=3999 (2999ms later, 1ms before interval)
        currentTime = 3999L
        assertFalse(throttler.shouldUpdate("AA:BB:CC:DD:EE:FF"))
    }

    // ==================== Multiple Address Tests ====================

    @Test
    fun `shouldUpdate handles multiple addresses independently`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"

        // First updates for both addresses should succeed
        assertTrue(throttler.shouldUpdate(address1))
        assertTrue(throttler.shouldUpdate(address2))

        // Second updates within interval should both fail
        currentTime = 2000L
        assertFalse(throttler.shouldUpdate(address1))
        assertFalse(throttler.shouldUpdate(address2))

        // After interval, both should succeed again
        currentTime = 4000L
        assertTrue(throttler.shouldUpdate(address1))
        assertTrue(throttler.shouldUpdate(address2))
    }

    @Test
    fun `shouldUpdate throttles each address independently with different timings`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"

        // Update address1 at t=1000
        assertTrue(throttler.shouldUpdate(address1))

        // Update address2 at t=2000
        currentTime = 2000L
        assertTrue(throttler.shouldUpdate(address2))

        // At t=3500, address1 can update (1000 + 3000 <= 3500)
        // but address2 cannot (2000 + 3000 > 3500)
        currentTime = 3500L
        assertFalse(throttler.shouldUpdate(address1)) // 3500 - 1000 = 2500 < 3000
        assertFalse(throttler.shouldUpdate(address2)) // 3500 - 2000 = 1500 < 3000

        // At t=4000, address1 can update (4000 - 1000 >= 3000)
        // but address2 still cannot (4000 - 2000 < 3000)
        currentTime = 4000L
        assertTrue(throttler.shouldUpdate(address1))
        assertFalse(throttler.shouldUpdate(address2))

        // At t=5000, address2 can finally update (5000 - 2000 >= 3000)
        currentTime = 5000L
        assertTrue(throttler.shouldUpdate(address2))
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset allows immediate update for specific address`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address = "AA:BB:CC:DD:EE:FF"

        // First update
        assertTrue(throttler.shouldUpdate(address))

        // Second update within interval should fail
        currentTime = 2000L
        assertFalse(throttler.shouldUpdate(address))

        // Reset the address
        throttler.reset(address)

        // Now the update should succeed immediately
        assertTrue(throttler.shouldUpdate(address))
    }

    @Test
    fun `reset only affects the specified address`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"

        // First updates for both
        assertTrue(throttler.shouldUpdate(address1))
        assertTrue(throttler.shouldUpdate(address2))

        // Reset only address1
        throttler.reset(address1)

        // address1 should update immediately, address2 should still be throttled
        currentTime = 2000L
        assertTrue(throttler.shouldUpdate(address1))
        assertFalse(throttler.shouldUpdate(address2))
    }

    @Test
    fun `reset on non-existent address does not cause errors`() {
        val throttler = RssiThrottler(intervalMs = 3000)

        // Should not throw exception
        throttler.reset("NONEXISTENT:ADDRESS")
    }

    // ==================== Clear Tests ====================

    @Test
    fun `clear allows immediate updates for all addresses`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address1 = "AA:BB:CC:DD:EE:FF"
        val address2 = "11:22:33:44:55:66"

        // First updates for both
        assertTrue(throttler.shouldUpdate(address1))
        assertTrue(throttler.shouldUpdate(address2))

        // Clear all state
        throttler.clear()

        // Both should update immediately without waiting
        currentTime = 2000L
        assertTrue(throttler.shouldUpdate(address1))
        assertTrue(throttler.shouldUpdate(address2))
    }

    @Test
    fun `clear on empty throttler does not cause errors`() {
        val throttler = RssiThrottler(intervalMs = 3000)

        // Should not throw exception
        throttler.clear()
    }

    // ==================== Custom Interval Tests ====================

    @Test
    fun `throttler respects custom interval`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 5000, timeProvider = { currentTime })

        val address = "AA:BB:CC:DD:EE:FF"

        // First update
        assertTrue(throttler.shouldUpdate(address))

        // At t=4000 (3000ms later), should still be throttled with 5000ms interval
        currentTime = 4000L
        assertFalse(throttler.shouldUpdate(address))

        // At t=6000 (5000ms later), should be allowed
        currentTime = 6000L
        assertTrue(throttler.shouldUpdate(address))
    }

    @Test
    fun `throttler with zero interval always allows updates`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 0, timeProvider = { currentTime })

        val address = "AA:BB:CC:DD:EE:FF"

        // All updates should be allowed with zero interval
        assertTrue(throttler.shouldUpdate(address))
        assertTrue(throttler.shouldUpdate(address))
        assertTrue(throttler.shouldUpdate(address))
    }

    // ==================== Constant Verification ====================

    @Test
    fun `DEFAULT_INTERVAL_MS is 3000`() {
        assertEquals(3000L, RssiThrottler.DEFAULT_INTERVAL_MS)
    }

    @Test
    fun `default constructor uses DEFAULT_INTERVAL_MS`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(timeProvider = { currentTime })

        val address = "AA:BB:CC:DD:EE:FF"

        // First update
        assertTrue(throttler.shouldUpdate(address))

        // At t=3999, should be throttled (default interval is 3000ms)
        currentTime = 3999L
        assertFalse(throttler.shouldUpdate(address))

        // At t=4000, should be allowed
        currentTime = 4000L
        assertTrue(throttler.shouldUpdate(address))
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `shouldUpdate handles rapid sequential calls correctly`() {
        var currentTime = 1000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address = "AA:BB:CC:DD:EE:FF"

        // First call succeeds
        assertTrue(throttler.shouldUpdate(address))

        // All subsequent calls at same time should fail
        assertFalse(throttler.shouldUpdate(address))
        assertFalse(throttler.shouldUpdate(address))
        assertFalse(throttler.shouldUpdate(address))
    }

    @Test
    fun `shouldUpdate handles time going backwards gracefully`() {
        var currentTime = 5000L
        val throttler = RssiThrottler(intervalMs = 3000, timeProvider = { currentTime })

        val address = "AA:BB:CC:DD:EE:FF"

        // First update at t=5000
        assertTrue(throttler.shouldUpdate(address))

        // Time goes backwards to t=3000 (should handle gracefully)
        currentTime = 3000L
        // Since (3000 - 5000) = -2000, which is < 3000, it should be throttled
        // This is correct behavior - negative elapsed time means time went backwards
        assertFalse(throttler.shouldUpdate(address))
    }
}
