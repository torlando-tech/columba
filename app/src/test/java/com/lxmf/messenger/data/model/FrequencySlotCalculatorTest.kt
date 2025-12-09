package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FrequencySlotCalculator.
 * Tests Meshtastic-style frequency slot calculations across various regions.
 */
class FrequencySlotCalculatorTest {
    // ========== Test Data ==========

    private val usRegion = FrequencyRegions.findById("us_915")!!
    private val euRegionP = FrequencyRegions.findById("eu_868_p")!!
    private val euRegion433 = FrequencyRegions.findById("eu_433")!!

    // ========== calculateFrequency Tests ==========

    @Test
    fun `calculateFrequency with US region slot 0 returns correct frequency`() {
        // Slot 0: freqStart + (bandwidth / 2) = 902_000_000 + 125_000 = 902_125_000
        val freq = FrequencySlotCalculator.calculateFrequency(usRegion, 250_000, 0)
        assertEquals(902_125_000L, freq)
    }

    @Test
    fun `calculateFrequency with US region slot 50 returns correct frequency`() {
        // Slot 50: 902_000_000 + 125_000 + (50 * 250_000) = 914_625_000
        val freq = FrequencySlotCalculator.calculateFrequency(usRegion, 250_000, 50)
        assertEquals(914_625_000L, freq)
    }

    @Test
    fun `calculateFrequency with EU 868-P slot 0 returns correct frequency`() {
        // EU 868-P is a narrow band: 869.4-869.65 MHz
        // Slot 0: 869_400_000 + 125_000 = 869_525_000
        val freq = FrequencySlotCalculator.calculateFrequency(euRegionP, 250_000, 0)
        assertEquals(869_525_000L, freq)
    }

    @Test
    fun `calculateFrequency slot spacing matches bandwidth`() {
        val bandwidth = 250_000
        val slot0 = FrequencySlotCalculator.calculateFrequency(usRegion, bandwidth, 0)
        val slot1 = FrequencySlotCalculator.calculateFrequency(usRegion, bandwidth, 1)
        val slot2 = FrequencySlotCalculator.calculateFrequency(usRegion, bandwidth, 2)

        assertEquals(bandwidth.toLong(), slot1 - slot0)
        assertEquals(bandwidth.toLong(), slot2 - slot1)
    }

    @Test
    fun `calculateFrequency with different bandwidths produces different spacing`() {
        val slot125kHz = FrequencySlotCalculator.calculateFrequency(usRegion, 125_000, 1)
        val slot250kHz = FrequencySlotCalculator.calculateFrequency(usRegion, 250_000, 1)

        // Slot 1 with 125kHz: 902_000_000 + 62_500 + 125_000 = 902_187_500
        // Slot 1 with 250kHz: 902_000_000 + 125_000 + 250_000 = 902_375_000
        assertEquals(902_187_500L, slot125kHz)
        assertEquals(902_375_000L, slot250kHz)
    }

    // ========== getNumSlots Tests ==========

    @Test
    fun `getNumSlots US region with 250kHz returns 104 slots`() {
        // (928_000_000 - 902_000_000) / 250_000 = 104
        val numSlots = FrequencySlotCalculator.getNumSlots(usRegion, 250_000)
        assertEquals(104, numSlots)
    }

    @Test
    fun `getNumSlots US region with 125kHz returns 208 slots`() {
        // (928_000_000 - 902_000_000) / 125_000 = 208
        val numSlots = FrequencySlotCalculator.getNumSlots(usRegion, 125_000)
        assertEquals(208, numSlots)
    }

    @Test
    fun `getNumSlots EU 868-P with 250kHz returns 1 slot`() {
        // (869_650_000 - 869_400_000) / 250_000 = 1 (narrow band)
        val numSlots = FrequencySlotCalculator.getNumSlots(euRegionP, 250_000)
        assertEquals(1, numSlots)
    }

    @Test
    fun `getNumSlots EU 433 with 125kHz returns expected slots`() {
        // (434_790_000 - 433_050_000) / 125_000 = 13
        val numSlots = FrequencySlotCalculator.getNumSlots(euRegion433, 125_000)
        assertEquals(13, numSlots)
    }

    // ========== getDefaultSlot Tests ==========

    @Test
    fun `getDefaultSlot US region avoids Meshtastic slots 9 and 20`() {
        val defaultSlot = FrequencySlotCalculator.getDefaultSlot(usRegion, 250_000)

        // Should return slot 50 (middle-ish, avoids 9 and 20)
        assertEquals(50, defaultSlot)
    }

    @Test
    fun `getDefaultSlot returns valid slot for all regions`() {
        val bandwidth = 250_000

        FrequencyRegions.regions.forEach { region ->
            val numSlots = FrequencySlotCalculator.getNumSlots(region, bandwidth)
            if (numSlots > 0) {
                val defaultSlot = FrequencySlotCalculator.getDefaultSlot(region, bandwidth)

                assertTrue(
                    "Default slot $defaultSlot should be < $numSlots for region ${region.id}",
                    defaultSlot < numSlots,
                )
                assertTrue(
                    "Default slot $defaultSlot should be >= 0 for region ${region.id}",
                    defaultSlot >= 0,
                )
            }
        }
    }

