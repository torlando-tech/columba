package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.data.model.FrequencyRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RNodeConfigValidator.
 */
class RNodeConfigValidatorTest {
    // Test regions
    private val usRegion =
        FrequencyRegion(
            id = "us_915",
            name = "US / Americas (915 MHz)",
            description = "902-928 MHz",
            frequencyStart = 902_000_000L,
            frequencyEnd = 928_000_000L,
            maxTxPower = 30,
            defaultTxPower = 17,
            dutyCycle = 100,
        )

    private val euRegion =
        FrequencyRegion(
            id = "eu_868_p",
            name = "EU (868 MHz) - 10% duty",
            description = "869.4-869.65 MHz, 10% duty cycle",
            frequencyStart = 869_400_000L,
            frequencyEnd = 869_650_000L,
            maxTxPower = 14,
            defaultTxPower = 14,
            dutyCycle = 10,
        )

    // NZ 865 region has highest TX power limit (36 dBm)
    private val nzRegion =
        FrequencyRegion(
            id = "nz_865",
            name = "New Zealand (865 MHz)",
            description = "864-868 MHz alternative band",
            frequencyStart = 864_000_000L,
            frequencyEnd = 868_000_000L,
            maxTxPower = 36,
            defaultTxPower = 17,
            dutyCycle = 100,
        )

    // ========== validateName Tests ==========

    @Test
    fun `validateName valid name`() {
        val result = RNodeConfigValidator.validateName("RNode LoRa")
        assertTrue(result.isValid)
        assertNull(result.errorMessage)
    }

