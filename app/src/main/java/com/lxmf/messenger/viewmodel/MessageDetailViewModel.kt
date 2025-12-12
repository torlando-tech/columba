package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.ui.model.MessageUi
import com.lxmf.messenger.ui.model.toMessageUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Message Detail screen.
 * Loads and exposes message details including delivery method and error information.
 */
@HiltViewModel
class MessageDetailViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MessageDetailViewModel"
        }

        private val _message = MutableStateFlow<MessageUi?>(null)
        val message: StateFlow<MessageUi?> = _message.asStateFlow()

        /**
         * Load message details by ID.
         * The message is fetched from the repository and converted to UI model.
         */
        fun loadMessage(messageId: String) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Loading message details for: $messageId")
                    val entity = conversationRepository.getMessageById(messageId)
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
                        _message.value = domainMessage.toMessageUi()
                        Log.d(TAG, "Loaded message: status=${entity.status}, method=${entity.deliveryMethod}")
                    } else {
                        Log.w(TAG, "Message not found: $messageId")
                        _message.value = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading message details", e)
                    _message.value = null
                }
            }
        }
    }
