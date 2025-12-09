package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RNodeRegionalPresets.
 * Tests community-curated RNode frequency configurations.
 */
class RNodeRegionalPresetsTest {
    // ========== getCountries Tests ==========

    @Test
    fun `getCountries returns sorted list`() {
        val countries = RNodeRegionalPresets.getCountries()

        // Verify sorted
        val sortedCountries = countries.sorted()
        assertEquals(sortedCountries, countries)
    }

    @Test
    fun `getCountries returns distinct countries`() {
        val countries = RNodeRegionalPresets.getCountries()

        // No duplicates
        assertEquals(countries.size, countries.toSet().size)
    }

    @Test
    fun `getCountries includes expected countries`() {
        val countries = RNodeRegionalPresets.getCountries()

        assertTrue("Should include Australia", "Australia" in countries)
        assertTrue("Should include United States", "United States" in countries)
        assertTrue("Should include Germany", "Germany" in countries)
        assertTrue("Should include United Kingdom", "United Kingdom" in countries)
    }

    // ========== getByCountry Tests ==========

    @Test
    fun `getByCountry groups correctly for Australia`() {
        val byCountry = RNodeRegionalPresets.getByCountry()
        val auPresets = byCountry["Australia"]

        assertNotNull(auPresets)
        assertEquals(4, auPresets!!.size) // au_default, au_sydney, au_brisbane, au_western_sydney
    }

    @Test
    fun `getByCountry groups correctly for United States`() {
        val byCountry = RNodeRegionalPresets.getByCountry()
        val usPresets = byCountry["United States"]

        assertNotNull(usPresets)
        assertEquals(4, usPresets!!.size) // us_default, us_portsmouth, us_olympia, us_chicago
    }

    @Test
    fun `getByCountry groups correctly for Italy`() {
        val byCountry = RNodeRegionalPresets.getByCountry()
        val itPresets = byCountry["Italy"]

        assertNotNull(itPresets)
        assertEquals(5, itPresets!!.size) // Multiple Italian cities
    }

    // ========== getPresetsForCountry Tests ==========

    @Test
    fun `getPresetsForCountry returns default first`() {
        val presets = RNodeRegionalPresets.getPresetsForCountry("Australia")

        assertTrue("Should have presets", presets.isNotEmpty())
        assertNull("First preset should be default (null city)", presets.first().cityOrRegion)
    }

    @Test
    fun `getPresetsForCountry city presets follow default`() {
        val presets = RNodeRegionalPresets.getPresetsForCountry("Australia")

        // First is default, rest have cities
        assertNull(presets[0].cityOrRegion)
        assertNotNull(presets[1].cityOrRegion)
    }

    @Test
    fun `getPresetsForCountry returns empty for unknown country`() {
        val presets = RNodeRegionalPresets.getPresetsForCountry("Atlantis")
        assertTrue(presets.isEmpty())
    }

    @Test
    fun `getPresetsForCountry all presets have matching country`() {
        val countries = RNodeRegionalPresets.getCountries()

        countries.forEach { country ->
            val presets = RNodeRegionalPresets.getPresetsForCountry(country)
            presets.forEach { preset ->
                assertEquals(
                    "Preset ${preset.id} should have countryName=$country",
                    country,
                    preset.countryName,
                )
            }
        }
    }

    // ========== findMatchingPreset Tests ==========

    @Test
    fun `findMatchingPreset finds exact match`() {
        // Australia default: 925875000, 250000, SF9
        val preset =
            RNodeRegionalPresets.findMatchingPreset(
                frequency = 925_875_000,
                bandwidth = 250_000,
                spreadingFactor = 9,
            )

        assertNotNull(preset)
        assertEquals("au_default", preset!!.id)
    }

    @Test
    fun `findMatchingPreset finds Sydney preset`() {
        // Sydney uses SF11 for long range
        val preset =
            RNodeRegionalPresets.findMatchingPreset(
                frequency = 925_875_000,
                bandwidth = 250_000,
                spreadingFactor = 11,
            )

        assertNotNull(preset)
        assertEquals("au_sydney", preset!!.id)
    }

    @Test
    fun `findMatchingPreset returns null for no match`() {
        // Non-existent combination
        val preset =
            RNodeRegionalPresets.findMatchingPreset(
                frequency = 999_000_000,
                bandwidth = 250_000,
                spreadingFactor = 7,
            )

        assertNull(preset)
    }

    @Test
    fun `findMatchingPreset returns null for partial match`() {
        // Correct frequency and bandwidth, wrong SF
        val preset =
            RNodeRegionalPresets.findMatchingPreset(
                frequency = 925_875_000,
                bandwidth = 250_000,
                spreadingFactor = 12, // No Australian preset uses SF12 at 250kHz
            )

        assertNull(preset)
    }

    // ========== Preset Data Validity Tests ==========

    @Test
    fun `all presets have valid frequency range`() {
        RNodeRegionalPresets.presets.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} should have positive frequency",
                preset.frequency > 0,
            )
            assertTrue(
                "Preset ${preset.id} frequency should be reasonable",
                preset.frequency in 100_000_000..3_000_000_000,
            )
        }
    }

    @Test
    fun `all presets have valid bandwidth`() {
        val validBandwidths = setOf(125_000, 250_000, 500_000, 812_500)
        RNodeRegionalPresets.presets.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} should have valid bandwidth, got ${preset.bandwidth}",
                preset.bandwidth in validBandwidths,
            )
        }
    }

    @Test
    fun `all presets have valid spreading factor`() {
        RNodeRegionalPresets.presets.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} should have SF 7-12, got ${preset.spreadingFactor}",
                preset.spreadingFactor in 7..12,
            )
        }
    }

    @Test
    fun `all presets have valid coding rate`() {
        RNodeRegionalPresets.presets.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} should have CR 5-8, got ${preset.codingRate}",
                preset.codingRate in 5..8,
            )
        }
    }

    @Test
    fun `all presets have valid TX power`() {
        RNodeRegionalPresets.presets.forEach { preset ->
            assertTrue(
                "Preset ${preset.id} should have TX power 1-30, got ${preset.txPower}",
                preset.txPower in 1..30,
            )
        }
    }

    @Test
    fun `all presets have valid country codes`() {
        RNodeRegionalPresets.presets.forEach { preset ->
            assertEquals(
                "Preset ${preset.id} should have 2-char country code",
                2,
                preset.countryCode.length,
            )
        }
    }

    @Test
    fun `all presets have unique IDs`() {
        val ids = RNodeRegionalPresets.presets.map { it.id }
        assertEquals("All preset IDs should be unique", ids.size, ids.toSet().size)
    }
}
