package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.ui.model.LocationSharingState
import com.lxmf.messenger.ui.model.SharingDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for managing location sharing with a specific peer.
 *
 * Extracted from MessagingViewModel to follow single responsibility principle.
 * Handles:
 * - Computing location sharing state for the current peer
 * - Starting/stopping location sharing with the peer
 *
 * This ViewModel can be used alongside MessagingViewModel or independently
 * in other screens that need location sharing functionality (e.g., contact details).
 *
 * ## Usage
 * ```kotlin
 * // Set the current peer context
 * locationSharingViewModel.setCurrentPeer(peerHash)
 *
 * // Observe the sharing state
 * val state by locationSharingViewModel.locationSharingState.collectAsState()
 *
 * // Start sharing
 * locationSharingViewModel.startSharing("Alice", SharingDuration.ONE_HOUR)
 *
 * // Stop sharing
 * locationSharingViewModel.stopSharing()
 * ```
 */
@HiltViewModel
class LocationSharingViewModel
    @Inject
    constructor(
        private val locationSharingManager: LocationSharingManager,
        private val contactRepository: ContactRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "LocationSharingVM"
        }

        // The current peer we're viewing/interacting with
        private val _currentPeerHash = MutableStateFlow<String?>(null)
        val currentPeerHash: StateFlow<String?> = _currentPeerHash

        /**
         * Location sharing state with the current peer.
         *
         * Computes the bidirectional sharing state:
         * - MUTUAL: Both sharing with each other
         * - SHARING_WITH_THEM: We share, they don't
         * - THEY_SHARE_WITH_ME: They share, we don't
         * - NONE: No sharing in either direction
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val locationSharingState: StateFlow<LocationSharingState> =
            _currentPeerHash
                .flatMapLatest { peerHash ->
                    if (peerHash == null) {
                        flowOf(LocationSharingState.NONE)
                    } else {
                        combine(
                            locationSharingManager.activeSessions,
                            contactRepository.getEnrichedContacts(),
                        ) { sessions, allContacts ->
                            val sharingWithThem = sessions.any { it.destinationHash == peerHash }
                            val theyShareWithUs =
                                allContacts
                                    .find { it.destinationHash == peerHash }
                                    ?.isReceivingLocationFrom == true

                            when {
                                sharingWithThem && theyShareWithUs -> LocationSharingState.MUTUAL
                                sharingWithThem -> LocationSharingState.SHARING_WITH_THEM
                                theyShareWithUs -> LocationSharingState.THEY_SHARE_WITH_ME
                                else -> LocationSharingState.NONE
                            }
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = LocationSharingState.NONE,
                )

        /**
         * Whether we're currently sharing our location with anyone (not just this peer).
         *
         * Useful for showing a global "sharing active" indicator.
         */
        val isSharing: StateFlow<Boolean> = locationSharingManager.isSharing

        /**
         * Set the current peer context for this ViewModel.
         *
         * @param peerHash The destination hash of the peer, or null to clear
         */
        fun setCurrentPeer(peerHash: String?) {
            _currentPeerHash.value = peerHash
            if (peerHash != null) {
                Log.d(TAG, "Set current peer: $peerHash")
            }
        }

        /**
         * Start sharing location with the current peer.
         *
         * @param peerName The display name of the peer (for UI/notifications)
         * @param duration How long to share location
         */
        fun startSharing(
            peerName: String,
            duration: SharingDuration,
        ) {
            val peerHash = _currentPeerHash.value
            if (peerHash == null) {
                Log.w(TAG, "Cannot start sharing: no peer set")
                return
            }

            Log.d(TAG, "Starting location sharing with $peerName for $duration")
            locationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }

        /**
         * Stop sharing location with the current peer.
         */
        fun stopSharing() {
            val peerHash = _currentPeerHash.value
            if (peerHash == null) {
                Log.w(TAG, "Cannot stop sharing: no peer set")
                return
            }

            Log.d(TAG, "Stopping location sharing with $peerHash")
            locationSharingManager.stopSharing(peerHash)
        }

        /**
         * Start sharing location with a specific peer (without setting them as current).
         *
         * Useful when starting sharing from a contact picker or bulk share UI.
         *
         * @param peerHash The destination hash of the peer
         * @param peerName The display name of the peer
         * @param duration How long to share location
         */
        fun startSharingWith(
            peerHash: String,
            peerName: String,
            duration: SharingDuration,
        ) {
            Log.d(TAG, "Starting location sharing with $peerName ($peerHash) for $duration")
            locationSharingManager.startSharing(
                contactHashes = listOf(peerHash),
                displayNames = mapOf(peerHash to peerName),
                duration = duration,
            )
        }

        /**
         * Stop sharing location with a specific peer.
         *
         * @param peerHash The destination hash of the peer
         */
        fun stopSharingWith(peerHash: String) {
            Log.d(TAG, "Stopping location sharing with $peerHash")
            locationSharingManager.stopSharing(peerHash)
        }
    }