    @Test
    fun `getDefaultSlot EU 868-P returns 0 for single slot band`() {
        val defaultSlot = FrequencySlotCalculator.getDefaultSlot(euRegionP, 250_000)
        assertEquals(0, defaultSlot)
    }

    // ========== calculateSlotFromFrequency Tests ==========

    @Test
    fun `calculateSlotFromFrequency roundtrip works correctly`() {
        val bandwidth = 250_000
        val originalSlot = 42

        val frequency = FrequencySlotCalculator.calculateFrequency(usRegion, bandwidth, originalSlot)
        val recoveredSlot = FrequencySlotCalculator.calculateSlotFromFrequency(usRegion, bandwidth, frequency)

        assertEquals(originalSlot, recoveredSlot)
    }

    @Test
    fun `calculateSlotFromFrequency returns null for non-aligned frequency`() {
        // Use a frequency that doesn't align with any slot
        val nonAlignedFreq = 915_000_001L // Just 1 Hz off

        val slot = FrequencySlotCalculator.calculateSlotFromFrequency(usRegion, 250_000, nonAlignedFreq)

        assertNull("Non-aligned frequency should return null", slot)
    }

    @Test
    fun `calculateSlotFromFrequency returns null for frequency below band`() {
        val belowBandFreq = 900_000_000L // Below US 902 MHz start

        val slot = FrequencySlotCalculator.calculateSlotFromFrequency(usRegion, 250_000, belowBandFreq)

        assertNull("Frequency below band should return null", slot)
    }

    @Test
    fun `calculateSlotFromFrequency returns null for frequency above band`() {
        // Calculate a frequency that would be slot 200 (way above 104 slots)
        val aboveBandFreq = 952_125_000L

        val slot = FrequencySlotCalculator.calculateSlotFromFrequency(usRegion, 250_000, aboveBandFreq)

        assertNull("Frequency above band should return null", slot)
    }

    @Test
    fun `calculateSlotFromFrequency handles all valid slots`() {
        val bandwidth = 250_000
        val numSlots = FrequencySlotCalculator.getNumSlots(usRegion, bandwidth)

        for (slot in 0 until minOf(numSlots, 20)) { // Test first 20 slots
            val freq = FrequencySlotCalculator.calculateFrequency(usRegion, bandwidth, slot)
            val recovered = FrequencySlotCalculator.calculateSlotFromFrequency(usRegion, bandwidth, freq)

            assertEquals("Slot $slot should roundtrip correctly", slot, recovered)
        }
    }

    // ========== formatFrequency Tests ==========

    @Test
    fun `formatFrequency whole MHz shows no decimals`() {
        val formatted = FrequencySlotCalculator.formatFrequency(915_000_000L)
        assertEquals("915 MHz", formatted)
    }

    @Test
    fun `formatFrequency decimal MHz shows 3 decimal places`() {
        val formatted = FrequencySlotCalculator.formatFrequency(914_875_000L)
        assertEquals("914.875 MHz", formatted)
    }

    @Test
    fun `formatFrequency EU 868 frequency formats correctly`() {
        val formatted = FrequencySlotCalculator.formatFrequency(868_100_000L)
        assertEquals("868.100 MHz", formatted)
    }

    @Test
    fun `formatFrequency 433 MHz band formats correctly`() {
        val formatted = FrequencySlotCalculator.formatFrequency(433_575_000L)
        assertEquals("433.575 MHz", formatted)
    }

    @Test
    fun `formatFrequency 2point4 GHz formats correctly`() {
        val formatted = FrequencySlotCalculator.formatFrequency(2_427_000_000L)
        assertEquals("2427 MHz", formatted)
    }

    // ========== Edge Cases ==========

    @Test
    fun `calculateFrequency handles maximum slot number`() {
        val numSlots = FrequencySlotCalculator.getNumSlots(usRegion, 250_000)
        val lastSlot = numSlots - 1

        val freq = FrequencySlotCalculator.calculateFrequency(usRegion, 250_000, lastSlot)

        // Should be within band
        assertTrue("Frequency should be <= band end", freq <= usRegion.frequencyEnd)
    }

    @Test
    fun `all region IDs are unique`() {
        val ids = FrequencyRegions.regions.map { it.id }
        assertEquals("All region IDs should be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `all regions have valid frequency ranges`() {
        FrequencyRegions.regions.forEach { region ->
            assertTrue(
                "Region ${region.id} frequencyEnd should be > frequencyStart",
                region.frequencyEnd > region.frequencyStart,
            )
            assertTrue(
                "Region ${region.id} maxTxPower should be > 0",
                region.maxTxPower > 0,
            )
            assertTrue(
                "Region ${region.id} dutyCycle should be 1-100",
                region.dutyCycle in 1..100,
            )
        }
    }
}
