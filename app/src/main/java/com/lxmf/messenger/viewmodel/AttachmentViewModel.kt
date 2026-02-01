package com.lxmf.messenger.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.service.AttachmentStorageService
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for managing message attachment selection and compression.
 *
 * Extracted from MessagingViewModel to follow single responsibility principle.
 * Handles UI state for:
 * - Image selection, compression, and quality options
 * - File attachment management (add, remove, clear)
 *
 * For saving/sharing received attachments, use [AttachmentStorageService] directly.
 * This separation keeps the ViewModel focused on UI state management while
 * storage operations are handled by a dedicated service.
 *
 * @see AttachmentStorageService for save/share operations
 */
@HiltViewModel
class AttachmentViewModel
    @Inject
    constructor(
        val storageService: AttachmentStorageService,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AttachmentViewModel"
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
            combine(
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
