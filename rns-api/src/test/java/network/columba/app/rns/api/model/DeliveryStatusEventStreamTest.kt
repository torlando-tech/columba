package network.columba.app.rns.api.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryStatusEventStreamTest {
    @Test
    fun `late subscriber does not treat advisory IPC deltas as canonical replay state`() = runTest {
        val stream = DeliveryStatusEventStream()
        stream.publish(DeliveryStatusUpdate("one", DeliveryStatus.PENDING, 1L))
        assertEquals(emptyList<DeliveryStatusUpdate>(), stream.events.replayCache)

        val next = async { stream.events.first() }
        runCurrent()
        stream.publish(DeliveryStatusUpdate("two", DeliveryStatus.DELIVERED, 2L))
        assertEquals("two", next.await().messageHash)
    }
}
