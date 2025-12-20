package com.lxmf.messenger.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.DeliveryMethod
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.SyncResult
import com.lxmf.messenger.ui.model.ImageCache
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.decodeAndCacheImage
import com.lxmf.messenger.ui.model.loadFileAttachmentData
import com.lxmf.messenger.ui.model.loadFileAttachmentMetadata
import com.lxmf.messenger.ui.model.toMessageUi
import com.lxmf.messenger.util.FileAttachment
import com.lxmf.messenger.util.FileUtils
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import com.lxmf.messenger.data.repository.Message as DataMessage
import com.lxmf.messenger.reticulum.model.Message as ReticulumMessage

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("TooManyFunctions") // ViewModel handles multiple UI operations
class MessagingViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val conversationRepository: com.lxmf.messenger.data.repository.ConversationRepository,
        private val announceRepository: com.lxmf.messenger.data.repository.AnnounceRepository,
        private val contactRepository: com.lxmf.messenger.data.repository.ContactRepository,
        private val activeConversationManager: com.lxmf.messenger.service.ActiveConversationManager,
        private val settingsRepository: SettingsRepository,
        private val propagationNodeManager: PropagationNodeManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MessagingViewModel"
        }

        // Track the currently active conversation - drives reactive message loading
        private val _currentConversation = MutableStateFlow<String?>(null)
        private var currentPeerName: String = "Unknown"

        // Messages automatically update when conversation changes OR database changes
        // Uses Paging3 for efficient infinite scroll: loads 30 messages initially,
        // then loads more in background as user scrolls up
        // PERFORMANCE: toMessageUi() is now fast (cache lookup only, no disk I/O)
        // Image decoding happens asynchronously via loadImageAsync()
        val messages: Flow<PagingData<MessageUi>> =
            _currentConversation
                .flatMapLatest { peerHash ->
                    Log.d(TAG, "Flow: Switching to conversation $peerHash")
                    if (peerHash != null) {
                        conversationRepository.getMessagesPaged(peerHash)
                            .map { pagingData ->
                                pagingData.map { it.toMessageUi() }
                            }
                    } else {
                        flowOf(PagingData.empty())
                    }
                }
                .cachedIn(viewModelScope)

        // Announce info for online status - updates in real-time when new announces arrive
        val announceInfo: StateFlow<com.lxmf.messenger.data.repository.Announce?> =
            _currentConversation
                .flatMapLatest { peerHash ->
                    if (peerHash != null) {
                        announceRepository.getAnnounceFlow(peerHash)
                    } else {
                        flowOf(null)
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = null,
                )

        // Source identity for sending messages (reuse the same one from DebugViewModel concept)
        private var sourceIdentity: Identity? = null

        // Image attachment state
        private val _selectedImageData = MutableStateFlow<ByteArray?>(null)
        val selectedImageData: StateFlow<ByteArray?> = _selectedImageData

        private val _selectedImageFormat = MutableStateFlow<String?>(null)
        val selectedImageFormat: StateFlow<String?> = _selectedImageFormat

        private val _isProcessingImage = MutableStateFlow(false)
        val isProcessingImage: StateFlow<Boolean> = _isProcessingImage

        // File attachment state (LXMF Field 5)
        private val _selectedFileAttachments = MutableStateFlow<List<FileAttachment>>(emptyList())
        val selectedFileAttachments: StateFlow<List<FileAttachment>> = _selectedFileAttachments.asStateFlow()

        private val _isProcessingFile = MutableStateFlow(false)
        val isProcessingFile: StateFlow<Boolean> = _isProcessingFile.asStateFlow()

        // Computed total size of all attachments (images + files)
        val totalAttachmentSize: StateFlow<Int> =
            _selectedFileAttachments
                .map { files -> files.sumOf { it.sizeBytes } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = 0,
                )

        // File attachment error events for UI feedback
        private val _fileAttachmentError = MutableSharedFlow<String>()
        val fileAttachmentError: SharedFlow<String> = _fileAttachmentError.asSharedFlow()

        // Maximum outbound attachment size in bytes from settings (0 means unlimited)
        val maxOutboundAttachmentSizeBytes: StateFlow<Int> =
            settingsRepository.maxOutboundAttachmentSizeKbFlow
                .map { kb -> kb * 1024 }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = SettingsRepository.DEFAULT_MAX_OUTBOUND_ATTACHMENT_SIZE_KB * 1024,
                )

        // Sync state from PropagationNodeManager
        val isSyncing: StateFlow<Boolean> = propagationNodeManager.isSyncing

        // Manual sync result events for Snackbar notifications
        val manualSyncResult: SharedFlow<SyncResult> = propagationNodeManager.manualSyncResult

        // Track which images have been decoded - used to trigger recomposition
        // when images become available. The UI observes this to know when to re-check the cache.
        private val _loadedImageIds = MutableStateFlow<Set<String>>(emptySet())
        val loadedImageIds: StateFlow<Set<String>> = _loadedImageIds.asStateFlow()

        // Contact status for current conversation - updates reactively
        val isContactSaved: StateFlow<Boolean> =
            _currentConversation
                .flatMapLatest { peerHash ->
                    if (peerHash != null) {
                        contactRepository.hasContactFlow(peerHash)
                    } else {
                        flowOf(false)
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = false,
                )

        // Contact toggle result events for toast notifications
        private val _contactToggleResult = MutableSharedFlow<ContactToggleResult>()
        val contactToggleResult: SharedFlow<ContactToggleResult> = _contactToggleResult.asSharedFlow()

        /**
         * Toggle contact status for the current conversation.
         * If the peer is already a contact, removes them. Otherwise, adds them.
         * Emits result via [contactToggleResult] for UI feedback.
         */
        fun toggleContact() {
            val peerHash = _currentConversation.value ?: return
            viewModelScope.launch {
                try {
                    val wasContact = contactRepository.hasContact(peerHash)
                    if (wasContact) {
                        contactRepository.deleteContact(peerHash)
                        Log.d(TAG, "Removed $peerHash from contacts")
                        _contactToggleResult.emit(ContactToggleResult.Removed)
                    } else {
                        // Get public key from conversation
                        val publicKey = conversationRepository.getPeerPublicKey(peerHash)
                        if (publicKey != null) {
                            contactRepository.addContactFromConversation(peerHash, publicKey)
                            Log.d(TAG, "Added $peerHash to contacts from messaging")
                            _contactToggleResult.emit(ContactToggleResult.Added)
                        } else {
                            Log.e(TAG, "Cannot add contact: public key not available for $peerHash")
                            _contactToggleResult.emit(
                                ContactToggleResult.Error("Identity not available - peer hasn't announced"),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling contact status", e)
                    _contactToggleResult.emit(
                        ContactToggleResult.Error(e.message ?: "Failed to update contact"),
                    )
                }
            }
        }

        init {
            // NOTE: Message collection has been moved to MessageCollector service
            // which runs at application level to ensure messages are collected
            // even when no conversations are open.
            // See: com.lxmf.messenger.service.MessageCollector

            // NOTE: Identity loading moved to loadIdentityIfNeeded() and called lazily
            // when sending messages, to avoid crashes during init when LXMF router
            // may not be ready yet.

            // Collect delivery status updates and update database
            // Safe to enable now that identity loading is lazy (doesn't crash init)
            if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                viewModelScope.launch {
                    reticulumProtocol.observeDeliveryStatus().collect { update ->
                        try {
                            Log.d(TAG, "Delivery status update: ${update.messageHash.take(16)}... -> ${update.status}")
                            handleDeliveryStatusUpdate(update)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling delivery status update", e)
                        }
                    }
                }
            } else {
                Log.d(TAG, "Delivery status collection skipped for ${reticulumProtocol.javaClass.simpleName}")
            }
        }

        /**
         * Load LXMF identity lazily when needed for sending messages.
         * This avoids crashes during init when LXMF router may not be ready.
         */
        private suspend fun loadIdentityIfNeeded(): Identity? {
            // Return cached identity if already loaded
            if (sourceIdentity != null) {
                return sourceIdentity
            }

            // Try to load identity
            return try {
                if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                    val identity = reticulumProtocol.getLxmfIdentity().getOrThrow()
                    sourceIdentity = identity
                    Log.d(TAG, "Loaded LXMF identity for messaging: ${identity.hash.take(8).joinToString("") { "%02x".format(it) }}")
                    identity
                } else {
                    // Fallback for non-service protocols
                    val identity =
                        reticulumProtocol.loadIdentity("default_identity").getOrNull()
                            ?: reticulumProtocol.createIdentity().getOrThrow().also {
                                reticulumProtocol.saveIdentity(it, "default_identity")
                            }
                    sourceIdentity = identity
                    Log.d(TAG, "Loaded fallback identity for messaging")
                    identity
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading identity for messaging", e)
                null
            }
        }

        private suspend fun handleDeliveryStatusUpdate(update: com.lxmf.messenger.reticulum.protocol.DeliveryStatusUpdate) {
            try {
                // Retry mechanism to handle race condition where delivery proof arrives
                // before database transaction completes
                val maxRetries = 3
                val retryDelays = listOf(50L, 100L, 200L) // ms

                var message = conversationRepository.getMessageById(update.messageHash)
                var attempt = 0

                while (message == null && attempt < maxRetries) {
                    Log.d(
                        TAG,
                        "Message ${update.messageHash.take(
                            16,
                        )}... not found, retrying in ${retryDelays[attempt]}ms (attempt ${attempt + 1}/$maxRetries)",
                    )
                    kotlinx.coroutines.delay(retryDelays[attempt])
                    message = conversationRepository.getMessageById(update.messageHash)
                    attempt++
                }

                if (message != null) {
                    // Update status
                    conversationRepository.updateMessageStatus(update.messageHash, update.status)

                    // When retrying via propagation, also update the delivery method
                    if (update.status == "retrying_propagated") {
                        conversationRepository.updateMessageDeliveryDetails(
                            update.messageHash,
                            deliveryMethod = "propagated",
                            errorMessage = null,
                        )
                    }

                    Log.d(TAG, "Updated message ${update.messageHash.take(16)}... status to ${update.status}")
                } else {
                    Log.w(TAG, "Delivery status update for unknown message after $maxRetries retries: ${update.messageHash.take(16)}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating message status", e)
            }
        }

        private suspend fun saveMessageToDatabase(
            peerHash: String,
            peerName: String,
            message: DataMessage,
        ) {
            try {
                // Look up public key from peer_identities BEFORE calling saveMessage
                // to avoid nested transaction issues
                val publicKey = conversationRepository.getPeerPublicKey(peerHash)

                conversationRepository.saveMessage(peerHash, peerName, message, publicKey)
                Log.d(TAG, "Saved message to database for conversation $peerHash")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving message to database", e)
            }
        }

        private fun DataMessage.toReticulumMessage() =
            ReticulumMessage(
                id = id,
                destinationHash = destinationHash,
                content = content,
                timestamp = timestamp,
                isFromMe = isFromMe,
                status = status,
                fieldsJson = fieldsJson,
            )

        fun loadMessages(
            destinationHash: String,
            peerName: String = "Unknown",
        ) {
            // Set current conversation - this triggers the reactive Flow to load messages
            currentPeerName = peerName
            _currentConversation.value = destinationHash

            // Register this conversation as active (suppresses notifications for this peer)
            activeConversationManager.setActive(destinationHash)

            // Enable fast polling (1s) for active conversation
            reticulumProtocol.setConversationActive(true)

            // Mark conversation as read when opening
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Marking conversation $destinationHash as read...")
                    conversationRepository.markConversationAsRead(destinationHash)
                    Log.d(TAG, "Conversation marked as read")
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking conversation as read", e)
                }
            }

            Log.d(TAG, "Switched to conversation $destinationHash ($peerName)")
        }

        fun markAsRead(destinationHash: String) {
            viewModelScope.launch {
                try {
                    conversationRepository.markConversationAsRead(destinationHash)
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking conversation as read", e)
                }
            }
        }

        fun sendMessage(
            destinationHash: String,
            content: String,
        ) {
            viewModelScope.launch {
                try {
                    val imageData = _selectedImageData.value
                    val imageFormat = _selectedImageFormat.value
                    val fileAttachments = _selectedFileAttachments.value

                    val sanitized = validateAndSanitizeContent(content, imageData, fileAttachments) ?: return@launch
                    val destHashBytes = validateDestinationHash(destinationHash) ?: return@launch
                    val identity =
                        loadIdentityIfNeeded() ?: run {
                            Log.e(TAG, "Failed to load source identity")
                            return@launch
                        }

                    val tryPropOnFail = settingsRepository.getTryPropagationOnFail()
                    val defaultMethod = settingsRepository.getDefaultDeliveryMethod()
                    val deliveryMethod = determineDeliveryMethod(sanitized, imageData, fileAttachments, defaultMethod)
                    val deliveryMethodString = deliveryMethod.toStorageString()

                    // Convert file attachments to protocol format: List<Pair<String, ByteArray>>
                    val fileAttachmentPairs = fileAttachments.map { it.filename to it.data }

                    Log.d(
                        TAG,
                        "Sending LXMF message to $destinationHash " +
                            "(${sanitized.length} chars, hasImage=${imageData != null}, " +
                            "files=${fileAttachments.size}, method=$deliveryMethod, tryPropOnFail=$tryPropOnFail)...",
                    )

                    val result =
                        reticulumProtocol.sendLxmfMessageWithMethod(
                            destinationHash = destHashBytes,
                            content = sanitized,
                            sourceIdentity = identity,
                            deliveryMethod = deliveryMethod,
                            tryPropagationOnFail = tryPropOnFail,
                            imageData = imageData,
                            imageFormat = imageFormat,
                            fileAttachments = fileAttachmentPairs.ifEmpty { null },
                        )

                    result.onSuccess { receipt ->
                        handleSendSuccess(receipt, sanitized, destinationHash, imageData, imageFormat, fileAttachments, deliveryMethodString)
                    }.onFailure { error ->
                        handleSendFailure(error, sanitized, destinationHash, deliveryMethodString)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message", e)
                }
            }
        }

        private suspend fun handleSendSuccess(
            receipt: com.lxmf.messenger.reticulum.protocol.MessageReceipt,
            sanitized: String,
            destinationHash: String,
            imageData: ByteArray?,
            imageFormat: String?,
            fileAttachments: List<FileAttachment>,
            deliveryMethodString: String,
        ) {
            Log.d(TAG, "Message sent successfully")
            val fieldsJson = buildFieldsJson(imageData, imageFormat, fileAttachments)
            val actualDestHash = resolveActualDestHash(receipt, destinationHash)
            Log.d(TAG, "Original dest hash: $destinationHash, Actual LXMF dest hash: $actualDestHash")

            val message =
                DataMessage(
                    id = receipt.messageHash.joinToString("") { "%02x".format(it) },
                    destinationHash = actualDestHash,
                    content = sanitized,
                    timestamp = receipt.timestamp,
                    isFromMe = true,
                    status = "pending",
                    fieldsJson = fieldsJson,
                    deliveryMethod = deliveryMethodString,
                )
            clearSelectedImage()
            clearFileAttachments()
            saveMessageToDatabase(actualDestHash, currentPeerName, message)
        }

        private suspend fun handleSendFailure(
            error: Throwable,
            sanitized: String,
            destinationHash: String,
            deliveryMethodString: String,
        ) {
            Log.e(TAG, "Failed to send message: ${error.message}", error)
            val message =
                DataMessage(
                    id = UUID.randomUUID().toString(),
                    destinationHash = destinationHash,
                    content = sanitized,
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    status = "failed",
                    deliveryMethod = deliveryMethodString,
                    errorMessage = error.message,
                )
            saveMessageToDatabase(destinationHash, currentPeerName, message)
        }

        fun selectImage(
            imageData: ByteArray,
            imageFormat: String,
        ) {
            Log.d(TAG, "Selected image: ${imageData.size} bytes, format=$imageFormat")
            _selectedImageData.value = imageData
            _selectedImageFormat.value = imageFormat
        }

        fun clearSelectedImage() {
            Log.d(TAG, "Clearing selected image")
            _selectedImageData.value = null
            _selectedImageFormat.value = null
        }

        fun setProcessingImage(processing: Boolean) {
            _isProcessingImage.value = processing
        }

        /**
         * Add a file attachment from its data.
         * Validates size limit and adds to the list if valid.
         *
         * @param attachment The file attachment to add
         */
        fun addFileAttachment(attachment: FileAttachment) {
            viewModelScope.launch {
                // Get configurable limit from settings (KB to bytes)
                val maxSizeKb = settingsRepository.getMaxOutboundAttachmentSizeKb()
                val maxSizeBytes = maxSizeKb * 1024

                val currentFiles = _selectedFileAttachments.value
                val currentTotal = currentFiles.sumOf { it.sizeBytes }

                // Check if adding this file would exceed the limit (0 means unlimited)
                if (maxSizeBytes > 0 &&
                    FileUtils.wouldExceedSizeLimit(currentTotal, attachment.sizeBytes, maxSizeBytes)
                ) {
                    Log.w(TAG, "File attachment rejected: would exceed size limit")
                    _fileAttachmentError.emit(
                        "File too large. Total attachments cannot exceed ${FileUtils.formatFileSize(maxSizeBytes)}",
                    )
                    return@launch
                }

                // Check single file size (0 means unlimited)
                if (maxSizeBytes > 0 && attachment.sizeBytes > maxSizeBytes) {
                    Log.w(TAG, "File attachment rejected: exceeds single file size limit")
                    _fileAttachmentError.emit(
                        "File too large. Maximum size is ${FileUtils.formatFileSize(maxSizeBytes)}",
                    )
                    return@launch
                }

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
                // Get the message from the database
                val messageEntity = conversationRepository.getMessageById(messageId)
                if (messageEntity == null) {
                    Log.e(TAG, "Message not found: $messageId")
                    return false
                }

                // Load the file data from the message's fieldsJson
                val fileData = loadFileAttachmentData(messageEntity.fieldsJson, fileIndex)
                if (fileData == null) {
                    Log.e(TAG, "Could not load file attachment data for message $messageId index $fileIndex")
                    return false
                }

                // Write to the destination Uri
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
                    // Get the message from the database
                    val messageEntity = conversationRepository.getMessageById(messageId)
                    if (messageEntity == null) {
                        Log.e(TAG, "Message not found: $messageId")
                        return@withContext null
                    }

                    // Get file metadata (filename, MIME type)
                    val metadata = loadFileAttachmentMetadata(messageEntity.fieldsJson, fileIndex)
                    if (metadata == null) {
                        Log.e(TAG, "Could not load file metadata for message $messageId index $fileIndex")
                        return@withContext null
                    }

                    // Load the file data
                    val fileData = loadFileAttachmentData(messageEntity.fieldsJson, fileIndex)
                    if (fileData == null) {
                        Log.e(TAG, "Could not load file data for message $messageId index $fileIndex")
                        return@withContext null
                    }

                    // Create attachments directory if needed
                    val attachmentsDir = File(context.filesDir, "attachments")
                    if (!attachmentsDir.exists()) {
                        attachmentsDir.mkdirs()
                    }

                    // Write to temp file with original filename
                    val tempFile = File(attachmentsDir, metadata.filename)
                    tempFile.writeBytes(fileData)
                    Log.d(TAG, "Created temp file for sharing: ${tempFile.absolutePath}")

                    // Get FileProvider URI
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile,
                    )

                    Pair(uri, metadata.mimeType)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get file attachment URI", e)
                    null
                }
            }
        }

        /**
         * Load an image attachment asynchronously.
         *
         * Called by the UI when a message has hasImageAttachment=true but decodedImage=null.
         * Decodes the image on IO thread, caches it, and updates loadedImageIds to trigger
         * recomposition so the UI can display the decoded image.
         *
         * @param messageId The message ID (used as cache key)
         * @param fieldsJson The message's fields JSON containing image data
         */
        fun loadImageAsync(
            messageId: String,
            fieldsJson: String?,
        ) {
            // Skip if already loaded/loading
            if (ImageCache.contains(messageId) || _loadedImageIds.value.contains(messageId)) {
                return
            }

            viewModelScope.launch {
                try {
                    // Decode on IO thread
                    val decoded =
                        withContext(Dispatchers.IO) {
                            decodeAndCacheImage(messageId, fieldsJson)
                        }

                    if (decoded != null) {
                        // Signal that this image is now available
                        _loadedImageIds.update { it + messageId }
                        Log.d(TAG, "Image loaded async: ${messageId.take(8)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading image async: ${messageId.take(8)}...", e)
                }
            }
        }

        /**
         * Trigger a manual sync with the propagation node.
         */
        fun syncFromPropagationNode() {
            viewModelScope.launch {
                try {
                    propagationNodeManager.triggerSync()
                    Log.d(TAG, "Manual sync triggered from MessagingScreen")
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering manual sync", e)
                }
            }
        }

        /**
         * Retry sending a failed message.
         * Re-sends the message with the same content and destination,
         * updating the database with the new message hash.
         *
         * @param messageId The ID (hash) of the failed message to retry
         */
        fun retryFailedMessage(messageId: String) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Retrying failed message: $messageId")

                    // Load the failed message from database
                    val failedMessage = conversationRepository.getMessageById(messageId)
                    if (failedMessage == null) {
                        Log.e(TAG, "Failed message not found: $messageId")
                        return@launch
                    }

                    // Only retry failed messages
                    if (failedMessage.status != "failed") {
                        Log.w(TAG, "Message $messageId is not failed (status: ${failedMessage.status}), skipping retry")
                        return@launch
                    }

                    // Get destination hash bytes (MessageEntity uses conversationHash)
                    val destHashBytes = validateDestinationHash(failedMessage.conversationHash)
                    if (destHashBytes == null) {
                        Log.e(TAG, "Invalid destination hash in failed message")
                        return@launch
                    }

                    // Load identity for sending
                    val identity = loadIdentityIfNeeded()
                    if (identity == null) {
                        Log.e(TAG, "Failed to load source identity for retry")
                        return@launch
                    }

                    // Parse image data from fieldsJson if present
                    val imageData = failedMessage.fieldsJson?.let { parseImageFromFieldsJson(it) }
                    val imageFormat = if (imageData != null) "jpg" else null

                    // Parse file attachments from fieldsJson if present
                    // For retry, we need to reconstruct file attachments from stored data
                    // TODO: Implement file attachment parsing from fieldsJson when retrying
                    val fileAttachments = emptyList<FileAttachment>()

                    // Get delivery settings
                    val tryPropOnFail = settingsRepository.getTryPropagationOnFail()
                    val defaultMethod = settingsRepository.getDefaultDeliveryMethod()
                    val deliveryMethod = determineDeliveryMethod(failedMessage.content, imageData, fileAttachments, defaultMethod)

                    Log.d(TAG, "Retrying message via $deliveryMethod delivery")

                    // Mark message as pending before sending
                    conversationRepository.updateMessageStatus(messageId, "pending")

                    // Send the message
                    val result =
                        reticulumProtocol.sendLxmfMessageWithMethod(
                            destinationHash = destHashBytes,
                            content = failedMessage.content,
                            sourceIdentity = identity,
                            deliveryMethod = deliveryMethod,
                            tryPropagationOnFail = tryPropOnFail,
                            imageData = imageData,
                            imageFormat = imageFormat,
                        )

                    result.onSuccess { receipt ->
                        val newMessageHash = receipt.messageHash.joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Retry successful, new hash: ${newMessageHash.take(16)}...")

                        // Update the message with the new hash
                        // Delete the old message entry and create a new one with the new hash
                        conversationRepository.updateMessageId(messageId, newMessageHash)
                    }.onFailure { error ->
                        Log.e(TAG, "Retry failed: ${error.message}", error)
                        // Mark as failed again with the error message
                        conversationRepository.updateMessageStatus(messageId, "failed")
                        conversationRepository.updateMessageDeliveryDetails(
                            messageId,
                            deliveryMethod = null,
                            errorMessage = error.message,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrying message", e)
                    // Restore failed status
                    try {
                        conversationRepository.updateMessageStatus(messageId, "failed")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Error restoring failed status", e2)
                    }
                }
            }
        }

        override fun onCleared() {
            super.onCleared()

            // Note: Conversation marking as read happens via loadMessages() when opening
            // and via UI layer's LaunchedEffect. Cleanup here was redundant and violated
            // Phase 1 threading policy (zero runBlocking in production code).
            // See THREADING_REDESIGN_PLAN.md Phase 1.2

            // Clear active conversation (re-enables notifications)
            activeConversationManager.setActive(null)

            // Disable fast polling when conversation screen is closed
            reticulumProtocol.setConversationActive(false)
            Log.d(TAG, "ViewModel cleared - disabled fast polling")
        }
    }

