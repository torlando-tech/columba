package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.toMessageUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Message Detail screen.
 * Observes message details reactively so the UI automatically updates when
 * delivery status changes (e.g., pending → delivered or failed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessageDetailViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MessageDetailViewModel"
        }

        // The message ID to observe - set via loadMessage()
        private val _messageId = MutableStateFlow<String?>(null)

        /**
         * Reactive message state that automatically updates when the database changes.
         * Uses flatMapLatest to switch to a new Flow when messageId changes.
         */
        val message: StateFlow<MessageUi?> =
            _messageId
                .flatMapLatest { messageId ->
                    if (messageId != null) {
                        Log.d(TAG, "Observing message: $messageId")
                        conversationRepository.observeMessageById(messageId)
                            .map { entity ->
                                if (entity != null) {
                                    // Convert entity to domain model, then to UI model
                                    val domainMessage =
                                        com.lxmf.messenger.data.repository.Message(
                                            id = entity.id,
                                            destinationHash = entity.conversationHash,
                                            content = entity.content,
                                            timestamp = entity.timestamp,
                                            isFromMe = entity.isFromMe,
                                            status = entity.status,
                                            fieldsJson = entity.fieldsJson,
                                            deliveryMethod = entity.deliveryMethod,
                                            errorMessage = entity.errorMessage,
                                        )
                                    Log.d(TAG, "Message updated: status=${entity.status}, method=${entity.deliveryMethod}")
                                    domainMessage.toMessageUi()
                                } else {
                                    Log.w(TAG, "Message not found: $messageId")
                                    null
                                }
                            }
                    } else {
                        flowOf(null)
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = null,
                )

        /**
         * Start observing a message by ID.
         * The message state will automatically update when the database changes.
         */
        fun loadMessage(messageId: String) {
            Log.d(TAG, "loadMessage called for: $messageId")
            _messageId.value = messageId
        }
    }
