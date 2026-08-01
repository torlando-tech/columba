package network.columba.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterfaceStatsSnapshotTest {
    @Test
    fun `returns traffic counters for configured interface from resilient snapshot`() {
        val interfaces =
            listOf(
                mapOf(
                    "name" to "mac",
                    "online" to true,
                    "rx_bytes" to 12_345L,
                    "tx_bytes" to 6_789L,
                ),
                mapOf(
                    "name" to "BLEPeerInterface[peer]",
                    "online" to true,
                    "rx_bytes" to 42L,
                    "tx_bytes" to 24L,
                ),
            )

        assertEquals(
            mapOf(
                "online" to true,
                "rxb" to 12_345L,
                "txb" to 6_789L,
            ),
            interfaceStatsFromSnapshot(interfaces, "mac"),
        )
    }

    @Test
    fun `returns null when configured interface is absent`() {
        assertNull(interfaceStatsFromSnapshot(emptyList(), "mac"))
    }
}