// Top-level helper functions to keep class function count under threshold

private const val HELPER_TAG = "MessagingViewModel"

private fun validateAndSanitizeContent(
    content: String,
    imageData: ByteArray?,
    fileAttachments: List<FileAttachment> = emptyList(),
): String? {
    // Empty content is OK when sending attachments only
    if (content.trim().isEmpty() && (imageData != null || fileAttachments.isNotEmpty())) {
        return ""
    }
    val validationResult = InputValidator.validateMessageContent(content)
    if (validationResult is ValidationResult.Error) {
        Log.w(HELPER_TAG, "Invalid message content: ${validationResult.message}")
        return null
    }
    return validationResult.getOrThrow()
}

private fun validateDestinationHash(destinationHash: String): ByteArray? {
    return when (val result = InputValidator.validateDestinationHash(destinationHash)) {
        is ValidationResult.Success -> result.value
        is ValidationResult.Error -> {
            Log.e(HELPER_TAG, "Invalid destination hash: ${result.message}")
            null
        }
    }
}

private fun DeliveryMethod.toStorageString(): String =
    when (this) {
        DeliveryMethod.OPPORTUNISTIC -> "opportunistic"
        DeliveryMethod.DIRECT -> "direct"
        DeliveryMethod.PROPAGATED -> "propagated"
    }

