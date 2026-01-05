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
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.SharingSession
import com.lxmf.messenger.ui.model.SharingDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents the freshness state of a contact's location marker.
 */
enum class MarkerState {
    /** Location is fresh (received within stale threshold). */
    FRESH,

    /** Location is stale (older than threshold but sharing not yet expired). */
    STALE,

    /** Sharing has expired but within grace period (last known location). */
    EXPIRED_GRACE_PERIOD,
}

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
    val state: MarkerState = MarkerState.FRESH,
    // Coarsening radius in meters (0 = precise)
    val approximateRadius: Int = 0,
    // Profile icon fields (null = use identicon/initials fallback)
    // iconForegroundColor/iconBackgroundColor are Hex RGB e.g., "FFFFFF", "1E88E5"
    val iconName: String? = null,
    val iconForegroundColor: String? = null,
    val iconBackgroundColor: String? = null,
    // publicKey is for identicon fallback when no icon available
    val publicKey: ByteArray? = null,
)

/**
 * UI state for the Map screen.
 */
@Immutable
data class MapState(
    val userLocation: Location? = null,
    val hasLocationPermission: Boolean = false,
    val isPermissionCardDismissed: Boolean = false,
    val hasUserDismissedPermissionSheet: Boolean = false,
    val contactMarkers: List<ContactMarker> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isSharing: Boolean = false,
    val activeSessions: List<SharingSession> = emptyList(),
    val lastRefresh: Long = 0L,
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
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MapViewModel"
            private const val STALE_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
            private const val GRACE_PERIOD_MS = 60 * 60 * 1000L // 1 hour
            private const val REFRESH_INTERVAL_MS = 30_000L // 30 seconds

            /**
             * Controls whether periodic refresh is enabled.
             * Set to false in tests to prevent infinite coroutine loops.
             * @suppress VisibleForTesting
             */
            internal var enablePeriodicRefresh = true
        }

        private val _state = MutableStateFlow(MapState())
        val state: StateFlow<MapState> = _state.asStateFlow()

        // Refresh trigger for periodic staleness recalculation
        private val _refreshTrigger = MutableStateFlow(0L)

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
            // Collect location permission sheet dismissal state
            viewModelScope.launch {
                settingsRepository.hasDismissedLocationPermissionSheetFlow.collect { dismissed ->
                    _state.update { it.copy(hasUserDismissedPermissionSheet = dismissed) }
                }
            }

            // Collect received locations and convert to markers
            // Combines with both contacts and announces for display name lookup
            // Uses unfiltered query - filtering for stale/expired done in ViewModel
            // Refresh trigger causes periodic recalculation of staleness
            viewModelScope.launch {
                combine(
                    receivedLocationDao.getLatestLocationsPerSenderUnfiltered(),
                    contacts,
                    announceDao.getAllAnnounces(),
                    _refreshTrigger,
                ) { locations, contactList, announceList, _ ->
                    val currentTime = System.currentTimeMillis()

                    // Create lookup maps from contacts
                    val contactMap = contactList.associateBy { it.destinationHash }
                    val contactMapLower = contactList.associateBy { it.destinationHash.lowercase() }

                    // Create lookup maps from announces (for name fallback and icon data)
                    val announceMap = announceList.associateBy { it.destinationHash }
                    val announceMapLower = announceList.associateBy { it.destinationHash.lowercase() }

                    Log.d(TAG, "Processing ${locations.size} locations, ${contactList.size} contacts, ${announceList.size} announces")

                    locations.mapNotNull { loc ->
                        // Calculate marker state - returns null if marker should be hidden
                        // Use receivedAt for staleness (when we got the update) rather than
                        // sender's timestamp to avoid issues with clock skew between devices
                        val markerState =
                            calculateMarkerState(
                                timestamp = loc.receivedAt,
                                expiresAt = loc.expiresAt,
                                currentTime = currentTime,
                            ) ?: return@mapNotNull null

                        // Look up announce for icon data and name fallback
                        val announce =
                            announceMap[loc.senderHash]
                                ?: announceMapLower[loc.senderHash.lowercase()]

                        // Try contacts first (exact, then case-insensitive)
                        // Then try announces (exact, then case-insensitive)
                        val displayName =
                            contactMap[loc.senderHash]?.displayName
                                ?: contactMapLower[loc.senderHash.lowercase()]?.displayName
                                ?: announce?.peerName
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
                            state = markerState,
                            approximateRadius = loc.approximateRadius,
                            iconName = announce?.iconName,
                            iconForegroundColor = announce?.iconForegroundColor,
                            iconBackgroundColor = announce?.iconBackgroundColor,
                            publicKey = announce?.publicKey,
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

            // Periodic refresh to update stale states (every 30 seconds)
            // This ensures markers transition from FRESH -> STALE as time passes
            // Can be disabled for tests via enablePeriodicRefresh companion property
            if (enablePeriodicRefresh) {
                viewModelScope.launch {
                    while (isActive) {
                        delay(REFRESH_INTERVAL_MS)
                        _refreshTrigger.value = System.currentTimeMillis()
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
         * Dismiss the location permission card for this session.
         * User can still trigger permission request via My Location button.
         */
        fun dismissPermissionCard() {
            _state.update { currentState ->
                currentState.copy(isPermissionCardDismissed = true)
            }
        }

        /**
         * Dismiss the location permission bottom sheet for the current app session.
         * The dismissal state persists until the app is relaunched.
         */
        fun dismissLocationPermissionSheet() {
            viewModelScope.launch {
                settingsRepository.markLocationPermissionSheetDismissed()
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

        /**
         * Calculate the marker state based on timestamp and expiry.
         *
         * @param timestamp When the location was captured
         * @param expiresAt When sharing ends (null = indefinite)
         * @param currentTime Current time for comparison (injectable for testing)
         * @return MarkerState indicating freshness, or null if marker should be hidden
         */
        internal fun calculateMarkerState(
            timestamp: Long,
            expiresAt: Long?,
            currentTime: Long = System.currentTimeMillis(),
        ): MarkerState? {
            val age = currentTime - timestamp
            val isExpired = expiresAt != null && expiresAt < currentTime
            val gracePeriodEnd = (expiresAt ?: Long.MAX_VALUE) + GRACE_PERIOD_MS

            return when {
                // Beyond grace period - should not be shown
                isExpired && currentTime > gracePeriodEnd -> null

                // Expired but within grace period (last known location)
                isExpired -> MarkerState.EXPIRED_GRACE_PERIOD

                // Not expired but stale (5+ minutes without update)
                age > STALE_THRESHOLD_MS -> MarkerState.STALE

                // Fresh location
                else -> MarkerState.FRESH
            }
        }
    }
