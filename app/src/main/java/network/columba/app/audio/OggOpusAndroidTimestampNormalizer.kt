package network.columba.app.audio

import java.io.File
import java.io.FileOutputStream

/**
 * Repairs the packet-start granule positions emitted by Android MediaRecorder's Ogg muxer.
 *
 * libopusfile requires each completed Ogg page granule to identify the end of its final Opus
 * packet. Android MediaRecorder instead emits the packet start position, beginning at zero.
 * Android's own decoder and FFmpeg tolerate that layout, but Sideband's LXST/libopusfile path
 * rejects it with OP_EBADTIMESTAMP. Only the exact MediaRecorder layout is rewritten.
 */
internal object OggOpusAndroidTimestampNormalizer {
    private const val CAPTURE_PATTERN = "OggS"
    private const val OPUS_HEAD = "OpusHead"
    private const val OPUS_TAGS = "OpusTags"
    private const val OGG_HEADER_SIZE = 27
    private const val CHECKSUM_OFFSET = 22
    private const val SAMPLE_RATE = 48_000
    private const val MAX_PAGES = 65_536
    private const val MAX_FILE_SIZE_BYTES = 8 * 1024 * 1024

    /** Returns true when [file] was atomically replaced with corrected Ogg bytes. */
    fun normalize(file: File): Boolean {
        require(file.isFile) { "Ogg Opus recording does not exist: $file" }
        require(file.length() in 1..MAX_FILE_SIZE_BYTES.toLong()) { "Ogg Opus recording has an invalid size" }
        val original = file.readBytes()
        val normalized = normalize(original)
        if (normalized === original) return false

        val parent = checkNotNull(file.absoluteFile.parentFile) { "Ogg Opus recording has no parent directory" }
        val replacement = File.createTempFile(".${file.name}.", ".normalized", parent)
        val backup = File.createTempFile(".${file.name}.", ".backup", parent).also { check(it.delete()) }
        try {
            FileOutputStream(replacement).use { output ->
                output.write(normalized)
                output.fd.sync()
            }
            check(file.renameTo(backup)) { "Could not stage original Ogg Opus recording" }
            if (!replacement.renameTo(file)) {
                check(backup.renameTo(file)) { "Could not restore original Ogg Opus recording" }
                error("Could not publish normalized Ogg Opus recording")
            }
            backup.delete()
        } finally {
            replacement.delete()
            if (!file.exists()) backup.renameTo(file)
            backup.delete()
        }
        return true
    }

    /** Returns the original instance when the input already uses end-of-packet granules. */
    internal fun normalize(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_SIZE_BYTES) { "Invalid Ogg Opus size" }
        val pages = parsePages(bytes)
        require(pages.size >= 3) { "Ogg Opus recording has no audio pages" }
        require(bytes.matchesAscii(pages[0].payloadStart, OPUS_HEAD)) { "Missing Opus identification header" }
        require(bytes.matchesAscii(pages[1].payloadStart, OPUS_TAGS)) { "Missing Opus comment header" }

        val audioPages = pages.drop(2)
        if (audioPages.first().granulePosition != 0L) return bytes

        val normalized = bytes.copyOf()
        var elapsedSamples = 0L
        for (page in audioPages) {
            require(!page.isContinued && page.packetCount == 1 && page.packetSize == page.payloadSize) {
                "Unsupported Android Ogg packet layout"
            }
            require(page.granulePosition == elapsedSamples) { "Unexpected Android Ogg granule sequence" }
            val packetSamples = opusPacketSamples(normalized, page.payloadStart, page.packetSize)
            elapsedSamples += packetSamples
            normalized.writeLittleEndianLong(page.offset + 6, elapsedSamples)
        }

