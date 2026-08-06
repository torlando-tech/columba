package network.columba.app.audio

internal data class OggOpusMetadata(
    val durationMs: Int,
)

/**
 * Reads duration from finalized Ogg/Opus bytes.
 *
 * Duration comes from the final Ogg granule position after validating the Opus
 * identification header. Waveforms are decoded from PCM separately so
 * compressed packet sizes are never presented as audio amplitude.
 */
internal object OggOpusMetadataReader {
    private const val SAMPLE_RATE = 48_000L
    private const val MAX_PAGES = 65_536

    // Ogg parsing is intentionally fail-closed at each structural boundary.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    fun read(bytes: ByteArray): OggOpusMetadata? {
        var offset = 0
        var pageCount = 0
        var preSkip: Int? = null
        var finalGranule = -1L


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

            if (payloadSize >= 12 && bytes.matchesAscii(payloadStart, "OpusHead")) {
                preSkip = bytes.readLittleEndianUnsignedShort(payloadStart + 10)
            }

            offset = pageEnd
            pageCount += 1
        }

        val parsedPreSkip = preSkip ?: return null
        if (finalGranule <= 0) return null
        val playableSamples = finalGranule - parsedPreSkip
        if (playableSamples <= 0) return null
        val durationMs = ((playableSamples * 1_000L) / SAMPLE_RATE).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return OggOpusMetadata(durationMs = durationMs)
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
