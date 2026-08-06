package network.columba.app.audio

import kotlin.math.sqrt

internal data class OggOpusMetadata(
    val durationMs: Int,
    val waveformLevels: List<Float>,
)

/**
 * Reads duration and a compact visual envelope from finalized Ogg/Opus bytes.
 *
 * Duration comes from the final Ogg granule position and Opus pre-skip. The
 * envelope reflects relative Opus packet sizes, which gives a stable, cheap
 * visualization without decoding the complete recording to PCM.
 */
internal object OggOpusMetadataReader {
    private const val SAMPLE_RATE = 48_000L
    private const val TARGET_WAVEFORM_BARS = 32
    private const val MIN_WAVEFORM_LEVEL = 0.18f
    private const val MAX_PAGES = 65_536
    private const val MAX_PACKET_SAMPLES = 65_536

    // Ogg parsing is intentionally fail-closed at each structural boundary.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    fun read(bytes: ByteArray): OggOpusMetadata? {
        var offset = 0
        var pageCount = 0
        var preSkip: Int? = null
        var finalGranule = -1L
        var packetSize = 0
        val audioPacketSizes = mutableListOf<Int>()

        while (offset < bytes.size && pageCount < MAX_PAGES) {
            if (offset + 27 > bytes.size || !bytes.matchesAscii(offset, "OggS")) return null
            val segmentCount = bytes[offset + 26].toInt() and 0xff
            val segmentTableStart = offset + 27
            val payloadStart = segmentTableStart + segmentCount
            if (payloadStart > bytes.size) return null

            var payloadSize = 0
            for (index in 0 until segmentCount) payloadSize += bytes[segmentTableStart + index].toInt() and 0xff
            val pageEnd = payloadStart + payloadSize
            if (pageEnd > bytes.size) return null

            val granule = bytes.readLittleEndianLong(offset + 6)
            if (granule >= 0) finalGranule = granule

            var payloadOffset = payloadStart
            for (index in 0 until segmentCount) {
                val segmentSize = bytes[segmentTableStart + index].toInt() and 0xff
                if (packetSize == 0 && segmentSize >= 8 && bytes.matchesAscii(payloadOffset, "OpusHead")) {
                    if (segmentSize >= 12) preSkip = bytes.readLittleEndianUnsignedShort(payloadOffset + 10)
                }
                packetSize += segmentSize
                payloadOffset += segmentSize
                if (segmentSize < 255) {
                    val packetStart = payloadOffset - packetSize
                    if (!bytes.matchesAscii(packetStart, "OpusHead") && !bytes.matchesAscii(packetStart, "OpusTags")) {
                        if (audioPacketSizes.size < MAX_PACKET_SAMPLES) audioPacketSizes += packetSize
                    }
                    packetSize = 0
                }
            }

            offset = pageEnd
            pageCount += 1
        }

        preSkip ?: return null
        if (finalGranule <= 0) return null
        val durationMs = ((finalGranule * 1_000L) / SAMPLE_RATE).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return OggOpusMetadata(durationMs = durationMs, waveformLevels = audioPacketSizes.toWaveformLevels())
    }

    private fun List<Int>.toWaveformLevels(): List<Float> {
        if (isEmpty()) return List(TARGET_WAVEFORM_BARS) { 0.35f }
        val bars = minOf(TARGET_WAVEFORM_BARS, size)
        val grouped =
            List(bars) { bar ->
                val start = bar * size / bars
                val end = ((bar + 1) * size / bars).coerceAtLeast(start + 1)
                subList(start, end).average().toFloat()
            }
        val maximum = grouped.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return grouped.map { value ->
            (MIN_WAVEFORM_LEVEL + (1f - MIN_WAVEFORM_LEVEL) * sqrt(value / maximum)).coerceIn(MIN_WAVEFORM_LEVEL, 1f)
        }
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        if (offset < 0 || offset + value.length > size) return false
        return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
    }

    private fun ByteArray.readLittleEndianUnsignedShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readLittleEndianLong(offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((this[offset + index].toLong() and 0xffL) shl (index * 8))
        return value
    }
}
