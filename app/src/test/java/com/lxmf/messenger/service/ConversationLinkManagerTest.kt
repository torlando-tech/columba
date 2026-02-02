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
    fun `estimateTransferTimeSeconds prefers establishmentRateBps over nextHopBitrateBps`() {
        // Conservative approach: establishment rate measures actual path, nextHop is just first hop
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                establishmentRateBps = 8000, // 1000 B/s - should use this (measures full path)
                nextHopBitrateBps = 80000, // 10000 B/s - should ignore (only first hop)
            )
        // 1000 bytes at 1000 B/s = 1 second (using establishmentRateBps)
        assertEquals(1.0, state.estimateTransferTimeSeconds(1000)!!, 0.01)
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

    // ========== recommendPreset() tests ==========

    @Test
    fun `recommendPreset returns MEDIUM for slow interface under 50kbps`() {
        // LoRa/BLE-like interface at 10 kbps (in MEDIUM range: 5-50 kbps)
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 10_000, // 10 kbps - slow interface
            )
        assertEquals(ImageCompressionPreset.MEDIUM, state.recommendPreset())
    }

    @Test
    fun `recommendPreset returns LOW for very slow interface under 5kbps`() {
        // Very slow LoRa interface at 2 kbps
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 2_000, // 2 kbps - very slow
            )
        assertEquals(ImageCompressionPreset.LOW, state.recommendPreset())
    }

    @Test
    fun `recommendPreset prefers link rate over slow interface`() {
        // Slow interface but we have actual link measurements
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 40_000, // 40 kbps - slow interface
                establishmentRateBps = 20_000, // 20 kbps - actual measured path
            )
        // Should use establishmentRateBps (20k) -> MEDIUM (5-50 kbps range)
        assertEquals(ImageCompressionPreset.MEDIUM, state.recommendPreset())
    }

    @Test
    fun `recommendPreset trusts fast interface for single hop only when no link metrics`() {
        // Single hop with fast interface but NO link measurements
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 1_000_000, // 1 Mbps - fast interface
                hops = 1, // Single hop
                // No establishmentRateBps or expectedRateBps
            )
        // 1 Mbps > 500 kbps -> ORIGINAL (trusts interface when no measurements)
        assertEquals(ImageCompressionPreset.ORIGINAL, state.recommendPreset())
    }

    @Test
    fun `recommendPreset prefers link metrics over fast interface even for single hop`() {
        // Single hop with fast interface BUT we have actual measurements
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 1_000_000, // 1 Mbps - fast interface
                establishmentRateBps = 100_000, // 100 kbps - actual measured
                hops = 1,
            )
        // Should use establishmentRateBps (100 kbps) -> HIGH, not interface (1 Mbps -> ORIGINAL)
        assertEquals(ImageCompressionPreset.HIGH, state.recommendPreset())
    }

    @Test
    fun `recommendPreset uses link rate for multi-hop with fast first hop`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 1_000_000, // 1 Mbps first hop
                establishmentRateBps = 100_000, // 100 kbps measured path
                hops = 3, // Multi-hop
            )
        // Should use establishmentRateBps, not trust the fast first hop
        // 100 kbps is > 50k and < 500k -> HIGH
        assertEquals(ImageCompressionPreset.HIGH, state.recommendPreset())
    }

    @Test
    fun `recommendPreset returns MEDIUM for multi-hop with no link metrics`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                nextHopBitrateBps = 1_000_000, // 1 Mbps first hop
                hops = 3, // Multi-hop - can't trust fast first hop
                // No establishmentRateBps or expectedRateBps
            )
        // Conservative: can't trust fast first hop for multi-hop paths
        assertEquals(ImageCompressionPreset.MEDIUM, state.recommendPreset())
    }

    @Test
    fun `recommendPreset uses expectedRateBps when available`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                expectedRateBps = 600_000, // 600 kbps actual measured
                establishmentRateBps = 100_000, // ignored
                nextHopBitrateBps = 1_000_000, // ignored
                hops = 2,
            )
        // expectedRateBps is most accurate - 600 kbps > 500k -> ORIGINAL
        assertEquals(ImageCompressionPreset.ORIGINAL, state.recommendPreset())
    }

    @Test
    fun `recommendPreset falls back to hop count heuristics when no rate data`() {
        // Direct connection (1 hop), no rate info
        val state1 = ConversationLinkManager.LinkState(isActive = true, hops = 1)
        assertEquals(ImageCompressionPreset.HIGH, state1.recommendPreset())

        // 2-3 hops, no rate info
        val state2 = ConversationLinkManager.LinkState(isActive = true, hops = 2)
        assertEquals(ImageCompressionPreset.MEDIUM, state2.recommendPreset())

        // Many hops, no rate info
        val state3 = ConversationLinkManager.LinkState(isActive = true, hops = 5)
        assertEquals(ImageCompressionPreset.LOW, state3.recommendPreset())
    }

    @Test
    fun `recommendPreset returns LOW on connection error`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = false,
                error = "Connection failed",
            )
        assertEquals(ImageCompressionPreset.LOW, state.recommendPreset())
    }

    @Test
    fun `recommendPreset returns MEDIUM when no info available`() {
        val state = ConversationLinkManager.LinkState(isActive = true)
        assertEquals(ImageCompressionPreset.MEDIUM, state.recommendPreset())
    }

    // ========== Transfer time tests ==========

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

    // ========== lastActivityTimestamp tests ==========

    @Test
    fun `lastActivityTimestamp defaults to zero`() {
        val state = ConversationLinkManager.LinkState(isActive = true)
        assertEquals(0L, state.lastActivityTimestamp)
    }

    @Test
    fun `lastActivityTimestamp can be set during construction`() {
        val timestamp = System.currentTimeMillis()
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                lastActivityTimestamp = timestamp,
            )
        assertEquals(timestamp, state.lastActivityTimestamp)
    }

    @Test
    fun `lastActivityTimestamp is preserved when copying state`() {
        val originalTimestamp = 1234567890L
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                lastActivityTimestamp = originalTimestamp,
            )

        // Copy with different isActive but same timestamp
        val copied = state.copy(isActive = false)
        assertEquals(originalTimestamp, copied.lastActivityTimestamp)
    }

    @Test
    fun `lastActivityTimestamp can be updated via copy`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                lastActivityTimestamp = 1000L,
            )

        val newTimestamp = 2000L
        val updated = state.copy(lastActivityTimestamp = newTimestamp)

        assertEquals(newTimestamp, updated.lastActivityTimestamp)
        assertEquals(1000L, state.lastActivityTimestamp) // Original unchanged
    }

    @Test
    fun `LinkState with activity timestamp preserves other fields`() {
        val state =
            ConversationLinkManager.LinkState(
                isActive = true,
                establishmentRateBps = 50000L,
                expectedRateBps = 40000L,
                nextHopBitrateBps = 100000L,
                rttSeconds = 0.5,
                hops = 3,
                linkMtu = 500,
                isEstablishing = false,
                error = null,
                lastActivityTimestamp = 9999L,
            )

        assertEquals(true, state.isActive)
        assertEquals(50000L, state.establishmentRateBps)
        assertEquals(40000L, state.expectedRateBps)
        assertEquals(100000L, state.nextHopBitrateBps)
        assertEquals(0.5, state.rttSeconds!!, 0.001)
        assertEquals(3, state.hops)
        assertEquals(500, state.linkMtu)
        assertEquals(false, state.isEstablishing)
        assertNull(state.error)
        assertEquals(9999L, state.lastActivityTimestamp)
    }

    @Test
    fun `inactive state with recent activity timestamp is valid`() {
        // This represents a peer we received a message from but don't have an active link to
        val recentTimestamp = System.currentTimeMillis()
        val state =
            ConversationLinkManager.LinkState(
                isActive = false,
                lastActivityTimestamp = recentTimestamp,
            )

        assertEquals(false, state.isActive)
        assertEquals(recentTimestamp, state.lastActivityTimestamp)
    }

    // ========== Refresh cleanup tests (require mocking) ==========

    @Test
    fun `refreshAllLinkStatuses does not cleanup entry that becomes active during refresh`() =
        kotlinx.coroutines.test.runTest {
            // Given: A mock protocol that returns "active" when queried
            val mockProtocol = io.mockk.mockk<com.lxmf.messenger.reticulum.protocol.ReticulumProtocol>()
            io.mockk.coEvery { mockProtocol.getConversationLinkStatus(any()) } returns
                com.lxmf.messenger.reticulum.protocol.ConversationLinkResult(
                    isActive = true, // Peer established link to us!
                    establishmentRateBps = 100_000L,
                    expectedRateBps = null,
                    nextHopBitrateBps = null,
                    rttSeconds = 0.1,
                    hops = 1,
                    linkMtu = 500,
                )

            val manager = ConversationLinkManager(mockProtocol)

            // Set up an inactive, stale entry (would normally be cleaned up)
            val staleTimestamp = System.currentTimeMillis() - (20 * 60 * 1000L) // 20 min ago
            manager.updateLinkState(
                "abc123def456",
                ConversationLinkManager.LinkState(
                    isActive = false,
                    lastActivityTimestamp = staleTimestamp,
                ),
            )

            // When: Refresh runs (which queries protocol and finds link is now active)
            manager.refreshAllLinkStatuses()

            // Then: Entry should NOT be cleaned up - it's now active!
            val finalState = manager.getLinkState("abc123def456")
            org.junit.Assert.assertNotNull("Entry should not be cleaned up", finalState)
            org.junit.Assert.assertTrue("Entry should now be active", finalState!!.isActive)
        }
}
