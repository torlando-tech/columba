package com.lxmf.messenger.reticulum.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for LinkSpeedProbeResult.
 * Verifies correct behavior of bestRateBps calculation, transfer time estimation,
 * and fromMap parsing.
 */
class LinkSpeedProbeResultTest {
    // ========== bestRateBps Tests ==========

    @Test
    fun `bestRateBps prefers expectedRateBps when available`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 100_000L, // 100 kbps
            expectedRateBps = 1_000_000L, // 1 Mbps - should be preferred
            rttSeconds = 0.1,
            hops = 2,
            linkReused = true,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps
        )

        assertEquals(1_000_000L, result.bestRateBps)
    }

    @Test
    fun `bestRateBps falls back to max when expectedRateBps is null`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 100_000L, // 100 kbps
            expectedRateBps = null,
            rttSeconds = 0.1,
            hops = 2,
            linkReused = false,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps - should be used (max)
        )

        assertEquals(10_000_000L, result.bestRateBps)
    }

    @Test
    fun `bestRateBps falls back to max when expectedRateBps is zero`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 100_000L, // 100 kbps
            expectedRateBps = 0L,
            rttSeconds = 0.1,
            hops = 2,
            linkReused = false,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps - should be used (max)
        )

        assertEquals(10_000_000L, result.bestRateBps)
    }

    @Test
    fun `bestRateBps uses establishmentRateBps when higher than nextHopBitrateBps`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 50_000_000L, // 50 Mbps - higher than nextHop
            expectedRateBps = null,
            rttSeconds = 0.1,
            hops = 1,
            linkReused = false,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps
        )

        assertEquals(50_000_000L, result.bestRateBps)
    }

    @Test
    fun `bestRateBps uses nextHopBitrateBps when establishmentRateBps is null`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = null,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps
        )

        assertEquals(10_000_000L, result.bestRateBps)
    }

    @Test
    fun `bestRateBps uses establishmentRateBps when nextHopBitrateBps is null`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 100_000L, // 100 kbps
            expectedRateBps = null,
            rttSeconds = 0.1,
            hops = 2,
            linkReused = false,
            nextHopBitrateBps = null,
        )

        assertEquals(100_000L, result.bestRateBps)
    }

    @Test
    fun `bestRateBps returns null when all rates are null`() {
        val result = LinkSpeedProbeResult(
            status = "no_path",
            establishmentRateBps = null,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
            nextHopBitrateBps = null,
        )

        assertNull(result.bestRateBps)
    }

    @Test
    fun `bestRateBps returns null when all rates are zero`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 0L,
            expectedRateBps = 0L,
            rttSeconds = 0.1,
            hops = 1,
            linkReused = false,
            nextHopBitrateBps = 0L,
        )

        assertNull(result.bestRateBps)
    }

    @Test
    fun `bestRateBps ignores negative rates in fallback`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = -100L,
            expectedRateBps = null,
            rttSeconds = 0.1,
            hops = 2,
            linkReused = false,
            nextHopBitrateBps = 10_000_000L,
        )

        assertEquals(10_000_000L, result.bestRateBps)
    }

    // ========== isSuccess Tests ==========

    @Test
    fun `isSuccess returns true for success status`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 100_000L,
            expectedRateBps = null,
            rttSeconds = 0.1,
            hops = 2,
            linkReused = false,
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `isSuccess returns false for timeout status`() {
        val result = LinkSpeedProbeResult(
            status = "timeout",
            establishmentRateBps = null,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
        )

        assertFalse(result.isSuccess)
    }

    @Test
    fun `isSuccess returns false for no_path status`() {
        val result = LinkSpeedProbeResult(
            status = "no_path",
            establishmentRateBps = null,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
        )

        assertFalse(result.isSuccess)
    }

    // ========== estimateTransferTimeSeconds Tests ==========

    @Test
    fun `estimateTransferTimeSeconds calculates correctly`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = null,
            expectedRateBps = 1_000_000L, // 1 Mbps = 125 KB/s
            rttSeconds = 0.1,
            hops = 2,
            linkReused = true,
        )

        // 1 MB file = 8 Mb = 8,000,000 bits
        // At 1 Mbps = 8 seconds
        val timeSeconds = result.estimateTransferTimeSeconds(1_000_000L)
        assertEquals(8.0, timeSeconds!!, 0.001)
    }

    @Test
    fun `estimateTransferTimeSeconds returns null when no rate available`() {
        val result = LinkSpeedProbeResult(
            status = "no_path",
            establishmentRateBps = null,
            expectedRateBps = null,
            rttSeconds = null,
            hops = null,
            linkReused = false,
        )

        assertNull(result.estimateTransferTimeSeconds(1_000_000L))
    }

    @Test
    fun `estimateTransferTimeSeconds returns null for zero rate`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 0L,
            expectedRateBps = 0L,
            rttSeconds = 0.1,
            hops = 1,
            linkReused = false,
            nextHopBitrateBps = 0L,
        )

        assertNull(result.estimateTransferTimeSeconds(1_000_000L))
    }

    @Test
    fun `estimateTransferTimeSeconds handles small files`() {
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = null,
            expectedRateBps = 10_000_000L, // 10 Mbps
            rttSeconds = 0.01,
            hops = 1,
            linkReused = true,
        )

        // 1 KB file = 8,000 bits
        // At 10 Mbps = 0.0008 seconds
        val timeSeconds = result.estimateTransferTimeSeconds(1_000L)
        assertEquals(0.0008, timeSeconds!!, 0.0001)
    }

    // ========== fromMap Tests ==========

    @Test
    fun `fromMap parses all fields correctly`() {
        val map = mapOf<String, Any?>(
            "status" to "success",
            "establishment_rate_bps" to 100000L,
            "expected_rate_bps" to 1000000L,
            "rtt_seconds" to 0.15,
            "hops" to 3,
            "link_reused" to true,
            "next_hop_bitrate_bps" to 10000000L,
            "link_mtu" to 1196,
            "error" to null,
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals("success", result.status)
        assertEquals(100000L, result.establishmentRateBps)
        assertEquals(1000000L, result.expectedRateBps)
        assertEquals(0.15, result.rttSeconds!!, 0.001)
        assertEquals(3, result.hops)
        assertTrue(result.linkReused)
        assertEquals(10000000L, result.nextHopBitrateBps)
        assertEquals(1196, result.linkMtu)
        assertNull(result.error)
    }

    @Test
    fun `fromMap handles missing optional fields`() {
        val map = mapOf<String, Any?>(
            "status" to "no_path",
            "link_reused" to false,
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals("no_path", result.status)
        assertNull(result.establishmentRateBps)
        assertNull(result.expectedRateBps)
        assertNull(result.rttSeconds)
        assertNull(result.hops)
        assertFalse(result.linkReused)
        assertNull(result.nextHopBitrateBps)
        assertNull(result.linkMtu)
        assertNull(result.error)
    }

    @Test
    fun `fromMap handles error field`() {
        val map = mapOf<String, Any?>(
            "status" to "error",
            "error" to "Connection failed",
            "link_reused" to false,
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals("error", result.status)
        assertEquals("Connection failed", result.error)
    }

    @Test
    fun `fromMap defaults status to error when missing`() {
        val map = mapOf<String, Any?>(
            "link_reused" to false,
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals("error", result.status)
    }

    @Test
    fun `fromMap handles Int numbers for Long fields`() {
        val map = mapOf<String, Any?>(
            "status" to "success",
            "establishment_rate_bps" to 100000, // Int, not Long
            "expected_rate_bps" to 1000000, // Int, not Long
            "hops" to 3,
            "link_reused" to true,
            "next_hop_bitrate_bps" to 10000000, // Int, not Long
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals(100000L, result.establishmentRateBps)
        assertEquals(1000000L, result.expectedRateBps)
        assertEquals(10000000L, result.nextHopBitrateBps)
    }

    @Test
    fun `fromMap handles Double for rttSeconds`() {
        val map = mapOf<String, Any?>(
            "status" to "success",
            "rtt_seconds" to 0.123456789,
            "link_reused" to false,
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals(0.123456789, result.rttSeconds!!, 0.000000001)
    }

    // ========== Real-world Scenario Tests ==========

    @Test
    fun `WiFi scenario - uses expectedRate from prior transfer`() {
        // Simulates WiFi where establishment_rate is artificially low
        // but expected_rate from prior transfer shows actual throughput
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 36_000L, // 36 kbps - artificially low establishment
            expectedRateBps = 14_711_025L, // 14.7 Mbps - actual measured throughput
            rttSeconds = 0.017,
            hops = 2,
            linkReused = true,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps interface
        )

        // Should use expectedRate (14.7 Mbps) not establishment (36 kbps) or interface (10 Mbps)
        assertEquals(14_711_025L, result.bestRateBps)
    }

    @Test
    fun `First connection scenario - uses interface bitrate when no prior transfer`() {
        // First time connecting - no expected_rate yet
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 36_000L, // 36 kbps - artificially low
            expectedRateBps = null, // No prior transfer
            rttSeconds = 0.017,
            hops = 2,
            linkReused = false,
            nextHopBitrateBps = 10_000_000L, // 10 Mbps interface
        )

        // Should use interface bitrate (10 Mbps) since it's higher than establishment
        assertEquals(10_000_000L, result.bestRateBps)
    }

    @Test
    fun `LoRa scenario - uses establishment rate when no interface bitrate`() {
        // LoRa link where interface bitrate may not be available
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 1_200L, // 1.2 kbps typical LoRa
            expectedRateBps = null,
            rttSeconds = 2.5,
            hops = 1,
            linkReused = false,
            nextHopBitrateBps = null, // Not available for LoRa
        )

        assertEquals(1_200L, result.bestRateBps)
    }

    @Test
    fun `Propagated mode with backchannel expected_rate`() {
        // Using propagated delivery but have expected_rate from backchannel
        val result = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 50_000L, // Establishment to propagation node
            expectedRateBps = 8_000_000L, // Measured from prior transfer with peer
            rttSeconds = 0.5,
            hops = 3,
            linkReused = true,
            nextHopBitrateBps = 10_000_000L,
        )

        // Should prefer expected_rate (actual measured) over interface capability
        assertEquals(8_000_000L, result.bestRateBps)
    }

    // ========== linkMtu Tests ==========

    @Test
    fun `fromMap parses linkMtu correctly`() {
        val map = mapOf<String, Any?>(
            "status" to "success",
            "link_mtu" to 1196, // AutoInterface MTU
            "link_reused" to false,
        )

        val result = LinkSpeedProbeResult.fromMap(map)

        assertEquals(1196, result.linkMtu)
    }

    @Test
    fun `linkMtu values for different interface types`() {
        // Basic MTU (default Reticulum.MTU)
        val basicResult = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 1_200L,
            expectedRateBps = null,
            rttSeconds = 2.5,
            hops = 1,
            linkReused = false,
            linkMtu = 500, // Basic MTU
        )
        assertEquals(500, basicResult.linkMtu)

        // AutoInterface MTU (WiFi/LAN)
        val wifiResult = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 36_000L,
            expectedRateBps = 14_711_025L,
            rttSeconds = 0.017,
            hops = 2,
            linkReused = true,
            nextHopBitrateBps = 10_000_000L,
            linkMtu = 1196, // AutoInterface MTU
        )
        assertEquals(1196, wifiResult.linkMtu)

        // BackboneInterface MTU (TCP)
        val backboneResult = LinkSpeedProbeResult(
            status = "success",
            establishmentRateBps = 100_000_000L,
            expectedRateBps = null,
            rttSeconds = 0.001,
            hops = 1,
            linkReused = false,
            linkMtu = 1_048_576, // 1 MB - BackboneInterface
        )
        assertEquals(1_048_576, backboneResult.linkMtu)
    }
}
