package network.columba.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.repository.BlockedPeerRepository
import network.columba.app.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlockedUsersViewModel
    @Inject
    constructor(
        private val blockedPeerRepository: BlockedPeerRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) : ViewModel() {
        companion object {
            private const val TAG = "BlockedUsersViewModel"
        }

        val blockedPeers: StateFlow<List<BlockedPeerEntity>> =
            blockedPeerRepository
                .getBlockedPeers()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        val blockedPeerCount: StateFlow<Int> =
            blockedPeerRepository
                .getBlockedPeerCount()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = 0,
                )

        fun unblockUser(peer: BlockedPeerEntity) {
            viewModelScope.launch {
                try {
                    blockedPeerRepository.unblockPeer(peer.peerHash)
                    reticulumProtocol.unblockDestination(peer.peerHash)
                    val identityHash = peer.peerIdentityHash
                    if (peer.isBlackholeEnabled && identityHash != null) {
                        reticulumProtocol.unblackholeIdentity(identityHash)
                    }
                    Log.d(TAG, "Unblocked user ${peer.peerHash.take(16)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unblocking user", e)
                }
            }
        }

        fun toggleBlackhole(
            peer: BlockedPeerEntity,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    blockedPeerRepository.updateBlackhole(peer.peerHash, enabled)
                    val identityHash = peer.peerIdentityHash
                    if (identityHash != null) {
                        if (enabled) {
                            reticulumProtocol.blackholeIdentity(identityHash)
                        } else {
                            reticulumProtocol.unblackholeIdentity(identityHash)
                        }
                    }
                    Log.d(TAG, "Toggled blackhole for ${peer.peerHash.take(16)} to $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling blackhole", e)
                }
            }
        }
    }
