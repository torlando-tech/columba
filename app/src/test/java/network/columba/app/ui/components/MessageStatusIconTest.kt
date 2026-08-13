package network.columba.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the typed visual indicator selected for each outgoing message state. */
class MessageStatusIconTest {
    @Test
    fun `pending status shows hollow circle`() {
        assertEquals(MessageStatusIndicatorModel.Glyph("○"), getMessageStatusIndicator("pending"))
    }

    @Test
    fun `sent status shows single checkmark`() {
        assertEquals(MessageStatusIndicatorModel.Glyph("✓"), getMessageStatusIndicator("sent"))
    }

    @Test
    fun `relay submission uses cloud upload`() {
        assertEquals(MessageStatusIndicatorModel.RelayUploading, getMessageStatusIndicator("retrying_propagated"))
    }

    @Test
    fun `completed relay acceptance uses cloud done`() {
        assertEquals(MessageStatusIndicatorModel.RelayStored, getMessageStatusIndicator("propagated"))
    }

    @Test
    fun `delivered status shows double checkmark`() {
        assertEquals(MessageStatusIndicatorModel.Glyph("✓✓"), getMessageStatusIndicator("delivered"))
    }

    @Test
    fun `failed status shows exclamation`() {
        assertEquals(MessageStatusIndicatorModel.Glyph("!"), getMessageStatusIndicator("failed"))
    }

    @Test
    fun `unknown statuses have no indicator`() {
        assertEquals(MessageStatusIndicatorModel.None, getMessageStatusIndicator("unknown"))
        assertEquals(MessageStatusIndicatorModel.None, getMessageStatusIndicator(""))
        assertEquals(MessageStatusIndicatorModel.None, getMessageStatusIndicator("null"))
    }

    @Test
    fun `propagation fallback has distinct pending accepted and delivered indicators`() {
        assertEquals(MessageStatusIndicatorModel.RelayUploading, getMessageStatusIndicator("retrying_propagated"))
        assertEquals(MessageStatusIndicatorModel.RelayStored, getMessageStatusIndicator("propagated"))
        assertEquals(MessageStatusIndicatorModel.Glyph("✓✓"), getMessageStatusIndicator("delivered"))
    }
}
