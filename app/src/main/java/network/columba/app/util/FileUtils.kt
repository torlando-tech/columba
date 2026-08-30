package network.columba.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Utilities for handling file attachments in LXMF messages.
 *
 * Provides functions for reading files from URIs, extracting metadata,
 * and formatting file information for display.
 */
object FileUtils {
    private const val TAG = "FileUtils"

    /**
     * Maximum size for a single file attachment, in bytes (512KB).
     *
     * Bounds the in-memory read performed by [readFileFromUri] /
     * [readFileFromUriWithResult]: an unbounded readBytes() of a user-picked
     * file OOMed a device on a ~181MB pick (COLUMBA-4A). Files above this cap
     * are rejected as FileTooLarge instead of being read into memory.
     * Composer SEND picks use the larger send-path bound (32MB, the same
     * ceiling MessagingViewModel enforces before handing bytes to Reticulum)
     * via [readFileForSendWithResult]; both bounds are enforced by a bounded
     * stream read, never an unbounded one.
     */
    const val MAX_SINGLE_FILE_SIZE = 512 * 1024 // 512KB

    /**
     * Maximum total size for all file attachments combined, in bytes (32MB).
     *
     * Used by [wouldExceedSizeLimit] to bound the combined in-memory working
     * set for the message composer. Must stay below Int.MAX_VALUE — that value
     * disabled the guard (COLUMBA-4A regression).
     */
    const val MAX_TOTAL_ATTACHMENT_SIZE = 512 * 1024 * 64 // 32MB

    /**
     * Result of attempting to read a file attachment.
     */
    sealed class FileReadResult {
        data class Success(
            val attachment: FileAttachment,
        ) : FileReadResult()

        data class FileTooLarge(
            /** Actual size in bytes, or -1 when the provider never revealed a
             *  size and the bounded stream read tripped the cap mid-stream. */
            val actualSize: Long,
            val maxSize: Int,
        ) : FileReadResult()

        data class Error(
            val message: String,
        ) : FileReadResult()
    }

