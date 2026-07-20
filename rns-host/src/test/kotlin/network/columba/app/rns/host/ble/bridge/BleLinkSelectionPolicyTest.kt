package network.columba.app.rns.host.ble.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BleLinkSelectionPolicyTest {
    @Test
    fun `larger usable MTU wins regardless of identity ordering`() {
        assertEquals(
            PreferredBleRole.CENTRAL,
            preferredBleRole(
                centralMtu = 512,
                peripheralMtu = 20,
                localIdentity = "ff",
                peerIdentity = "00",
            ),
        )
        assertEquals(
            PreferredBleRole.PERIPHERAL,
            preferredBleRole(
                centralMtu = 20,
                peripheralMtu = 512,
                localIdentity = "00",
                peerIdentity = "ff",
            ),
        )
    }

    @Test
    fun `equal MTUs use deterministic identity role tie break`() {
        assertEquals(
            PreferredBleRole.CENTRAL,
            preferredBleRole(244, 244, localIdentity = "00", peerIdentity = "ff"),
        )
        assertEquals(
            PreferredBleRole.PERIPHERAL,
            preferredBleRole(244, 244, localIdentity = "ff", peerIdentity = "00"),
        )
    }
}
