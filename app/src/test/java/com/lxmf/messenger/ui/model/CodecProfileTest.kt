package com.lxmf.messenger.ui.model

import android.app.Application
import com.lxmf.messenger.reticulum.model.LinkSpeedProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for CodecProfile recommendation logic.
 *
 * Tests the conservative bandwidth estimation and codec profile
 * recommendation based on link speed probe results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CodecProfileTest {
    // ========== getConservativeBandwidthBps Tests ==========

    @Test
    fun `getConservativeBandwidthBps prefers expectedRateBps when available`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 50000L, // 50 kbps - measured throughput
                establishmentRateBps = 100000L, // 100 kbps - higher establishment rate
                nextHopBitrateBps = 1000000L, // 1 Mbps - even higher interface rate
                rttSeconds = 0.1,
                hops = 1,
                linkReused = true,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        assertEquals(50000L, result)
    }

    @Test
    fun `getConservativeBandwidthBps uses min of establishment and nextHop when no expectedRate`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null, // No measured throughput
                establishmentRateBps = 20000L, // 20 kbps
                nextHopBitrateBps = 50000L, // 50 kbps - higher next hop rate
                rttSeconds = 0.2,
                hops = 2,
                linkReused = false,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        // Should use min(20000, 50000) = 20000
        assertEquals(20000L, result)
    }

    @Test
    fun `getConservativeBandwidthBps uses min when nextHop is lower than establishment`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = 100000L, // 100 kbps
                nextHopBitrateBps = 5000L, // 5 kbps - slow next hop (e.g., BLE)
                rttSeconds = 0.5,
                hops = 1,
                linkReused = false,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        // Should use min(100000, 5000) = 5000 (slow next hop is limiting)
        assertEquals(5000L, result)
    }

    @Test
    fun `getConservativeBandwidthBps returns establishment when no nextHop`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = 30000L, // 30 kbps
                nextHopBitrateBps = null, // No next hop info
                rttSeconds = 0.15,
                hops = 3,
                linkReused = false,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        assertEquals(30000L, result)
    }

    @Test
    fun `getConservativeBandwidthBps returns nextHop when no establishment`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = null, // No establishment rate
                nextHopBitrateBps = 10000L, // 10 kbps
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        assertEquals(10000L, result)
    }

    @Test
    fun `getConservativeBandwidthBps returns null when no metrics`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        assertNull(result)
    }

    @Test
    fun `getConservativeBandwidthBps ignores zero values`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 0L, // Zero should be ignored
                establishmentRateBps = 0L, // Zero should be ignored
                nextHopBitrateBps = 25000L, // 25 kbps - only valid value
                rttSeconds = 0.1,
                hops = 1,
                linkReused = false,
            )

        val result = CodecProfile.getConservativeBandwidthBps(probe)

        assertEquals(25000L, result)
    }

    // ========== recommendFromProbe Tests ==========

    @Test
    fun `recommendFromProbe returns BANDWIDTH_ULTRA_LOW for very slow links`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 1000L, // 1 kbps - very slow
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = 1.0,
                hops = 5,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, result)
    }

    @Test
    fun `recommendFromProbe returns BANDWIDTH_VERY_LOW for slow links`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 3000L, // 3 kbps - slow (between 1.5 and 4 kbps)
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = 0.5,
                hops = 3,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, result)
    }

    @Test
    fun `recommendFromProbe returns BANDWIDTH_LOW for limited bandwidth`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 8000L, // 8 kbps - limited (between 4 and 10 kbps)
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = 0.3,
                hops = 2,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.BANDWIDTH_LOW, result)
    }

    @Test
    fun `recommendFromProbe returns QUALITY_MEDIUM for moderate bandwidth`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 25000L, // 25 kbps - moderate (between 10 and 32 kbps)
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = 0.1,
                hops = 1,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.QUALITY_MEDIUM, result)
    }

    @Test
    fun `recommendFromProbe returns QUALITY_HIGH for good bandwidth`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 50000L, // 50 kbps - good (between 32 and 64 kbps)
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = 0.05,
                hops = 1,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.QUALITY_HIGH, result)
    }

    @Test
    fun `recommendFromProbe returns QUALITY_MAX for fast links`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 100000L, // 100 kbps - fast (> 64 kbps)
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = 0.02,
                hops = 1,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.QUALITY_MAX, result)
    }

    @Test
    fun `recommendFromProbe returns DEFAULT when probe has no data`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.DEFAULT, result)
    }

    @Test
    fun `recommendFromProbe returns DEFAULT for failed probe`() {
        val probe =
            LinkSpeedProbeResult(
                status = "error",
                expectedRateBps = null,
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
                error = "Connection timeout",
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.DEFAULT, result)
    }

    // ========== Boundary Value Tests ==========

    @Test
    fun `recommendFromProbe at 1500 bps threshold recommends BANDWIDTH_VERY_LOW`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 1500L, // Exactly at threshold
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, result)
    }

    @Test
    fun `recommendFromProbe at 4000 bps threshold recommends BANDWIDTH_LOW`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 4000L, // Exactly at threshold
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.BANDWIDTH_LOW, result)
    }

    @Test
    fun `recommendFromProbe at 10000 bps threshold recommends QUALITY_MEDIUM`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 10000L, // Exactly at threshold
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.QUALITY_MEDIUM, result)
    }

    @Test
    fun `recommendFromProbe at 32000 bps threshold recommends QUALITY_HIGH`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 32000L, // Exactly at threshold
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.QUALITY_HIGH, result)
    }

    @Test
    fun `recommendFromProbe at 64000 bps threshold recommends QUALITY_MAX`() {
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 64000L, // Exactly at threshold
                establishmentRateBps = null,
                nextHopBitrateBps = null,
                rttSeconds = null,
                hops = null,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        assertEquals(CodecProfile.QUALITY_MAX, result)
    }

    // ========== Integration-style Tests ==========

    @Test
    fun `typical BLE link recommends low bandwidth codec`() {
        // BLE typically has ~5 kbps effective throughput
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = 4000L,
                nextHopBitrateBps = 5000L, // BLE interface
                rttSeconds = 0.5,
                hops = 1,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        // min(4000, 5000) = 4000 -> BANDWIDTH_LOW
        assertEquals(CodecProfile.BANDWIDTH_LOW, result)
    }

    @Test
    fun `TCP with slow intermediate hop recommends conservative codec`() {
        // Fast TCP next hop but slow establishment rate indicates slow intermediate hops
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = null,
                establishmentRateBps = 8000L, // Slow establishment (slow intermediate hop)
                nextHopBitrateBps = 1000000L, // 1 Mbps TCP interface
                rttSeconds = 0.8,
                hops = 4,
                linkReused = false,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        // min(8000, 1000000) = 8000 -> BANDWIDTH_LOW
        assertEquals(CodecProfile.BANDWIDTH_LOW, result)
    }

    @Test
    fun `fast local TCP link recommends high quality codec`() {
        // Fast TCP with measured throughput
        val probe =
            LinkSpeedProbeResult(
                status = "success",
                expectedRateBps = 500000L, // 500 kbps measured
                establishmentRateBps = 100000L,
                nextHopBitrateBps = 10000000L, // 10 Mbps interface
                rttSeconds = 0.01,
                hops = 1,
                linkReused = true,
            )

        val result = CodecProfile.recommendFromProbe(probe)

        // Uses expectedRateBps = 500000 -> QUALITY_MAX
        assertEquals(CodecProfile.QUALITY_MAX, result)
    }

    // ========== Existing CodecProfile Tests ==========

    @Test
    fun `CodecProfile DEFAULT is QUALITY_MEDIUM`() {
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.DEFAULT)
    }

    @Test
    fun `CodecProfile fromCode returns correct profile`() {
        assertEquals(CodecProfile.BANDWIDTH_ULTRA_LOW, CodecProfile.fromCode(0x10))
        assertEquals(CodecProfile.BANDWIDTH_VERY_LOW, CodecProfile.fromCode(0x20))
        assertEquals(CodecProfile.BANDWIDTH_LOW, CodecProfile.fromCode(0x30))
        assertEquals(CodecProfile.QUALITY_MEDIUM, CodecProfile.fromCode(0x40))
        assertEquals(CodecProfile.QUALITY_HIGH, CodecProfile.fromCode(0x50))
        assertEquals(CodecProfile.QUALITY_MAX, CodecProfile.fromCode(0x60))
        assertEquals(CodecProfile.LATENCY_LOW, CodecProfile.fromCode(0x80))
        assertEquals(CodecProfile.LATENCY_ULTRA_LOW, CodecProfile.fromCode(0x70))
    }

    @Test
    fun `CodecProfile fromCode returns null for invalid code`() {
        assertNull(CodecProfile.fromCode(0x00))
        assertNull(CodecProfile.fromCode(0xFF))
        assertNull(CodecProfile.fromCode(-1))
    }

    @Test
    fun `each CodecProfile has unique code`() {
        val codes = CodecProfile.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `each CodecProfile has displayName and description`() {
        CodecProfile.entries.forEach { profile ->
            assertTrue("displayName should not be blank", profile.displayName.isNotBlank())
            assertTrue("description should not be blank", profile.description.isNotBlank())
        }
    }

    // ========== Experimental Flag Tests ==========

    @Test
    fun `LATENCY_LOW and LATENCY_ULTRA_LOW are experimental`() {
        assertTrue(CodecProfile.LATENCY_LOW.isExperimental)
        assertTrue(CodecProfile.LATENCY_ULTRA_LOW.isExperimental)
    }

    @Test
    fun `non-latency profiles are not experimental`() {
        CodecProfile.entries
            .filter { it != CodecProfile.LATENCY_LOW && it != CodecProfile.LATENCY_ULTRA_LOW }
            .forEach { assertFalse("${it.name} should not be experimental", it.isExperimental) }
    }
}
