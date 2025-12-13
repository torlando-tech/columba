package com.lxmf.messenger.viewmodel

import android.util.Log
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
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.toMessageUi
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        // PERFORMANCE: Maps to MessageUi with pre-decoded images to avoid expensive
        // decoding during composition (critical for smooth 60 FPS scrolling)
        val messages: Flow<PagingData<MessageUi>> =
            _currentConversation
                .flatMapLatest { peerHash ->
                    Log.d(TAG, "Flow: Switching to conversation $peerHash")
                    if (peerHash != null) {
                        conversationRepository.getMessagesPaged(peerHash)
                            .map { pagingData ->
                                pagingData.map { it.toMessageUi() }
                            }
                            .flowOn(Dispatchers.Default) // Decode images off main thread
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

        // Sync state from PropagationNodeManager
        val isSyncing: StateFlow<Boolean> = propagationNodeManager.isSyncing

        // Manual sync result events for Snackbar notifications
        val manualSyncResult: SharedFlow<SyncResult> = propagationNodeManager.manualSyncResult

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

                    val sanitized = validateAndSanitizeContent(content, imageData) ?: return@launch
                    val destHashBytes = validateDestinationHash(destinationHash) ?: return@launch
                    val identity =
                        loadIdentityIfNeeded() ?: run {
                            Log.e(TAG, "Failed to load source identity")
                            return@launch
                        }

                    val tryPropOnFail = settingsRepository.getTryPropagationOnFail()
                    val defaultMethod = settingsRepository.getDefaultDeliveryMethod()
                    val deliveryMethod = determineDeliveryMethod(sanitized, imageData, defaultMethod)
                    val deliveryMethodString = deliveryMethod.toStorageString()

                    Log.d(
                        TAG,
                        "Sending LXMF message to $destinationHash " +
                            "(${sanitized.length} chars, hasImage=${imageData != null}, method=$deliveryMethod, tryPropOnFail=$tryPropOnFail)...",
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
                        )

                    result.onSuccess { receipt ->
                        handleSendSuccess(receipt, sanitized, destinationHash, imageData, imageFormat, deliveryMethodString)
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
            deliveryMethodString: String,
        ) {
            Log.d(TAG, "Message sent successfully")
            val fieldsJson = buildFieldsJson(imageData, imageFormat)
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

                    // Get delivery settings
                    val tryPropOnFail = settingsRepository.getTryPropagationOnFail()
                    val defaultMethod = settingsRepository.getDefaultDeliveryMethod()
                    val deliveryMethod = determineDeliveryMethod(failedMessage.content, imageData, defaultMethod)

                    Log.d(TAG, "Retrying message via $deliveryMethod delivery")

                    // Mark message as pending before sending
                    conversationRepository.updateMessageStatus(messageId, "pending")

                    // Send the message
                    val result = reticulumProtocol.sendLxmfMessageWithMethod(
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
): String? {
    if (content.trim().isEmpty() && imageData != null) {
        return "" // Empty content is OK when sending image-only message
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
    defaultMethod: String,
): DeliveryMethod {
    val contentSize = sanitized.toByteArray().size
    return if (imageData == null && contentSize <= OPPORTUNISTIC_MAX_BYTES_HELPER) {
        Log.d(HELPER_TAG, "Using OPPORTUNISTIC delivery (content: $contentSize bytes)")
        DeliveryMethod.OPPORTUNISTIC
    } else {
        if (defaultMethod == "propagated") DeliveryMethod.PROPAGATED else DeliveryMethod.DIRECT
    }
}

private fun buildFieldsJson(
    imageData: ByteArray?,
    imageFormat: String?,
): String? {
    if (imageData == null || imageFormat == null) return null
    val hexImageData = imageData.joinToString("") { "%02x".format(it) }
    return """{"6":"$hexImageData"}"""
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