private const val OPPORTUNISTIC_MAX_BYTES_HELPER = 295

private fun determineDeliveryMethod(
    sanitized: String,
    imageData: ByteArray?,
    fileAttachments: List<FileAttachment> = emptyList(),
    defaultMethod: String,
): DeliveryMethod {
    val contentSize = sanitized.toByteArray().size
    val hasAttachments = imageData != null || fileAttachments.isNotEmpty()
    return if (!hasAttachments && contentSize <= OPPORTUNISTIC_MAX_BYTES_HELPER) {
        Log.d(HELPER_TAG, "Using OPPORTUNISTIC delivery (content: $contentSize bytes)")
        DeliveryMethod.OPPORTUNISTIC
    } else {
        if (defaultMethod == "propagated") DeliveryMethod.PROPAGATED else DeliveryMethod.DIRECT
    }
}

private fun buildFieldsJson(
    imageData: ByteArray?,
    imageFormat: String?,
    fileAttachments: List<FileAttachment> = emptyList(),
): String? {
    val hasImage = imageData != null && imageFormat != null
    val hasFiles = fileAttachments.isNotEmpty()

    if (!hasImage && !hasFiles) return null

    val json = org.json.JSONObject()

    // Add image field (Field 6)
    if (hasImage && imageData != null) {
        val hexImageData = imageData.joinToString("") { "%02x".format(it) }
        json.put("6", hexImageData)
    }

    // Add file attachments field (Field 5)
    if (hasFiles) {
        val attachmentsArray = org.json.JSONArray()
        for (attachment in fileAttachments) {
            val attachmentObj = org.json.JSONObject()
            attachmentObj.put("filename", attachment.filename)
            attachmentObj.put("data", attachment.data.joinToString("") { "%02x".format(it) })
            attachmentObj.put("size", attachment.sizeBytes)
            attachmentsArray.put(attachmentObj)
        }
        json.put("5", attachmentsArray)
    }

    return json.toString()
}

private fun resolveActualDestHash(
    receipt: com.lxmf.messenger.reticulum.protocol.MessageReceipt,
    fallbackHash: String,
): String {
    return if (receipt.destinationHash.isNotEmpty()) {
        receipt.destinationHash.joinToString("") { "%02x".format(it) }
    } else {
        Log.w(HELPER_TAG, "Received empty destination hash from Python, falling back to original: $fallbackHash")
        fallbackHash
    }
}

/**
 * Parse image data from LXMF fields JSON.
 * Field 6 contains the image data as hex string.
 */
private fun parseImageFromFieldsJson(fieldsJson: String): ByteArray? {
    return try {
        val json = org.json.JSONObject(fieldsJson)
        val hexImageData = json.optString("6", "")
        if (hexImageData.isNotEmpty()) {
            hexImageData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w(HELPER_TAG, "Failed to parse image from fieldsJson: ${e.message}")
        null
    }
}

/**
 * Result of a contact toggle operation.
 */
sealed class ContactToggleResult {
    /** Contact was successfully added */
    data object Added : ContactToggleResult()

    /** Contact was successfully removed */
    data object Removed : ContactToggleResult()

    /** Operation failed with the given message */
    data class Error(val message: String) : ContactToggleResult()
}
