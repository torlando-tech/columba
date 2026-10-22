package network.columba.app.data.util

import java.lang.management.ManagementFactory
import network.columba.app.data.util.HashUtils.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashUtilsTest {
    @Test
    fun `toHexString converts empty byte array to empty string`() {
        assertEquals("", byteArrayOf().toHexString())
    }

    @Test
    fun `toHexString converts single byte to two hex chars`() {
        assertEquals("00", byteArrayOf(0x00).toHexString())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHexString())
        assertEquals("0a", byteArrayOf(0x0A).toHexString())
        assertEquals("a0", byteArrayOf(0xA0.toByte()).toHexString())
    }

    @Test
    fun `toHexString converts multiple bytes correctly`() {
        assertEquals("0102030405", byteArrayOf(1, 2, 3, 4, 5).toHexString())
        assertEquals("deadbeef", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toHexString())
    }

    @Test
    fun `toHexString produces lowercase output`() {
        val result = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()).toHexString()
        assertEquals("abcdef", result)
        assertEquals(result, result.lowercase())
    }

    @Test
    fun `toHexString on List converts correctly`() {
        val list = listOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte())
        assertEquals("010203", list.toHexString())
    }

    @Test
    fun `computeIdentityHash returns 32 character hex string`() {
        // Any public key should produce a 32 character hex string (16 bytes = 32 hex chars)
        val publicKey = ByteArray(32) { it.toByte() }
        val result = HashUtils.computeIdentityHash(publicKey)
        assertEquals(32, result.length)
    }

    @Test
    fun `computeIdentityHash is deterministic`() {
        val publicKey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val result1 = HashUtils.computeIdentityHash(publicKey)
        val result2 = HashUtils.computeIdentityHash(publicKey)
        assertEquals(result1, result2)
    }

    @Test
    fun `computeIdentityHash produces different output for different inputs`() {
        val publicKey1 = byteArrayOf(1, 2, 3, 4, 5)
        val publicKey2 = byteArrayOf(5, 4, 3, 2, 1)
        val result1 = HashUtils.computeIdentityHash(publicKey1)
        val result2 = HashUtils.computeIdentityHash(publicKey2)
        assert(result1 != result2) { "Different inputs should produce different hashes" }
    }

    @Test
    fun `computeIdentityHash produces lowercase hex`() {
        val publicKey = ByteArray(32) { 0xFF.toByte() }
        val result = HashUtils.computeIdentityHash(publicKey)
        assertEquals(result, result.lowercase())
    }

    @Test
    fun `computeIdentityHash matches expected SHA256 truncation`() {
        // Known test vector: SHA256 of empty byte array
        // SHA256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        // First 16 bytes = e3b0c44298fc1c149afbf4c8996fb924
        val emptyKey = byteArrayOf()
        val result = HashUtils.computeIdentityHash(emptyKey)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924", result)
    }

    @Test
    fun `computeIdentityHash with known input produces expected output`() {
        // SHA256 of [0x00] = 6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d
        // First 16 bytes = 6e340b9cffb37a989ca544e6bb780a2c
        val singleZero = byteArrayOf(0x00)
        val result = HashUtils.computeIdentityHash(singleZero)
        assertEquals("6e340b9cffb37a989ca544e6bb780a2c", result)
    }

    @Test
    fun `toHexString ByteArray allocation stays bounded relative to output`() {
        val bytes = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        assertAllocationBounded("ByteArray.toHexString", bytes.size * 2) { bytes.toHexString() }
    }

    @Test
    fun `toHexString List allocation stays bounded relative to output`() {
        val list = ByteArray(64 * 1024) { (it and 0xFF).toByte() }.toList()
        assertAllocationBounded("List<Byte>.toHexString", list.size * 2) { list.toHexString() }
    }

    /**
     * Sentry COLUMBA-EE: the Formatter-based implementation (`"%02x".format(it)`)
     * allocated on the order of a kilobyte of garbage per byte formatted, which
     * exhausted the 256 MB heap under memory pressure. A lookup-table conversion
     * allocates ~3x the output (CharArray + String copy); multi-MB allocation for
     * a 128 KiB hex string means per-byte Formatter churn is back.
     */
    private fun assertAllocationBounded(
        label: String,
        expectedHexLength: Int,
        convert: () -> String,
    ) {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(
            "JVM must support per-thread allocation counting",
            bean.isThreadAllocatedMemorySupported,
        )
        if (!bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }

        // Warm up so the measured call runs compiled code.
        repeat(5) { convert() }

        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        val hex = convert()
        val allocated = bean.getThreadAllocatedBytes(threadId) - before

        assertEquals(expectedHexLength, hex.length)
        assertTrue(
            "$label allocated $allocated bytes for ${hex.length} hex chars; budget ${expectedHexLength * 8L}",
            allocated < expectedHexLength * 8L,
        )
    }
}
