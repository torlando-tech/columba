package com.lxmf.messenger.ui.screens

import com.lxmf.messenger.viewmodel.ContactMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapScreenDeclutterTest {
    @Test
    fun `deduplicateMarkersByDestination keeps newest marker per hash case-insensitively`() {
        val older =
            ContactMarker(
                destinationHash = "ABCDEF1234",
                displayName = "Old",
                latitude = 44.0,
                longitude = -0.7,
                timestamp = 1000L,
            )
        val newer =
            ContactMarker(
                destinationHash = "abcdef1234",
                displayName = "New",
                latitude = 45.0,
                longitude = -0.8,
                timestamp = 2000L,
            )
        val other =
            ContactMarker(
                destinationHash = "otherhash",
                displayName = "Other",
                latitude = 46.0,
                longitude = -0.9,
                timestamp = 1500L,
            )

        val result = deduplicateMarkersByDestination(listOf(older, newer, other))

        assertEquals(2, result.size)
        assertTrue(result.any { it.destinationHash.equals("abcdef1234", ignoreCase = true) && it.timestamp == 2000L })
        assertTrue(result.any { it.destinationHash == "otherhash" && it.timestamp == 1500L })
    }
}
