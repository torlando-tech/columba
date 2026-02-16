package com.lxmf.messenger.viewmodel

import android.location.Location
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.OfflineMapRegionRepository
import com.lxmf.messenger.map.MapStyleResult
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.service.LocationSharingManager
import com.lxmf.messenger.service.SharingSession
import com.lxmf.messenger.service.TelemetryCollectorManager
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
 * Saved camera position for restoring viewport across tab switches.
 */
@Immutable
data class SavedCameraPosition(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
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
    val mapStyleResult: MapStyleResult? = null,
    val collectorAddress: String? = null,
    val isTelemetrySendEnabled: Boolean = false,
    val isTelemetryRequestEnabled: Boolean = false,
    val isSendingTelemetry: Boolean = false,
    val isRequestingTelemetry: Boolean = false,
    /** Center coordinates of the default offline map region (fallback when no GPS) */
    val defaultRegionCenter: SavedCameraPosition? = null,
    /** Last camera position for restoring viewport after tab switches */
    val lastCameraPosition: SavedCameraPosition? = null,
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
        private val savedStateHandle: SavedStateHandle,
        private val contactRepository: ContactRepository,
        private val receivedLocationDao: ReceivedLocationDao,
        private val locationSharingManager: LocationSharingManager,
        private val announceDao: AnnounceDao,
        private val settingsRepository: SettingsRepository,
        private val mapTileSourceManager: MapTileSourceManager,
        private val telemetryCollectorManager: TelemetryCollectorManager,
        private val offlineMapRegionRepository: OfflineMapRegionRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "MapViewModel"
            private const val STALE_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
            private const val GRACE_PERIOD_MS = 60 * 60 * 1000L // 1 hour
            private const val REFRESH_INTERVAL_MS = 30_000L // 30 seconds
            private const val KEY_PERMISSION_CARD_DISMISSED = "isPermissionCardDismissed"
            private const val KEY_PERMISSION_SHEET_DISMISSED = "hasUserDismissedPermissionSheet"

            /**
             * Controls whether periodic refresh is enabled.
             * Set to false in tests to prevent infinite coroutine loops.
             * @suppress VisibleForTesting
             */
            internal var enablePeriodicRefresh = true

            /**
             * Parse appearance JSON from telemetry into icon fields.
             *
             * @param appearanceJson JSON string with icon_name, foreground_color, background_color
             * @return Triple of (iconName, foregroundColor, backgroundColor) or null if invalid/empty
             */
            internal fun parseAppearanceJson(appearanceJson: String?): Triple<String, String, String>? =
                appearanceJson?.let { json ->
                    try {
                        val obj = org.json.JSONObject(json)
                        Triple(
                            obj.optString("icon_name", ""),
                            obj.optString("foreground_color", ""),
                            obj.optString("background_color", ""),
                        ).takeIf { it.first.isNotEmpty() }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse appearanceJson", e)
                        null
                    }
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

        private val _state =
            MutableStateFlow(
                MapState(
                    // Restore permission card dismissed state from SavedStateHandle
                    // This survives tab switches (Navigation saveState/restoreState) and process death,
                    // but resets on fresh app launch — matching the expected 0.6.x behavior.
                    // Fixes issue #342: permission card reappearing on every tab switch.
                    isPermissionCardDismissed =
                        (savedStateHandle.get<Boolean>(KEY_PERMISSION_CARD_DISMISSED) ?: false).also {
                            Log.d(TAG, "MapViewModel created: isPermissionCardDismissed from SavedStateHandle = $it (handle=${savedStateHandle.hashCode()})")
                        },
                    // Restore permission sheet dismissed state from SavedStateHandle
                    // Same pattern as the card - survives tab switches but resets on fresh launch.
                    hasUserDismissedPermissionSheet =
                        (savedStateHandle.get<Boolean>(KEY_PERMISSION_SHEET_DISMISSED) ?: false).also {
                            Log.d(TAG, "MapViewModel created: hasUserDismissedPermissionSheet from SavedStateHandle = $it")
                        },
                ),
            )
        val state: StateFlow<MapState> = _state.asStateFlow()

        // Refresh trigger for periodic staleness recalculation
        private val _refreshTrigger = MutableStateFlow(0L)

        // Contacts from repository (exposed for ShareLocationBottomSheet)
        // Use Lazily instead of WhileSubscribed to prevent flow cancellation when switching tabs
        // This ensures markers are immediately available when returning to the map
        val contacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Lazily,
                    initialValue = emptyList(),
                )

        init {
            // Resolve initial map style (with null location)
            resolveMapStyle(null, null)

            // Load default offline map region center as fallback for initial map position
            viewModelScope.launch {
                val defaultRegion = offlineMapRegionRepository.getDefaultRegion()
                if (defaultRegion != null) {
                    _state.update {
                        it.copy(
                            defaultRegionCenter =
                                SavedCameraPosition(
                                    latitude = defaultRegion.centerLatitude,
                                    longitude = defaultRegion.centerLongitude,
                                    zoom = 12.0,
                                ),
                        )
                    }
                    Log.d(TAG, "Default region loaded: ${defaultRegion.name} at ${defaultRegion.centerLatitude}, ${defaultRegion.centerLongitude}")
                }
            }

            // Refresh map style when offline map availability changes (e.g., after download)
            viewModelScope.launch {
                mapTileSourceManager.hasOfflineMaps().collect {
                    refreshMapStyle()
                }
            }

            // Refresh map style when HTTP setting changes
            viewModelScope.launch {
                mapTileSourceManager.httpEnabledFlow.collect {
                    refreshMapStyle()
                }
            }

            // Note: Location permission sheet dismissal state is now managed via SavedStateHandle
            // (initialized above). This survives tab switches but resets on fresh app launch.
            // DataStore is still written to in dismissLocationPermissionSheet() for consistency
            // with MainActivity's reset logic, but we don't need to collect from it.

            // Collect received locations and convert to markers
            // Combines with both contacts and announces for display name lookup
            // Uses unfiltered query - filtering for stale/expired done in ViewModel
            // Refresh trigger causes periodic recalculation of staleness
            // Uses enriched announces (with icon data from peer_icons table) for marker display
            viewModelScope.launch {
                combine(
                    receivedLocationDao.getLatestLocationsPerSenderUnfiltered(),
                    contacts,
                    announceDao.getEnrichedAnnounces(),
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

                        // Prefer appearance from telemetry message, fall back to announce
                        val telemetryAppearance = parseAppearanceJson(loc.appearanceJson)

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
                            iconName = telemetryAppearance?.first ?: announce?.iconName,
                            iconForegroundColor = telemetryAppearance?.second ?: announce?.iconForegroundColor,
                            iconBackgroundColor = telemetryAppearance?.third ?: announce?.iconBackgroundColor,
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

            // Collect telemetry collector state for map FABs
            viewModelScope.launch {
                telemetryCollectorManager.collectorAddress.collect { address ->
                    _state.update { it.copy(collectorAddress = address) }
                }
            }
            viewModelScope.launch {
                telemetryCollectorManager.isEnabled.collect { enabled ->
                    _state.update { it.copy(isTelemetrySendEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                telemetryCollectorManager.isRequestEnabled.collect { enabled ->
                    _state.update { it.copy(isTelemetryRequestEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                telemetryCollectorManager.isSending.collect { sending ->
                    _state.update { it.copy(isSendingTelemetry = sending) }
                }
            }
            viewModelScope.launch {
                telemetryCollectorManager.isRequesting.collect { requesting ->
                    _state.update { it.copy(isRequestingTelemetry = requesting) }
                }
            }
        }

        /**
         * Update the user's current location.
         * Called by the MapScreen when location updates are received.
         */
        fun updateUserLocation(location: Location) {
            val previousLocation = _state.value.userLocation
            _state.update { currentState ->
                currentState.copy(userLocation = location)
            }
            // Resolve map style on first location fix
            if (previousLocation == null) {
                resolveMapStyle(location.latitude, location.longitude)
            }
        }

        /**
         * Resolve which map style to use based on location and settings.
         */
        private fun resolveMapStyle(
            latitude: Double?,
            longitude: Double?,
        ) {
            viewModelScope.launch {
                val result = mapTileSourceManager.getMapStyle(latitude, longitude)
                _state.update { it.copy(mapStyleResult = result) }
            }
        }

        /**
         * Force refresh of map style (e.g., after settings change).
         */
        fun refreshMapStyle() {
            val location = _state.value.userLocation
            resolveMapStyle(location?.latitude, location?.longitude)
        }

        /**
         * Enable HTTP map source and refresh the map style.
         * Called from the "No Map Source" overlay.
         * Clears the "enabled for download" flag since user explicitly wants HTTP enabled.
         */
        fun enableHttp() {
            viewModelScope.launch {
                // Clear the flag - user is explicitly choosing to enable HTTP
                settingsRepository.setHttpEnabledForDownload(false)
                mapTileSourceManager.setHttpEnabled(true)
                refreshMapStyle()
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
         *
         * Persisted via SavedStateHandle so it survives tab switches (Navigation
         * saveState/restoreState) and process death, but resets on fresh app launch.
         * Fixes issue #342.
         */
        fun dismissPermissionCard() {
            savedStateHandle[KEY_PERMISSION_CARD_DISMISSED] = true
            _state.update { currentState ->
                currentState.copy(isPermissionCardDismissed = true)
            }
        }

        /**
         * Dismiss the location permission bottom sheet for the current app session.
         *
         * Persisted via SavedStateHandle so it survives tab switches (Navigation
         * saveState/restoreState) and process death, but resets on fresh app launch.
         * Also saves to DataStore so MainActivity can reset it on cold start.
         */
        fun dismissLocationPermissionSheet() {
            // Persist to SavedStateHandle for immediate tab switch survival
            savedStateHandle[KEY_PERMISSION_SHEET_DISMISSED] = true
            // Update state immediately so UI responds right away
            _state.update { it.copy(hasUserDismissedPermissionSheet = true) }
            // Also update DataStore for app-level state management
            viewModelScope.launch {
                settingsRepository.markLocationPermissionSheetDismissed()
            }
        }

        /**
         * Save the current camera position for viewport restoration after tab switches.
         */
        fun saveCameraPosition(
            latitude: Double,
            longitude: Double,
            zoom: Double,
        ) {
            _state.update {
                it.copy(lastCameraPosition = SavedCameraPosition(latitude, longitude, zoom))
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
         * When stopping all (destinationHash == null), also disables group telemetry sending.
         *
         * @param destinationHash Specific contact to stop sharing with, or null to stop all
         */
        fun stopSharing(destinationHash: String? = null) {
            Log.d(TAG, "Stopping location sharing: ${destinationHash ?: "all"}")
            locationSharingManager.stopSharing(destinationHash)
            // When stopping all sharing, also disable group telemetry sending
            if (destinationHash == null && _state.value.isTelemetrySendEnabled) {
                viewModelScope.launch {
                    telemetryCollectorManager.setEnabled(false)
                }
            }
        }

        /**
         * Manually send location telemetry to the configured collector.
         */
        fun sendTelemetryNow() {
            viewModelScope.launch {
                telemetryCollectorManager.sendTelemetryNow()
            }
        }

        /**
         * Manually request location telemetry from the configured collector.
         */
        fun requestTelemetryNow() {
            viewModelScope.launch {
                telemetryCollectorManager.requestTelemetryNow()
            }
        }

        /**
         * Delete a stale marker by removing all stored locations for the given sender.
         *
         * @param destinationHash The destination hash of the contact whose marker to remove
         */
        fun deleteMarker(destinationHash: String) {
            Log.d(TAG, "Deleting marker for: ${destinationHash.take(16)}")
            viewModelScope.launch {
                receivedLocationDao.deleteLocationsForSender(destinationHash)
            }
        }
    }
