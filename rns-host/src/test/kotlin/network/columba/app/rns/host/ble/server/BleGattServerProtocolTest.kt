package network.columba.app.rns.host.ble.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattServerProtocolTest {
    @Test
    fun `unidentified peer must write exactly one transport identity`() {
        assertTrue(acceptsRxWrite(hasIdentity = false, valueSize = 16))
        assertFalse(acceptsRxWrite(hasIdentity = false, valueSize = 0))
        assertFalse(acceptsRxWrite(hasIdentity = false, valueSize = 15))
        assertFalse(acceptsRxWrite(hasIdentity = false, valueSize = 17))
        assertFalse(acceptsRxWrite(hasIdentity = false, valueSize = 64))
    }

    @Test
    fun `identified peer may write application payload lengths`() {
        assertTrue(acceptsRxWrite(hasIdentity = true, valueSize = 0))
        assertTrue(acceptsRxWrite(hasIdentity = true, valueSize = 16))
        assertTrue(acceptsRxWrite(hasIdentity = true, valueSize = 512))
    }
}
