package network.columba.app.rns.host.ble.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BleConstantsTest {
    @Test
    fun `minimum ATT MTU exposes only characteristic payload bytes`() {
        assertEquals(23, BleConstants.MIN_MTU)
        assertEquals(20, BleConstants.MIN_USABLE_MTU)
    }

    @Test
    fun `raw ATT MTU is normalized to characteristic value capacity`() {
        assertEquals(20, BleConstants.usableValueLength(23))
        assertEquals(182, BleConstants.usableValueLength(185))
        assertEquals(244, BleConstants.usableValueLength(247))
        assertEquals(512, BleConstants.usableValueLength(517))
    }
}
