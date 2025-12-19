package com.lxmf.messenger.viewmodel

import android.location.Location
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.SharingSession
import com.lxmf.messenger.ui.model.SharingDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents a contact's location marker on the map.
 *
 * Locations come from LXMF location telemetry (field 7).
 */
@Immutable
data class ContactMarker(
    val destinationHash: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val timestamp: Long = 0L,
    val expiresAt: Long? = null,
)

/**
 * UI state for the Map screen.
 */
@Immutable
data class MapState(
    val userLocation: Location? = null,
    val hasLocationPermission: Boolean = false,
    val contactMarkers: List<ContactMarker> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isSharing: Boolean = false,
    val activeSessions: List<SharingSession> = emptyList(),
)

/**
 * ViewModel for the Map screen.
 *
 * Manages:
 * - User's current location
 * - Contact markers from received location telemetry
 * - Location sharing state
 * - Location permission state
 */
@HiltViewModel
class MapViewModel
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
        private val receivedLocationDao: ReceivedLocationDao,
        private val locationSharingManager: LocationSharingManager,
        private val announceDao: AnnounceDao,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MapViewModel"
        }

        private val _state = MutableStateFlow(MapState())
        val state: StateFlow<MapState> = _state.asStateFlow()

        // Contacts from repository (exposed for ShareLocationBottomSheet)
        val contacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        init {
            // Collect received locations and convert to markers
            // Combines with both contacts and announces for display name lookup
            viewModelScope.launch {
                combine(
                    receivedLocationDao.getLatestLocationsPerSender(),
                    contacts,
                    announceDao.getAllAnnounces(),
                ) { locations, contactList, announceList ->
                    // Create lookup maps from contacts
                    val contactMap = contactList.associateBy { it.destinationHash }
                    val contactMapLower = contactList.associateBy { it.destinationHash.lowercase() }

                    // Create lookup maps from announces (fallback for peers not in contacts)
                    val announceMap = announceList.associate { it.destinationHash to it.peerName }
                    val announceMapLower = announceList.associate { it.destinationHash.lowercase() to it.peerName }

                    Log.d(TAG, "Processing ${locations.size} locations, ${contactList.size} contacts, ${announceList.size} announces")

                    locations.map { loc ->
                        // Try contacts first (exact, then case-insensitive)
                        // Then try announces (exact, then case-insensitive)
                        val displayName = contactMap[loc.senderHash]?.displayName
                            ?: contactMapLower[loc.senderHash.lowercase()]?.displayName
                            ?: announceMap[loc.senderHash]
                            ?: announceMapLower[loc.senderHash.lowercase()]
                            ?: loc.senderHash.take(8)

                        if (displayName == loc.senderHash.take(8)) {
                            Log.w(TAG, "No name found for senderHash: ${loc.senderHash}")
                        }

                        ContactMarker(
                            destinationHash = loc.senderHash,
                            displayName = displayName,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            timestamp = loc.timestamp,
                            expiresAt = loc.expiresAt,
                        )
                    }
                }.collect { markers ->
                    _state.update { currentState ->
                        currentState.copy(
                            contactMarkers = markers,
                            isLoading = false,
                        )
                    }
                }
            }

            // Collect sharing state
            viewModelScope.launch {
                locationSharingManager.isSharing.collect { isSharing ->
                    _state.update { it.copy(isSharing = isSharing) }
                }
            }

            viewModelScope.launch {
                locationSharingManager.activeSessions.collect { sessions ->
                    _state.update { it.copy(activeSessions = sessions) }
                }
            }
        }

        /**
         * Update the user's current location.
         * Called by the MapScreen when location updates are received.
         */
        fun updateUserLocation(location: Location) {
            _state.update { currentState ->
                currentState.copy(userLocation = location)
            }
        }

        /**
         * Update location permission state.
         */
        fun onPermissionResult(granted: Boolean) {
            _state.update { currentState ->
                currentState.copy(hasLocationPermission = granted)
            }
        }

        /**
         * Clear any error message.
         */
        fun clearError() {
            _state.update { currentState ->
                currentState.copy(errorMessage = null)
            }
        }

        /**
         * Start sharing location with selected contacts.
         *
         * @param selectedContacts Contacts to share location with
         * @param duration How long to share
         */
        fun startSharing(
            selectedContacts: List<EnrichedContact>,
            duration: SharingDuration,
        ) {
            Log.d(TAG, "Starting location sharing with ${selectedContacts.size} contacts for $duration")

            val contactHashes = selectedContacts.map { it.destinationHash }
            val displayNames = selectedContacts.associate { it.destinationHash to it.displayName }

            locationSharingManager.startSharing(contactHashes, displayNames, duration)
        }

        /**
         * Stop sharing location.
         *
         * @param destinationHash Specific contact to stop sharing with, or null to stop all
         */
        fun stopSharing(destinationHash: String? = null) {
            Log.d(TAG, "Stopping location sharing: ${destinationHash ?: "all"}")
            locationSharingManager.stopSharing(destinationHash)
        }
    }
