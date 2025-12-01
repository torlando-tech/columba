package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.Conversation
import com.lxmf.messenger.data.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class ChatsViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
        private val contactRepository: ContactRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "ChatsViewModel"
        }

        // Cache for contact saved state flows to prevent flickering on recomposition
        private val contactSavedCache = ConcurrentHashMap<String, StateFlow<Boolean>>()

        // Search query state
        val searchQuery = MutableStateFlow("")

        // Filtered conversations based on search query
        val conversations: StateFlow<List<Conversation>> =
            searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        conversationRepository.getConversations()
                    } else {
                        conversationRepository.searchConversations(query)
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyList(),
                )

        fun deleteConversation(peerHash: String) {
            viewModelScope.launch {
                try {
                    conversationRepository.deleteConversation(peerHash)
                    Log.d(TAG, "Deleted conversation with $peerHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting conversation", e)
                }
            }
        }

        fun markAsUnread(peerHash: String) {
            viewModelScope.launch {
                try {
                    conversationRepository.markConversationAsUnread(peerHash)
                    Log.d(TAG, "Marked conversation $peerHash as unread")
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking conversation as unread", e)
                }
            }
        }

        /**
         * Save a conversation peer to contacts
         */
        fun saveToContacts(conversation: Conversation) {
            viewModelScope.launch {
                try {
                    // Get public key from conversation or fallback to peer_identities table
                    val publicKey =
                        conversation.peerPublicKey
                            ?: conversationRepository.getPeerPublicKey(conversation.peerHash)

                    if (publicKey == null) {
                        Log.e(TAG, "Cannot save to contacts: Public key not available for ${conversation.peerHash}")
                        // TODO: Emit error state for UI to show error message
                        return@launch
                    }

                    // Use addContactFromAnnounce with CONVERSATION as source
                    // Note: We'll update ContactRepository to support this
                    contactRepository.addContactFromConversation(
                        destinationHash = conversation.peerHash,
                        publicKey = publicKey,
                        peerName = conversation.peerName,
                    )
                    Log.d(TAG, "Saved ${conversation.peerName} to contacts")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving to contacts", e)
                }
            }
        }

        /**
         * Remove a conversation peer from contacts
         */
        fun removeFromContacts(peerHash: String) {
            viewModelScope.launch {
                try {
                    contactRepository.deleteContact(peerHash)
                    Log.d(TAG, "Removed $peerHash from contacts")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing from contacts", e)
                }
            }
        }

        /**
         * Check if a peer is saved as a contact.
         * Uses a cache to prevent flickering when the LazyColumn recomposes.
         */
        fun isContactSaved(peerHash: String): StateFlow<Boolean> {
            return contactSavedCache.getOrPut(peerHash) {
                contactRepository.hasContactFlow(peerHash)
                    .stateIn(
                        scope = viewModelScope,
                        started = SharingStarted.WhileSubscribed(5000),
                        initialValue = false,
                    )
            }
        }
    }
