package com.lxmf.messenger.viewmodel

import java.io.InputStream

/**
 * Extracts identity files from Sideband tar archives.
 *
 * Sideband backup tars use `arcname="Sideband Backup"`, so the identity
 * file is at path `Sideband Backup/primary_identity`.
 */
internal object TarIdentityExtractor {
    /**
     * Scan tar entries and return the content of the first entry whose name
     * ends with `primary_identity` or `/identity`.
     *
     * @throws IllegalStateException if the identity is not found or the archive is truncated.
     */
    fun extract(inputStream: InputStream): ByteArray {
        val headerBuffer = ByteArray(512)
        while (true) {
            val bytesRead = inputStream.readFully(headerBuffer, 0, 512)
            check(bytesRead == 512) { "Identity not found in backup (unexpected end of archive)" }

            // Check for zero block (end of archive)
            check(!headerBuffer.all { it == 0.toByte() }) { "Identity not found in backup" }

            // Extract filename from header (bytes 0-99, null-terminated)
            val nameEnd = headerBuffer.indexOfFirst(0, 100) { it == 0.toByte() }
            val fileName = String(headerBuffer, 0, nameEnd).trim()

            // Extract file size from header (bytes 124-135, octal ASCII)
            val sizeStr = String(headerBuffer, 124, 11).trim()
            val fileSize = sizeStr.toLongOrNull(8)
            check(fileSize != null && fileSize >= 0) { "Malformed tar entry size: \"$sizeStr\"" }

            // Calculate padded size (tar entries are padded to 512-byte boundaries)
            val paddedSize = ((fileSize + 511) / 512) * 512

            if (fileName.endsWith("primary_identity") || fileName.endsWith("/identity")) {
                val data = ByteArray(fileSize.toInt())
                val dataRead = inputStream.readFully(data, 0, fileSize.toInt())
                check(dataRead == fileSize.toInt()) { "Truncated identity file in backup" }
                return data
            } else {
                inputStream.skipFully(paddedSize)
            }
        }
    }

    /** Read exactly [len] bytes into [buf] at [off]. Returns bytes read (< len only at EOF). */
    internal fun InputStream.readFully(
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        var totalRead = 0
        while (totalRead < len) {
            val n = read(buf, off + totalRead, len - totalRead)
            if (n < 0) break
            totalRead += n
        }
        return totalRead
    }

    /** Skip exactly [n] bytes, looping since InputStream.skip() may skip fewer. */
    internal fun InputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped == 0L) {
                if (read() < 0) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    /** Find first index where [predicate] is true, searching only bytes 0 until [limit]. */
    private fun ByteArray.indexOfFirst(
        from: Int,
        limit: Int,
        predicate: (Byte) -> Boolean,
    ): Int {
        for (i in from until limit) {
            if (predicate(this[i])) return i
        }
        return limit
    }
}
