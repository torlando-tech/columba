package com.lxmf.messenger.viewmodel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tests for [TarIdentityExtractor].
 *
 * Builds synthetic tar archives in memory to exercise all parsing paths
 * without needing real Sideband backup files.
 */
class TarIdentityExtractorTest {
    // -- Happy path --

    @Test
    fun `extract finds primary_identity as first entry`() {
        val identityData = ByteArray(64) { it.toByte() }
        val tar = buildTar(TarEntry("Sideband Backup/primary_identity", identityData))

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertArrayEquals(identityData, result)
    }

    @Test
    fun `extract finds primary_identity after skipping other entries`() {
        val otherData = ByteArray(1024) { 0xAB.toByte() }
        val identityData = ByteArray(64) { it.toByte() }
        val tar =
            buildTar(
                TarEntry("Sideband Backup/config", otherData),
                TarEntry("Sideband Backup/known_peers", otherData),
                TarEntry("Sideband Backup/primary_identity", identityData),
            )

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertArrayEquals(identityData, result)
    }

    @Test
    fun `extract matches filename ending with slash identity`() {
        val identityData = ByteArray(64) { 0xFF.toByte() }
        val tar = buildTar(TarEntry("some/path/identity", identityData))

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertArrayEquals(identityData, result)
    }

    @Test
    fun `extract handles entry with size not aligned to 512 bytes`() {
        // 100 bytes -> padded to 512 in tar
        val identityData = ByteArray(100) { (it * 3).toByte() }
        val tar = buildTar(TarEntry("Sideband Backup/primary_identity", identityData))

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertArrayEquals(identityData, result)
    }

    @Test
    fun `extract handles entry with size exactly 512 bytes`() {
        val identityData = ByteArray(512) { it.toByte() }
        val tar = buildTar(TarEntry("Sideband Backup/primary_identity", identityData))

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertArrayEquals(identityData, result)
    }

    @Test
    fun `extract skips entries with varied sizes before identity`() {
        val tar =
            buildTar(
                TarEntry("Sideband Backup/a", ByteArray(1)), // 1 byte -> 512 padded
                TarEntry("Sideband Backup/b", ByteArray(511)), // 511 -> 512 padded
                TarEntry("Sideband Backup/c", ByteArray(513)), // 513 -> 1024 padded
                TarEntry("Sideband Backup/primary_identity", ByteArray(64) { 0x42 }),
            )

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertEquals(64, result.size)
        assertEquals(0x42.toByte(), result[0])
    }

    @Test
    fun `extract handles zero-length entries before identity`() {
        val identityData = ByteArray(64) { it.toByte() }
        val tar =
            buildTar(
                TarEntry("Sideband Backup/empty_file", ByteArray(0)),
                TarEntry("Sideband Backup/primary_identity", identityData),
            )

        val result = TarIdentityExtractor.extract(ByteArrayInputStream(tar))
        assertArrayEquals(identityData, result)
    }

    // -- Error cases --

    @Test(expected = IllegalStateException::class)
    fun `extract throws when archive ends before identity found`() {
        val tar =
            buildTar(
                TarEntry("Sideband Backup/config", ByteArray(128)),
            )
        // Archive has end-of-archive marker (two zero blocks) but no identity
        TarIdentityExtractor.extract(ByteArrayInputStream(tar))
    }

    @Test(expected = IllegalStateException::class)
    fun `extract throws on empty archive`() {
        // Two 512-byte zero blocks = end of archive
        val tar = ByteArray(1024)
        TarIdentityExtractor.extract(ByteArrayInputStream(tar))
    }

    @Test(expected = IllegalStateException::class)
    fun `extract throws on truncated archive mid-header`() {
        // Only 256 bytes — can't read a full 512-byte header
        val tar = ByteArray(256) { 0x01 }
        TarIdentityExtractor.extract(ByteArrayInputStream(tar))
    }

    @Test(expected = IllegalStateException::class)
    fun `extract throws on truncated data block`() {
        // Header claims 64 bytes of data but stream ends after header
        val header = makeTarHeader("Sideband Backup/primary_identity", 64)
        TarIdentityExtractor.extract(ByteArrayInputStream(header))
    }

    @Test(expected = IllegalStateException::class)
    fun `extract throws on malformed octal size`() {
        val header = ByteArray(512)
        "not_a_file".toByteArray().copyInto(header)
        // Write non-octal garbage into size field (bytes 124-135)
        "ZZZZZZZZZZZ".toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        header[156] = '0'.code.toByte()
        // Append end-of-archive so it doesn't loop forever
        val tar = header + ByteArray(1024)
        TarIdentityExtractor.extract(ByteArrayInputStream(tar))
    }

