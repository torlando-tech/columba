package network.columba.app.rns.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryStatusTest {
    @Test
    fun `wire values are closed and round trip exactly`() {
        val expected =
            mapOf(
                "pending" to DeliveryStatus.PENDING,
                "retrying_propagated" to DeliveryStatus.RETRYING_PROPAGATED,
                "propagated" to DeliveryStatus.PROPAGATED,
                "delivered" to DeliveryStatus.DELIVERED,
                "failed" to DeliveryStatus.FAILED,
            )

        expected.forEach { (wireValue, status) ->
            assertEquals(status, DeliveryStatus.fromWireValue(wireValue))
            assertEquals(wireValue, status.wireValue)
        }
        assertNull(DeliveryStatus.fromWireValue("sent"))
        assertNull(DeliveryStatus.fromWireValue("unknown"))
    }
}