        for (page in pages) {
            normalized.fill(0, page.offset + CHECKSUM_OFFSET, page.offset + CHECKSUM_OFFSET + 4)
            normalized.writeLittleEndianInt(page.offset + CHECKSUM_OFFSET, oggChecksum(normalized, page.offset, page.endOffset))
        }
        return normalized
    }

    private fun parsePages(bytes: ByteArray): List<OggPage> {
        val pages = mutableListOf<OggPage>()
        var offset = 0
        var expectedSequence = 0
        var streamSerial: Int? = null
        while (offset < bytes.size && pages.size < MAX_PAGES) {
            require(offset + OGG_HEADER_SIZE <= bytes.size && bytes.matchesAscii(offset, CAPTURE_PATTERN)) {
                "Malformed Ogg page"
            }
            val segmentCount = bytes[offset + 26].toInt() and 0xff
            val segmentTableStart = offset + OGG_HEADER_SIZE
            val payloadStart = segmentTableStart + segmentCount
            require(payloadStart <= bytes.size) { "Truncated Ogg segment table" }
            var payloadSize = 0
            var packetSize = 0
            var packetCount = 0
            for (index in 0 until segmentCount) {
                val lace = bytes[segmentTableStart + index].toInt() and 0xff
                payloadSize += lace
                packetSize += lace
                if (lace < 255) packetCount += 1
            }
            val endOffset = payloadStart + payloadSize
            require(endOffset <= bytes.size) { "Truncated Ogg page payload" }
            val serial = bytes.readLittleEndianInt(offset + 14)
            val sequence = bytes.readLittleEndianInt(offset + 18)
            if (streamSerial == null) streamSerial = serial
            require(serial == streamSerial && sequence == expectedSequence) { "Unexpected Ogg stream sequence" }
            require(oggChecksum(bytes, offset, endOffset, zeroStoredChecksum = true) == bytes.readLittleEndianInt(offset + CHECKSUM_OFFSET)) {
                "Invalid Ogg page checksum"
            }
            pages +=
                OggPage(
                    offset = offset,
                    endOffset = endOffset,
                    payloadStart = payloadStart,
                    payloadSize = payloadSize,
                    packetSize = packetSize,
                    packetCount = packetCount,
                    isContinued = bytes[offset + 5].toInt() and 0x01 != 0,
                    granulePosition = bytes.readLittleEndianLong(offset + 6),
                )
            expectedSequence += 1
            offset = endOffset
        }
        require(offset == bytes.size && pages.size < MAX_PAGES) { "Invalid Ogg page count" }
        return pages
    }

    private fun opusPacketSamples(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(length > 0 && offset + length <= bytes.size) { "Empty Opus packet" }
        val toc = bytes[offset].toInt() and 0xff
        val frames =
            when (toc and 0x03) {
                0 -> 1
                1, 2 -> 2
                else -> {
                    require(length >= 2) { "Truncated multi-frame Opus packet" }
                    bytes[offset + 1].toInt() and 0x3f
                }
            }
        require(frames in 1..48) { "Invalid Opus frame count" }
        val samplesPerFrame =
            when {
                toc and 0x80 != 0 -> (SAMPLE_RATE shl ((toc ushr 3) and 0x03)) / 400
                toc and 0x60 == 0x60 -> if (toc and 0x08 != 0) SAMPLE_RATE / 50 else SAMPLE_RATE / 100
                else -> {
                    val size = (toc ushr 3) and 0x03
                    if (size == 3) SAMPLE_RATE * 60 / 1_000 else (SAMPLE_RATE shl size) / 100
                }
            }
        return (frames * samplesPerFrame).also { require(it in 1..(SAMPLE_RATE * 120 / 1_000)) { "Invalid Opus packet duration" } }
    }

    private fun oggChecksum(
        bytes: ByteArray,
        start: Int,
        end: Int,
        zeroStoredChecksum: Boolean = false,
    ): Int {
        var checksum = 0
        for (index in start until end) {
            val value =
                if (zeroStoredChecksum && index in (start + CHECKSUM_OFFSET) until (start + CHECKSUM_OFFSET + 4)) {
                    0
                } else {
                    bytes[index].toInt() and 0xff
                }
            checksum = (checksum shl 8) xor CRC_LOOKUP[((checksum ushr 24) xor value) and 0xff]
        }
        return checksum
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= size && value.indices.all { this[offset + it] == value[it].code.toByte() }

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.readLittleEndianLong(offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = value or ((this[offset + index].toLong() and 0xffL) shl (index * 8))
        return value
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.writeLittleEndianLong(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private data class OggPage(
        val offset: Int,
        val endOffset: Int,
        val payloadStart: Int,
        val payloadSize: Int,
        val packetSize: Int,
        val packetCount: Int,
        val isContinued: Boolean,
        val granulePosition: Long,
    )

    private val CRC_LOOKUP =
        IntArray(256) { index ->
            var value = index shl 24
            repeat(8) { value = if (value and Int.MIN_VALUE != 0) (value shl 1) xor 0x04c11db7 else value shl 1 }
            value
        }
}
