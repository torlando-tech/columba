package network.columba.app.nomadnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NomadNetImagePolicy.shouldLoadAutomatically] — the auto-load
 * bandwidth gate (upstream NomadNet `should_load_images`, commit 0db7d4b).
 */
class NomadNetImagePolicyTest {
    @Test
    fun `loopback always loads regardless of mode`() {
        assertTrue(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.NEVER,
                isLoopback = true,
                rttSeconds = null,
                expectedRateBps = null,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `never mode never auto-loads`() {
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.NEVER,
                isLoopback = false,
                rttSeconds = 0.1,
                expectedRateBps = 100_000L,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `manual mode never auto-loads even on a fast link`() {
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.MANUAL,
                isLoopback = false,
                rttSeconds = 0.1,
                expectedRateBps = 100_000L,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `always mode auto-loads without any link data`() {
        assertTrue(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.ALWAYS,
                isLoopback = false,
                rttSeconds = null,
                expectedRateBps = null,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `auto fast link with sufficient rate loads`() {
        assertTrue(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = 0.2,
                expectedRateBps = 50_000L,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `auto slow rtt does not load`() {
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = 2.0,
                expectedRateBps = 50_000L,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `auto low rate does not load`() {
        // 9k6-class LoRa links (~9600 bps) must never spend airtime on images.
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = 0.2,
                expectedRateBps = 9_600L,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `auto without any link data does not load`() {
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = null,
                expectedRateBps = null,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `auto falls back to measured page response speed when no expected rate`() {
        assertTrue(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = 0.2,
                expectedRateBps = null,
                measuredResponseSpeedBps = 25_000.0,
            ),
        )
    }

    @Test
    fun `auto rtt exactly at the limit does not load`() {
        // Gate is strictly `< 1.5 s`.
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = NomadNetImagePolicy.AUTO_RTT_LIMIT_S,
                expectedRateBps = 50_000L,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `auto rate exactly at the limit does not load`() {
        // Gate is strictly `> 10 000 bps`.
        assertFalse(
            NomadNetImagePolicy.shouldLoadAutomatically(
                mode = ImageLoadingMode.AUTO,
                isLoopback = false,
                rttSeconds = 0.2,
                expectedRateBps = NomadNetImagePolicy.AUTO_EDR_LIMIT_BPS,
                measuredResponseSpeedBps = null,
            ),
        )
    }

    @Test
    fun `fromName maps config values case-insensitively with auto default`() {
        assertEquals(ImageLoadingMode.MANUAL, ImageLoadingMode.fromName("manual"))
        assertEquals(ImageLoadingMode.ALWAYS, ImageLoadingMode.fromName(" Always "))
        assertEquals(ImageLoadingMode.AUTO, ImageLoadingMode.fromName(null))
        assertEquals(ImageLoadingMode.AUTO, ImageLoadingMode.fromName("garbage"))
    }
}
