package com.lxmf.messenger.viewmodel

import android.location.Location
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Represents a contact's location marker on the map.
 *
 * In Phase 1 (MVP), locations are generated as static test positions.
 * In Phase 2+, these will be real locations received via LXMF telemetry.
 */
@Immutable
data class ContactMarker(
    val destinationHash: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
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
)

/**
 * ViewModel for the Map screen.
 *
 * Manages:
 * - User's current location
 * - Contact markers (static test positions in Phase 1)
 * - Location permission state
 */
@HiltViewModel
class MapViewModel
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
    ) : ViewModel() {
        companion object {
            // Default map center (San Francisco) - used when no user location
            private const val DEFAULT_LATITUDE = 37.7749
            private const val DEFAULT_LONGITUDE = -122.4194

            // Radius for distributing test markers around user location (in degrees)
            private const val TEST_MARKER_RADIUS = 0.005
        }

        private val _state = MutableStateFlow(MapState())
        val state: StateFlow<MapState> = _state.asStateFlow()

        // Contacts from repository
        private val contacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        init {
            // Generate static test markers from contacts
            viewModelScope.launch {
                contacts.collect { contactList ->
                    val markers = generateTestMarkers(contactList)
                    _state.update { currentState ->
                        currentState.copy(
                            contactMarkers = markers,
                            isLoading = false,
                        )
                    }
                }
            }
        }

        /**
         * Update the user's current location.
         * Called by the MapScreen when location updates are received.
         */
        fun updateUserLocation(location: Location) {
            _state.update { currentState ->
                currentState.copy(
                    userLocation = location,
                )
            }

            // Re-generate markers centered around user location
            viewModelScope.launch {
                val markers = generateTestMarkers(contacts.value)
                _state.update { currentState ->
                    currentState.copy(contactMarkers = markers)
                }
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
         * Generate static test markers for contacts.
         *
         * In Phase 1 (MVP), we distribute contacts in a circle around the user's location
         * (or default location if user location is not available).
         *
         * In Phase 2+, this will be replaced with real location data from LXMF telemetry.
         */
        private fun generateTestMarkers(contactList: List<EnrichedContact>): List<ContactMarker> {
            val centerLat = _state.value.userLocation?.latitude ?: DEFAULT_LATITUDE
            val centerLng = _state.value.userLocation?.longitude ?: DEFAULT_LONGITUDE

            return contactList.mapIndexed { index, contact ->
                // Distribute contacts in a circle around the center point
                val angle = (index.toDouble() / contactList.size.coerceAtLeast(1)) * 2 * Math.PI
                val lat = centerLat + TEST_MARKER_RADIUS * sin(angle)
                val lng = centerLng + TEST_MARKER_RADIUS * cos(angle)

                ContactMarker(
                    destinationHash = contact.destinationHash,
                    displayName = contact.displayName,
                    latitude = lat,
                    longitude = lng,
                )
            }
        }
    }
