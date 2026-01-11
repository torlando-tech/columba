package com.lxmf.messenger.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RmspServer and RmspServerAnnounce data classes.
 *
 * Tests the data model logic including geohash coverage and format support.
 */
class RmspServerRepositoryTest {
    companion object {
        private val DEFAULT_SERVER =
            RmspServer(
                destinationHash = "abc123",
                serverName = "Test Server",
                publicKey = byteArrayOf(1, 2, 3),
                coverageGeohashes = listOf("9q8y"),
                minZoom = 0,
                maxZoom = 14,
                formats = listOf("pbf"),
                layers = listOf("base"),
                dataUpdatedTimestamp = 1000000L,
                dataSize = 1000000L,
                version = "1.0",
                lastSeenTimestamp = 2000000L,
                hops = 1,
            )
    }

    // ========== RmspServer coversGeohash() Tests ==========

    @Test
    fun `coversGeohash returns true when geohash matches coverage prefix`() {
        val server = DEFAULT_SERVER.copy(coverageGeohashes = listOf("9q8y", "9q8z"))

        assertTrue(server.coversGeohash("9q8y"))
        assertTrue(server.coversGeohash("9q8y1"))
        assertTrue(server.coversGeohash("9q8y12"))
        assertTrue(server.coversGeohash("9q8z"))
    }

    @Test
    fun `coversGeohash returns false when geohash does not match`() {
        val server = DEFAULT_SERVER.copy(coverageGeohashes = listOf("9q8y"))

        assertFalse(server.coversGeohash("9q9a"))
        assertFalse(server.coversGeohash("u10h"))
        assertFalse(server.coversGeohash("xn76"))
    }

    @Test
    fun `coversGeohash returns true for empty coverage list (global)`() {
        val server = DEFAULT_SERVER.copy(coverageGeohashes = emptyList())

        assertTrue(server.coversGeohash("9q8y"))
        assertTrue(server.coversGeohash("u10h"))
        assertTrue(server.coversGeohash("xn76"))
    }

    @Test
    fun `coversGeohash handles prefix matching both directions`() {
        val server = DEFAULT_SERVER.copy(coverageGeohashes = listOf("9q8"))

        // Coverage prefix is shorter than query geohash
        assertTrue(server.coversGeohash("9q8y1"))

        // Query geohash is shorter than coverage prefix
        val server2 = DEFAULT_SERVER.copy(coverageGeohashes = listOf("9q8y1234"))
        assertTrue(server2.coversGeohash("9q8y"))
    }

    @Test
    fun `coversGeohash is case-sensitive`() {
        val server = DEFAULT_SERVER.copy(coverageGeohashes = listOf("9q8y"))

        // Geohashes should match as-is
        assertTrue(server.coversGeohash("9q8y"))
    }

    // ========== RmspServer supportsFormat() Tests ==========

    @Test
    fun `supportsFormat returns true when format is in list`() {
        val server = DEFAULT_SERVER.copy(formats = listOf("pbf", "mvt"))

        assertTrue(server.supportsFormat("pbf"))
        assertTrue(server.supportsFormat("mvt"))
    }

    @Test
    fun `supportsFormat returns false when format is not in list`() {
        val server = DEFAULT_SERVER.copy(formats = listOf("pbf"))

        assertFalse(server.supportsFormat("png"))
        assertFalse(server.supportsFormat("jpeg"))
    }

    @Test
    fun `supportsFormat returns true for empty formats list (supports all)`() {
        val server = DEFAULT_SERVER.copy(formats = emptyList())

        assertTrue(server.supportsFormat("pbf"))
        assertTrue(server.supportsFormat("png"))
        assertTrue(server.supportsFormat("anything"))
    }

    // ========== RmspServer getDataSizeString() Tests ==========

    @Test
    fun `getDataSizeString returns null when dataSize is null`() {
        val server = DEFAULT_SERVER.copy(dataSize = null)

        assertNull(server.getDataSizeString())
    }

    @Test
    fun `getDataSizeString formats bytes correctly`() {
        val server = DEFAULT_SERVER.copy(dataSize = 500L)

        assertEquals("500 B", server.getDataSizeString())
    }

    @Test
    fun `getDataSizeString formats kilobytes correctly`() {
        val server = DEFAULT_SERVER.copy(dataSize = 5 * 1024L)

        assertEquals("5 KB", server.getDataSizeString())
    }

    @Test
    fun `getDataSizeString formats megabytes correctly`() {
        val server = DEFAULT_SERVER.copy(dataSize = 50 * 1024 * 1024L)

        assertEquals("50 MB", server.getDataSizeString())
    }

    @Test
    fun `getDataSizeString formats gigabytes correctly`() {
        val server = DEFAULT_SERVER.copy(dataSize = 2 * 1024 * 1024 * 1024L)

        assertEquals("2.0 GB", server.getDataSizeString())
    }

