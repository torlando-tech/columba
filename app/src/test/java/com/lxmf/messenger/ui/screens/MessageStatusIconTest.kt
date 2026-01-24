package com.lxmf.messenger.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for message status icon logic in MessagingScreen.
 *
 * Tests the visual indicator for message delivery status:
 * - pending: hollow circle (○) - message created, waiting to send
 * - sent: single check (✓) - transmitted to network
 * - retrying_propagated: single check (✓) - retrying via propagation node
 * - propagated: single check (✓) - stored on propagation relay
 * - delivered: double check (✓✓) - delivered and acknowledged
 * - failed: exclamation (!) - delivery failed
 */
class MessageStatusIconTest {
    // ==========================================
    // Pending Status Tests
    // ==========================================

    @Test
    fun `pending status shows hollow circle`() {
        assertEquals("○", getMessageStatusIcon("pending"))
    }

    // ==========================================
    // Sent Status Tests
    // ==========================================

    @Test
    fun `sent status shows single checkmark`() {
        assertEquals("✓", getMessageStatusIcon("sent"))
    }

    // ==========================================
    // Propagation Status Tests
    // ==========================================

    @Test
    fun `retrying_propagated status shows single checkmark`() {
        assertEquals("✓", getMessageStatusIcon("retrying_propagated"))
    }

    @Test
    fun `propagated status shows single checkmark`() {
        assertEquals("✓", getMessageStatusIcon("propagated"))
    }

    @Test
    fun `sent and retrying_propagated and propagated have same icon`() {
        val sentIcon = getMessageStatusIcon("sent")
        assertEquals(sentIcon, getMessageStatusIcon("retrying_propagated"))
        assertEquals(sentIcon, getMessageStatusIcon("propagated"))
    }

    // ==========================================
    // Delivered Status Tests
    // ==========================================

    @Test
    fun `delivered status shows double checkmark`() {
        assertEquals("✓✓", getMessageStatusIcon("delivered"))
    }

    // ==========================================
    // Failed Status Tests
    // ==========================================

    @Test
    fun `failed status shows exclamation`() {
        assertEquals("!", getMessageStatusIcon("failed"))
    }

    // ==========================================
    // Unknown Status Tests
    // ==========================================

    @Test
    fun `unknown status shows empty string`() {
        assertEquals("", getMessageStatusIcon("unknown"))
    }

    @Test
    fun `empty status shows empty string`() {
        assertEquals("", getMessageStatusIcon(""))
    }

    @Test
    fun `null-like status shows empty string`() {
        assertEquals("", getMessageStatusIcon("null"))
    }

    // ==========================================
    // Status Progression Tests
    // ==========================================

    @Test
    fun `status icons are distinct for main states`() {
        val pending = getMessageStatusIcon("pending")
        val sent = getMessageStatusIcon("sent")
        val delivered = getMessageStatusIcon("delivered")
        val failed = getMessageStatusIcon("failed")

        // All main states should have distinct icons
        val icons = setOf(pending, sent, delivered, failed)
        assertEquals(4, icons.size)
    }

    @Test
    fun `normal delivery flow has correct icon progression`() {
        // Normal flow: pending -> sent -> delivered
        assertEquals("○", getMessageStatusIcon("pending"))
        assertEquals("✓", getMessageStatusIcon("sent"))
        assertEquals("✓✓", getMessageStatusIcon("delivered"))
    }

    @Test
    fun `propagation fallback flow has correct icon progression`() {
        // Propagation fallback: pending -> retrying_propagated -> delivered
        assertEquals("○", getMessageStatusIcon("pending"))
        assertEquals("✓", getMessageStatusIcon("retrying_propagated"))
        assertEquals("✓✓", getMessageStatusIcon("delivered"))
    }
}
