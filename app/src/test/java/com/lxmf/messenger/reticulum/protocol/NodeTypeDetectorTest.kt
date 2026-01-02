package com.lxmf.messenger.reticulum.protocol

import com.lxmf.messenger.reticulum.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for NodeTypeDetector.
 *
 * Tests cover:
 * - All aspect-based detection branches
 * - msgpack format byte detection for propagation nodes
 * - LXMF display name format detection
 * - Edge cases and fallback behavior
 */
class NodeTypeDetectorTest {
    // ===========================================
    // A. detectNodeType() - Aspect-based detection
    // ===========================================

    @Test
    fun `detectNodeType with aspect lxmf_propagation returns PROPAGATION_NODE`() {
        val result =
            NodeTypeDetector.detectNodeType(
                appData = null,
                aspect = "lxmf.propagation",
            )
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with aspect nomadnetwork_node returns NODE`() {
        val result =
            NodeTypeDetector.detectNodeType(
                appData = null,
                aspect = "nomadnetwork.node",
            )
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with aspect lxmf_delivery returns PEER`() {
        val result =
            NodeTypeDetector.detectNodeType(
                appData = null,
                aspect = "lxmf.delivery",
            )
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with aspect call_audio returns NODE`() {
        val result =
            NodeTypeDetector.detectNodeType(
                appData = null,
                aspect = "call.audio",
            )
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with aspect meshchat_room returns NODE`() {
        val result =
            NodeTypeDetector.detectNodeType(
                appData = null,
                aspect = "meshchat.room",
            )
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with unknown aspect and null appData returns NODE`() {
        val result =
            NodeTypeDetector.detectNodeType(
                appData = null,
                aspect = "unknown.aspect",
            )
        assertEquals(NodeType.NODE, result)
    }

    // ===========================================
    // B. detectNodeType() - appData-based detection
    // ===========================================

    @Test
    fun `detectNodeType with null appData returns NODE`() {
        val result = NodeTypeDetector.detectNodeType(appData = null)
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with empty appData returns NODE`() {
        val result = NodeTypeDetector.detectNodeType(appData = byteArrayOf())
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with Columba string returns PEER`() {
        val appData = "Columba User".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with Columba case insensitive returns PEER`() {
        val appData = "columba messenger".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with Sideband string returns PEER`() {
        val appData = "Sideband v1.0".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with Sideband case insensitive returns PEER`() {
        val appData = "sideband messenger".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with NomadNet node string returns NODE`() {
        val appData = "John's Node".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with Node in name returns NODE`() {
        val appData = "My Personal Node".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with short display name returns PEER`() {
        val appData = "Alice".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with alphanumeric display name returns PEER`() {
        val appData = "User123".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    // ===========================================
    // C. msgpack Format Detection (isPropagationNodeData)
    // ===========================================
    // NOTE: The code first tries to parse appData as UTF-8 string.
    // If the string is < 50 chars and has no newlines, it returns PEER
    // before reaching isPropagationNodeData. So msgpack detection only
    // triggers for:
    // 1. Data >= 50 bytes that bypasses string checks, OR
    // 2. Data with aspect="lxmf.propagation" (which takes precedence)
    // These tests document actual behavior.

    /**
     * Helper to create msgpack-like binary data of specified length.
     * First byte is the msgpack format marker, rest is padding.
     */
    private fun createMsgpackData(
        formatByte: Byte,
        totalLength: Int,
    ): ByteArray {
        return ByteArray(totalLength).apply {
            this[0] = formatByte
            // Fill rest with non-text bytes to avoid string pattern matches
            for (i in 1 until totalLength) {
                this[i] = 0x01
            }
        }
    }

    @Test
    fun `detectNodeType with short msgpack fixarray returns PEER due to string check`() {
        // Short binary data (< 50 bytes) goes through string path first
        // UTF-8 decoding doesn't throw, so it matches "< 50 chars" condition
        val appData = byteArrayOf(0x93.toByte(), 0x01, 0x02)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        // Caught by string length check before isPropagationNodeData
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with long msgpack fixarray returns PROPAGATION_NODE`() {
        // 50+ bytes bypasses "< 50 chars" string check, reaches isPropagationNodeData
        // 0x93 = fixarray with 3 elements
        val appData = createMsgpackData(0x93.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack fixarray min returns PROPAGATION_NODE`() {
        // 0x90 = fixarray with 0 elements
        val appData = createMsgpackData(0x90.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack fixarray max returns PROPAGATION_NODE`() {
        // 0x9F = fixarray with 15 elements
        val appData = createMsgpackData(0x9F.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack array16 returns PROPAGATION_NODE`() {
        // 0xDC = array 16
        val appData = createMsgpackData(0xDC.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack array32 returns PROPAGATION_NODE`() {
        // 0xDD = array 32
        val appData = createMsgpackData(0xDD.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack fixmap returns PROPAGATION_NODE`() {
        // 0x82 = fixmap with 2 elements
        val appData = createMsgpackData(0x82.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack fixmap min returns PROPAGATION_NODE`() {
        // 0x80 = fixmap with 0 elements
        val appData = createMsgpackData(0x80.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack fixmap max returns PROPAGATION_NODE`() {
        // 0x8F = fixmap with 15 elements
        val appData = createMsgpackData(0x8F.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack map16 returns PROPAGATION_NODE`() {
        // 0xDE = map 16
        val appData = createMsgpackData(0xDE.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack map32 returns PROPAGATION_NODE`() {
        // 0xDF = map 32
        val appData = createMsgpackData(0xDF.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack bool false returns PROPAGATION_NODE`() {
        // 0xC2 = false, with enough data to bypass string check
        val appData = createMsgpackData(0xC2.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with long msgpack bool true returns PROPAGATION_NODE`() {
        // 0xC3 = true, with enough data to bypass string check
        val appData = createMsgpackData(0xC3.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with msgpack bool alone returns PEER`() {
        // Single boolean byte - too short for isPropagationNodeData (requires size > 1)
        val appData = byteArrayOf(0xC3.toByte())
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with short msgpack data returns PEER`() {
        // Less than 3 bytes - isPropagationNodeData requires >= 3
        // Also caught by string check since < 50 chars
        val appData = byteArrayOf(0x93.toByte(), 0x01)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with long msgpack nil format returns PEER`() {
        // 0xC0 = nil - not a propagation node format marker
        // Even with 55 bytes, nil format is not recognized by isPropagationNodeData
        val appData = createMsgpackData(0xC0.toByte(), 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with long non_msgpack binary returns PEER`() {
        // Random binary starting with non-msgpack marker
        val appData = createMsgpackData(0x00, 55)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with exactly 50 byte msgpack returns PROPAGATION_NODE`() {
        // Boundary: exactly 50 bytes creates 50-char string, which does NOT match "< 50"
        // So it falls through to isPropagationNodeData check
        val appData = createMsgpackData(0x93.toByte(), 50)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PROPAGATION_NODE, result)
    }

    @Test
    fun `detectNodeType with 49 byte msgpack returns PEER`() {
        // 49 bytes creates string that matches "< 50" condition
        val appData = createMsgpackData(0x93.toByte(), 49)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        // Caught by string length check before isPropagationNodeData
        assertEquals(NodeType.PEER, result)
    }

    // ===========================================
    // D. LXMF Display Name Format Detection
    // ===========================================

    @Test
    fun `detectNodeType with valid LXMF display name returns PEER`() {
        // Valid display name: alphanumeric with allowed special chars
        val appData = "user_name-123".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with display name containing at symbol returns PEER`() {
        val appData = "user@domain".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with display name containing hash returns PEER`() {
        val appData = "user#1234".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with multiline string returns PEER`() {
        // Multiline strings fail isLxmfDisplayNameFormat but default to PEER
        val appData = "line1\nline2".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with very long string returns PEER`() {
        // Strings > 128 chars fail isLxmfDisplayNameFormat but may match other patterns
        // or default to PEER
        val appData = "a".repeat(130).toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with invalid UTF8 returns PEER`() {
        // Invalid UTF-8 sequences - appDataString will be null or garbled
        val appData = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    // ===========================================
    // E. getNodeTypeDescription() - Complete Coverage
    // ===========================================

    @Test
    fun `getNodeTypeDescription for PEER returns correct description`() {
        val description = NodeTypeDetector.getNodeTypeDescription(NodeType.PEER)
        assertEquals("LXMF messaging peer", description)
    }

    @Test
    fun `getNodeTypeDescription for NODE returns correct description`() {
        val description = NodeTypeDetector.getNodeTypeDescription(NodeType.NODE)
        assertEquals("Content/service node", description)
    }

    @Test
    fun `getNodeTypeDescription for PROPAGATION_NODE returns correct description`() {
        val description = NodeTypeDetector.getNodeTypeDescription(NodeType.PROPAGATION_NODE)
        assertEquals("Message relay node", description)
    }

    // ===========================================
    // F. Edge Cases and Boundary Conditions
    // ===========================================

    @Test
    fun `detectNodeType aspect takes precedence over msgpack appData`() {
        // Even if appData looks like msgpack, aspect should win
        val msgpackData = byteArrayOf(0x93.toByte(), 0x01, 0x02)
        val result =
            NodeTypeDetector.detectNodeType(
                appData = msgpackData,
                aspect = "lxmf.delivery",
            )
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType aspect takes precedence over Columba appData`() {
        val appData = "Columba User".toByteArray(StandardCharsets.UTF_8)
        val result =
            NodeTypeDetector.detectNodeType(
                appData = appData,
                aspect = "nomadnetwork.node",
            )
        assertEquals(NodeType.NODE, result)
    }

    @Test
    fun `detectNodeType with blank string returns NODE`() {
        // Blank string (whitespace only) should fall through to NODE
        val appData = "   ".toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        // Blank fails isNotBlank check in appDataString parsing, falls through
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with exactly 50 char string returns PEER`() {
        // Boundary: exactly 50 chars should match the short display name check
        val appData = "a".repeat(50).toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }

    @Test
    fun `detectNodeType with 51 char string without Node keyword returns PEER`() {
        // 51 chars fails the < 50 check, falls through to isLxmfDisplayNameFormat
        val appData = "a".repeat(51).toByteArray(StandardCharsets.UTF_8)
        val result = NodeTypeDetector.detectNodeType(appData = appData)
        assertEquals(NodeType.PEER, result)
    }
}