    @Test
    fun `extract parses name correctly when zero byte appears in later header field`() {
        // Craft a header where a zero byte exists at position 50 (inside the
        // name field) AND at position 10 in some other field. The name field
        // is 0-99 so only the null within 0-99 should terminate the name.
        val identityData = ByteArray(64) { it.toByte() }
        val header = makeTarHeader("Sideband Backup/primary_identity", 64)
        // Verify name is parsed correctly even though other header fields
        // (e.g., bytes 100-511) contain zeros — the bounded search should
        // only look at bytes 0-99
        val result =
            TarIdentityExtractor.extract(
                ByteArrayInputStream(header + identityData + ByteArray(512 - 64) + ByteArray(1024)),
            )
        assertArrayEquals(identityData, result)
    }

    // -- readFully tests --

    @Test
    fun `readFully reads complete data`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val buf = ByteArray(5)
        val stream = ByteArrayInputStream(data)

        val read = with(TarIdentityExtractor) { stream.readFully(buf, 0, 5) }
        assertEquals(5, read)
        assertArrayEquals(data, buf)
    }

    @Test
    fun `readFully returns partial count at EOF`() {
        val data = byteArrayOf(1, 2, 3)
        val buf = ByteArray(10)
        val stream = ByteArrayInputStream(data)

        val read = with(TarIdentityExtractor) { stream.readFully(buf, 0, 10) }
        assertEquals(3, read)
    }

    @Test
    fun `readFully respects offset`() {
        val data = byteArrayOf(0xA, 0xB, 0xC)
        val buf = ByteArray(6)
        val stream = ByteArrayInputStream(data)

        with(TarIdentityExtractor) { stream.readFully(buf, 2, 3) }
        assertEquals(0.toByte(), buf[0])
        assertEquals(0.toByte(), buf[1])
        assertEquals(0xA.toByte(), buf[2])
        assertEquals(0xB.toByte(), buf[3])
        assertEquals(0xC.toByte(), buf[4])
    }

    @Test
    fun `readFully returns 0 on empty stream`() {
        val buf = ByteArray(5)
        val stream = ByteArrayInputStream(ByteArray(0))

        val read = with(TarIdentityExtractor) { stream.readFully(buf, 0, 5) }
        assertEquals(0, read)
    }

    // -- skipFully tests --

    @Test
    fun `skipFully skips exact number of bytes`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val stream = ByteArrayInputStream(data)

        with(TarIdentityExtractor) { stream.skipFully(5) }
        assertEquals(6, stream.read()) // Next byte should be 6
    }

    @Test
    fun `skipFully handles skip past EOF gracefully`() {
        val data = byteArrayOf(1, 2, 3)
        val stream = ByteArrayInputStream(data)

        // Should not throw — just stops at EOF
        with(TarIdentityExtractor) { stream.skipFully(100) }
        assertEquals(-1, stream.read())
    }

    @Test
    fun `skipFully with zero bytes is a no-op`() {
        val data = byteArrayOf(1, 2, 3)
        val stream = ByteArrayInputStream(data)

        with(TarIdentityExtractor) { stream.skipFully(0) }
        assertEquals(1, stream.read()) // Stream position unchanged
    }

    // -- Tar building helpers --

    private data class TarEntry(
        val name: String,
        val data: ByteArray,
    )

    private fun buildTar(vararg entries: TarEntry): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            out.write(makeTarHeader(entry.name, entry.data.size.toLong()))
            out.write(entry.data)
            // Pad data to 512-byte boundary
            val remainder = entry.data.size % 512
            if (remainder > 0) {
                out.write(ByteArray(512 - remainder))
            }
        }
        // End-of-archive: two 512-byte zero blocks
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    /**
     * Build a minimal POSIX tar header (512 bytes).
     * Only populates name (0-99) and size (124-135) fields, which are
     * the only ones [TarIdentityExtractor] reads.
     */
    private fun makeTarHeader(
        name: String,
        size: Long,
    ): ByteArray {
        val header = ByteArray(512)
        // Name field: bytes 0-99, null-terminated
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        nameBytes.copyInto(header, 0, 0, minOf(nameBytes.size, 100))
        // Size field: bytes 124-135, octal ASCII null-terminated
        val sizeOctal = size.toString(8).padStart(11, '0')
        sizeOctal.toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        // Typeflag: regular file ('0')
        header[156] = '0'.code.toByte()
        return header
    }
}
