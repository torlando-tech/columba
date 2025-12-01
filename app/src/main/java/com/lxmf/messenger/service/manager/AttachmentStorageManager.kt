package com.lxmf.messenger.service.manager

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages disk storage for message attachments.
 *
 * Attachments that exceed AIDL's ~1MB Parcel limit are saved to disk before
 * IPC and loaded back when needed by the UI.
 *
 * Storage layout:
 *   files/attachments/{messageHash}/{fieldKey}
 *
 * Example:
 *   files/attachments/abc123def/6  (image field)
 */
class AttachmentStorageManager(private val context: Context) {
    companion object {
        private const val TAG = "AttachmentStorage"
        private const val ATTACHMENTS_DIR = "attachments"

        /**
         * Threshold for extracting attachments to disk (500KB).
         * Android AIDL limit is ~1MB, but with JSON overhead we use a lower threshold.
         */
        const val SIZE_THRESHOLD = 500 * 1024

        /**
         * Marker key indicating a field is stored on disk.
         */
        const val FILE_REF_KEY = "_file_ref"
    }

    private val attachmentsDir: File by lazy {
        File(context.filesDir, ATTACHMENTS_DIR).also { it.mkdirs() }
    }

    /**
     * Save attachment data to disk.
     *
     * @param messageHash Unique message identifier
     * @param fieldKey LXMF field key (e.g., "6" for image)
     * @param data Attachment data (hex-encoded string)
     * @return File path where data was saved, or null on failure
     */
    fun saveAttachment(
        messageHash: String,
        fieldKey: String,
        data: String,
    ): String? {
        return try {
            val messageDir = File(attachmentsDir, messageHash).also { it.mkdirs() }
            val file = File(messageDir, fieldKey)
            file.writeText(data)
            Log.d(TAG, "Saved attachment ${file.absolutePath} (${data.length} chars)")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save attachment for $messageHash/$fieldKey", e)
            null
        }
    }

    /**
     * Load attachment data from disk.
     *
     * @param filePath Absolute path to attachment file
     * @return Attachment data (hex-encoded string), or null if not found
     */
    fun loadAttachment(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.readText().also {
                    Log.d(TAG, "Loaded attachment $filePath (${it.length} chars)")
                }
            } else {
                Log.w(TAG, "Attachment file not found: $filePath")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load attachment $filePath", e)
            null
        }
    }

    /**
     * Delete all attachments for a message.
     *
     * @param messageHash Message identifier
     */
    fun deleteAttachments(messageHash: String) {
        try {
            val messageDir = File(attachmentsDir, messageHash)
            if (messageDir.exists()) {
                messageDir.deleteRecursively()
                Log.d(TAG, "Deleted attachments for message $messageHash")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete attachments for $messageHash", e)
        }
    }

    /**
     * Clean up orphaned attachments older than the given age.
     *
     * @param maxAgeMs Maximum age in milliseconds (default: 7 days)
     */
    fun cleanupOldAttachments(maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L) {
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            var deletedCount = 0

            attachmentsDir.listFiles()?.forEach { messageDir ->
                if (messageDir.isDirectory && messageDir.lastModified() < cutoff) {
                    messageDir.deleteRecursively()
                    deletedCount++
                }
            }

            if (deletedCount > 0) {
                Log.i(TAG, "Cleaned up $deletedCount old attachment directories")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during attachment cleanup", e)
        }
    }
}
