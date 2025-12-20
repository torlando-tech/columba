package com.lxmf.messenger.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.material.icons.Icons
import java.util.Locale
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Utilities for handling file attachments in LXMF messages.
 *
 * Provides functions for reading files from URIs, extracting metadata,
 * and formatting file information for display.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    /**
     * Default maximum total size for all file attachments combined (8 MB).
     * This is a fallback value; the actual limit is configurable via settings.
     */
    const val DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE = 8 * 1024 * 1024 // 8MB

    /**
     * Default maximum size for a single file attachment (8 MB).
     * This is a fallback value; the actual limit is configurable via settings.
     */
    const val DEFAULT_MAX_SINGLE_FILE_SIZE = 8 * 1024 * 1024 // 8MB

    /**
     * Threshold above which a slow transfer warning is shown (1 MB).
     * Files larger than this will display a warning about potential slow transfer
     * on mesh networks (LoRa, packet radio, etc.).
     */
    const val SLOW_TRANSFER_WARNING_THRESHOLD = 1024 * 1024 // 1MB

    /**
     * Legacy constants for backwards compatibility.
     * @deprecated Use DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE instead
     */
    @Deprecated(
        "Use DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE instead",
        ReplaceWith("DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE"),
    )
    const val MAX_TOTAL_ATTACHMENT_SIZE = DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE

    /**
     * Legacy constants for backwards compatibility.
     * @deprecated Use DEFAULT_MAX_SINGLE_FILE_SIZE instead
     */
    @Deprecated(
        "Use DEFAULT_MAX_SINGLE_FILE_SIZE instead",
        ReplaceWith("DEFAULT_MAX_SINGLE_FILE_SIZE"),
    )
    const val MAX_SINGLE_FILE_SIZE = DEFAULT_MAX_SINGLE_FILE_SIZE

    /**
     * Read file data from a content URI.
     *
     * @param context Android context for ContentResolver access
     * @param uri The content URI of the file to read
     * @param maxSingleFileSize Maximum allowed file size in bytes, or 0 for no limit.
     *                          Defaults to DEFAULT_MAX_SINGLE_FILE_SIZE.
     * @return FileAttachment containing the file data and metadata, or null if the file
     *         couldn't be read or exceeds size limits
     */
    fun readFileFromUri(
        context: Context,
        uri: Uri,
        maxSingleFileSize: Int = DEFAULT_MAX_SINGLE_FILE_SIZE,
    ): FileAttachment? {
        return try {
            val contentResolver = context.contentResolver

            // Get filename
            val filename = getFilename(context, uri) ?: "unknown"

            // Get MIME type
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

            // Read data
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val data = inputStream.readBytes()

                // Only enforce size limit if maxSingleFileSize > 0
                if (maxSingleFileSize > 0 && data.size > maxSingleFileSize) {
                    Log.w(TAG, "File too large: ${data.size} bytes (max: $maxSingleFileSize)")
                    return null
                }

                FileAttachment(
                    filename = filename,
                    data = data,
                    mimeType = mimeType,
                    sizeBytes = data.size,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file from URI: $uri", e)
            null
        }
    }

    /**
     * Extract filename from a content URI.
     *
     * Uses the OpenableColumns.DISPLAY_NAME column to get the original filename.
     * Falls back to extracting the last path segment if the column is not available.
     *
     * @param context Android context for ContentResolver access
     * @param uri The content URI to extract the filename from
     * @return The filename, or null if it couldn't be determined
     */
    fun getFilename(
        context: Context,
        uri: Uri,
    ): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get filename from URI", e)
            uri.lastPathSegment
        }
    }

    /**
     * Get the appropriate Material icon for a MIME type.
     *
     * @param mimeType The MIME type to get an icon for
     * @return An ImageVector icon appropriate for the file type
     */
    fun getFileIconForMimeType(mimeType: String): ImageVector {
        return when {
            mimeType.startsWith("application/pdf") -> Icons.Default.PictureAsPdf
            mimeType.startsWith("text/") -> Icons.Default.Description
            mimeType.startsWith("audio/") -> Icons.Default.AudioFile
            mimeType.startsWith("video/") -> Icons.Default.VideoFile
            mimeType.contains("zip") ||
                mimeType.contains("compressed") ||
                mimeType.contains("archive") ||
                mimeType.contains("tar") ||
                mimeType.contains("gzip") -> Icons.Default.FolderZip
            else -> Icons.Default.InsertDriveFile
        }
    }

    /**
     * Format file size in human-readable format.
     *
     * @param bytes The file size in bytes
     * @return A formatted string like "1.5 KB" or "512 B"
     */
    fun formatFileSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Map of file extensions to MIME types.
     */
    private val extensionToMimeType =
        mapOf(
            // Documents
            "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // Text
            "txt" to "text/plain",
            "csv" to "text/csv",
            "json" to "application/json",
            "xml" to "application/xml",
            "html" to "text/html",
            "htm" to "text/html",
            // Archives
            "zip" to "application/zip",
            "rar" to "application/x-rar-compressed",
            "7z" to "application/x-7z-compressed",
            "tar" to "application/x-tar",
            "gz" to "application/gzip",
            "gzip" to "application/gzip",
            // Audio
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "flac" to "audio/flac",
            // Video
            "mp4" to "video/mp4",
            "avi" to "video/x-msvideo",
            "mkv" to "video/x-matroska",
            "webm" to "video/webm",
            // Images
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            // Other
            "apk" to "application/vnd.android.package-archive",
        )

    /**
     * Determine MIME type from filename extension.
     *
     * Used as a fallback when the actual MIME type is not available.
     *
     * @param filename The filename to analyze
     * @return The corresponding MIME type, or "application/octet-stream" if unknown
     */
    fun getMimeTypeFromFilename(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extensionToMimeType[extension] ?: "application/octet-stream"
    }

    /**
     * Check if the total size of attachments plus a new file would exceed the limit.
     *
     * @param currentTotal Current total size of selected attachments in bytes
     * @param newFileSize Size of the new file to add in bytes
     * @param maxTotalSize Maximum allowed total size in bytes, or 0 for no limit.
     *                     Defaults to DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE.
     * @return true if adding the file would exceed the limit, false if within limit or no limit set
     */
    fun wouldExceedSizeLimit(
        currentTotal: Int,
        newFileSize: Int,
        maxTotalSize: Int = DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE,
    ): Boolean {
        // No limit if maxTotalSize is 0 or negative
        if (maxTotalSize <= 0) return false
        return (currentTotal + newFileSize) > maxTotalSize
    }

    /**
     * Check if a file size exceeds the slow transfer warning threshold.
     *
     * @param sizeBytes Total file size in bytes
     * @return true if the size exceeds the warning threshold (1 MB)
     */
    fun exceedsSlowTransferThreshold(sizeBytes: Int): Boolean {
        return sizeBytes > SLOW_TRANSFER_WARNING_THRESHOLD
    }
}
