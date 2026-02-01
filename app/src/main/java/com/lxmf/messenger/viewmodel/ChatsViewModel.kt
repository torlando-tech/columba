package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.Conversation
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.SyncProgress
import com.lxmf.messenger.service.SyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * UI state for the Chats tab, including loading status.
 */
data class ChatsState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ChatsViewModel
    @Inject
    constructor(
        private val conversationRepository: ConversationRepository,
        private val contactRepository: ContactRepository,
        private val propagationNodeManager: PropagationNodeManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "ChatsViewModel"
        }

        // Sync state from PropagationNodeManager
        val isSyncing: StateFlow<Boolean> = propagationNodeManager.isSyncing

        // Manual sync result events for Snackbar notifications
        val manualSyncResult: SharedFlow<SyncResult> = propagationNodeManager.manualSyncResult

        // Sync progress for UI display
        val syncProgress: StateFlow<SyncProgress> = propagationNodeManager.syncProgress

        // Cache for contact saved state flows to prevent flickering on recomposition
        private val contactSavedCache = ConcurrentHashMap<String, StateFlow<Boolean>>()

        // Search query state
        val searchQuery = MutableStateFlow("")

        // Filtered conversations based on search query, with loading state
        // onStart emits loading state each time flow is collected (tab switch, screen entry)
        val chatsState: StateFlow<ChatsState> =
            searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        conversationRepository.getConversations()
                    } else {
                        conversationRepository.searchConversations(query)
                    }
                }.map { conversations ->
                    ChatsState(
                        conversations = conversations,
                        isLoading = false,
                    )
                }.onStart {
                    emit(ChatsState(isLoading = true))
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = ChatsState(isLoading = true),
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

                    // Use addContactFromConversation which uses the announce peerName via COALESCE
                    contactRepository.addContactFromConversation(
                        destinationHash = conversation.peerHash,
                        publicKey = publicKey,
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
        fun isContactSaved(peerHash: String): StateFlow<Boolean> =
            contactSavedCache.getOrPut(peerHash) {
                contactRepository
                    .hasContactFlow(peerHash)
                    .stateIn(
                        scope = viewModelScope,
                        started = SharingStarted.WhileSubscribed(5000),
                        initialValue = false,
                    )
            }

        /**
         * Trigger a manual sync with the propagation node.
         */
        fun syncFromPropagationNode() {
            viewModelScope.launch {
                try {
                    propagationNodeManager.triggerSync()
                    Log.d(TAG, "Manual sync triggered from ChatsScreen")
                } catch (e: Exception) {
                    Log.e(TAG, "Error triggering manual sync", e)
                }
            }
        }
    }