    @Test
    fun `getDataSizeString handles large gigabytes`() {
        val server = DEFAULT_SERVER.copy(dataSize = 15 * 1024L * 1024L * 1024L)

        assertEquals("15.0 GB", server.getDataSizeString())
    }

    // ========== RmspServer equals() and hashCode() Tests ==========

    @Test
    fun `equals returns true for identical servers`() {
        val server1 = DEFAULT_SERVER
        val server2 = DEFAULT_SERVER

        assertEquals(server1, server2)
    }

    @Test
    fun `equals returns false for different destination hash`() {
        val server1 = DEFAULT_SERVER.copy(destinationHash = "abc123")
        val server2 = DEFAULT_SERVER.copy(destinationHash = "def456")

        assertNotEquals(server1, server2)
    }

    @Test
    fun `equals returns false for different server name`() {
        val server1 = DEFAULT_SERVER.copy(serverName = "Server1")
        val server2 = DEFAULT_SERVER.copy(serverName = "Server2")

        assertNotEquals(server1, server2)
    }

    @Test
    fun `equals handles ByteArray comparison correctly`() {
        val server1 = DEFAULT_SERVER.copy(publicKey = byteArrayOf(1, 2, 3))
        val server2 = DEFAULT_SERVER.copy(publicKey = byteArrayOf(1, 2, 3))
        val server3 = DEFAULT_SERVER.copy(publicKey = byteArrayOf(4, 5, 6))

        assertEquals(server1, server2)
        assertNotEquals(server1, server3)
    }

    @Test
    fun `hashCode is consistent for equal objects`() {
        val server1 = DEFAULT_SERVER
        val server2 = DEFAULT_SERVER

        assertEquals(server1.hashCode(), server2.hashCode())
    }

    @Test
    fun `hashCode is different for different objects`() {
        val server1 = DEFAULT_SERVER.copy(destinationHash = "abc123")
        val server2 = DEFAULT_SERVER.copy(destinationHash = "def456")

        assertNotEquals(server1.hashCode(), server2.hashCode())
    }

    // ========== RmspServerAnnounce Tests ==========

    @Test
    fun `RmspServerAnnounce holds correct values`() {
        val announce =
            RmspServerAnnounce(
                destinationHash = "abc123",
                serverName = "Test Server",
                publicKey = byteArrayOf(1, 2, 3),
                coverageGeohashes = listOf("9q8y"),
                minZoom = 0,
                maxZoom = 14,
                formats = listOf("pbf"),
                layers = listOf("base"),
                dataUpdatedTimestamp = 1000L,
                dataSize = 5000L,
                version = "1.0",
                hops = 2,
            )

        assertEquals("abc123", announce.destinationHash)
        assertEquals("Test Server", announce.serverName)
        assertEquals(3, announce.publicKey.size)
        assertEquals(listOf("9q8y"), announce.coverageGeohashes)
        assertEquals(0, announce.minZoom)
        assertEquals(14, announce.maxZoom)
        assertEquals(listOf("pbf"), announce.formats)
        assertEquals(listOf("base"), announce.layers)
        assertEquals(1000L, announce.dataUpdatedTimestamp)
        assertEquals(5000L, announce.dataSize)
        assertEquals("1.0", announce.version)
        assertEquals(2, announce.hops)
    }

    @Test
    fun `RmspServerAnnounce handles null dataSize`() {
        val announce =
            RmspServerAnnounce(
                destinationHash = "abc123",
                serverName = "Test Server",
                publicKey = byteArrayOf(),
                coverageGeohashes = emptyList(),
                minZoom = 0,
                maxZoom = 14,
                formats = emptyList(),
                layers = emptyList(),
                dataUpdatedTimestamp = 0L,
                dataSize = null,
                version = "1.0",
                hops = 0,
            )

        assertNull(announce.dataSize)
    }

    // ========== RmspServer Data Validation Tests ==========

    @Test
    fun `RmspServer handles empty lists correctly`() {
        val server =
            DEFAULT_SERVER.copy(
                coverageGeohashes = emptyList(),
                formats = emptyList(),
                layers = emptyList(),
            )

        assertEquals(emptyList<String>(), server.coverageGeohashes)
        assertEquals(emptyList<String>(), server.formats)
        assertEquals(emptyList<String>(), server.layers)
    }

    @Test
    fun `RmspServer handles zero values correctly`() {
        val server =
            DEFAULT_SERVER.copy(
                minZoom = 0,
                maxZoom = 0,
                hops = 0,
            )

        assertEquals(0, server.minZoom)
        assertEquals(0, server.maxZoom)
        assertEquals(0, server.hops)
    }

    @Test
    fun `RmspServer handles large hop counts`() {
        val server = DEFAULT_SERVER.copy(hops = 255)

        assertEquals(255, server.hops)
    }
}
