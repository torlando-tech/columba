package com.lxmf.messenger.reticulum.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.msgpack.core.MessagePack

/**
 * Unit tests for AppDataParser.
 * Tests extraction of propagation node metadata from msgpack-encoded app_data.
 */
class AppDataParserTest {
    @Test
    fun `extractPeerName returns displayName when provided`() {
        val result =
            AppDataParser.extractPeerName(
                appData = null,
                destinationHash = "abc123def456",
                displayName = "TestNode",
            )

        assertEquals("TestNode", result)
    }

    @Test
    fun `extractPeerName returns fallback when displayName is null`() {
        val result =
            AppDataParser.extractPeerName(
                appData = null,
                destinationHash = "abc123def456",
                displayName = null,
            )

        assertEquals("Peer ABC123DE", result)
    }

    @Test
    fun `extractPeerName returns fallback when displayName is blank`() {
        val result =
            AppDataParser.extractPeerName(
                appData = null,
                destinationHash = "abc123def456",
                displayName = "   ",
            )

        assertEquals("Peer ABC123DE", result)
    }

    @Test
    fun `extractPeerName returns Unknown Peer for short hash`() {
        val result =
            AppDataParser.extractPeerName(
                appData = null,
                destinationHash = "abc",
                displayName = null,
            )

        assertEquals("Unknown Peer", result)
    }

    @Test
    fun `extractPropagationNodeMetadata returns nulls for null appData`() {
        val result = AppDataParser.extractPropagationNodeMetadata(null)

        assertNull(result.name)
        assertNull(result.transferLimitKb)
    }

    @Test
    fun `extractPropagationNodeMetadata returns nulls for empty appData`() {
        val result = AppDataParser.extractPropagationNodeMetadata(ByteArray(0))

        assertNull(result.name)
        assertNull(result.transferLimitKb)
    }

    @Test
    fun `extractPropagationNodeMetadata extracts integer transfer limit`() {
        // Create msgpack array: [false, 12345, false, 256, 1024, [], {}]
        // Index 3 is integer transfer limit (256 KB)
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false) // 0: legacy
        packer.packInt(12345) // 1: timebase
        packer.packBoolean(false) // 2: node state
        packer.packInt(256) // 3: per-message transfer limit (KB)
        packer.packInt(1024) // 4: per-sync transfer limit
        packer.packArrayHeader(0) // 5: stamp cost array (empty)
        packer.packMapHeader(0) // 6: metadata map (empty)
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertEquals(256, result.transferLimitKb)
        assertNull(result.name)
    }

    @Test
    fun `extractPropagationNodeMetadata extracts float transfer limit`() {
        // LXMF may encode transfer limit as float64
        // Create msgpack array with float at index 3
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false) // 0: legacy
        packer.packInt(12345) // 1: timebase
        packer.packBoolean(false) // 2: node state
        packer.packDouble(25600.0) // 3: per-message transfer limit as float (25600 KB = 25 MB)
        packer.packDouble(102400.0) // 4: per-sync transfer limit
        packer.packArrayHeader(0) // 5: stamp cost array
        packer.packMapHeader(0) // 6: metadata map
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertEquals(25600, result.transferLimitKb)
    }

    @Test
    fun `extractPropagationNodeMetadata extracts name from metadata map with n key`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false)
        packer.packInt(12345)
        packer.packBoolean(true)
        packer.packInt(256)
        packer.packInt(1024)
        packer.packArrayHeader(0)
        // Metadata map with "n" key
        packer.packMapHeader(1)
        packer.packString("n")
        packer.packString("MyNode")
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertEquals("MyNode", result.name)
        assertEquals(256, result.transferLimitKb)
    }

    @Test
    fun `extractPropagationNodeMetadata extracts name from metadata map with name key`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false)
        packer.packInt(12345)
        packer.packBoolean(true)
        packer.packInt(512)
        packer.packInt(2048)
        packer.packArrayHeader(0)
        // Metadata map with "name" key
        packer.packMapHeader(1)
        packer.packString("name")
        packer.packString("PropagationServer")
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertEquals("PropagationServer", result.name)
        assertEquals(512, result.transferLimitKb)
    }

    @Test
    fun `extractPropagationNodeMetadata returns null for array too small`() {
        // Array with only 3 elements (not enough for transfer limit at index 3)
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packBoolean(false)
        packer.packInt(12345)
        packer.packBoolean(false)
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertNull(result.transferLimitKb)
        assertNull(result.name)
    }

    @Test
    fun `extractPropagationNodeMetadata returns null for non-array data`() {
        // Pack a simple string instead of array
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString("not an array")
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertNull(result.transferLimitKb)
        assertNull(result.name)
    }

    @Test
    fun `extractPropagationNodeMetadata handles malformed data gracefully`() {
        // Invalid msgpack data
        val appData = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertNull(result.transferLimitKb)
        assertNull(result.name)
    }

    @Test
    fun `extractPropagationNodeMetadata ignores blank name in metadata`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false)
        packer.packInt(12345)
        packer.packBoolean(true)
        packer.packInt(256)
        packer.packInt(1024)
        packer.packArrayHeader(0)
        // Metadata map with blank name
        packer.packMapHeader(1)
        packer.packString("n")
        packer.packString("   ")
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertNull(result.name)
        assertEquals(256, result.transferLimitKb)
    }

    @Test
    fun `extractPropagationNodeMetadata handles real world propagation node data`() {
        // Real data from logs: b'\x97\xc2\xcei\\1\x9b\xc2\xcb@p\x00\x00\x00\x00\x00\x00...'
        // This is: array(7), false, uint32(timestamp), false, float64(256.0), ...
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false) // legacy support
        packer.packLong(1735934000) // timebase (realistic timestamp)
        packer.packBoolean(false) // node state
        packer.packDouble(256.0) // transfer limit 256 KB
        packer.packDouble(10240.0) // sync limit
        // Stamp cost array [16, 3, 18]
        packer.packArrayHeader(3)
        packer.packInt(16)
        packer.packInt(3)
        packer.packInt(18)
        packer.packMapHeader(0) // empty metadata
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertEquals(256, result.transferLimitKb)
        assertNull(result.name)
    }

    @Test
    fun `extractPropagationNodeMetadata extracts large transfer limit`() {
        // Test 25MB limit (25600 KB)
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(7)
        packer.packBoolean(false)
        packer.packInt(12345)
        packer.packBoolean(true)
        packer.packDouble(25600.0) // 25 MB in KB
        packer.packDouble(102400.0)
        packer.packArrayHeader(0)
        packer.packMapHeader(0)
        val appData = packer.toByteArray()
        packer.close()

        val result = AppDataParser.extractPropagationNodeMetadata(appData)

        assertEquals(25600, result.transferLimitKb)
    }

    @Test
    fun `PropagationNodeMetadata data class works correctly`() {
        val metadata1 = PropagationNodeMetadata(name = "Node1", transferLimitKb = 256)
        val metadata2 = PropagationNodeMetadata(name = "Node1", transferLimitKb = 256)
        val metadata3 = PropagationNodeMetadata(name = "Node2", transferLimitKb = 512)

        assertEquals(metadata1, metadata2)
        assertEquals(metadata1.hashCode(), metadata2.hashCode())
        assert(metadata1 != metadata3)
    }
}