    @Test
    fun `validateName blank name is invalid`() {
        val result = RNodeConfigValidator.validateName("")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("required"))
    }

    @Test
    fun `validateName whitespace only is invalid`() {
        val result = RNodeConfigValidator.validateName("   ")
        assertFalse(result.isValid)
    }

    // ========== validateFrequency Tests ==========

    @Test
    fun `validateFrequency valid frequency with region`() {
        val result = RNodeConfigValidator.validateFrequency("915000000", usRegion)
        assertTrue(result.isValid)
        assertNull(result.errorMessage)
    }

    @Test
    fun `validateFrequency blank is valid (while typing)`() {
        val result = RNodeConfigValidator.validateFrequency("", usRegion)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateFrequency invalid number`() {
        val result = RNodeConfigValidator.validateFrequency("abc", usRegion)
        assertFalse(result.isValid)
        assertEquals("Invalid number", result.errorMessage)
    }

    @Test
    fun `validateFrequency below region minimum`() {
        val result = RNodeConfigValidator.validateFrequency("900000000", usRegion)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("MHz"))
    }

    @Test
    fun `validateFrequency above region maximum`() {
        val result = RNodeConfigValidator.validateFrequency("930000000", usRegion)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("MHz"))
    }

    @Test
    fun `validateFrequency at region boundary is valid`() {
        val result = RNodeConfigValidator.validateFrequency("902000000", usRegion)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateFrequency without region uses default range`() {
        // Default range is 137 MHz - 3000 MHz
        val result = RNodeConfigValidator.validateFrequency("915000000", null)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateFrequency without region rejects out of range`() {
        // Default max is 3 GHz
        val result = RNodeConfigValidator.validateFrequency("4000000000", null)
        assertFalse(result.isValid)
    }

    // ========== validateBandwidth Tests ==========

    @Test
    fun `validateBandwidth valid bandwidth`() {
        val result = RNodeConfigValidator.validateBandwidth("250000")
        assertTrue(result.isValid)
    }

    @Test
    fun `validateBandwidth blank is valid (while typing)`() {
        val result = RNodeConfigValidator.validateBandwidth("")
        assertTrue(result.isValid)
    }

    @Test
    fun `validateBandwidth below minimum`() {
        val result = RNodeConfigValidator.validateBandwidth("5000")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("7.8"))
    }

    @Test
    fun `validateBandwidth above maximum`() {
        val result = RNodeConfigValidator.validateBandwidth("2000000")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("1625"))
    }

    @Test
    fun `validateBandwidth at boundaries`() {
        assertTrue(RNodeConfigValidator.validateBandwidth("7800").isValid)
        assertTrue(RNodeConfigValidator.validateBandwidth("1625000").isValid)
    }

    // ========== validateSpreadingFactor Tests ==========

    @Test
    fun `validateSpreadingFactor valid SF`() {
        val result = RNodeConfigValidator.validateSpreadingFactor("11")
        assertTrue(result.isValid)
    }

    @Test
    fun `validateSpreadingFactor blank is valid (while typing)`() {
        val result = RNodeConfigValidator.validateSpreadingFactor("")
        assertTrue(result.isValid)
    }

    @Test
    fun `validateSpreadingFactor below minimum`() {
        val result = RNodeConfigValidator.validateSpreadingFactor("6")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("7"))
    }

    @Test
    fun `validateSpreadingFactor above maximum`() {
        val result = RNodeConfigValidator.validateSpreadingFactor("13")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("12"))
    }

    @Test
    fun `validateSpreadingFactor at boundaries`() {
        assertTrue(RNodeConfigValidator.validateSpreadingFactor("7").isValid)
        assertTrue(RNodeConfigValidator.validateSpreadingFactor("12").isValid)
    }

    // ========== validateCodingRate Tests ==========

    @Test
    fun `validateCodingRate valid CR`() {
        val result = RNodeConfigValidator.validateCodingRate("5")
        assertTrue(result.isValid)
    }

    @Test
    fun `validateCodingRate below minimum`() {
        val result = RNodeConfigValidator.validateCodingRate("4")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("5"))
    }

    @Test
    fun `validateCodingRate above maximum`() {
        val result = RNodeConfigValidator.validateCodingRate("9")
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("8"))
    }

    // ========== validateTxPower Tests ==========

    @Test
    fun `validateTxPower valid for region`() {
        val result = RNodeConfigValidator.validateTxPower("17", usRegion)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateTxPower exceeds region max`() {
        val result = RNodeConfigValidator.validateTxPower("35", usRegion)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("30"))
    }

    @Test
    fun `validateTxPower negative`() {
        val result = RNodeConfigValidator.validateTxPower("-1", usRegion)
        assertFalse(result.isValid)
    }

    @Test
    fun `validateTxPower without region uses default max`() {
        // Default max is 22
        val result = RNodeConfigValidator.validateTxPower("25", null)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("22"))
    }

    @Test
    fun `validateTxPower NZ region allows up to 36 dBm`() {
        // NZ 865 allows highest TX power (36 dBm)
        val result = RNodeConfigValidator.validateTxPower("36", nzRegion)
        assertTrue(result.isValid)
        assertNull(result.errorMessage)
    }

    @Test
    fun `validateTxPower NZ region rejects above 36 dBm`() {
        val result = RNodeConfigValidator.validateTxPower("37", nzRegion)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("36"))
    }

    @Test
    fun `validateTxPower US region allows 30 dBm`() {
        val result = RNodeConfigValidator.validateTxPower("30", usRegion)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateTxPower EU region limits to 14 dBm`() {
        // EU 868-P max is 14 dBm
        val validResult = RNodeConfigValidator.validateTxPower("14", euRegion)
        assertTrue(validResult.isValid)

        val invalidResult = RNodeConfigValidator.validateTxPower("15", euRegion)
        assertFalse(invalidResult.isValid)
        assertTrue(invalidResult.errorMessage!!.contains("14"))
    }

    // ========== validateAirtimeLimit Tests ==========

    @Test
    fun `validateAirtimeLimit valid limit`() {
        val result = RNodeConfigValidator.validateAirtimeLimit("5", euRegion)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateAirtimeLimit blank is valid (no limit)`() {
        val result = RNodeConfigValidator.validateAirtimeLimit("", euRegion)
        assertTrue(result.isValid)
    }

    @Test
    fun `validateAirtimeLimit exceeds regional duty cycle`() {
        val result = RNodeConfigValidator.validateAirtimeLimit("20", euRegion)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("10"))
    }

    @Test
    fun `validateAirtimeLimit negative`() {
        val result = RNodeConfigValidator.validateAirtimeLimit("-1", euRegion)
        assertFalse(result.isValid)
    }

    @Test
    fun `validateAirtimeLimit over 100 percent`() {
        val result = RNodeConfigValidator.validateAirtimeLimit("101", usRegion)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("100"))
    }

    @Test
    fun `validateAirtimeLimit no limit for 100 percent duty cycle region`() {
        // US has 100% duty cycle (no limit)
        val result = RNodeConfigValidator.validateAirtimeLimit("50", usRegion)
        assertTrue(result.isValid)
    }

    // ========== validateConfigSilent Tests ==========

    @Test
    fun `validateConfigSilent all valid`() {
        val result =
            RNodeConfigValidator.validateConfigSilent(
                name = "RNode LoRa",
                frequency = "915000000",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "17",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertTrue(result)
    }

    @Test
    fun `validateConfigSilent blank name fails`() {
        val result =
            RNodeConfigValidator.validateConfigSilent(
                name = "",
                frequency = "915000000",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "17",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertFalse(result)
    }

    @Test
    fun `validateConfigSilent blank frequency fails`() {
        val result =
            RNodeConfigValidator.validateConfigSilent(
                name = "RNode",
                frequency = "",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "17",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertFalse(result)
    }

    @Test
    fun `validateConfigSilent invalid txPower fails`() {
        val result =
            RNodeConfigValidator.validateConfigSilent(
                name = "RNode",
                frequency = "915000000",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "50", // Too high
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertFalse(result)
    }

    // ========== validateConfig Tests ==========

    @Test
    fun `validateConfig all valid returns valid result`() {
        val result =
            RNodeConfigValidator.validateConfig(
                name = "RNode LoRa",
                frequency = "915000000",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "17",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertTrue(result.isValid)
        assertNull(result.nameError)
        assertNull(result.frequencyError)
        assertNull(result.bandwidthError)
        assertNull(result.spreadingFactorError)
        assertNull(result.codingRateError)
        assertNull(result.txPowerError)
        assertNull(result.stAlockError)
        assertNull(result.ltAlockError)
    }

    @Test
    fun `validateConfig blank name returns error`() {
        val result =
            RNodeConfigValidator.validateConfig(
                name = "",
                frequency = "915000000",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "17",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertFalse(result.isValid)
        assertTrue(result.nameError!!.contains("required"))
    }

    @Test
    fun `validateConfig blank frequency returns error with range`() {
        val result =
            RNodeConfigValidator.validateConfig(
                name = "RNode",
                frequency = "",
                bandwidth = "250000",
                spreadingFactor = "11",
                codingRate = "5",
                txPower = "17",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertFalse(result.isValid)
        assertTrue(result.frequencyError!!.contains("902"))
        assertTrue(result.frequencyError!!.contains("928"))
    }

    @Test
    fun `validateConfig multiple errors`() {
        val result =
            RNodeConfigValidator.validateConfig(
                name = "",
                frequency = "",
                bandwidth = "",
                spreadingFactor = "",
                codingRate = "",
                txPower = "",
                stAlock = "",
                ltAlock = "",
                region = usRegion,
            )
        assertFalse(result.isValid)
        assertTrue(result.nameError != null)
        assertTrue(result.frequencyError != null)
        assertTrue(result.bandwidthError != null)
        assertTrue(result.spreadingFactorError != null)
        assertTrue(result.codingRateError != null)
        assertTrue(result.txPowerError != null)
    }

    // ========== Helper Function Tests ==========

    @Test
    fun `getMaxTxPower returns region max`() {
        assertEquals(30, RNodeConfigValidator.getMaxTxPower(usRegion))
        assertEquals(14, RNodeConfigValidator.getMaxTxPower(euRegion))
        assertEquals(36, RNodeConfigValidator.getMaxTxPower(nzRegion))
    }

    @Test
    fun `getMaxTxPower returns default without region`() {
        assertEquals(22, RNodeConfigValidator.getMaxTxPower(null))
    }

    @Test
    fun `getFrequencyRange returns region range`() {
        val (min, max) = RNodeConfigValidator.getFrequencyRange(usRegion)
        assertEquals(902_000_000L, min)
        assertEquals(928_000_000L, max)
    }

    @Test
    fun `getFrequencyRange returns default without region`() {
        val (min, max) = RNodeConfigValidator.getFrequencyRange(null)
        assertEquals(137_000_000L, min)
        assertEquals(3_000_000_000L, max)
    }

    @Test
    fun `getMaxAirtimeLimit returns duty cycle for limited region`() {
        assertEquals(10.0, RNodeConfigValidator.getMaxAirtimeLimit(euRegion))
    }

    @Test
    fun `getMaxAirtimeLimit returns null for unlimited region`() {
        assertNull(RNodeConfigValidator.getMaxAirtimeLimit(usRegion))
    }

    @Test
    fun `getMaxAirtimeLimit returns null without region`() {
        assertNull(RNodeConfigValidator.getMaxAirtimeLimit(null))
    }
}
