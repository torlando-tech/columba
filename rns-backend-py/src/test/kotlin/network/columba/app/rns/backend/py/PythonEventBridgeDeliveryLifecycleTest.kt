package network.columba.app.rns.backend.py

import network.columba.app.rns.api.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PythonEventBridgeDeliveryLifecycleTest {
    @Test
    fun `delivered state overrides propagated method after shared-object fallback repack`() {
        assertEquals(
            DeliveryStatus.DELIVERED,
            PythonEventBridge.lxmfDeliveryStatus(state = 0x08, method = 0x03, desired = 0x03),
        )
    }

    @Test
    fun `sent propagated acceptance remains propagated`() {
        assertEquals(
            DeliveryStatus.PROPAGATED,
            PythonEventBridge.lxmfDeliveryStatus(state = 0x04, method = 0x03, desired = 0x03),
        )
    }
}
