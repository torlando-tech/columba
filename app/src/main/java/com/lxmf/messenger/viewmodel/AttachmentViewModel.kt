package com.lxmf.messenger.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.ui.model.loadFileAttachmentData
import com.lxmf.messenger.ui.model.loadFileAttachmentMetadata
import com.lxmf.messenger.ui.model.loadImageData
import com.lxmf.messenger.util.FileAttachment
import com.lxmf.messenger.util.ImageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for managing message attachments (images and files).
 *
 * Extracted from MessagingViewModel to follow single responsibility principle.
 * Handles:
 * - Image selection, compression, and quality options
 * - File attachment management (add, remove, clear)
 * - Saving received attachments to user storage
 * - Creating shareable URIs for attachments
 *
 * This ViewModel can be used alongside MessagingViewModel in screens
 * that need attachment functionality.
 */
@HiltViewModel
class AttachmentViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AttachmentViewModel"

            /**
             * Sanitize a filename to prevent path traversal attacks.
             * Removes path separators and invalid characters, limits length.
             */
            private fun sanitizeFilename(filename: String): String =
                filename
                    .replace(Regex("[/\\\\]"), "_")
                    .replace(Regex("[<>:\"|?*]"), "_")
                    .take(255)
                    .ifEmpty { "attachment" }
        }

        // ========== Image Attachment State ==========

        private val _selectedImageData = MutableStateFlow<ByteArray?>(null)
        val selectedImageData: StateFlow<ByteArray?> = _selectedImageData.asStateFlow()

        private val _selectedImageFormat = MutableStateFlow<String?>(null)
        val selectedImageFormat: StateFlow<String?> = _selectedImageFormat.asStateFlow()

        private val _isProcessingImage = MutableStateFlow(false)
        val isProcessingImage: StateFlow<Boolean> = _isProcessingImage.asStateFlow()

        private val _selectedImageIsAnimated = MutableStateFlow(false)
        val selectedImageIsAnimated: StateFlow<Boolean> = _selectedImageIsAnimated.asStateFlow()

        // ========== File Attachment State ==========

        private val _selectedFileAttachments = MutableStateFlow<List<FileAttachment>>(emptyList())
        val selectedFileAttachments: StateFlow<List<FileAttachment>> = _selectedFileAttachments.asStateFlow()

        private val _isProcessingFile = MutableStateFlow(false)
        val isProcessingFile: StateFlow<Boolean> = _isProcessingFile.asStateFlow()

        private val _fileAttachmentError = MutableSharedFlow<String>()
        val fileAttachmentError: SharedFlow<String> = _fileAttachmentError.asSharedFlow()

        // ========== Computed State ==========

        val totalAttachmentSize: StateFlow<Int> =
            _selectedFileAttachments
                .map { files -> files.sumOf { it.sizeBytes } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = 0,
                )

        /**
         * Check if any attachments are selected (image or files).
         */
        val hasAttachments: StateFlow<Boolean> =
            kotlinx.coroutines.flow
                .combine(
                    _selectedImageData,
                    _selectedFileAttachments,
                ) { imageData, files ->
                    imageData != null || files.isNotEmpty()
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = false,
                )

        // ========== Quality Selection State ==========

        private val _qualitySelectionState = MutableStateFlow<ImageQualitySelectionState?>(null)
        val qualitySelectionState: StateFlow<ImageQualitySelectionState?> = _qualitySelectionState.asStateFlow()

        // ========== Image Selection Methods ==========

        /**
         * Select an image for attachment.
         *
         * @param imageData The raw image bytes
         * @param imageFormat The image format (e.g., "jpg", "png", "gif")
         * @param isAnimated Whether the image is animated (GIF)
         */
        fun selectImage(
            imageData: ByteArray,
            imageFormat: String,
            isAnimated: Boolean = false,
        ) {
            Log.d(TAG, "Selected image: ${imageData.size} bytes, format=$imageFormat, animated=$isAnimated")
            _selectedImageData.value = imageData
            _selectedImageFormat.value = imageFormat
            _selectedImageIsAnimated.value = isAnimated
        }

        /**
         * Clear the currently selected image.
         */
        fun clearSelectedImage() {
            Log.d(TAG, "Clearing selected image")
            _selectedImageData.value = null
            _selectedImageFormat.value = null
            _selectedImageIsAnimated.value = false
        }

        /**
         * Set the image processing state (shown during compression).
         */
        fun setProcessingImage(processing: Boolean) {
            _isProcessingImage.value = processing
        }

        // ========== File Attachment Methods ==========

        /**
         * Add a file attachment.
         *
         * File attachments have no size limit - they are sent uncompressed.
         * Large files may be slow or unreliable over mesh networks.
         *
         * @param attachment The file attachment to add
         */
        fun addFileAttachment(attachment: FileAttachment) {
            viewModelScope.launch {
                val currentFiles = _selectedFileAttachments.value
                _selectedFileAttachments.value = currentFiles + attachment
                Log.d(TAG, "Added file attachment: ${attachment.filename} (${attachment.sizeBytes} bytes)")
            }
        }

        /**
         * Remove a file attachment by index.
         *
         * @param index The index of the file to remove
         */
        fun removeFileAttachment(index: Int) {
            val currentFiles = _selectedFileAttachments.value
            if (index in currentFiles.indices) {
                val removed = currentFiles[index]
                _selectedFileAttachments.value = currentFiles.toMutableList().apply { removeAt(index) }
                Log.d(TAG, "Removed file attachment: ${removed.filename}")
            }
        }

        /**
         * Clear all selected file attachments.
         */
        fun clearFileAttachments() {
            Log.d(TAG, "Clearing all file attachments")
            _selectedFileAttachments.value = emptyList()
        }

        /**
         * Set the file processing state.
         */
        fun setProcessingFile(processing: Boolean) {
            _isProcessingFile.value = processing
        }

        /**
         * Clear all attachments (images and files).
         */
        fun clearAllAttachments() {
            clearSelectedImage()
            clearFileAttachments()
        }

        // ========== Save/Share Received Attachments ==========

        /**
         * Save a received file attachment to the user's chosen location.
         *
         * @param context Android context for content resolver
         * @param messageId The message ID containing the file attachment
         * @param fileIndex The index of the file attachment in the message's field 5
         * @param destinationUri The Uri where the user wants to save the file
         * @return true if save was successful, false otherwise
         */
        suspend fun saveReceivedFileAttachment(
            context: Context,
            messageId: String,
            fileIndex: Int,
            destinationUri: Uri,
        ): Boolean {
            return try {
                val messageEntity = conversationRepository.getMessageById(messageId)
                if (messageEntity == null) {
                    Log.e(TAG, "Message not found: $messageId")
                    return false
                }

                val fileData = loadFileAttachmentData(messageEntity.fieldsJson, fileIndex)
                if (fileData == null) {
                    Log.e(TAG, "Could not load file attachment data for message $messageId index $fileIndex")
                    return false
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
         * @param context Android context for file operations
         * @param messageId The message ID containing the file attachment
         * @param fileIndex The index of the file attachment in the message's field 5
         * @return Pair of (Uri, mimeType) or null if the file cannot be accessed
         */
        suspend fun getFileAttachmentUri(
            context: Context,
            messageId: String,
            fileIndex: Int,
        ): Pair<Uri, String>? {
            return withContext(Dispatchers.IO) {
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
        }

        /**
         * Save a received image to the user's chosen location.
         *
         * @param context Android context for content resolver
         * @param messageId The message ID containing the image
         * @param destinationUri The Uri where the user wants to save the image
         * @return true if save was successful, false otherwise
         */
        suspend fun saveImage(
            context: Context,
            messageId: String,
            destinationUri: Uri,
        ): Boolean {
            return try {
                val messageEntity = conversationRepository.getMessageById(messageId)
                if (messageEntity == null) {
                    Log.e(TAG, "Message not found: $messageId")
                    return false
                }

                val imageData = loadImageData(messageEntity.fieldsJson)
                if (imageData == null) {
                    Log.e(TAG, "Could not load image data for message $messageId")
                    return false
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
         * @param context Android context for FileProvider
         * @param messageId The message ID containing the image
         * @return The URI for sharing, or null if creation failed
         */
        suspend fun getImageShareUri(
            context: Context,
            messageId: String,
        ): Uri? {
            return withContext(Dispatchers.IO) {
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
                    val metadata =
                        com.lxmf.messenger.ui.model
                            .getImageMetadata(messageEntity.fieldsJson)
                            ?: return@withContext "jpg"
                    // getImageMetadata returns Pair<mimeType, extension>
                    metadata.second.lowercase()
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting image extension", e)
                    "jpg"
                }
            }

        // ========== Image Compression ==========

        /**
         * Process image with compression based on user's selection.
         *
         * Shows quality selection dialog for non-animated images.
         * For animated GIFs under the size limit, preserves animation.
         * For oversized GIFs, compresses (losing animation).
         *
         * @param context Android context for compression operations
         * @param uri URI of the image to process
         * @param recommendedPreset The recommended preset based on network conditions
         */
        fun processImageWithCompression(
            context: Context,
            uri: Uri,
            recommendedPreset: ImageCompressionPreset = ImageCompressionPreset.AUTO,
        ) {
            viewModelScope.launch {
                setProcessingImage(true)

                try {
                    // Check for animated GIF first
                    val rawBytes =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }

                    if (rawBytes != null && ImageUtils.isAnimatedGif(rawBytes)) {
                        if (rawBytes.size <= ImageUtils.MAX_IMAGE_SIZE_BYTES) {
                            // Small animated GIF - preserve animation
                            Log.d(TAG, "Preserving animated GIF (${rawBytes.size} bytes)")
                            selectImage(rawBytes, "gif", isAnimated = true)
                            return@launch
                        } else {
                            Log.w(TAG, "Animated GIF too large, will compress (animation lost)")
                        }
                    }

                    // Show quality selection dialog for non-animated or oversized images
                    _qualitySelectionState.value =
                        ImageQualitySelectionState(
                            imageUri = uri,
                            context = context,
                            recommendedPreset = recommendedPreset,
                        )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                } finally {
                    setProcessingImage(false)
                }
            }
        }

        /**
         * User selected a quality preset from the dialog.
         *
         * Uses ImageUtils.compressImageWithPreset which handles:
         * - EXIF orientation correction
         * - Progressive quality reduction to meet target size
         * - Proper bitmap recycling
         */
        fun selectImageQuality(preset: ImageCompressionPreset) {
            val state = _qualitySelectionState.value ?: return
            _qualitySelectionState.value = null

            viewModelScope.launch {
                setProcessingImage(true)
                try {
                    Log.d(TAG, "User selected quality: ${preset.name}")

                    val result =
                        withContext(Dispatchers.IO) {
                            ImageUtils.compressImageWithPreset(state.context, state.imageUri, preset)
                        }

                    if (result == null) {
                        Log.e(TAG, "Failed to compress image")
                        return@launch
                    }

                    Log.d(TAG, "Image compressed to ${result.compressedImage.data.size} bytes")
                    selectImage(result.compressedImage.data, result.compressedImage.format)
                } catch (e: Exception) {
                    Log.e(TAG, "Error compressing image with selected quality", e)
                } finally {
                    setProcessingImage(false)
                }
            }
        }

        /**
         * Dismiss quality selection dialog without selecting.
         */
        fun dismissQualitySelection() {
            _qualitySelectionState.value = null
        }

        /**
         * Emit a file attachment error for UI feedback.
         */
        fun emitFileAttachmentError(error: String) {
            viewModelScope.launch {
                _fileAttachmentError.emit(error)
            }
        }
    }

/**
 * State for the image quality selection dialog.
 *
 * @property imageUri The URI of the image to compress
 * @property context Android context for compression operations
 * @property recommendedPreset The preset recommended based on network conditions
 */
data class ImageQualitySelectionState(
    val imageUri: Uri,
    val context: Context,
    val recommendedPreset: ImageCompressionPreset,
)
