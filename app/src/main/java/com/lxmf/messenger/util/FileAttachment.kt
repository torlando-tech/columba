package com.lxmf.messenger.util

/**
 * Represents a file attachment for LXMF messages.
 *
 * Used for LXMF Field 5 (FILE_ATTACHMENTS) which stores a list of
 * [filename, bytes] tuples. This class holds the file data before
 * sending and provides metadata for display.
 *
 * @property filename The original filename including extension
 * @property data The raw file bytes
 * @property mimeType The MIME type of the file (e.g., "application/pdf")
 * @property sizeBytes The size of the file in bytes
 */
data class FileAttachment(
    val filename: String,
    val data: ByteArray,
    val mimeType: String,
    val sizeBytes: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileAttachment

        if (filename != other.filename) return false
        if (!data.contentEquals(other.data)) return false
        if (mimeType != other.mimeType) return false
        if (sizeBytes != other.sizeBytes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + sizeBytes
        return result
    }
}
