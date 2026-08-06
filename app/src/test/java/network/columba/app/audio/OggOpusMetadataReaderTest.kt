package network.columba.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggOpusMetadataReaderTest {
    @Test
    fun `reads duration from final granule before playback`() {
        val preSkip = 312
        val bytes =
            oggPage(sequence = 0, granule = 0, payload = opusHead(preSkip)) +
                oggPage(sequence = 1, granule = 48_000, payload = ByteArray(80) { 1 }) +
                oggPage(sequence = 2, granule = 96_000, payload = ByteArray(160) { 2 })

        val metadata = OggOpusMetadataReader.read(bytes)

        assertNotNull(metadata)
        assertEquals(2_000, metadata!!.durationMs)
    }

    @Test
    fun `rejects truncated ogg data`() {
        assertEquals(null, OggOpusMetadataReader.read("OggS".encodeToByteArray()))
    }

    private fun opusHead(preSkip: Int): ByteArray =
        ByteBuffer
            .allocate(19)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".encodeToByteArray())
            .put(1)
            .put(1)
            .putShort(preSkip.toShort())
            .putInt(48_000)
            .putShort(0)
            .put(0)
            .array()

    private fun oggPage(
        sequence: Int,
        granule: Long,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size < 255)
        val output = ByteArrayOutputStream()
        output.write("OggS".encodeToByteArray())
        output.write(0)
        output.write(if (sequence == 0) 2 else 0)
        output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(granule).array())
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence).array())
        output.write(ByteArray(4))
        output.write(1)
        output.write(payload.size)
        output.write(payload)
        return output.toByteArray()
    }
}
