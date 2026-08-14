package network.columba.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggOpusAndroidTimestampNormalizerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `normalizes Android packet-start granules for Sideband libopusfile`() {
        val source = androidMediaRecorderOgg(granules = listOf(0L, 960L, 1_920L))

        val normalized = OggOpusAndroidTimestampNormalizer.normalize(source)

        assertNotSame(source, normalized)
        assertEquals(listOf(960L, 1_920L, 2_880L), audioGranules(normalized))
        assertTrue(allPageChecksumsAreValid(normalized))
    }

    @Test
    fun `normalization is idempotent for canonical end-of-packet granules`() {
        val canonical = androidMediaRecorderOgg(granules = listOf(960L, 1_920L, 2_880L))

        val normalized = OggOpusAndroidTimestampNormalizer.normalize(canonical)

        assertTrue(canonical === normalized)
        assertArrayEquals(canonical, normalized)
    }

    @Test
    fun `rejects inconsistent Android timestamp sequence instead of corrupting audio`() {
        val malformed = androidMediaRecorderOgg(granules = listOf(0L, 500L, 1_920L))

        val failure = runCatching { OggOpusAndroidTimestampNormalizer.normalize(malformed) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("granule sequence") == true)
    }

    @Test
    fun `file normalization atomically replaces only affected recording`() {
        val recording = temporaryFolder.newFile("voice.ogg")
        recording.writeBytes(androidMediaRecorderOgg(granules = listOf(0L, 960L)))

        assertTrue(OggOpusAndroidTimestampNormalizer.normalize(recording))
        assertEquals(listOf(960L, 1_920L), audioGranules(recording.readBytes()))
        assertFalse(OggOpusAndroidTimestampNormalizer.normalize(recording))
        assertTrue(allPageChecksumsAreValid(recording.readBytes()))
    }

    @Test
    fun `failed atomic publication preserves the original recording`() {
        val original = androidMediaRecorderOgg(granules = listOf(0L, 960L))
        val recording = temporaryFolder.newFile("voice.ogg").apply { writeBytes(original) }

        val failure =
            runCatching {
                OggOpusAndroidTimestampNormalizer.normalize(recording) { _, _ ->
                    throw IOException("move failed")
                }
            }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertArrayEquals(original, recording.readBytes())
        assertEquals(listOf("voice.ogg"), temporaryFolder.root.listFiles()?.map { it.name })
    }

    private fun androidMediaRecorderOgg(granules: List<Long>): ByteArray {
        val serial = 0x10203040
        val output = ByteArrayOutputStream()
        output.write(oggPage(serial, sequence = 0, flags = 2, granule = 0, opusHead()))
        output.write(oggPage(serial, sequence = 1, flags = 0, granule = 0, opusTags()))
        granules.forEachIndexed { index, granule ->
            output.write(oggPage(serial, sequence = index + 2, flags = 0, granule = granule, SILENCE_PACKET))
        }
        return output.toByteArray()
    }

    private fun opusHead(): ByteArray =
        ByteBuffer
            .allocate(19)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".encodeToByteArray())
            .put(1)
            .put(1)
            .putShort(312.toShort())
            .putInt(48_000)
            .putShort(0)
            .put(0)
            .array()

    private fun opusTags(): ByteArray {
        val vendor = "Android".encodeToByteArray()
        return ByteBuffer
            .allocate(8 + 4 + vendor.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".encodeToByteArray())
            .putInt(vendor.size)
            .put(vendor)
            .putInt(0)
            .array()
    }

    private fun oggPage(
        serial: Int,
        sequence: Int,
        flags: Int,
        granule: Long,
        packet: ByteArray,
    ): ByteArray {
        require(packet.size < 255)
        val page =
            ByteBuffer
                .allocate(28 + packet.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("OggS".encodeToByteArray())
                .put(0)
                .put(flags.toByte())
                .putLong(granule)
                .putInt(serial)
                .putInt(sequence)
                .putInt(0)
                .put(1)
                .put(packet.size.toByte())
                .put(packet)
                .array()
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(22, oggChecksum(page))
        return page
    }

    private fun audioGranules(bytes: ByteArray): List<Long> =
        pages(bytes).drop(2).map { page -> ByteBuffer.wrap(bytes, page.first + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long }

    private fun allPageChecksumsAreValid(bytes: ByteArray): Boolean =
        pages(bytes).all { page ->
            val copy = bytes.copyOfRange(page.first, page.last + 1)
            val stored = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
            copy.fill(0, 22, 26)
            stored == oggChecksum(copy)
        }

    private fun pages(bytes: ByteArray): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var offset = 0
        while (offset < bytes.size) {
            val segments = bytes[offset + 26].toInt() and 0xff
            val payloadSize = (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 0xff }
            val end = offset + 27 + segments + payloadSize
            result += offset until end
            offset = end
        }
        return result
    }

    private fun oggChecksum(bytes: ByteArray): Int {
        var checksum = 0
        bytes.forEach { byte ->
            checksum = (checksum shl 8) xor CRC_LOOKUP[((checksum ushr 24) xor (byte.toInt() and 0xff)) and 0xff]
        }
        return checksum
    }

    private companion object {
        val SILENCE_PACKET = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        val CRC_LOOKUP =
            IntArray(256) { index ->
                var value = index shl 24
                repeat(8) { value = if (value and Int.MIN_VALUE != 0) (value shl 1) xor 0x04c11db7 else value shl 1 }
                value
            }
    }
}
