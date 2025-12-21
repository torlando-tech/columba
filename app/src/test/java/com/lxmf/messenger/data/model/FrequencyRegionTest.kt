package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FrequencyRegion and FrequencyRegions.
 * Tests regulatory frequency band data and computed properties.
 */
class FrequencyRegionTest {
    // ========== Computed Property Tests ==========

    @Test
    fun `frequency computes midpoint correctly`() {
        val usRegion = FrequencyRegions.findById("us_915")!!

        // Midpoint of 902-928 MHz = 915 MHz
        val expectedMidpoint = (902_000_000L + 928_000_000L) / 2
        assertEquals(expectedMidpoint, usRegion.frequency)
        assertEquals(915_000_000L, usRegion.frequency)
    }

    @Test
    fun `frequency computes midpoint for narrow band`() {
        val euRegionP = FrequencyRegions.findById("eu_868_p")!!

        // Midpoint of 869.4-869.65 MHz = 869.525 MHz
        val expectedMidpoint = (869_400_000L + 869_650_000L) / 2
        assertEquals(expectedMidpoint, euRegionP.frequency)
    }

    @Test
    fun `hasDutyCycleLimit true for EU 868-P with 10 percent`() {
        val euRegionP = FrequencyRegions.findById("eu_868_p")!!

        assertEquals(10, euRegionP.dutyCycle)
        assertTrue(euRegionP.hasDutyCycleLimit)
    }

    @Test
    fun `hasDutyCycleLimit true for EU 868-L with 1 percent`() {
        val euRegionL = FrequencyRegions.findById("eu_868_l")!!

        assertEquals(1, euRegionL.dutyCycle)
        assertTrue(euRegionL.hasDutyCycleLimit)
    }

    @Test
    fun `hasDutyCycleLimit false for US with 100 percent`() {
        val usRegion = FrequencyRegions.findById("us_915")!!

        assertEquals(100, usRegion.dutyCycle)
        assertFalse(usRegion.hasDutyCycleLimit)
    }

    @Test
    fun `hasDutyCycleLimit false for Australia with 100 percent`() {
        val auRegion = FrequencyRegions.findById("au_915")!!

        assertEquals(100, auRegion.dutyCycle)
        assertFalse(auRegion.hasDutyCycleLimit)
    }

    @Test
    fun `dutyCycleDisplay shows percentage for limited regions`() {
        val euRegionP = FrequencyRegions.findById("eu_868_p")!!
        assertEquals("10%", euRegionP.dutyCycleDisplay)

        val euRegionL = FrequencyRegions.findById("eu_868_l")!!
        assertEquals("1%", euRegionL.dutyCycleDisplay)
    }

    @Test
    fun `dutyCycleDisplay shows Unlimited for 100 percent`() {
        val usRegion = FrequencyRegions.findById("us_915")!!
        assertEquals("Unlimited", usRegion.dutyCycleDisplay)
    }

    // ========== FrequencyRegions.findById Tests ==========

    @Test
    fun `findById returns correct region for us_915`() {
        val region = FrequencyRegions.findById("us_915")

        assertNotNull(region)
        assertEquals("us_915", region!!.id)
        assertEquals("US / Americas (915 MHz)", region.name)
        assertEquals(902_000_000L, region.frequencyStart)
        assertEquals(928_000_000L, region.frequencyEnd)
    }

    @Test
    fun `findById returns correct region for all EU sub-bands`() {
        val euL = FrequencyRegions.findById("eu_868_l")
        val euM = FrequencyRegions.findById("eu_868_m")
        val euP = FrequencyRegions.findById("eu_868_p")
        val euQ = FrequencyRegions.findById("eu_868_q")

        assertNotNull(euL)
        assertNotNull(euM)
        assertNotNull(euP)
        assertNotNull(euQ)

        // Verify they have different duty cycles
        assertEquals(1, euL!!.dutyCycle)
        assertEquals(1, euM!!.dutyCycle)
        assertEquals(10, euP!!.dutyCycle)
        assertEquals(1, euQ!!.dutyCycle)
    }

