package network.columba.app.rns.api.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressUpdateTest {
    @Test
    fun `outgoing resource progress is keyed by message hash`() {
        val update = TransferProgressUpdate(
            transferId = "resource-1",
            messageHash = "aabbcc",
            direction = Direction.OUT,
            progress = 0.64f,
            phase = TransferPhase.TRANSFERRING,
            totalBytes = 4_800_000L,
            deliveryMethod = DeliveryMethod.DIRECT,
            currentAttempt = 2,
            maxAttempts = 5,
        )

        assertEquals("aabbcc", update.messageHash)
        assertEquals(0.64f, update.progress)
        assertEquals(Direction.OUT, update.direction)
        assertEquals(2, update.currentAttempt)
        assertEquals(5, update.maxAttempts)
    }

    @Test
    fun `incoming resource progress does not claim a message identity`() {
        val update = TransferProgressUpdate(
            transferId = "resource-2",
            messageHash = null,
            sourceDestinationHash = "deadbeef",
            direction = Direction.IN,
            progress = 0.38f,
            phase = TransferPhase.TRANSFERRING,
            totalBytes = 2_100_000L,
        )

        assertNull(update.messageHash)
        assertEquals("deadbeef", update.sourceDestinationHash)
        assertEquals(Direction.IN, update.direction)
        assertTrue(update.isIncomingForConversation("DEADBEEF"))
        assertFalse(update.isIncomingForConversation("cafebabe"))
        assertFalse(update.copy(sourceDestinationHash = null).isIncomingForConversation("deadbeef"))
    }
}
