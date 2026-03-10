package com.lxmf.messenger.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewModelDedupTest {
    @Test
    fun `deduplicateContactMarkersByDestination keeps latest marker per destination hash`() {
        val oldUpper =
            ContactMarker(
                destinationHash = "ABC123",
                displayName = "Old",
                latitude = 1.0,
                longitude = 1.0,
                timestamp = 1000L,
            )
        val newLower =
            ContactMarker(
                destinationHash = "abc123",
                displayName = "New",
                latitude = 2.0,
                longitude = 2.0,
                timestamp = 2000L,
            )
        val other =
            ContactMarker(
                destinationHash = "def456",
                displayName = "Other",
                latitude = 3.0,
                longitude = 3.0,
                timestamp = 1500L,
            )

        val result = deduplicateContactMarkersByDestination(listOf(oldUpper, newLower, other))

        assertEquals(2, result.size)
        assertTrue(result.any { it.destinationHash.equals("abc123", ignoreCase = true) && it.timestamp == 2000L })
        assertTrue(result.any { it.destinationHash == "def456" && it.timestamp == 1500L })
    }
}
