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
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.protocol.DeliveryMethod
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.service.ConversationLinkManager
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.SyncProgress
import com.lxmf.messenger.service.SyncResult
import com.lxmf.messenger.ui.model.CodecProfile
import com.lxmf.messenger.ui.model.DecodedImageResult
import com.lxmf.messenger.ui.model.ImageCache
import com.lxmf.messenger.ui.model.LocationSharingState
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.SharingDuration
import com.lxmf.messenger.ui.model.decodeImageWithAnimation
import com.lxmf.messenger.ui.model.getImageMetadata
import com.lxmf.messenger.ui.model.loadFileAttachmentData
import com.lxmf.messenger.ui.model.loadFileAttachmentMetadata
import com.lxmf.messenger.ui.model.loadImageData
import com.lxmf.messenger.ui.model.toMessageUi
import com.lxmf.messenger.util.FileAttachment
import com.lxmf.messenger.util.FileUtils
import com.lxmf.messenger.util.ImageUtils
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import com.lxmf.messenger.data.repository.Message as DataMessage
import com.lxmf.messenger.reticulum.model.Message as ReticulumMessage

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList") // ViewModel handles multiple UI operations
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
        private val identityRepository: com.lxmf.messenger.data.repository.IdentityRepository,
        private val conversationLinkManager: ConversationLinkManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MessagingViewModel"

            /**
             * Sanitize a filename to prevent path traversal attacks.
             * Removes path separators and invalid characters, limits length.
             */
            private fun sanitizeFilename(filename: String): String =
                filename
                    .replace(Regex("[/\\\\]"), "_") // Remove path separators
                    .replace(Regex("[<>:\"|?*]"), "_") // Remove invalid chars
                    .take(255) // Limit length for filesystem compatibility
                    .ifEmpty { "attachment" } // Fallback if empty after sanitization
        }

        // Track the currently active conversation - drives reactive message loading
        private val _currentConversation = MutableStateFlow<String?>(null)
        private var currentPeerName: String = "Unknown"

        // Refresh trigger for forcing PagingData refresh when delivery status updates
        // Room's automatic invalidation sometimes doesn't trigger UI refresh with cachedIn()
        private val _messagesRefreshTrigger = MutableStateFlow(0)

        // Messages automatically update when conversation changes OR database changes
        // Uses Paging3 for efficient infinite scroll: loads 30 messages initially,
        // then loads more in background as user scrolls up
        // PERFORMANCE: toMessageUi() is now fast (cache lookup only, no disk I/O)
        // Image decoding happens asynchronously via loadImageAsync()
        // Combined with refresh trigger to force refresh on delivery status updates
        val messages: Flow<PagingData<MessageUi>> =
            kotlinx.coroutines.flow
                .combine(
                    _currentConversation,
                    _messagesRefreshTrigger,
                ) { peerHash, _ -> peerHash }
                .flatMapLatest { peerHash ->
                    Log.d(TAG, "Flow: Switching to conversation $peerHash")
                    if (peerHash != null) {
                        conversationRepository
                            .getMessagesPaged(peerHash)
                            .map { pagingData ->
                                pagingData.map { it.toMessageUi() }
                            }
                    } else {
                        flowOf(PagingData.empty())
                    }
                }.cachedIn(viewModelScope)

        // Announce info for online status - updates in real-time when new announces arrive
        val announceInfo: StateFlow<com.lxmf.messenger.data.repository.Announce?> =
            _currentConversation
                .flatMapLatest { peerHash ->
                    if (peerHash != null) {
                        announceRepository.getAnnounceFlow(peerHash)
                    } else {
                        flowOf(null)
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = null,
                )

        // Link state for current conversation - provides real-time connectivity status
        val conversationLinkState: StateFlow<com.lxmf.messenger.service.ConversationLinkManager.LinkState?> =
            _currentConversation
                .flatMapLatest { peerHash ->
                    if (peerHash != null) {
                        conversationLinkManager.linkStates.map { states -> states[peerHash] }
                    } else {
                        flowOf(null)
                    }
                }.stateIn(
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

        private val _selectedImageIsAnimated = MutableStateFlow(false)
        val selectedImageIsAnimated: StateFlow<Boolean> = _selectedImageIsAnimated.asStateFlow()

        // File attachment state (LXMF Field 5)
        private val _selectedFileAttachments = MutableStateFlow<List<FileAttachment>>(emptyList())
        val selectedFileAttachments: StateFlow<List<FileAttachment>> = _selectedFileAttachments.asStateFlow()

        private val _isProcessingFile = MutableStateFlow(false)
        val isProcessingFile: StateFlow<Boolean> = _isProcessingFile.asStateFlow()

        // Track when a message is being sent to prevent double-sends
        private val _isSending = MutableStateFlow(false)
        val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

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

        // Image quality selection dialog state
        private val _qualitySelectionState = MutableStateFlow<QualitySelectionState?>(null)
        val qualitySelectionState: StateFlow<QualitySelectionState?> = _qualitySelectionState.asStateFlow()

        // Expose current conversation's link state for UI
        val currentLinkState: StateFlow<ConversationLinkManager.LinkState?> =
            combine(
                _currentConversation,
                conversationLinkManager.linkStates,
            ) { peerHash, linkStates ->
                peerHash?.let { linkStates[it] }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        // Sync state from PropagationNodeManager
        val isSyncing: StateFlow<Boolean> = propagationNodeManager.isSyncing
        val currentRelay = propagationNodeManager.currentRelay

        // Manual sync result events for Snackbar notifications
        val manualSyncResult: SharedFlow<SyncResult> = propagationNodeManager.manualSyncResult

        // Real-time sync progress for status UI
        val syncProgress: StateFlow<SyncProgress> = propagationNodeManager.syncProgress

        // Track which images have been decoded - used to trigger recomposition
        // when images become available. The UI observes this to know when to re-check the cache.
        private val _loadedImageIds = MutableStateFlow<Set<String>>(emptySet())
        val loadedImageIds: StateFlow<Set<String>> = _loadedImageIds.asStateFlow()

        // Map of messageId -> decoded image result (for animated GIFs and raw bytes)
        private val _decodedImages = MutableStateFlow<Map<String, DecodedImageResult>>(emptyMap())
        val decodedImages: StateFlow<Map<String, DecodedImageResult>> = _decodedImages.asStateFlow()

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
                }.stateIn(
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
                val theyShareWithUs =
                    allContacts
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

        // Reaction mode state - for overlay display with scroll positioning
        data class ReactionModeState(
            val messageId: String,
            val targetScrollIndex: Int,
            val isFromMe: Boolean,
            val isFailed: Boolean = false,
            val messageBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
            val messageX: Float = 0f,
            val messageY: Float = 0f,
            val messageWidth: Int = 0,
            val messageHeight: Int = 0,
            // Unique ID for each overlay instance
            val instanceId: Long = System.currentTimeMillis(),
            // Controls message visibility during overlay animation
            val isMessageHidden: Boolean = true,
        )

        private val _reactionModeState = MutableStateFlow<ReactionModeState?>(null)
        val reactionModeState: StateFlow<ReactionModeState?> = _reactionModeState.asStateFlow()

        /**
         * Enter reaction mode for a message. Shows overlay with emoji bar and action buttons.
         * Triggers smooth scroll animation to position the message in view.
         * Signal-style: captures a bitmap snapshot of the message to display in the overlay.
         *
         * @param messageId The ID of the message to react to
         * @param scrollIndex The current scroll position index of the message
         * @param isFromMe Whether the message was sent by the current user
         * @param isFailed Whether the message delivery failed
         * @param messageBitmap Bitmap snapshot of the message bubble
         * @param messageX X position of the message on screen
         * @param messageY Y position of the message on screen
         * @param messageWidth Width of the message bubble
         * @param messageHeight Height of the message bubble
         */
        @Suppress("LongParameterList") // All params needed for overlay animation state
        fun enterReactionMode(
            messageId: String,
            scrollIndex: Int,
            isFromMe: Boolean,
            isFailed: Boolean = false,
            messageBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
            messageX: Float = 0f,
            messageY: Float = 0f,
            messageWidth: Int = 0,
            messageHeight: Int = 0,
        ) {
            _reactionModeState.value =
                ReactionModeState(
                    messageId,
                    scrollIndex,
                    isFromMe,
                    isFailed,
                    messageBitmap,
                    messageX,
                    messageY,
                    messageWidth,
                    messageHeight,
                )
            Log.d(TAG, "Entered reaction mode for message: ${messageId.take(16)}... at ($messageX, $messageY)")
        }

        /**
         * Exit reaction mode. Dismisses the overlay and clears the state.
         */
        fun exitReactionMode() {
            _reactionModeState.value = null
            Log.d(TAG, "Exited reaction mode")
        }

        /**
         * Show the original message immediately when dismiss animation starts.
         * The overlay animation continues independently.
         */
        fun showOriginalMessage() {
            _reactionModeState.value = _reactionModeState.value?.copy(isMessageHidden = false)
            Log.d(TAG, "Showing original message during dismiss animation")
        }

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
                        _pendingReplyTo.value =
                            com.lxmf.messenger.ui.model.ReplyPreviewUi(
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
                    val updatedFieldsJson =
                        addReactionToFieldsJson(
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
                    val result =
                        reticulumProtocol.sendReaction(
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
                    try {
                        reticulumProtocol.observeDeliveryStatus().collect { update ->
                            try {
                                Log.d(TAG, "Delivery status update: ${update.messageHash.take(16)}... -> ${update.status}")
                                handleDeliveryStatusUpdate(update)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling delivery status update", e)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting delivery status updates", e)
                    }
                }

                // Collect incoming reactions and update target messages
                viewModelScope.launch {
                    try {
                        reticulumProtocol.reactionReceivedFlow.collect { reactionJson ->
                            try {
                                handleIncomingReaction(reactionJson)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling incoming reaction", e)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting reactions", e)
                    }
                }

                // Pre-load identity hash for reaction ownership checks
                viewModelScope.launch {
                    try {
                        loadIdentityIfNeeded()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pre-loading identity in init", e)
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
                    // Guard: Don't degrade from terminal success states to failed (Issue #257 fix)
                    // This provides defense-in-depth in case Python layer misses the spurious callback
                    if (update.status == "failed" && isTerminalSuccessStatus(message.status)) {
                        Log.w(
                            TAG,
                            "Blocking status degradation from '${message.status}' to 'failed' " +
                                "for message ${update.messageHash.take(16)}...",
                        )
                        return
                    }

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

                    // Record peer activity when delivery proof is received
                    // This proves the peer was recently online and received our message
                    if (update.status == "delivered") {
                        conversationLinkManager.recordPeerActivity(message.conversationHash, update.timestamp)
                    }

                    // Trigger refresh to ensure UI updates (Room invalidation doesn't always propagate with cachedIn)
                    _messagesRefreshTrigger.value++

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
                val updatedFieldsJson =
                    addReactionToFieldsJson(
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

        /**
         * Check if a message status represents a terminal success state.
         * Terminal success states should never degrade to "failed" (Issue #257 fix).
         *
         * @param status The current message status
         * @return true if this is a terminal success status that shouldn't be degraded
         */
        private fun isTerminalSuccessStatus(status: String): Boolean = status in setOf("sent", "propagated", "delivered")

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
                _isSending.value = true
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

                    // Get user's icon appearance for Sideband/MeshChat interoperability
                    val iconAppearance =
                        identityRepository.getActiveIdentitySync()?.let { activeId ->
                            val name = activeId.iconName
                            val fg = activeId.iconForegroundColor
                            val bg = activeId.iconBackgroundColor
                            if (name != null && fg != null && bg != null) {
                                com.lxmf.messenger.reticulum.protocol.IconAppearance(
                                    iconName = name,
                                    foregroundColor = fg,
                                    backgroundColor = bg,
                                )
                            } else {
                                null
                            }
                        }

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
                            iconAppearance = iconAppearance,
                        )

                    result
                        .onSuccess { receipt ->
                            // Clear pending reply after successful send
                            handleSendSuccess(receipt, sanitized, destinationHash, imageData, imageFormat, fileAttachments, deliveryMethodString, replyToId)
                            clearReplyTo()
                        }.onFailure { error ->
                            handleSendFailure(error, sanitized, destinationHash, deliveryMethodString)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending message", e)
                } finally {
                    _isSending.value = false
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
            isAnimated: Boolean = false,
        ) {
            Log.d(
                TAG,
                "Selected image: ${imageData.size} bytes, format=$imageFormat, animated=$isAnimated",
            )
            _selectedImageData.value = imageData
            _selectedImageFormat.value = imageFormat
            _selectedImageIsAnimated.value = isAnimated
        }

        fun clearSelectedImage() {
            Log.d(TAG, "Clearing selected image")
            _selectedImageData.value = null
            _selectedImageFormat.value = null
            _selectedImageIsAnimated.value = false
        }

        fun setProcessingImage(processing: Boolean) {
            _isProcessingImage.value = processing
        }

        /**
         * Add a file attachment from its data.
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

                    // Create attachments cache directory if needed (auto-cleaned by Android)
                    val attachmentsDir = File(context.cacheDir, "attachments")
                    if (!attachmentsDir.exists()) {
                        attachmentsDir.mkdirs()
                    }

                    // Sanitize filename to prevent path traversal attacks
                    val safeFilename = sanitizeFilename(metadata.filename)
                    val tempFile = File(attachmentsDir, safeFilename)
                    tempFile.writeBytes(fileData)
                    Log.d(TAG, "Created cache file for sharing: ${tempFile.absolutePath}")

                    // Get FileProvider URI
                    val uri =
                        FileProvider.getUriForFile(
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
         * Save an image attachment to a user-selected destination.
         *
         * @param context Android context for content resolver
         * @param messageId The message ID containing the image attachment
         * @param destinationUri The Uri where the user wants to save the image
         * @return true if save was successful, false otherwise
         */
        suspend fun saveImage(
            context: Context,
            messageId: String,
            destinationUri: Uri,
        ): Boolean {
            return try {
                // Get the message from the database
                val messageEntity = conversationRepository.getMessageById(messageId)
                if (messageEntity == null) {
                    Log.e(TAG, "Message not found: $messageId")
                    return false
                }

                // Load the image data from the message's fieldsJson
                val imageData = loadImageData(messageEntity.fieldsJson)
                if (imageData == null) {
                    Log.e(TAG, "Could not load image data for message $messageId")
                    return false
                }

                // Write to the destination Uri
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
         * Get a FileProvider URI for an image attachment.
         *
         * Creates a temporary file in the attachments directory and returns a content URI
         * that can be shared with external apps via Intent.ACTION_SEND.
         *
         * @param context Android context for file operations
         * @param messageId The message ID containing the image attachment
         * @return Pair of (Uri, mimeType) or null if the image cannot be accessed
         */
        suspend fun getImageShareUri(
            context: Context,
            messageId: String,
        ): Pair<Uri, String>? {
            return withContext(Dispatchers.IO) {
                try {
                    // Get the message from the database
                    val messageEntity = conversationRepository.getMessageById(messageId)
                    if (messageEntity == null) {
                        Log.e(TAG, "Message not found: $messageId")
                        return@withContext null
                    }

                    // Get image metadata (MIME type and extension)
                    val metadata = getImageMetadata(messageEntity.fieldsJson)
                    if (metadata == null) {
                        Log.e(TAG, "Could not get image metadata for message $messageId")
                        return@withContext null
                    }

                    val (mimeType, extension) = metadata

                    // Load the image data
                    val imageData = loadImageData(messageEntity.fieldsJson)
                    if (imageData == null) {
                        Log.e(TAG, "Could not load image data for message $messageId")
                        return@withContext null
                    }

                    // Create attachments cache directory if needed (auto-cleaned by Android)
                    val attachmentsDir = File(context.cacheDir, "attachments")
                    if (!attachmentsDir.exists()) {
                        attachmentsDir.mkdirs()
                    }

                    // Generate filename with timestamp
                    val timestamp = System.currentTimeMillis()
                    val filename = "image_$timestamp.$extension"

                    // Write to cache file
                    val tempFile = File(attachmentsDir, filename)
                    tempFile.writeBytes(imageData)
                    Log.d(TAG, "Created cache file for sharing: ${tempFile.absolutePath}")

                    // Get FileProvider URI
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile,
                        )

                    Pair(uri, mimeType)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get image share URI", e)
                    null
                }
            }
        }

        /**
         * Get the file extension for an image attachment.
         *
         * Detects the actual image format from magic bytes and returns the appropriate extension.
         * Falls back to "bin" if format cannot be detected.
         */
        suspend fun getImageExtension(messageId: String): String =
            withContext(Dispatchers.IO) {
                try {
                    val messageEntity = conversationRepository.getMessageById(messageId) ?: return@withContext "bin"
                    val metadata = getImageMetadata(messageEntity.fieldsJson) ?: return@withContext "bin"
                    metadata.second // extension
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get image extension", e)
                    "bin"
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
                        val uiPreview =
                            com.lxmf.messenger.ui.model.ReplyPreviewUi(
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
                        val deletedPreview =
                            com.lxmf.messenger.ui.model.ReplyPreviewUi(
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
         * Process an image by showing the quality selection dialog.
         * The dialog shows recommended quality based on link state.
         *
         * @param context Android context for image processing
         * @param uri URI of the image to process
         */
        fun processImageWithCompression(
            context: android.content.Context,
            uri: android.net.Uri,
        ) {
            viewModelScope.launch {
                Log.d(TAG, "Opening quality selection for image")

                // Trigger link establishment for speed probing when user attaches an image
                _currentConversation.value?.let { destHash ->
                    conversationLinkManager.openConversationLink(destHash)
                }

                // Get the current link state for recommendations
                val linkState = currentLinkState.value

                // Determine recommended preset
                val recommendedPreset =
                    if (linkState != null && linkState.isActive) {
                        linkState.recommendPreset()
                    } else {
                        // No active link - use saved preset or default to MEDIUM
                        val savedPreset = settingsRepository.getImageCompressionPreset()
                        if (savedPreset == ImageCompressionPreset.AUTO) {
                            ImageCompressionPreset.MEDIUM
                        } else {
                            savedPreset
                        }
                    }

                // Calculate transfer time estimates for each preset
                val transferTimeEstimates = calculateTransferTimeEstimates(linkState, context, uri)

                // Show the quality selection dialog
                _qualitySelectionState.value =
                    QualitySelectionState(
                        imageUri = uri,
                        context = context,
                        recommendedPreset = recommendedPreset,
                        transferTimeEstimates = transferTimeEstimates,
                    )
            }
        }

        /**
         * Calculate transfer time estimates for each preset based on link state.
         *
         * For ORIGINAL preset, uses actual file size since it applies minimal compression.
         * For other presets, uses the target size (worst-case estimate).
         */
        private fun calculateTransferTimeEstimates(
            linkState: ConversationLinkManager.LinkState?,
            context: Context,
            imageUri: Uri,
        ): Map<ImageCompressionPreset, String?> {
            // Get actual file size for ORIGINAL preset (minimal compression)
            val actualFileSize = FileUtils.getFileSize(context, imageUri)

            return listOf(
                ImageCompressionPreset.LOW,
                ImageCompressionPreset.MEDIUM,
                ImageCompressionPreset.HIGH,
                ImageCompressionPreset.ORIGINAL,
            ).associateWith { preset ->
                val sizeBytes =
                    if (preset == ImageCompressionPreset.ORIGINAL && actualFileSize > 0) {
                        actualFileSize
                    } else {
                        preset.targetSizeBytes
                    }
                linkState?.estimateTransferTimeFormatted(sizeBytes)
            }
        }

        /**
         * User selected a quality preset - compress and attach the image.
         */
        fun selectImageQuality(preset: ImageCompressionPreset) {
            val state = _qualitySelectionState.value ?: return
            _qualitySelectionState.value = null

            viewModelScope.launch {
                _isProcessingImage.value = true
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
                    _isProcessingImage.value = false
                }
            }
        }

        /**
         * Dismiss the quality selection dialog without selecting.
         */
        fun dismissQualitySelection() {
            Log.d(TAG, "Dismissing quality selection dialog")
            _qualitySelectionState.value = null
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
            // Skip if already loaded/loading or already in cache
            if (_loadedImageIds.value.contains(messageId) ||
                _decodedImages.value.containsKey(messageId) ||
                ImageCache.contains(messageId)
            ) {
                return
            }

            viewModelScope.launch {
                try {
                    // Decode on IO thread with animation detection
                    val result =
                        withContext(Dispatchers.IO) {
                            decodeImageWithAnimation(messageId, fieldsJson)
                        }

                    if (result != null) {
                        // Store the decoded result for animated images
                        _decodedImages.update { it + (messageId to result) }
                        Log.d(
                            TAG,
                            "Image loaded async: ${messageId.take(8)}... " +
                                "(animated=${result.isAnimated}, ${result.rawBytes.size} bytes)",
                        )
                        // Mark as loaded (success)
                        _loadedImageIds.update { it + messageId }
                    } else if (hasImageField(fieldsJson)) {
                        // Image field exists but loading failed (file missing or corrupt)
                        // Mark as loaded to stop spinner, even though there's no image
                        Log.w(TAG, "Image not found for message: ${messageId.take(8)}...")
                        _loadedImageIds.update { it + messageId }
                    }
                    // If no image field exists, don't update loadedImageIds
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading image async: ${messageId.take(8)}...", e)
                    // Mark as loaded to stop spinner on error (if there was an image field)
                    if (hasImageField(fieldsJson)) {
                        _loadedImageIds.update { it + messageId }
                    }
                }
            }
        }

        /**
         * Check if fieldsJson contains an image field (field 6).
         * Used to determine if we should mark loading as complete on failure.
         */
        private fun hasImageField(fieldsJson: String?): Boolean {
            if (fieldsJson == null) return false
            return try {
                val fields = org.json.JSONObject(fieldsJson)
                val field6 = fields.opt("6")
                when {
                    field6 is org.json.JSONObject && field6.has("_file_ref") -> true
                    field6 is String && field6.isNotEmpty() -> true
                    else -> false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing fieldsJson for image field check: ${e.message}")
                false
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
         * Fetch a pending file by temporarily increasing the size limit and triggering a sync.
         *
         * Called when user taps a pending file notification bubble.
         * Temporarily increases the incoming message size limit to accommodate the file,
         * triggers a sync to fetch it from the relay, then reverts to the original limit
         * when sync completes (success or failure) or times out.
         *
         * @param fileSizeBytes The size of the pending file in bytes
         */
        fun fetchPendingFile(fileSizeBytes: Long) {
            viewModelScope.launch {
                var originalLimitKb: Int? = null
                try {
                    // Get the current (original) limit to restore after sync completes
                    originalLimitKb = settingsRepository.getIncomingMessageSizeLimitKb()

                    // Calculate new limit: file size + 10% buffer, rounded up to nearest 512KB
                    val fileSizeKb = (fileSizeBytes / 1024).toInt()
                    val withBuffer = (fileSizeKb * 1.1).toInt()
                    val roundedUp = ((withBuffer + 511) / 512) * 512 // Round up to nearest 512KB
                    val newLimitKb = roundedUp.coerceAtLeast(fileSizeKb + 512) // At least 512KB more than file

                    Log.d(TAG, "Temporarily increasing incoming size limit from ${originalLimitKb}KB to ${newLimitKb}KB for ${fileSizeKb}KB file")

                    // Update Python layer temporarily (don't persist to DataStore)
                    if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                        reticulumProtocol.setIncomingMessageSizeLimit(newLimitKb)
                        Log.d(TAG, "Python layer updated with temporary size limit")
                    }

                    // Trigger sync to fetch the file (silent = no toast)
                    // Don't use keepSyncingState so isSyncing properly reflects sync completion/failure
                    propagationNodeManager.triggerSync(silent = true)
                    Log.d(TAG, "Sync triggered to fetch pending file")

                    // Wait for sync to complete (isSyncing goes false) or timeout
                    // Timeout based on file size: ~100KB/s transfer rate + 60s buffer, clamped to 60-300s
                    val timeoutMs = ((fileSizeKb / 100) + 60).coerceIn(60, 300) * 1000L
                    Log.d(TAG, "Waiting up to ${timeoutMs / 1000}s for sync to complete")

                    // Wait for isSyncing to become false (sync complete or failed)
                    val syncCompleted =
                        withTimeoutOrNull(timeoutMs) {
                            // Wait for isSyncing to go true first (sync started), then wait for it to go false
                            propagationNodeManager.isSyncing.first { it } // Wait for sync to start
                            propagationNodeManager.isSyncing.first { !it } // Wait for sync to end
                            true
                        }

                    if (syncCompleted == true) {
                        Log.d(TAG, "Sync completed, reverting size limit to ${originalLimitKb}KB")
                    } else {
                        Log.d(TAG, "Sync timed out, reverting size limit to ${originalLimitKb}KB")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching pending file", e)
                } finally {
                    // Always revert the size limit when done
                    if (originalLimitKb != null) {
                        if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                            reticulumProtocol.setIncomingMessageSizeLimit(originalLimitKb)
                            Log.d(TAG, "Size limit reverted to ${originalLimitKb}KB")
                        }
                    }
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
                            // Preserve reply on retry
                            replyToMessageId = failedMessage.replyToMessageId,
                        )

                    result
                        .onSuccess { receipt ->
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

        /**
         * Get the recommended codec profile based on link speed probing.
         *
         * Probes the link to the current conversation peer and returns a
         * codec profile recommendation based on the measured bandwidth.
         *
         * @return Recommended codec profile, or DEFAULT if probing fails
         */
        suspend fun getRecommendedCodecProfile(): CodecProfile {
            val destHash = _currentConversation.value
            val destHashBytes = destHash?.let { validateDestinationHash(it) }
            val protocol = reticulumProtocol as? ServiceReticulumProtocol

            return if (destHashBytes != null && protocol != null) {
                probeAndRecommendCodec(protocol, destHashBytes)
            } else {
                CodecProfile.DEFAULT
            }
        }

        private suspend fun probeAndRecommendCodec(
            protocol: ServiceReticulumProtocol,
            destHashBytes: ByteArray,
        ): CodecProfile =
            try {
                val probe = protocol.probeLinkSpeed(destHashBytes, 5.0f, "direct")
                if (probe.isSuccess) CodecProfile.recommendFromProbe(probe) else CodecProfile.DEFAULT
            } catch (e: Exception) {
                Log.e(TAG, "Error probing link speed for codec recommendation", e)
                CodecProfile.DEFAULT
            }

        override fun onCleared() {
            super.onCleared()

            // Note: Conversation marking as read happens via loadMessages() when opening
            // and via UI layer's LaunchedEffect. Cleanup here was redundant and violated
            // Phase 1 threading policy (zero runBlocking in production code).
            // See THREADING_REDESIGN_PLAN.md Phase 1.2

            // Links are left open to naturally close via Reticulum's stale timeout (~12 min)
            // rather than explicitly closing - this allows link reuse if user returns quickly

            // Clear active conversation (re-enables notifications)
            activeConversationManager.setActive(null)

            // Disable fast polling when conversation screen is closed
            reticulumProtocol.setConversationActive(false)
            Log.d(TAG, "ViewModel cleared - disabled fast polling")
        }
    }

// Top-level helper functions to keep class function count under threshold

private const val HELPER_TAG = "MessagingViewModel"

/**
 * Validates and sanitizes message content for sending.
 *
 * Important: When sending attachments (images or files) without text content,
 * returns a single space " " instead of empty string. This is required for
 * Sideband compatibility - Sideband's database save logic skips messages with
 * empty content AND empty title, which would cause attachment-only messages
 * to be silently dropped.
 *
 * @param content The raw message content
 * @param imageData Optional image attachment data
 * @param fileAttachments Optional file attachments
 * @return Sanitized content string, or null if validation fails
 */
internal fun validateAndSanitizeContent(
    content: String,
    imageData: ByteArray?,
    fileAttachments: List<FileAttachment> = emptyList(),
): String? {
    // Sideband requires non-empty content to save messages to its database.
    // When sending attachments without text, use a single space (matching Sideband's behavior).
    if (content.trim().isEmpty() && (imageData != null || fileAttachments.isNotEmpty())) {
        return " "
    }
    val validationResult = InputValidator.validateMessageContent(content)
    if (validationResult is ValidationResult.Error) {
        Log.w(HELPER_TAG, "Invalid message content: ${validationResult.message}")
        return null
    }
    return validationResult.getOrThrow()
}

private fun validateDestinationHash(destinationHash: String): ByteArray? =
    when (val result = InputValidator.validateDestinationHash(destinationHash)) {
        is ValidationResult.Success -> result.value
        is ValidationResult.Error -> {
            Log.e(HELPER_TAG, "Invalid destination hash: ${result.message}")
            null
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

private suspend fun buildFieldsJson(
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
    val hasAnyContent = hasImage || hasFiles || hasReply || hasReactions

    if (!hasAnyContent) return null

    // Move CPU-intensive hex encoding to background thread
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val json = org.json.JSONObject()

        // Add image field (Field 6)
        if (hasImage && imageData != null) {
            val hexImageData = imageData.toHexString()
            json.put("6", hexImageData)
        }

        // Add file attachments field (Field 5)
        if (hasFiles) {
            val attachmentsArray = org.json.JSONArray()
            for (attachment in fileAttachments) {
                val attachmentObj = org.json.JSONObject()
                attachmentObj.put("filename", attachment.filename)
                attachmentObj.put("data", attachment.data.toHexString())
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

        json.toString()
    }
}

/**
 * Efficient hex string conversion for ByteArray.
 * Uses a lookup table for O(n) performance instead of O(n * string allocation).
 */
private fun ByteArray.toHexString(): String {
    val hexChars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        hexChars[i * 2] = HEX_CHARS[v ushr 4]
        hexChars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return String(hexChars)
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun resolveActualDestHash(
    receipt: com.lxmf.messenger.reticulum.protocol.MessageReceipt,
    fallbackHash: String,
): String =
    if (receipt.destinationHash.isNotEmpty()) {
        receipt.destinationHash.joinToString("") { "%02x".format(it) }
    } else {
        Log.w(HELPER_TAG, "Received empty destination hash from Python, falling back to original: $fallbackHash")
        fallbackHash
    }

/**
 * Parse image data from LXMF fields JSON.
 * Field 6 contains the image data as hex string.
 */
private fun parseImageFromFieldsJson(fieldsJson: String): ByteArray? =
    try {
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
    val json =
        if (fieldsJson.isNullOrEmpty()) {
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
    val field16 =
        if (json.has("16")) {
            json.getJSONObject("16")
        } else {
            org.json.JSONObject().also { json.put("16", it) }
        }

    // Get or create reactions dictionary
    val reactions =
        if (field16.has("reactions")) {
            field16.getJSONObject("reactions")
        } else {
            org.json.JSONObject().also { field16.put("reactions", it) }
        }

    // Get or create the sender list for this emoji
    val senderList =
        if (reactions.has(emoji)) {
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
    data class Error(
        val message: String,
    ) : ContactToggleResult()
}

/**
 * State for the image quality selection dialog.
 *
 * @property imageUri The URI of the image to be compressed
 * @property context The Android context for image processing
 * @property recommendedPreset The preset recommended based on link speed probe
 * @property transferTimeEstimates Map of preset to estimated transfer time string
 */
data class QualitySelectionState(
    val imageUri: Uri,
    val context: Context,
    val recommendedPreset: ImageCompressionPreset,
    val transferTimeEstimates: Map<ImageCompressionPreset, String?>,
)
