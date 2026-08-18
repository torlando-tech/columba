package network.columba.app.rns.api.model

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryStatusEventStreamTest {
    @Test
    fun `late subscriber receives the bounded replay window in publication order`() = runTest {
        val stream = DeliveryStatusEventStream(replay = 2)
        stream.publish(DeliveryStatusUpdate("one", DeliveryStatus.PENDING, 1L))
        stream.publish(DeliveryStatusUpdate("two", DeliveryStatus.RETRYING_PROPAGATED, 2L))
        stream.publish(DeliveryStatusUpdate("three", DeliveryStatus.PROPAGATED, 3L))

        assertEquals(listOf("two", "three"), stream.events.replayCache.map { it.messageHash })
        assertEquals("two", stream.events.first().messageHash)
    }
}
