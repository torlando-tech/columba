package com.lxmf.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ImageUtils.calculateTransferTime function.
 */
class ImageUtilsTransferTimeTest {
    @Test
    fun `calculateTransferTime returns correct seconds for simple case`() {
        // 1000 bytes at 8000 bps = 1 second
        val result = ImageUtils.calculateTransferTime(1000L, 8000)
        assertEquals(1, result.seconds)
    }

    @Test
    fun `calculateTransferTime returns correct formatted time for seconds`() {
        val result = ImageUtils.calculateTransferTime(1000L, 8000)
        assertEquals("1s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats minutes correctly`() {
        // 75000 bytes at 1000 bps = 600 seconds = 10 minutes
        val result = ImageUtils.calculateTransferTime(75000L, 1000)
        assertEquals(600, result.seconds)
        assertEquals("10m", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats minutes and seconds`() {
        // 9375 bytes at 1000 bps = 75 seconds = 1m 15s
        val result = ImageUtils.calculateTransferTime(9375L, 1000)
        assertEquals(75, result.seconds)
        assertEquals("1m 15s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats hours correctly`() {
        // 450000 bytes at 1000 bps = 3600 seconds = 1 hour
        val result = ImageUtils.calculateTransferTime(450000L, 1000)
        assertEquals(3600, result.seconds)
        assertEquals("1h", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime formats hours and minutes`() {
        // 562500 bytes at 1000 bps = 4500 seconds = 1h 15m
        val result = ImageUtils.calculateTransferTime(562500L, 1000)
        assertEquals(4500, result.seconds)
        assertEquals("1h 15m", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime handles zero bandwidth`() {
        val result = ImageUtils.calculateTransferTime(1000L, 0)
        assertEquals(0, result.seconds)
        assertEquals("Unknown", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime handles negative bandwidth`() {
        val result = ImageUtils.calculateTransferTime(1000L, -100)
        assertEquals(0, result.seconds)
        assertEquals("Unknown", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime handles sub-second transfers`() {
        // 100 bytes at 8000 bps = 0.1 seconds
        val result = ImageUtils.calculateTransferTime(100L, 8000)
        assertEquals(0, result.seconds)
        assertEquals("< 1s", result.formattedTime)
    }

    @Test
    fun `calculateTransferTime realistic LoRa example`() {
        // 32KB at 1000 bps (slow LoRa)
        val sizeBytes = 32 * 1024L
        val result = ImageUtils.calculateTransferTime(sizeBytes, 1000)
        // 32KB = 262144 bits, at 1000 bps = 262 seconds = 4m 22s
        assertTrue("LoRa transfer should take several minutes", result.seconds > 200)
        assertTrue("Formatted time should contain 'm'", result.formattedTime.contains("m"))
    }

    @Test
    fun `calculateTransferTime realistic BLE example`() {
        // 128KB at 14400 bps (BLE)
        val sizeBytes = 128 * 1024L
        val result = ImageUtils.calculateTransferTime(sizeBytes, 14400)
        // 128KB = 1048576 bits, at 14400 bps = 72 seconds = 1m 12s
        assertTrue("BLE transfer should be around a minute", result.seconds in 60..90)
    }

    @Test
    fun `calculateTransferTime realistic TCP example`() {
        // 512KB at 1000000 bps (1 Mbps TCP)
        val sizeBytes = 512 * 1024L
        val result = ImageUtils.calculateTransferTime(sizeBytes, 1_000_000)
        // 512KB = 4194304 bits, at 1Mbps = ~4 seconds
        assertTrue("TCP transfer should be quick", result.seconds < 10)
    }
}