    @Test
    fun `findById returns null for invalid ID`() {
        val region = FrequencyRegions.findById("invalid_region_id")
        assertNull(region)
    }

    @Test
    fun `findById returns null for empty string`() {
        val region = FrequencyRegions.findById("")
        assertNull(region)
    }

    @Test
    fun `findById all 21 regions are findable`() {
        val expectedIds =
            listOf(
                "us_915", "br_902",
                "eu_868_l", "eu_868_m", "eu_868_p", "eu_868_q", "eu_433",
                "ru_868", "ua_868",
                "au_915", "nz_865",
                "jp_920", "kr_920", "tw_920", "cn_470", "in_865",
                "th_920", "sg_923", "my_919", "ph_915",
                "lora_24",
            )

        expectedIds.forEach { id ->
            assertNotNull("Region with ID '$id' should exist", FrequencyRegions.findById(id))
        }
    }

    // ========== Region Data Validity Tests ==========

    @Test
    fun `all regions have valid TX power limits`() {
        FrequencyRegions.regions.forEach { region ->
            assertTrue(
                "Region ${region.id} maxTxPower should be > 0",
                region.maxTxPower > 0,
            )
            assertTrue(
                "Region ${region.id} defaultTxPower should be <= maxTxPower",
                region.defaultTxPower <= region.maxTxPower,
            )
            assertTrue(
                "Region ${region.id} defaultTxPower should be > 0",
                region.defaultTxPower > 0,
            )
        }
    }

    @Test
    fun `all regions have valid frequency ranges`() {
        FrequencyRegions.regions.forEach { region ->
            assertTrue(
                "Region ${region.id} should have positive frequencyStart",
                region.frequencyStart > 0,
            )
            assertTrue(
                "Region ${region.id} frequencyEnd should be > frequencyStart",
                region.frequencyEnd > region.frequencyStart,
            )
        }
    }

    @Test
    fun `all regions have valid duty cycles`() {
        FrequencyRegions.regions.forEach { region ->
            assertTrue(
                "Region ${region.id} dutyCycle should be 1-100, got ${region.dutyCycle}",
                region.dutyCycle in 1..100,
            )
        }
    }

    @Test
    fun `all regions have non-empty names and descriptions`() {
        FrequencyRegions.regions.forEach { region ->
            assertTrue(
                "Region ${region.id} should have non-empty name",
                region.name.isNotBlank(),
            )
            assertTrue(
                "Region ${region.id} should have non-empty description",
                region.description.isNotBlank(),
            )
        }
    }

    @Test
    fun `regions count is 21`() {
        assertEquals(21, FrequencyRegions.regions.size)
    }

    // ========== Specific Region Verification ==========

    @Test
    fun `Japan region has correct ARIB limits`() {
        val jpRegion = FrequencyRegions.findById("jp_920")!!

        assertEquals(920_800_000L, jpRegion.frequencyStart)
        assertEquals(927_800_000L, jpRegion.frequencyEnd)
        assertEquals(16, jpRegion.maxTxPower) // ARIB STD-T108 limit
    }

    @Test
    fun `2point4 GHz region spans worldwide ISM band`() {
        val region24 = FrequencyRegions.findById("lora_24")!!

        assertEquals(2_400_000_000L, region24.frequencyStart)
        assertEquals(2_483_500_000L, region24.frequencyEnd)
        assertEquals(10, region24.maxTxPower) // Low power for 2.4 GHz LoRa
    }

    @Test
    fun `China 470 MHz region is correct`() {
        val cnRegion = FrequencyRegions.findById("cn_470")!!

        assertEquals(470_000_000L, cnRegion.frequencyStart)
        assertEquals(510_000_000L, cnRegion.frequencyEnd)
    }
}
