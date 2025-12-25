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
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.DeliveryMethod
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.SyncResult
import com.lxmf.messenger.ui.model.ImageCache
import com.lxmf.messenger.ui.model.LocationSharingState
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.SharingDuration
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
import kotlinx.coroutines.flow.combine
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
@Suppress("TooManyFunctions", "LargeClass") // ViewModel handles multiple UI operations
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
        private val locationSharingManager: LocationSharingManager,
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

        // Sync state from PropagationNodeManager
        val isSyncing: StateFlow<Boolean> = propagationNodeManager.isSyncing

        // Manual sync result events for Snackbar notifications
        val manualSyncResult: SharedFlow<SyncResult> = propagationNodeManager.manualSyncResult

        // Track which images have been decoded - used to trigger recomposition
        // when images become available. The UI observes this to know when to re-check the cache.
        private val _loadedImageIds = MutableStateFlow<Set<String>>(emptySet())
        val loadedImageIds: StateFlow<Set<String>> = _loadedImageIds.asStateFlow()

        // Cache for loaded reply previews - maps message ID to its reply preview
        private val _replyPreviewCache = MutableStateFlow<Map<String, com.lxmf.messenger.ui.model.ReplyPreviewUi>>(emptyMap())
        val replyPreviewCache: StateFlow<Map<String, com.lxmf.messenger.ui.model.ReplyPreviewUi>> = _replyPreviewCache.asStateFlow()

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

        // Contacts list for ShareLocationBottomSheet
        val contacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Lazily,
                    initialValue = emptyList(),
                )

        // Location sharing state with current peer - for TopAppBar icon state
        val locationSharingState: StateFlow<LocationSharingState> =
            combine(
                locationSharingManager.activeSessions,
                _currentConversation,
                contactRepository.getEnrichedContacts(),
            ) { sessions, currentHash, allContacts ->
                if (currentHash == null) return@combine LocationSharingState.NONE

                val sharingWithThem = sessions.any { it.destinationHash == currentHash }
                val theyShareWithUs = allContacts
                    .find { it.destinationHash == currentHash }
                    ?.isReceivingLocationFrom == true

                when {
                    sharingWithThem && theyShareWithUs -> LocationSharingState.MUTUAL
                    sharingWithThem -> LocationSharingState.SHARING_WITH_THEM
                    theyShareWithUs -> LocationSharingState.THEY_SHARE_WITH_ME
                    else -> LocationSharingState.NONE
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = LocationSharingState.NONE,
            )

        // Contact toggle result events for toast notifications
        private val _contactToggleResult = MutableSharedFlow<ContactToggleResult>()
        val contactToggleResult: SharedFlow<ContactToggleResult> = _contactToggleResult.asSharedFlow()

        // Reply state - tracks which message is being replied to
        private val _pendingReplyTo = MutableStateFlow<com.lxmf.messenger.ui.model.ReplyPreviewUi?>(null)
        val pendingReplyTo: StateFlow<com.lxmf.messenger.ui.model.ReplyPreviewUi?> = _pendingReplyTo.asStateFlow()

        // Reaction state - tracks which message is selected for adding a reaction
        private val _pendingReactionMessageId = MutableStateFlow<String?>(null)
        val pendingReactionMessageId: StateFlow<String?> = _pendingReactionMessageId.asStateFlow()

        private val _showReactionPicker = MutableStateFlow(false)
        val showReactionPicker: StateFlow<Boolean> = _showReactionPicker.asStateFlow()

        // Current user's identity hash - used to identify own reactions
        private val _myIdentityHash = MutableStateFlow<String?>(null)
        val myIdentityHash: StateFlow<String?> = _myIdentityHash.asStateFlow()

        /**
         * Set a message to reply to. Called when user swipes on a message or selects "Reply".
         * Loads the reply preview data from the database asynchronously.
         *
         * @param messageId The ID of the message to reply to
         */
        fun setReplyTo(messageId: String) {
            viewModelScope.launch {
                try {
                    val replyPreview = conversationRepository.getReplyPreview(messageId, currentPeerName)
                    if (replyPreview != null) {
                        _pendingReplyTo.value = com.lxmf.messenger.ui.model.ReplyPreviewUi(
                            messageId = replyPreview.messageId,
                            senderName = replyPreview.senderName,
                            contentPreview = replyPreview.contentPreview,
                            hasImage = replyPreview.hasImage,
                            hasFileAttachment = replyPreview.hasFileAttachment,
                            firstFileName = replyPreview.firstFileName,
                        )
                        Log.d(TAG, "Set pending reply to message ${messageId.take(16)}")
                    } else {
                        Log.w(TAG, "Could not find message $messageId for reply preview")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading reply preview for $messageId", e)
                }
            }
        }

        /**
         * Clear the pending reply. Called when user cancels reply or after message is sent.
         */
        fun clearReplyTo() {
            _pendingReplyTo.value = null
            Log.d(TAG, "Cleared pending reply")
        }

        /**
         * Set a message as the target for adding a reaction.
         * Shows the reaction picker UI for the user to select an emoji.
         *
         * @param messageId The ID of the message to react to
         */
        fun setReactionTarget(messageId: String) {
            _pendingReactionMessageId.value = messageId
            _showReactionPicker.value = true
            Log.d(TAG, "Set reaction target: ${messageId.take(16)}...")
        }

        /**
         * Clear the pending reaction and hide the reaction picker.
         * Called when user cancels or after reaction is sent.
         */
        fun clearReactionTarget() {
            _pendingReactionMessageId.value = null
            _showReactionPicker.value = false
            Log.d(TAG, "Cleared reaction target")
        }

        /**
         * Dismiss the reaction picker without clearing the target.
         * Used when user taps outside the picker or presses back.
         */
        fun dismissReactionPicker() {
            _showReactionPicker.value = false
            Log.d(TAG, "Dismissed reaction picker")
        }

        /**
         * Send a reaction to a message.
         *
         * Updates the local database with the reaction (optimistic update) and
         * sends the reaction to the peer via LXMF protocol.
         *
         * @param messageId The ID of the message to react to
         * @param emoji The emoji reaction to send
         */
        fun sendReaction(
            messageId: String,
            emoji: String,
        ) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Sending reaction $emoji to message ${messageId.take(16)}...")

                    // Get the message to find the conversation hash
                    val message = conversationRepository.getMessageById(messageId)
                    if (message == null) {
                        Log.e(TAG, "Cannot send reaction: message not found")
                        clearReactionTarget()
                        return@launch
                    }

                    // Get source identity for sender hash
                    val identity = loadIdentityIfNeeded()
                    if (identity == null) {
                        Log.e(TAG, "Cannot send reaction: failed to load identity")
                        clearReactionTarget()
                        return@launch
                    }

                    // Validate destination hash
                    val destHashBytes = validateDestinationHash(message.conversationHash)
                    if (destHashBytes == null) {
                        Log.e(TAG, "Cannot send reaction: invalid destination hash")
                        clearReactionTarget()
                        return@launch
                    }

                    // Get the current user's hash as the sender
                    val senderHash = identity.hash.joinToString("") { "%02x".format(it) }

                    // Update local database with the reaction (optimistic update)
                    val updatedFieldsJson = addReactionToFieldsJson(
                        message.fieldsJson,
                        emoji,
                        senderHash,
                    )

                    // Save the updated fieldsJson to database
                    conversationRepository.updateMessageReactions(messageId, updatedFieldsJson)

                    Log.d(
                        TAG,
                        "Reaction $emoji added locally to message ${messageId.take(16)} from $senderHash",
                    )

                    // Send reaction via LXMF protocol
                    val result = reticulumProtocol.sendReaction(
                        destinationHash = destHashBytes,
                        targetMessageId = messageId,
                        emoji = emoji,
                        sourceIdentity = identity,
                    )

                    result.onSuccess { receipt ->
                        Log.d(TAG, "😀 Reaction $emoji sent successfully, hash: ${receipt.messageHash.take(8).joinToString("") { "%02x".format(it) }}")
                    }

                    result.onFailure { error ->
                        Log.e(TAG, "Failed to send reaction: ${error.message}")
                        // Note: We don't rollback local update - reaction still shows locally
                        // This is optimistic UI pattern - worst case the recipient doesn't see it
                    }

                    // Clear the reaction picker
                    clearReactionTarget()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending reaction", e)
                    clearReactionTarget()
                }
            }
        }

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

        /**
         * Start sharing location with a single peer.
         * Used from the conversation screen where the target is already known.
         *
         * @param peerHash The destination hash of the peer to share with
         * @param peerName The display name of the peer
         * @param duration How long to share location
         */
        fun startSharingWithPeer(
            peerHash: String,
            peerName: String,
            duration: SharingDuration,
        ) {
            Log.d(TAG, "Starting location sharing with $peerName for $duration")
            locationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }

        /**
         * Stop sharing location with a specific peer.
         *
         * @param peerHash The destination hash of the peer to stop sharing with
         */
        fun stopSharingWithPeer(peerHash: String) {
            Log.d(TAG, "Stopping location sharing with $peerHash")
            locationSharingManager.stopSharing(peerHash)
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

                // Collect incoming reactions and update target messages
                viewModelScope.launch {
                    reticulumProtocol.reactionReceivedFlow.collect { reactionJson ->
                        try {
                            handleIncomingReaction(reactionJson)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling incoming reaction", e)
                        }
                    }
                }

                // Pre-load identity hash for reaction ownership checks
                viewModelScope.launch {
                    loadIdentityIfNeeded()
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
                    // Cache the identity hash for reaction ownership checks
                    val hashHex = identity.hash.joinToString("") { "%02x".format(it) }
                    _myIdentityHash.value = hashHex
                    Log.d(TAG, "Loaded LXMF identity for messaging: ${hashHex.take(16)}...")
                    identity
                } else {
                    // Fallback for non-service protocols
                    val identity =
                        reticulumProtocol.loadIdentity("default_identity").getOrNull()
                            ?: reticulumProtocol.createIdentity().getOrThrow().also {
                                reticulumProtocol.saveIdentity(it, "default_identity")
                            }
                    sourceIdentity = identity
                    // Cache the identity hash for reaction ownership checks
                    val hashHex = identity.hash.joinToString("") { "%02x".format(it) }
                    _myIdentityHash.value = hashHex
                    Log.d(TAG, "Loaded fallback identity for messaging: ${hashHex.take(16)}...")
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

        /**
         * Handle an incoming reaction from another user.
         * Parses the reaction JSON and updates the target message's reactions in the database.
         *
         * Expected JSON format:
         * {"reaction_to": "msg_id", "emoji": "👍", "sender": "sender_hash", "source_hash": "...", "timestamp": ...}
         */
        private suspend fun handleIncomingReaction(reactionJson: String) {
            try {
                val json = org.json.JSONObject(reactionJson)
                val targetMessageId = json.optString("reaction_to")
                val emoji = json.optString("emoji")
                val senderHash = json.optString("sender")

                if (targetMessageId.isEmpty() || emoji.isEmpty() || senderHash.isEmpty()) {
                    Log.w(TAG, "Invalid reaction JSON, missing required fields: $reactionJson")
                    return
                }

                Log.d(TAG, "😀 Incoming reaction: $emoji to message ${targetMessageId.take(16)}... from ${senderHash.take(16)}...")

                // Find the target message
                val targetMessage = conversationRepository.getMessageById(targetMessageId)
                if (targetMessage == null) {
                    Log.w(TAG, "Reaction received for unknown message: ${targetMessageId.take(16)}...")
                    return
                }

                // Add the reaction to the message's fieldsJson
                val updatedFieldsJson = addReactionToFieldsJson(
                    targetMessage.fieldsJson,
                    emoji,
                    senderHash,
                )

                // Save the updated fieldsJson to database
                conversationRepository.updateMessageReactions(targetMessageId, updatedFieldsJson)

                Log.d(TAG, "😀 Reaction $emoji added to message ${targetMessageId.take(16)}... from ${senderHash.take(16)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming reaction: ${e.message}", e)
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

                    // Get pending reply ID if replying to a message
                    val replyToId = _pendingReplyTo.value?.messageId

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
                            replyToMessageId = replyToId,
                        )

                    result.onSuccess { receipt ->
                        // Clear pending reply after successful send
                        handleSendSuccess(receipt, sanitized, destinationHash, imageData, imageFormat, fileAttachments, deliveryMethodString, replyToId)
                        clearReplyTo()
                    }.onFailure { error ->
                        handleSendFailure(error, sanitized, destinationHash, deliveryMethodString)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message", e)
                }
            }
        }

        @Suppress("LongParameterList") // Refactoring to data class would add unnecessary complexity
        private suspend fun handleSendSuccess(
            receipt: com.lxmf.messenger.reticulum.protocol.MessageReceipt,
            sanitized: String,
            destinationHash: String,
            imageData: ByteArray?,
            imageFormat: String?,
            fileAttachments: List<FileAttachment>,
            deliveryMethodString: String,
            replyToMessageId: String? = null,
        ) {
            Log.d(TAG, "Message sent successfully${if (replyToMessageId != null) " (reply to ${replyToMessageId.take(16)})" else ""}")
            val fieldsJson = buildFieldsJson(imageData, imageFormat, fileAttachments, replyToMessageId)
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
                    replyToMessageId = replyToMessageId,
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
                val currentFiles = _selectedFileAttachments.value
                val currentTotal = currentFiles.sumOf { it.sizeBytes }

                // Check if adding this file would exceed the limit
                if (FileUtils.wouldExceedSizeLimit(currentTotal, attachment.sizeBytes)) {
                    Log.w(TAG, "File attachment rejected: would exceed size limit")
                    _fileAttachmentError.emit(
                        "File too large. Total attachments cannot exceed ${FileUtils.formatFileSize(FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)}",
                    )
                    return@launch
                }

                // Check single file size
                if (attachment.sizeBytes > FileUtils.MAX_SINGLE_FILE_SIZE) {
                    Log.w(TAG, "File attachment rejected: exceeds single file size limit")
                    _fileAttachmentError.emit(
                        "File too large. Maximum size is ${FileUtils.formatFileSize(FileUtils.MAX_SINGLE_FILE_SIZE)}",
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
         * Load a reply preview asynchronously.
         *
         * Called by the UI when a message has replyToMessageId but no cached replyPreview.
         * Loads the preview data on IO thread, caches it, and updates replyPreviewCache to
         * trigger recomposition so the UI can display the reply preview.
         *
         * @param messageId The message ID that has a reply (used as cache key)
         * @param replyToMessageId The ID of the message being replied to
         */
        fun loadReplyPreviewAsync(
            messageId: String,
            replyToMessageId: String,
        ) {
            // Skip if already loaded/loading
            if (_replyPreviewCache.value.containsKey(messageId)) {
                return
            }

            viewModelScope.launch {
                try {
                    val replyPreview = conversationRepository.getReplyPreview(replyToMessageId, currentPeerName)
                    if (replyPreview != null) {
                        val uiPreview = com.lxmf.messenger.ui.model.ReplyPreviewUi(
                            messageId = replyPreview.messageId,
                            senderName = replyPreview.senderName,
                            contentPreview = replyPreview.contentPreview,
                            hasImage = replyPreview.hasImage,
                            hasFileAttachment = replyPreview.hasFileAttachment,
                            firstFileName = replyPreview.firstFileName,
                        )
                        _replyPreviewCache.update { it + (messageId to uiPreview) }
                        Log.d(TAG, "Loaded reply preview for message ${messageId.take(16)}")
                    } else {
                        // Mark as loaded with a "deleted message" placeholder
                        val deletedPreview = com.lxmf.messenger.ui.model.ReplyPreviewUi(
                            messageId = replyToMessageId,
                            senderName = "",
                            contentPreview = "Message deleted",
                        )
                        _replyPreviewCache.update { it + (messageId to deletedPreview) }
                        Log.d(TAG, "Reply target message not found: ${replyToMessageId.take(16)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading reply preview for $messageId", e)
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
                            replyToMessageId = failedMessage.replyToMessageId, // Preserve reply on retry
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
    replyToMessageId: String? = null,
    reactions: Map<String, List<String>>? = null,
): String? {
    val hasImage = imageData != null && imageFormat != null
    val hasFiles = fileAttachments.isNotEmpty()
    val hasReply = replyToMessageId != null
    val hasReactions = !reactions.isNullOrEmpty()

    if (!hasImage && !hasFiles && !hasReply && !hasReactions) return null

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

    // Add app extensions field (Field 16) for replies, reactions, and future features
    if (hasReply || hasReactions) {
        val appExtensions = org.json.JSONObject()

        // Add reply_to if present
        if (hasReply) {
            appExtensions.put("reply_to", replyToMessageId)
        }

        // Add reactions if present
        // Format: {"reactions": {"👍": ["sender_hash1", "sender_hash2"], "❤️": ["sender_hash3"]}}
        if (hasReactions && reactions != null) {
            val reactionsObj = org.json.JSONObject()
            for ((emoji, senderHashes) in reactions) {
                val sendersArray = org.json.JSONArray()
                for (hash in senderHashes) {
                    sendersArray.put(hash)
                }
                reactionsObj.put(emoji, sendersArray)
            }
            appExtensions.put("reactions", reactionsObj)
        }

        json.put("16", appExtensions)
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
 * Add a reaction to the fieldsJson of a message.
 *
 * Updates or creates the Field 16 "reactions" dictionary, adding the sender hash
 * to the specified emoji's list of senders. If the sender already reacted with
 * this emoji, the reaction is not duplicated.
 *
 * @param fieldsJson The existing fieldsJson string (can be null)
 * @param emoji The emoji reaction to add
 * @param senderHash The hex hash of the sender adding the reaction
 * @return The updated fieldsJson string with the reaction added
 */
private fun addReactionToFieldsJson(
    fieldsJson: String?,
    emoji: String,
    senderHash: String,
): String {
    val json = if (fieldsJson.isNullOrEmpty()) {
        org.json.JSONObject()
    } else {
        try {
            org.json.JSONObject(fieldsJson)
        } catch (e: Exception) {
            Log.w(HELPER_TAG, "Failed to parse fieldsJson, creating new: ${e.message}")
            org.json.JSONObject()
        }
    }

    // Get or create Field 16 (app extensions)
    val field16 = if (json.has("16")) {
        json.getJSONObject("16")
    } else {
        org.json.JSONObject().also { json.put("16", it) }
    }

    // Get or create reactions dictionary
    val reactions = if (field16.has("reactions")) {
        field16.getJSONObject("reactions")
    } else {
        org.json.JSONObject().also { field16.put("reactions", it) }
    }

    // Get or create the sender list for this emoji
    val senderList = if (reactions.has(emoji)) {
        reactions.getJSONArray(emoji)
    } else {
        org.json.JSONArray().also { reactions.put(emoji, it) }
    }

    // Add sender if not already present (avoid duplicates)
    var alreadyReacted = false
    for (i in 0 until senderList.length()) {
        if (senderList.optString(i) == senderHash) {
            alreadyReacted = true
            break
        }
    }
    if (!alreadyReacted) {
        senderList.put(senderHash)
    }

    return json.toString()
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
