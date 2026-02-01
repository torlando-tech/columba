package com.lxmf.messenger.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.ui.model.getImageMetadata
import com.lxmf.messenger.ui.model.loadFileAttachmentData
import com.lxmf.messenger.ui.model.loadFileAttachmentMetadata
import com.lxmf.messenger.ui.model.loadImageData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for saving and sharing message attachments.
 *
 * Handles file I/O operations for received attachments:
 * - Saving attachments to user-selected locations
 * - Creating shareable URIs via FileProvider
 * - Getting file metadata (extensions, mime types)
 *
 * Separated from AttachmentViewModel to follow single responsibility:
 * - ViewModel manages UI state for attachment selection
 * - This service handles storage/sharing operations
 */
@Singleton
class AttachmentStorageService
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val conversationRepository: ConversationRepository,
    ) {
        companion object {
            private const val TAG = "AttachmentStorageService"

            /**
             * Sanitize a filename to prevent path traversal attacks.
             * Removes path separators and invalid characters, limits length.
             */
            fun sanitizeFilename(filename: String): String =
                filename
                    .replace(Regex("[/\\\\]"), "_")
                    .replace(Regex("[<>:\"|?*]"), "_")
                    .take(255)
                    .ifEmpty { "attachment" }
        }

        /**
         * Save a received file attachment to the user's chosen location.
         *
         * @param messageId The message ID containing the file attachment
         * @param fileIndex The index of the file attachment in the message's field 5
         * @param destinationUri The Uri where the user wants to save the file
         * @return true if save was successful, false otherwise
         */
        suspend fun saveReceivedFileAttachment(
            messageId: String,
            fileIndex: Int,
            destinationUri: Uri,
        ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val messageEntity = conversationRepository.getMessageById(messageId)
                    if (messageEntity == null) {
                        Log.e(TAG, "Message not found: $messageId")
                        return@withContext false
                    }

                    val fileData = loadFileAttachmentData(messageEntity.fieldsJson, fileIndex)
                    if (fileData == null) {
                        Log.e(TAG, "Could not load file attachment data for message $messageId index $fileIndex")
                        return@withContext false
                    }

                    context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                        outputStream.write(fileData)
                        Log.d(TAG, "Saved file attachment (${fileData.size} bytes) to $destinationUri")
                        true
                    } ?: run {
                        Log.e(TAG, "Could not open output stream for $destinationUri")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save file attachment", e)
                    false
                }
            }

        /**
         * Get a FileProvider URI for a received file attachment.
         *
         * Creates a temporary file in the attachments directory and returns a content URI
         * that can be shared with external apps via Intent.ACTION_VIEW.
         *
         * @param messageId The message ID containing the file attachment
         * @param fileIndex The index of the file attachment in the message's field 5
         * @return Pair of (Uri, mimeType) or null if the file cannot be accessed
         */
        suspend fun getFileAttachmentUri(
            messageId: String,
            fileIndex: Int,
        ): Pair<Uri, String>? =
            withContext(Dispatchers.IO) {
                try {
                    val messageEntity = conversationRepository.getMessageById(messageId)
                    if (messageEntity == null) {
                        Log.e(TAG, "Message not found: $messageId")
                        return@withContext null
                    }

                    val metadata = loadFileAttachmentMetadata(messageEntity.fieldsJson, fileIndex)
                    if (metadata == null) {
                        Log.e(TAG, "Could not load file metadata for message $messageId index $fileIndex")
                        return@withContext null
                    }

                    val (filename, mimeType) = metadata
                    val safeFilename = sanitizeFilename(filename)

                    val fileData = loadFileAttachmentData(messageEntity.fieldsJson, fileIndex)
                    if (fileData == null) {
                        Log.e(TAG, "Could not load file data for message $messageId index $fileIndex")
                        return@withContext null
                    }

                    val attachmentsDir = File(context.cacheDir, "attachments")
                    attachmentsDir.mkdirs()

                    val tempFile = File(attachmentsDir, "${UUID.randomUUID()}_$safeFilename")
                    tempFile.writeBytes(fileData)

                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile,
                        )

                    Log.d(TAG, "Created file URI for attachment: $uri (type: $mimeType)")
                    Pair(uri, mimeType)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get file attachment URI", e)
                    null
                }
            }

        /**
         * Save a received image to the user's chosen location.
         *
         * @param messageId The message ID containing the image
         * @param destinationUri The Uri where the user wants to save the image
         * @return true if save was successful, false otherwise
         */
        suspend fun saveImage(
            messageId: String,
            destinationUri: Uri,
        ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val messageEntity = conversationRepository.getMessageById(messageId)
                    if (messageEntity == null) {
                        Log.e(TAG, "Message not found: $messageId")
                        return@withContext false
                    }

                    val imageData = loadImageData(messageEntity.fieldsJson)
                    if (imageData == null) {
                        Log.e(TAG, "Could not load image data for message $messageId")
                        return@withContext false
                    }

                    context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                        outputStream.write(imageData)
                        Log.d(TAG, "Saved image (${imageData.size} bytes) to $destinationUri")
                        true
                    } ?: run {
                        Log.e(TAG, "Could not open output stream for $destinationUri")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save image", e)
                    false
                }
            }

        /**
         * Get a shareable URI for a received image.
         *
         * @param messageId The message ID containing the image
         * @return The URI for sharing, or null if creation failed
         */
        suspend fun getImageShareUri(messageId: String): Uri? =
            withContext(Dispatchers.IO) {
                try {
                    val messageEntity = conversationRepository.getMessageById(messageId)
                    if (messageEntity == null) {
                        Log.e(TAG, "Message not found: $messageId")
                        return@withContext null
                    }

                    val imageBytes = loadImageData(messageEntity.fieldsJson)
                    if (imageBytes == null) {
                        Log.e(TAG, "No image data found in message $messageId")
                        return@withContext null
                    }

                    val extension = getImageExtension(messageId)
                    val filename = "share_${messageId.take(8)}.$extension"

                    val cacheDir = File(context.cacheDir, "share_images")
                    cacheDir.mkdirs()

                    val tempFile = File(cacheDir, filename)
                    tempFile.writeBytes(imageBytes)

                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create share URI", e)
                    null
                }
            }

        /**
         * Get the image extension from message metadata.
         *
         * Returns the extension (e.g., "jpg", "png", "gif", "webp").
         * Falls back to "jpg" if format cannot be detected.
         */
        suspend fun getImageExtension(messageId: String): String =
            withContext(Dispatchers.IO) {
                try {
                    val messageEntity = conversationRepository.getMessageById(messageId) ?: return@withContext "jpg"
                    val metadata = getImageMetadata(messageEntity.fieldsJson) ?: return@withContext "jpg"
                    // getImageMetadata returns Pair<mimeType, extension>
                    metadata.second.lowercase()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting image extension", e)
                    "jpg"
                }
            }
    }