    /**
     * Get the size of a file from a content URI without reading the entire file.
     */
    fun getFileSize(
        context: Context,
        uri: Uri,
    ): Long =
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize
            } ?: -1
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine file size for: $uri", e)
            -1
        }

    /**
     * Read file data from a content URI with detailed result.
     *
     * Bounds the read to [MAX_SINGLE_FILE_SIZE] (512KB). Callers selecting a
     * composer *send* attachment must use [readFileForSendWithResult] instead:
     * the send path accepts up to [MAX_TOTAL_ATTACHMENT_SIZE] per file, and
     * capping composer picks at 512KB regressed the canonical E2E 1MB pick.
     *
     * @param context Android context for ContentResolver access
     * @param uri The content URI of the file to read
     * @return FileReadResult indicating success, file too large, or error
     */
    fun readFileFromUriWithResult(
        context: Context,
        uri: Uri,
    ): FileReadResult = readFileWithResultBounded(context, uri, MAX_SINGLE_FILE_SIZE)

    /**
     * Read file data from a content URI.
     *
     * The single-file size cap ([MAX_SINGLE_FILE_SIZE]) is enforced BEFORE any
     * bytes are read: oversized files return null instead of being pulled
     * into memory via readBytes(), which would OOM the process (COLUMBA-4A).
     * Composer SEND picks use the larger send-path bound via
     * [readFileForSendWithResult] instead.
     *
     * @param context Android context for ContentResolver access
     * @param uri The content URI of the file to read
     * @return FileAttachment containing the file data and metadata, or null if the file
     *         couldn't be read (including when it exceeds [MAX_SINGLE_FILE_SIZE])
     */
    fun readFileFromUri(
        context: Context,
        uri: Uri,
    ): FileAttachment? =
        try {
            val contentResolver = context.contentResolver

            // Check file size first without reading the entire file. Without
            // this guard an oversized pick is read fully into a ByteArray and
            // can crash the process (COLUMBA-4A).
            val fileSize = getFileSize(context, uri)
            if (fileSize > MAX_SINGLE_FILE_SIZE) {
                Log.w(TAG, "File exceeds ${MAX_SINGLE_FILE_SIZE} bytes, refusing to read: $uri")
                return null
            }

            // Get filename
            val filename = getFilename(context, uri) ?: "unknown"

            // Get MIME type
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

            // Read data with a hard bound on the allocation itself (sibling
            // parity with readFileFromUriWithResult): metadata alone cannot be
            // trusted, so the read aborts at the cap (Greptile P1).
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val data =
                    readBounded(
                        inputStream,
                        MAX_SINGLE_FILE_SIZE,
                        reopen = { contentResolver.openInputStream(uri) },
                    )
                        ?: run {
                            Log.w(TAG, "Stream exceeded ${MAX_SINGLE_FILE_SIZE} bytes, aborting read: $uri")
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
    ): String? =
        try {
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

    /**
     * Get the appropriate Material icon for a MIME type.
     *
     * @param mimeType The MIME type to get an icon for
     * @return An ImageVector icon appropriate for the file type
     */
    fun getFileIconForMimeType(mimeType: String): ImageVector =
        when {
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

    /**
     * Format file size in human-readable format.
     *
     * @param bytes The file size in bytes
     * @return A formatted string like "1.5 KB" or "512 B"
     */
    fun formatFileSize(bytes: Int): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
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
     * @return true if adding the file would exceed the limit
     */
    fun wouldExceedSizeLimit(
        currentTotal: Int,
        newFileSize: Int,
    ): Boolean = (currentTotal + newFileSize) > MAX_TOTAL_ATTACHMENT_SIZE

    /**
     * Threshold for file-based transfer via temp files.
     * Files larger than this are written to disk and passed via path to avoid
     * Android Binder IPC transaction size limits (~1MB).
     */
    const val FILE_TRANSFER_THRESHOLD = 500 * 1024 // 500KB

    private const val TEMP_ATTACHMENTS_DIR = "attachments"
    private const val SHARE_IMAGES_DIR = "share_images"
    private const val INCOMING_SHARE_DIR = "incoming_shares"
    private const val OUTGOING_HEX_DIR = "outgoing_hex"
    private const val VOICE_NOTES_DIR = "voice-notes"
    private val voiceCachePrefixes = listOf("voice_message_", "voice_preview_", "voice_waveform_")

    /**
     * Write file data to a temporary file for large file transfer.
     *
     * Used to bypass Android Binder IPC size limits by passing file paths
     * instead of raw bytes through AIDL.
     *
     * @param context Android context for accessing cache directory
     * @param filename Original filename (used as suffix for temp file)
     * @param data File data to write
     * @return The temporary file containing the data
     */
    fun writeTempAttachment(
        context: Context,
        filename: String,
        data: ByteArray,
    ): File {
        val tempDir = File(context.cacheDir, TEMP_ATTACHMENTS_DIR)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        // Use timestamp prefix to ensure uniqueness
        val safeFilename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val tempFile = File(tempDir, "${System.currentTimeMillis()}_$safeFilename")
        tempFile.writeBytes(data)
        Log.d(TAG, "Wrote temp attachment: ${tempFile.absolutePath} (${data.size} bytes)")
        return tempFile
    }

    /**
     * Clean up old temporary attachment files.
     *
     * Call this periodically to remove any orphaned temp files that weren't
     * cleaned up by Python after sending.
     *
     * @param context Android context for accessing cache directory
     * @param maxAgeMs Maximum age in milliseconds before files are deleted (default: 1 hour)
     */
    fun cleanupTempAttachments(
        context: Context,
        maxAgeMs: Long = 60 * 60 * 1000,
    ) {
        val tempDir = File(context.cacheDir, TEMP_ATTACHMENTS_DIR)
        if (!tempDir.exists()) return

        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        tempDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                if (file.delete()) {
                    Log.d(TAG, "Cleaned up old temp attachment: ${file.name}")
                }
            }
        }
    }

    /**
     * Clean up all temporary cache directories used for attachments and sharing.
     *
     * This includes:
     * - attachments/ - temp files for viewing received attachments
     * - share_images/ - temp files for sharing images to other apps
     *
     * Call this on app startup to prevent unbounded cache growth.
     * Uses file.lastModified() for age-based cleanup, independent of filename format.
     *
     * @param context Android context for accessing cache directory
     * @param maxAgeMs Maximum age in milliseconds before files are deleted (default: 1 hour)
     * @return Number of files cleaned up
     */
    fun cleanupAllTempFiles(
        context: Context,
        maxAgeMs: Long = 60 * 60 * 1000,
    ): Int {
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        val dirsToClean =
            listOf(TEMP_ATTACHMENTS_DIR, SHARE_IMAGES_DIR, INCOMING_SHARE_DIR, OUTGOING_HEX_DIR, VOICE_NOTES_DIR)

        val voiceRootCount =
            context.cacheDir.listFiles()
                ?.asSequence()
                ?.filter { file -> file.isFile && voiceCachePrefixes.any(file.name::startsWith) }
                ?.filter { it.lastModified() < cutoffTime }
                ?.count { file ->
                    file.delete().also { deleted ->
                        if (deleted) Log.d(TAG, "Cleaned up old voice temp file: ${file.name}")
                    }
                } ?: 0
        val cleanedCount =
            dirsToClean.sumOf { dirName ->
                cleanupDirectory(File(context.cacheDir, dirName), cutoffTime, dirName)
            } + voiceRootCount

        if (cleanedCount > 0) {
            Log.d(TAG, "Cleaned up $cleanedCount old temp file(s)")
        }
        return cleanedCount
    }

    /**
     * Copy a content:// URI to a stable temp file in cache/incoming_shares/.
     *
     * Content URIs from ACTION_SEND are ephemeral — the sending app may revoke
     * read permission once our Activity is paused or recreated. Copying the bytes
     * immediately while permissions are valid produces a file:// URI that remains
     * readable for the entire image-sharing flow.
     *
     * @param context Android context for ContentResolver and cache directory
     * @param uri The content URI to copy
     * @param index Ordinal used as filename prefix to preserve share order
     * @return A file:// URI pointing to the temp copy, or null on failure
     */
    fun copyUriToTempFile(
        context: Context,
        uri: Uri,
        index: Int,
    ): Uri? =
        try {
            val dir = File(context.cacheDir, INCOMING_SHARE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val extension =
                run {
                    val filename = getFilename(context, uri)
                    if (filename != null && filename.contains('.')) {
                        ".${filename.substringAfterLast('.')}"
                    } else {
                        val mimeType = context.contentResolver.getType(uri)
                        when {
                            mimeType == null -> ".jpg"
                            mimeType.contains("png") -> ".png"
                            mimeType.contains("gif") -> ".gif"
                            mimeType.contains("webp") -> ".webp"
                            else -> ".jpg"
                        }
                    }
                }
            val tempFile = File(dir, "${index}_${System.currentTimeMillis()}$extension")

            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.w(TAG, "Could not open input stream for URI: $uri")
                return null
            }

            Log.d(TAG, "Copied shared URI to temp file: ${tempFile.name} (${tempFile.length()} bytes)")
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to temp file: $uri", e)
            null
        }

    /**
     * Delete all files in the incoming_shares directory.
     * Called from SharedImageViewModel.onCleared() to clean up unconsumed temp files.
     */
    fun cleanupIncomingShares(context: Context) {
        val dir = File(context.cacheDir, INCOMING_SHARE_DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Clean up old files in a single directory.
     *
     * @param dir The directory to clean
     * @param cutoffTime Files modified before this time will be deleted
     * @param dirNameForLog Directory name for logging
     * @return Number of files deleted
     */
    private fun cleanupDirectory(
        dir: File,
        cutoffTime: Long,
        dirNameForLog: String,
    ): Int {
        if (!dir.exists()) return 0

        return dir
            .listFiles()
            ?.filter { it.lastModified() < cutoffTime }
            ?.count { file ->
                file.delete().also { deleted ->
                    if (deleted) Log.d(TAG, "Cleaned up old temp file: $dirNameForLog/${file.name}")
                }
            } ?: 0
    }
}

/** Copy buffer used by [readBounded]; also the max slack past the cap a bounded read may consume. */
private const val FILE_READ_BUFFER_SIZE = 64 * 1024

/**
 * Read a composer *send* attachment from a content URI with detailed result.
 *
 * Same contract as [FileUtils.readFileFromUriWithResult] but bounded to
 * [FileUtils.MAX_TOTAL_ATTACHMENT_SIZE] (32MB) — the per-file ceiling the
 * send path itself enforces before handing bytes to Reticulum
 * (MessagingViewModel rejects a larger payload), so the composer picker must
 * not reject files the send side would accept. A 1MB composer pick (the
 * canonical Real LXMF Resource Progress E2E case) attaches with full bytes.
 *
 * The COLUMBA-4A OOM guard is identical and NOT relaxed by the larger cap:
 * the metadata pre-check plus the bounded stream read still refuse a ~181MB
 * pick before (or mid-) any allocation, even with missing or understated
 * provider metadata.
 */
fun FileUtils.readFileForSendWithResult(
    context: Context,
    uri: Uri,
): FileUtils.FileReadResult = readFileWithResultBounded(context, uri, MAX_TOTAL_ATTACHMENT_SIZE)

/**
 * Shared implementation behind [FileUtils.readFileFromUriWithResult] and
 * [FileUtils.readFileForSendWithResult]: metadata pre-check first, then a
 * hard bound on the allocation itself via [readBounded] — unknown (-1) or
 * understated provider metadata passes the pre-check, so readBytes() must
 * never be trusted to stay small (COLUMBA-4A Greptile P1).
 */
private fun FileUtils.readFileWithResultBounded(
    context: Context,
    uri: Uri,
    maxFileSize: Int,
): FileUtils.FileReadResult {
    val tag = "FileUtils"
    return try {
        val contentResolver = context.contentResolver

        // Check file size first without reading the entire file
        val fileSize = getFileSize(context, uri)
        if (fileSize > maxFileSize) {
            return FileUtils.FileReadResult.FileTooLarge(fileSize, maxFileSize)
        }

        // Get filename
        val filename = getFilename(context, uri) ?: "unknown"

        // Get MIME type
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // Read data with a hard bound on the allocation itself: unknown
        // (-1) or understated provider metadata passes the pre-check, so
        // readBytes() must never be trusted to stay small (Greptile P1).
        // When the provider DID report a plausible size, seed the
        // accumulator with it so the read is a single exact allocation
        // (no doubling churn, no second copy — the heap-spike correction).
        val initialCapacity =
            if (fileSize in 1..maxFileSize.toLong()) {
                fileSize.toInt()
            } else {
                FILE_READ_BUFFER_SIZE
            }
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val data =
                readBounded(
                    inputStream,
                    maxFileSize,
                    initialCapacity,
                    reopen = { contentResolver.openInputStream(uri) },
                )
                    ?: return FileUtils.FileReadResult.FileTooLarge(-1L, maxFileSize)

            FileUtils.FileReadResult.Success(
                FileAttachment(
                    filename = filename,
                    data = data,
                    mimeType = mimeType,
                    sizeBytes = data.size,
                ),
            )
        } ?: FileUtils.FileReadResult.Error("Could not open file")
    } catch (e: Exception) {
        Log.e(tag, "Failed to read file from URI: $uri", e)
        FileUtils.FileReadResult.Error(e.message ?: "Unknown error")
    }
}

/**
 * Read [input] into memory but NEVER beyond [maxBytes]: aborts as soon as
 * the stream yields more than the cap (at most one read buffer past it),
 * returning null instead of allocating the whole stream.
 *
 * Heap shape (COLUMBA-4A Greptile P1 "Trim copy doubles heap"): a
 * near-cap successful read must NEVER transiently hold two near-full
 * arrays. The single-accumulator growth path ended in `copyOf(total)`,
 * which held the full accumulator AND a near-file-sized trimmed result at
 * once (~64MB peak on the 32MB send path). The fix:
 *
 *  - While the read is under [TRIM_FREE_RETAIN_THRESHOLD] the single
 *    hard-capped accumulator is kept and any final trim is bounded to a
 *    few MB (capacity < 2x threshold), so the transient can never be a
 *    near-cap spike. The exact-seed known-size case remains copy-free
 *    (capacity == total returns the accumulator itself).
 *  - A near-threshold-or-larger read that finished with an overshooting
 *    capacity does NOT trim. It REWINDS: [reopen] re-opens the stream,
 *    the fresh stream is verified byte-for-byte against the first
 *    [REWIND_VERIFY_BYTES] of the accumulator (a provider that regenerates
 *    content per open fails verification and falls back to the trim),
 *    the full length is counted with zero accumulation (aborting past the
 *    cap), the accumulator reference is DROPPED, and exactly ONE array of
 *    the true size is allocated and filled. The live set at the exact
 *    allocation is one array plus small buffers.
 *
 * The abort path stays strictly single-pass — an over-cap stream never
 * triggers a rewind (EOF is never reached), so bytes consumed stay within
 * maxBytes + one buffer exactly as pinned by the OOM-guard tests.
 *
 * This is the enforcement point for [FileUtils.MAX_SINGLE_FILE_SIZE]. The
 * metadata pre-check via [FileUtils.getFileSize] is only a fast path —
 * providers may report statSize as -1 or understate the stream size, and
 * [InputStream.readBytes] would then allocate the entire (potentially huge)
 * stream before any check could fire (COLUMBA-4A Greptile P1).
 *
 * @param reopen re-opens the underlying stream from the beginning for the
 *        rewind path; when null, rewinding is unavailable and the trim
 *        fallback is used for overshooting capacities.
 * @param heapObserver test seam: receives every accumulator allocation and
 *        release so the live-bytes bound can be asserted deterministically.
 * @return the bytes read (size <= [maxBytes]), or null if the stream exceeded
 *         [maxBytes] and the read was aborted.
 */
internal fun readBounded(
    input: InputStream,
    maxBytes: Int,
    initialCapacity: Int = FILE_READ_BUFFER_SIZE,
    reopen: (() -> InputStream?)? = null,
    heapObserver: ReadBoundedHeapObserver? = null,
): ByteArray? {
    val pass = accumulateFirstPass(input, maxBytes, initialCapacity, heapObserver)
        ?: return null
    if (pass.data.size == pass.total) {
        // Hand back the accumulator itself when its capacity is exact — no
        // second full copy (the known-size seed makes this the common case).
        return pass.data
    }
    return finishOversizedRead(pass, reopen, maxBytes, heapObserver)
}

/** First-pass accumulation state handed to the finalization step. */
private class FirstPass(
    val data: ByteArray,
    val total: Int,
    val rewindArmed: Boolean,
)

/**
 * Single bounded pass over [input] into a hard-capped accumulator.
 * Returns null iff the stream exceeded [maxBytes] (abort — the dangerous
 * unbounded allocation never happens).
 */
private fun accumulateFirstPass(
    input: InputStream,
    maxBytes: Int,
    initialCapacity: Int,
    heapObserver: ReadBoundedHeapObserver?,
): FirstPass? {
    val buffer = ByteArray(FILE_READ_BUFFER_SIZE)
    heapObserver?.onHeapEvent(released = false, FILE_READ_BUFFER_SIZE)
    val seedCapacity = minOf(maxBytes, maxOf(initialCapacity, FILE_READ_BUFFER_SIZE))
    heapObserver?.onHeapEvent(released = false, seedCapacity)
    var data = ByteArray(seedCapacity)
    // A large seed capacity can already sit above the trim-free threshold;
    // any later trim of it would double the heap, so arm the rewind from
    // the start in that case.
    var rewindArmed = seedCapacity > TRIM_FREE_RETAIN_THRESHOLD
    var total = 0
    while (true) {
        val n = input.read(buffer, 0, buffer.size)
        if (n < 0) break
        total += n
        if (total > maxBytes) {
            // Abort before accumulating the overflow — the dangerous
            // unbounded allocation never happens.
            return null
        }
        if (total > data.size) {
            // Grow, capped hard at maxBytes: the accumulator can NEVER
            // exceed the cap, so an oversized/unknown stream is refused
            // before any allocation past it.
            val doubled = if (data.size > maxBytes / 2) maxBytes else data.size * 2
            val newCapacity = minOf(maxBytes, maxOf(total, doubled))
            heapObserver?.onHeapEvent(released = false, newCapacity)
            val grown = data.copyOf(newCapacity)
            heapObserver?.onHeapEvent(released = true, data.size)
            data = grown
            // Once the accumulator is big, its final trim (if any) is a
            // near-cap second copy: arm the rewind double-read instead.
            if (newCapacity > TRIM_FREE_RETAIN_THRESHOLD) rewindArmed = true
        }
        buffer.copyInto(data, total - n, 0, n)
    }
    return FirstPass(data, total, rewindArmed)
}

/**
 * Produce the final array for an EOF'd pass whose accumulator capacity
 * overshot the read size ([pass].data.size > [pass].total).
 *
 * When the accumulator is big ([pass].rewindArmed) and the stream is
 * re-openable, trim-copying would hold TWO near-full arrays at once:
 * instead verify the provider restarts from the beginning, count the true
 * length with zero accumulation, record the accumulator release BEFORE the
 * single exact allocation, and fill it from a fresh open. A
 * non-restartable provider falls back to the trim (correctness over the
 * fast path); a stream whose true length passes the cap aborts.
 */
private fun finishOversizedRead(
    pass: FirstPass,
    reopen: (() -> InputStream?)?,
    maxBytes: Int,
    heapObserver: ReadBoundedHeapObserver?,
): ByteArray? {
    if (pass.rewindArmed && reopen != null) {
        val verified = verifyRewindableLength(reopen, pass.data, pass.total, maxBytes)
        if (verified == REWIND_EXCEEDS_CAP) return null // true length past the cap
        if (verified >= 0) {
            heapObserver?.onHeapEvent(released = true, pass.data.size)
            heapObserver?.onHeapEvent(released = false, verified)
            return fillExactFromReopen(reopen, verified)
        }
        // REWIND_NOT_RESTARTABLE: fall through to the trim below rather
        // than fail the read; the result is still hard-bounded at maxBytes.
    }
    // Small/medium reads (transient bounded by ~2x threshold, never a
    // near-cap spike) or the non-rewindable fallback: a single trim copy
    // keeps the returned array exactly sizeBytes long.
    heapObserver?.onHeapEvent(released = false, pass.total)
    val trimmed = pass.data.copyOf(pass.total)
    heapObserver?.onHeapEvent(released = true, pass.data.size)
    return trimmed
}

/**
 * Allocate exactly [verified] bytes and fill them from a fresh open of
 * [reopen]. Returns null only if the provider cannot serve the verified
 * length on this open (content shrank mid-fill — refuse rather than
 * return corrupt bytes).
 */
private fun fillExactFromReopen(
    reopen: () -> InputStream?,
    verified: Int,
): ByteArray? {
    val filler = reopen() ?: return null
    val exact = ByteArray(verified)
    filler.use { s ->
        var pos = 0
        while (pos < verified) {
            val n = s.read(exact, pos, verified - pos)
            if (n < 0) return null
            pos += n
        }
    }
    return exact
}

/**
 * Test seam for [readBounded]'s heap accounting: every accumulator
 * allocation and every drop of a no-longer-referenced accumulator array is
 * reported so tests can sum live bytes and assert the transient peak.
 */
internal fun interface ReadBoundedHeapObserver {
    fun onHeapEvent(
        released: Boolean,
        sizeBytes: Int,
    )
}

/** Prefix bytes compared to prove a re-opened stream restarts identically. */
private const val REWIND_VERIFY_BYTES = 64 * 1024

/** [verifyRewindableLength] result: provider does not restart from the beginning. */
private const val REWIND_NOT_RESTARTABLE = -1

/** [verifyRewindableLength] result: the stream's true length exceeds the cap. */
private const val REWIND_EXCEEDS_CAP = -2

/**
 * Reads larger than this must not pay a trim copy of a big accumulator
 * (transient 2x-arrays peak): [readBounded] rewinds and re-reads into one
 * exact-sized array instead. Below it the accumulator trim is bounded by
 * roughly one doubling step and stays a few MB at most.
 */
private const val TRIM_FREE_RETAIN_THRESHOLD = 4 * 1024 * 1024

/**
 * Verify — with zero big-array allocation — that a stream re-opened via
 * [reopen] restarts byte-for-byte identically to the first
 * [REWIND_VERIFY_BYTES] of [accumulator], and count its full length,
 * aborting past [maxBytes].
 *
 * A pipe/streamed provider (fresh open yields different/continued content)
 * or one whose stream shrank below what was already consumed fails
 * verification, and the caller must fall back to trimming the accumulator.
 *
 * @param accumulator first-pass bytes (only the first [total] are valid).
 * @return the verified total length (>= 0), [REWIND_NOT_RESTARTABLE], or
 *         [REWIND_EXCEEDS_CAP].
 */
private fun verifyRewindableLength(
    reopen: () -> InputStream?,
    accumulator: ByteArray,
    total: Int,
    maxBytes: Int,
): Int {
    val verifyLen = minOf(total, REWIND_VERIFY_BYTES)
    val verifyBuffer = ByteArray(minOf(verifyLen, FILE_READ_BUFFER_SIZE))
    val counterBuffer = ByteArray(FILE_READ_BUFFER_SIZE)
    val stream = reopen() ?: return REWIND_NOT_RESTARTABLE
    return stream.use { s ->
        var pos = 0
        while (pos < verifyLen) {
            val want = minOf(verifyBuffer.size, verifyLen - pos)
            val n = s.read(verifyBuffer, 0, want)
            // Fresh stream shorter than what we already consumed: not a
            // restart from the beginning.
            if (n < 0) return@use REWIND_NOT_RESTARTABLE
            for (i in 0 until n) {
                if (verifyBuffer[i] != accumulator[pos + i]) return@use REWIND_NOT_RESTARTABLE
            }
            pos += n
        }
        var counted = pos
        while (true) {
            val n = s.read(counterBuffer, 0, counterBuffer.size)
            if (n < 0) break
            counted += n
            if (counted > maxBytes) return@use REWIND_EXCEEDS_CAP
        }
        counted
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

/**
 * Stream this ByteArray to a file as hex characters, two per byte.
 *
 * Unlike in-memory hex conversion which allocates a CharArray(size*2),
 * this writes char-by-char through a BufferedWriter — O(1) extra memory
 * regardless of input size. Prevents OOM when hex-encoding large file
 * attachments (e.g., 111MB file would otherwise require a 222MB CharArray).
 */
fun ByteArray.streamHexToFile(outputFile: File) {
    outputFile.bufferedWriter().use { writer ->
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            writer.append(HEX_CHARS[v ushr 4])
            writer.append(HEX_CHARS[v and 0x0F])
        }
    }
}
