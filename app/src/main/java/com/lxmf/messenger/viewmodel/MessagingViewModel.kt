package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.toMessageUi
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class MessagingViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val conversationRepository: com.lxmf.messenger.data.repository.ConversationRepository,
        private val announceRepository: com.lxmf.messenger.data.repository.AnnounceRepository,
        private val activeConversationManager: com.lxmf.messenger.service.ActiveConversationManager,
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
                    started = SharingStarted.Eagerly,
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
                    // Get image attachment first - needed to determine if empty content is allowed
                    val imageData = _selectedImageData.value
                    val imageFormat = _selectedImageFormat.value

                    // Validate content - allow empty if image is attached
                    val sanitized =
                        if (content.trim().isEmpty() && imageData != null) {
                            // Empty content is OK when sending image-only message
                            ""
                        } else {
                            // Validate content using InputValidator which checks length BEFORE sanitizing
                            val validationResult = InputValidator.validateMessageContent(content)
                            if (validationResult is ValidationResult.Error) {
                                Log.w(TAG, "Invalid message content: ${validationResult.message}")
                                return@launch
                            }
                            validationResult.getOrThrow()
                        }

                    // Validate and convert destination hash with SAFE conversion
                    val destHashBytes =
                        when (val result = InputValidator.validateDestinationHash(destinationHash)) {
                            is ValidationResult.Success -> result.value
                            is ValidationResult.Error -> {
                                Log.e(TAG, "Invalid destination hash: ${result.message}")
                                // Could emit error to UI state here if needed
                                return@launch
                            }
                        }

                    // Load identity lazily (may not be ready during init)
                    val identity =
                        loadIdentityIfNeeded() ?: run {
                            Log.e(TAG, "Failed to load source identity")
                            return@launch
                        }

                    Log.d(TAG, "Sending LXMF message to $destinationHash (${sanitized.length} chars, hasImage=${imageData != null})...")

                    // Send via protocol with VALIDATED data
                    val result =
                        reticulumProtocol.sendLxmfMessage(
                            destinationHash = destHashBytes,
                            content = sanitized,
                            sourceIdentity = identity,
                            imageData = imageData,
                            imageFormat = imageFormat,
                        )

                    result.onSuccess { receipt ->
                        Log.d(TAG, "Message sent successfully")

                        // Build fieldsJson if image was included
                        val fieldsJson =
                            if (imageData != null && imageFormat != null) {
                                // Store image as hex string in JSON format: {"6": "hex_image_data"}
                                val hexImageData = imageData.joinToString("") { "%02x".format(it) }
                                """{"6":"$hexImageData"}"""
                            } else {
                                null
                            }

                        // Use the ACTUAL LXMF destination hash that was used for sending
                        // (not the announce hash, which might be for a different service like audio calls)
                        val actualDestHash =
                            if (receipt.destinationHash.isNotEmpty()) {
                                receipt.destinationHash.joinToString("") { "%02x".format(it) }
                            } else {
                                Log.w(
                                    TAG,
                                    "Received empty destination hash from Python, " +
                                        "falling back to original: $destinationHash",
                                )
                                destinationHash // Fallback to original if Python didn't return one
                            }
                        Log.d(TAG, "Original dest hash: $destinationHash, Actual LXMF dest hash: $actualDestHash")

                        // Add to conversation as sent message
                        val message =
                            DataMessage(
                                id = receipt.messageHash.joinToString("") { "%02x".format(it) },
                                destinationHash = actualDestHash, // Use actual LXMF dest hash
                                content = sanitized,
                                timestamp = receipt.timestamp,
                                isFromMe = true,
                                status = "pending", // Will be updated to "delivered" when LXMF proof arrives
                                fieldsJson = fieldsJson,
                            )

                        // Clear image after successful send
                        clearSelectedImage()

                        // Save to database using actual LXMF destination hash
                        saveMessageToDatabase(actualDestHash, currentPeerName, message)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send message: ${error.message}", error)

                        // Still add to DB but mark as failed
                        val message =
                            DataMessage(
                                id = UUID.randomUUID().toString(),
                                destinationHash = destinationHash,
                                content = sanitized,
                                timestamp = System.currentTimeMillis(),
                                isFromMe = true,
                                status = "failed",
                            )

                        // Save to database - reactive Flow will auto-update UI
                        saveMessageToDatabase(destinationHash, currentPeerName, message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message", e)
                }
            }
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
