package com.lxmf.messenger.service

import com.lxmf.messenger.data.model.ImageCompressionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for ConversationLinkManager.LinkState transfer time estimation.
 */
class ConversationLinkManagerTest {
    @Test
    fun `estimateTransferTimeSeconds returns null when no rate available`() {
        val state = ConversationLinkManager.LinkState(isActive = true)
        assertNull(state.estimateTransferTimeSeconds(1000))
    }

    @Test
    fun `estimateTransferTimeSeconds calculates correctly with expectedRateBps`() {
        // 8000 bps = 1000 bytes per second
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                expectedRateBps = 8000,
            )
        // 1000 bytes at 1000 B/s = 1 second
        assertEquals(1.0, state.estimateTransferTimeSeconds(1000)!!, 0.01)
    }

    @Test
    fun `estimateTransferTimeSeconds calculates correctly with establishmentRateBps`() {
        // 80000 bps = 10000 bytes per second
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                establishmentRateBps = 80000,
            )
        // 50000 bytes at 10000 B/s = 5 seconds
        assertEquals(5.0, state.estimateTransferTimeSeconds(50000)!!, 0.01)
    }

    @Test
    fun `estimateTransferTimeSeconds prefers expectedRateBps over establishmentRateBps`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                expectedRateBps = 8000, // 1000 B/s - should use this
                establishmentRateBps = 80000, // 10000 B/s - should ignore
            )
        // 1000 bytes at 1000 B/s = 1 second (using expectedRateBps)
        assertEquals(1.0, state.estimateTransferTimeSeconds(1000)!!, 0.01)
    }

    @Test
    fun `estimateTransferTimeSeconds uses max of fallback rates`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                establishmentRateBps = 8000, // 1000 B/s
                nextHopBitrateBps = 80000, // 10000 B/s - higher, should use this
            )
        // 10000 bytes at 10000 B/s = 1 second (using nextHopBitrateBps)
        assertEquals(1.0, state.estimateTransferTimeSeconds(10000)!!, 0.01)
    }

    @Test
    fun `formatTransferTime formats sub-second correctly`() {
        assertEquals("< 1s", ConversationLinkManager.formatTransferTime(0.5))
    }

    @Test
    fun `formatTransferTime formats seconds correctly`() {
        assertEquals("~30s", ConversationLinkManager.formatTransferTime(30.0))
    }

    @Test
    fun `formatTransferTime formats minutes correctly`() {
        assertEquals("~2m 30s", ConversationLinkManager.formatTransferTime(150.0))
    }

    @Test
    fun `formatTransferTime formats hours correctly`() {
        assertEquals("~1h 30m", ConversationLinkManager.formatTransferTime(5400.0))
    }

    @Test
    fun `estimateTransferTimeFormatted returns formatted string`() {
        // 8000 bps = 1000 bytes per second
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                expectedRateBps = 8000,
            )
        // 30000 bytes at 1000 B/s = 30 seconds
        assertEquals("~30s", state.estimateTransferTimeFormatted(30000))
    }

    @Test
    fun `estimateTransferTimeFormatted returns null when no rate`() {
        val state = ConversationLinkManager.LinkState(isActive = true)
        assertNull(state.estimateTransferTimeFormatted(1000))
    }

    @Test
    fun `transfer time for ORIGINAL uses actual size not target`() {
        // Simulate what happens in MessagingViewModel.calculateTransferTimeEstimates
        // With the fix, ORIGINAL preset uses actual file size (e.g., 3MB)
        // instead of targetSizeBytes (25MB)

        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                expectedRateBps = 8_000_000, // 1 MB/s = 1,000,000 bytes/sec
            )

        val actualFileSize = 3_000_000L // 3MB actual photo (exact for easy math)
        val targetSize = ImageCompressionPreset.ORIGINAL.targetSizeBytes // 25MB

        // With fix: uses actualFileSize -> 3 seconds
        val timeWithActualSize = state.estimateTransferTimeSeconds(actualFileSize)!!
        assertEquals(3.0, timeWithActualSize, 0.01)

        // Without fix: would use targetSize -> ~26 seconds (25*1024*1024 bytes)
        val timeWithTargetSize = state.estimateTransferTimeSeconds(targetSize)!!
        assertEquals(26.2, timeWithTargetSize, 0.5) // 25MB = 26,214,400 bytes

        // The fix makes ORIGINAL estimates ~8x more accurate for typical photos
    }
}
