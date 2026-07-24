package network.columba.app.ui.screens

import network.columba.app.rns.api.model.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for message status icon logic in MessagingScreen.
 *
 * Tests the visual indicator for message delivery state:
 * - Pending: hollow circle (○) - message created, waiting to send
 * - Sent: single check (✓) - transmitted to network
 * - RetryingViaPropagation: single check (✓) - retrying via propagation node
 * - Propagated: single check (✓) - stored on propagation relay
 * - Delivered: double check (✓✓) - delivered and acknowledged
 * - Failed: exclamation (!) - delivery failed
 * - null (unrecognized legacy DB string): empty
 */
class MessageStatusIconTest {
    // ==========================================
    // Pending Status Tests
    // ==========================================

    @Test
    fun `pending status shows hollow circle`() {
        assertEquals("○", getMessageStatusIcon(DeliveryState.Pending))
    }

    // ==========================================
    // Sent Status Tests
    // ==========================================

    @Test
    fun `sent status shows single checkmark`() {
        assertEquals("✓", getMessageStatusIcon(DeliveryState.Sent))
    }

    // ==========================================
    // Propagation Status Tests
    // ==========================================

    @Test
    fun `retrying_propagated status shows single checkmark`() {
        assertEquals("✓", getMessageStatusIcon(DeliveryState.RetryingViaPropagation))
    }

    @Test
    fun `propagated status shows single checkmark`() {
        assertEquals("✓", getMessageStatusIcon(DeliveryState.Propagated))
    }

    @Test
    fun `sent and retrying_propagated and propagated have same icon`() {
        val sentIcon = getMessageStatusIcon(DeliveryState.Sent)
        assertEquals(sentIcon, getMessageStatusIcon(DeliveryState.RetryingViaPropagation))
        assertEquals(sentIcon, getMessageStatusIcon(DeliveryState.Propagated))
    }

    // ==========================================
    // Delivered Status Tests
    // ==========================================

    @Test
    fun `delivered status shows double checkmark`() {
        assertEquals("✓✓", getMessageStatusIcon(DeliveryState.Delivered))
    }

    // ==========================================
    // Failed Status Tests
    // ==========================================

    @Test
    fun `failed status shows exclamation`() {
        assertEquals("!", getMessageStatusIcon(DeliveryState.Failed))
    }

    // ==========================================
    // Unknown Status Tests
    // ==========================================

    @Test
    fun `unknown legacy status decodes to null and shows empty string`() {
        assertEquals("", getMessageStatusIcon(DeliveryState.decode("unknown")))
    }

    @Test
    fun `empty legacy status decodes to null and shows empty string`() {
        assertEquals("", getMessageStatusIcon(DeliveryState.decode("")))
    }

    @Test
    fun `null state shows empty string`() {
        assertEquals("", getMessageStatusIcon(null))
    }

    // ==========================================
    // Status Progression Tests
    // ==========================================

    @Test
    fun `status icons are distinct for main states`() {
        val pending = getMessageStatusIcon(DeliveryState.Pending)
        val sent = getMessageStatusIcon(DeliveryState.Sent)
        val delivered = getMessageStatusIcon(DeliveryState.Delivered)
        val failed = getMessageStatusIcon(DeliveryState.Failed)

        // All main states should have distinct icons
        val icons = setOf(pending, sent, delivered, failed)
        assertEquals(4, icons.size)
    }

    @Test
    fun `normal delivery flow has correct icon progression`() {
        // Normal flow: Pending -> Sent -> Delivered
        assertEquals("○", getMessageStatusIcon(DeliveryState.Pending))
        assertEquals("✓", getMessageStatusIcon(DeliveryState.Sent))
        assertEquals("✓✓", getMessageStatusIcon(DeliveryState.Delivered))
    }

    @Test
    fun `propagation fallback flow has correct icon progression`() {
        // Propagation fallback: Pending -> RetryingViaPropagation -> Delivered
        assertEquals("○", getMessageStatusIcon(DeliveryState.Pending))
        assertEquals("✓", getMessageStatusIcon(DeliveryState.RetryingViaPropagation))
        assertEquals("✓✓", getMessageStatusIcon(DeliveryState.Delivered))
    }
}
