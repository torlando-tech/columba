package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.AnnounceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedPeersViewModel
    @Inject
    constructor(
        private val announceRepository: AnnounceRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "SavedPeersViewModel"
        }

        // Search query state
        val searchQuery = MutableStateFlow("")

        // Filtered saved peers based on search query
        val savedPeers: StateFlow<List<com.lxmf.messenger.data.repository.Announce>> =
            searchQuery
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        announceRepository.getFavoriteAnnounces()
                    } else {
                        announceRepository.searchFavoriteAnnounces(query)
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        // Get count of saved peers
        val favoriteCount = announceRepository.getFavoriteCount()

        /**
         * Toggle favorite status for a peer
         */
        fun toggleFavorite(destinationHash: String) {
            viewModelScope.launch {
                try {
                    announceRepository.toggleFavorite(destinationHash)
                    Log.d(TAG, "Toggled favorite status for $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle favorite status for $destinationHash", e)
                }
            }
        }

        /**
         * Remove a peer from saved (explicitly unfavorite)
         */
        fun removeFavorite(destinationHash: String) {
            viewModelScope.launch {
                try {
                    announceRepository.setFavorite(destinationHash, false)
                    Log.d(TAG, "Removed favorite for $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove favorite for $destinationHash", e)
                }
            }
        }

        /**
         * Delete an announce from the database.
         * The announce will reappear if the peer announces again.
         */
        fun deleteAnnounce(destinationHash: String) {
            viewModelScope.launch {
                try {
                    announceRepository.deleteAnnounce(destinationHash)
                    Log.d(TAG, "Deleted announce: $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete announce: $destinationHash", e)
                }
            }
        }
    }
