package network.columba.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.protocol.DiscoveredInterface
import network.columba.app.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sort mode for discovered interfaces list.
 */
enum class DiscoveredInterfacesSortMode {
    /** Sort by availability status and stamp quality (default from RNS) */
    AVAILABILITY_AND_QUALITY,

    /** Sort by proximity to user (nearest first). Requires user location. */
    PROXIMITY,
}

/**
 * Coarse-grained bucket for filtering discovered interfaces by type. Maps from
 * the fine-grained type strings RNS reports (e.g. "TCPServerInterface",
 * "BackboneInterface", "RNodeInterface") to UI-friendly buckets.
 */
enum class DiscoveredInterfaceTypeFilter(
    val label: String,
) {
    TCP("TCP"),
    RADIO("Radio"),
    I2P("I2P"),
    OTHER("Other"),
    ;

    companion object {
        /** Classify a DiscoveredInterface into a filter bucket. */
        fun classify(iface: DiscoveredInterface): DiscoveredInterfaceTypeFilter =
            when {
                iface.isTcpInterface -> TCP
                iface.isRadioInterface -> RADIO
                iface.type.contains("I2P", ignoreCase = true) -> I2P
                else -> OTHER
            }
    }
}

/**
 * State for the discovered interfaces screen.
 */
@androidx.compose.runtime.Immutable
data class DiscoveredInterfacesState(
    val interfaces: List<DiscoveredInterface> = emptyList(),
    // Original list from Python, sorted by availability/quality - used to restore when switching back
    val originalInterfaces: List<DiscoveredInterface> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val availableCount: Int = 0,
    val unknownCount: Int = 0,
    val staleCount: Int = 0,
    // User's location for distance calculation (nullable)
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    // Sort mode for the interface list
    val sortMode: DiscoveredInterfacesSortMode = DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY,
    // Discovery settings (from DataStore - user preference)
    val discoverInterfacesEnabled: Boolean = false,
    val autoconnectCount: Int = 0,
    /** When true, auto-connect only accepts interfaces that announced IFAC. */
    val autoconnectIfacOnly: Boolean = false,
    // Runtime status (from RNS - current state)
    val isDiscoveryEnabled: Boolean = false,
    // Bootstrap interfaces that enable discovery
    val bootstrapInterfaceNames: List<String> = emptyList(),
    // Service is currently restarting
    val isRestarting: Boolean = false,
    // Currently auto-connected interface endpoints (e.g., "host:port")
    val autoconnectedEndpoints: Set<String> = emptySet(),
    // Free-form search filter, matched against interface name + reachableOn + type.
    val searchQuery: String = "",
    // Multi-select type filter. Empty set = no filtering (all types shown).
    val typeFilters: Set<DiscoveredInterfaceTypeFilter> = emptySet(),
    // When true, only show interfaces that announced an IFAC network name.
    val ifacOnly: Boolean = false,
)

/**
 * ViewModel for displaying discovered interfaces from RNS 1.1.x discovery system.
 */
@HiltViewModel
class DiscoveredInterfacesViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val settingsRepository: SettingsRepository,
        private val interfaceRepository: InterfaceRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "DiscoveredIfacesVM"

            // Made internal var to allow injecting test dispatcher
            internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
        }

        private val _state = MutableStateFlow(DiscoveredInterfacesState())
        val state: StateFlow<DiscoveredInterfacesState> = _state.asStateFlow()

        init {
            loadDiscoveredInterfaces()
            loadDiscoverySettings()
        }

        /**
         * Load discovered interfaces from RNS 1.1.x discovery system.
         */
        fun loadDiscoveredInterfaces() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    _state.update { it.copy(isLoading = true, errorMessage = null) }

                    val isEnabled = reticulumProtocol.isDiscoveryEnabled()
                    val discovered = reticulumProtocol.getDiscoveredInterfaces()
                    val autoconnectedEndpoints = reticulumProtocol.getAutoconnectedEndpoints()
                    val availableCount = discovered.count { it.status == "available" }
                    val unknownCount = discovered.count { it.status == "unknown" }
                    val staleCount = discovered.count { it.status == "stale" }

                    _state.update { currentState ->
                        // Reset to QUALITY mode if in PROXIMITY but location unavailable
                        val effectiveSortMode =
                            if (currentState.sortMode == DiscoveredInterfacesSortMode.PROXIMITY &&
                                (currentState.userLatitude == null || currentState.userLongitude == null)
                            ) {
                                DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY
                            } else {
                                currentState.sortMode
                            }

                        val visibleInterfaces =
                            applySearchAndFilter(
                                source = discovered,
                                searchQuery = currentState.searchQuery,
                                typeFilters = currentState.typeFilters,
                                ifacOnly = currentState.ifacOnly,
                                sortMode = effectiveSortMode,
                                userLat = currentState.userLatitude,
                                userLon = currentState.userLongitude,
                            )
                        currentState.copy(
                            interfaces = visibleInterfaces,
                            originalInterfaces = discovered, // Store original list for re-filtering
                            sortMode = effectiveSortMode,
                            isLoading = false,
                            availableCount = availableCount,
                            unknownCount = unknownCount,
                            staleCount = staleCount,
                            isDiscoveryEnabled = isEnabled,
                            autoconnectedEndpoints = autoconnectedEndpoints,
                        )
                    }
                    Log.d(TAG, "Loaded ${discovered.size} discovered interfaces, ${autoconnectedEndpoints.size} auto-connected")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load discovered interfaces", e)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load discovered interfaces: ${e.message}",
                        )
                    }
                }
            }
        }

        /**
         * Load discovery settings from DataStore and bootstrap interface names.
         */
        private fun loadDiscoverySettings() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val discoverEnabled = settingsRepository.getDiscoverInterfacesEnabled()
                    val savedAutoconnect = settingsRepository.getAutoconnectDiscoveredCount()
                    val ifacOnly = settingsRepository.getAutoconnectIfacOnly()
                    val bootstrapNames = interfaceRepository.bootstrapInterfaceNames.first()

                    // Coerce -1 (never configured) to 0 for UI display
                    // The actual default of 3 is applied in toggleDiscovery() when enabling
                    val autoconnectCount = if (savedAutoconnect >= 0) savedAutoconnect else 0

                    // Push persisted value into the native protocol so the
                    // filter applies from the moment the service starts.
                    reticulumProtocol.setAutoconnectIfacOnly(ifacOnly)

                    _state.update {
                        it.copy(
                            discoverInterfacesEnabled = discoverEnabled,
                            autoconnectCount = autoconnectCount,
                            autoconnectIfacOnly = ifacOnly,
                            bootstrapInterfaceNames = bootstrapNames,
                        )
                    }
                    Log.d(
                        TAG,
                        "Loaded discovery settings: enabled=$discoverEnabled, autoconnect=$autoconnectCount (saved=$savedAutoconnect), ifacOnly=$ifacOnly, bootstrap=$bootstrapNames",
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load discovery settings", e)
                }
            }
        }

        /**
         * Toggle interface discovery on/off.
         * When enabled, preserves current autoconnect count (or defaults to 3 if 0).
         * When disabled, sets autoconnect count to 0.
         * Automatically restarts the Reticulum service to apply changes.
         */
        fun toggleDiscovery() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val currentEnabled = _state.value.discoverInterfacesEnabled
                    val newEnabled = !currentEnabled
                    // When enabling: restore user's saved preference from repository (or default to 3)
                    // savedCount of -1 means "never configured", 0+ means user explicitly chose that value
                    // When disabling: set to 0 for UI (but don't persist, to preserve preference)
                    val newAutoconnect =
                        if (newEnabled) {
                            val savedCount = settingsRepository.getAutoconnectDiscoveredCount()
                            if (savedCount >= 0) savedCount else 3
                        } else {
                            0
                        }

                    // Update UI immediately to show restarting state
                    _state.update {
                        it.copy(
                            discoverInterfacesEnabled = newEnabled,
                            autoconnectCount = newAutoconnect,
                            isRestarting = true,
                        )
                    }

                    // Save settings to DataStore
                    settingsRepository.saveDiscoverInterfacesEnabled(newEnabled)
                    // Only save autoconnect count when enabling (to preserve user's preference when disabling)
                    if (newEnabled) {
                        settingsRepository.saveAutoconnectDiscoveredCount(newAutoconnect)
                    }
                    Log.d(TAG, "Discovery settings saved: enabled=$newEnabled, autoconnect=$newAutoconnect")

                    // Apply discovery setting directly (no service restart needed on native stack)
                    Log.d(TAG, "Applying discovery setting: enabled=$newEnabled")
                    reticulumProtocol.setDiscoveryEnabled(newEnabled)
                    _state.update { it.copy(isRestarting = false) }
                    Log.d(TAG, "Discovery setting applied successfully")
                    loadDiscoveredInterfaces()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle discovery", e)
                    _state.update {
                        it.copy(
                            isRestarting = false,
                            errorMessage = "Failed to update discovery settings: ${e.message}",
                        )
                    }
                }
            }
        }

        /**
         * Set the number of discovered interfaces to auto-connect.
         * This allows users to configure how many interfaces RNS will automatically
         * connect to when discovery is enabled. Set to 0 to disable auto-connect
         * while keeping discovery active (useful for debugging).
         *
         * Automatically restarts the Reticulum service to apply changes.
         */
        fun setAutoconnectCount(count: Int) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val clampedCount = count.coerceIn(0, 10)

                    _state.update { it.copy(autoconnectCount = clampedCount) }

                    // Save settings to DataStore
                    settingsRepository.saveAutoconnectDiscoveredCount(clampedCount)
                    Log.d(TAG, "Autoconnect count saved: $clampedCount")

                    // Apply directly without restart (native stack supports hot update)
                    reticulumProtocol.setAutoconnectLimit(clampedCount)
                    loadDiscoveredInterfaces()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set autoconnect count", e)
                    _state.update {
                        it.copy(errorMessage = "Failed to update autoconnect count: ${e.message}")
                    }
                }
            }
        }

        /**
         * Toggle the "auto-connect to IFAC-protected interfaces only" setting.
         * Applies immediately — subsequent announces from non-IFAC interfaces
         * will be skipped by the autoconnect factory. Already-connected
         * non-IFAC interfaces are not affected retroactively.
         */
        fun toggleAutoconnectIfacOnly() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val newValue = !_state.value.autoconnectIfacOnly
                    _state.update { it.copy(autoconnectIfacOnly = newValue) }
                    settingsRepository.saveAutoconnectIfacOnly(newValue)
                    reticulumProtocol.setAutoconnectIfacOnly(newValue)
                    Log.d(TAG, "Autoconnect IFAC-only: $newValue")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle autoconnect IFAC-only", e)
                    _state.update {
                        it.copy(errorMessage = "Failed to update IFAC-only setting: ${e.message}")
                    }
                }
            }
        }

        /**
         * Set user's location for distance calculation.
         * Re-sorts the interface list if currently in PROXIMITY sort mode.
         */
        fun setUserLocation(
            latitude: Double,
            longitude: Double,
        ) {
            _state.update { currentState ->
                // Re-sort if in proximity mode with the new location
                val sortedInterfaces =
                    if (currentState.sortMode == DiscoveredInterfacesSortMode.PROXIMITY) {
                        sortInterfaces(currentState.interfaces, currentState.sortMode, latitude, longitude)
                    } else {
                        currentState.interfaces
                    }
                currentState.copy(
                    userLatitude = latitude,
                    userLongitude = longitude,
                    interfaces = sortedInterfaces,
                )
            }
        }

        /**
         * Clear error message.
         */
        fun clearError() {
            _state.update { it.copy(errorMessage = null) }
        }

        /**
         * Update the free-form search query. Matches against interface name,
         * reachableOn (host/IP), and raw type string, case-insensitive.
         */
        fun setSearchQuery(query: String) {
            _state.update { currentState ->
                val newInterfaces =
                    applySearchAndFilter(
                        source = currentState.originalInterfacesForFiltering(),
                        searchQuery = query,
                        typeFilters = currentState.typeFilters,
                        ifacOnly = currentState.ifacOnly,
                        sortMode = currentState.sortMode,
                        userLat = currentState.userLatitude,
                        userLon = currentState.userLongitude,
                    )
                currentState.copy(searchQuery = query, interfaces = newInterfaces)
            }
        }

        /**
         * Toggle a type filter chip. Empty filter set means no type filtering.
         */
        fun toggleTypeFilter(filter: DiscoveredInterfaceTypeFilter) {
            _state.update { currentState ->
                val newFilters =
                    if (filter in currentState.typeFilters) {
                        currentState.typeFilters - filter
                    } else {
                        currentState.typeFilters + filter
                    }
                val newInterfaces =
                    applySearchAndFilter(
                        source = currentState.originalInterfacesForFiltering(),
                        searchQuery = currentState.searchQuery,
                        typeFilters = newFilters,
                        ifacOnly = currentState.ifacOnly,
                        sortMode = currentState.sortMode,
                        userLat = currentState.userLatitude,
                        userLon = currentState.userLongitude,
                    )
                currentState.copy(typeFilters = newFilters, interfaces = newInterfaces)
            }
        }

        /**
         * Toggle the IFAC-only filter. When enabled, only interfaces that
         * announced an IFAC network name are shown.
         */
        fun toggleIfacOnlyFilter() {
            _state.update { currentState ->
                val newValue = !currentState.ifacOnly
                val newInterfaces =
                    applySearchAndFilter(
                        source = currentState.originalInterfacesForFiltering(),
                        searchQuery = currentState.searchQuery,
                        typeFilters = currentState.typeFilters,
                        ifacOnly = newValue,
                        sortMode = currentState.sortMode,
                        userLat = currentState.userLatitude,
                        userLon = currentState.userLongitude,
                    )
                currentState.copy(ifacOnly = newValue, interfaces = newInterfaces)
            }
        }

        /** Clear search query and all filters (type + IFAC). */
        fun clearFilters() {
            _state.update { currentState ->
                val newInterfaces =
                    applySearchAndFilter(
                        source = currentState.originalInterfacesForFiltering(),
                        searchQuery = "",
                        typeFilters = emptySet(),
                        ifacOnly = false,
                        sortMode = currentState.sortMode,
                        userLat = currentState.userLatitude,
                        userLon = currentState.userLongitude,
                    )
                currentState.copy(
                    searchQuery = "",
                    typeFilters = emptySet(),
                    ifacOnly = false,
                    interfaces = newInterfaces,
                )
            }
        }

        private fun DiscoveredInterfacesState.originalInterfacesForFiltering(): List<DiscoveredInterface> = originalInterfaces.ifEmpty { interfaces }

        private fun applySearchAndFilter(
            source: List<DiscoveredInterface>,
            searchQuery: String,
            typeFilters: Set<DiscoveredInterfaceTypeFilter>,
            ifacOnly: Boolean,
            sortMode: DiscoveredInterfacesSortMode,
            userLat: Double?,
            userLon: Double?,
        ): List<DiscoveredInterface> {
            val filtered =
                source
                    .asSequence()
                    .filter { iface ->
                        typeFilters.isEmpty() || DiscoveredInterfaceTypeFilter.classify(iface) in typeFilters
                    }.filter { iface ->
                        !ifacOnly || !iface.ifacNetname.isNullOrBlank()
                    }.filter { iface ->
                        if (searchQuery.isBlank()) return@filter true
                        val q = searchQuery.trim()
                        iface.name.contains(q, ignoreCase = true) ||
                            iface.type.contains(q, ignoreCase = true) ||
                            (iface.reachableOn?.contains(q, ignoreCase = true) ?: false) ||
                            (iface.ifacNetname?.contains(q, ignoreCase = true) ?: false)
                    }.toList()
            return sortInterfaces(filtered, sortMode, userLat, userLon)
        }

        /**
         * Set the sort mode and re-sort the interfaces list.
         * PROXIMITY mode requires user location; if unavailable, the request is ignored.
         */
        fun setSortMode(mode: DiscoveredInterfacesSortMode) {
            _state.update { currentState ->
                // Guard: can't switch to PROXIMITY without user location
                if (mode == DiscoveredInterfacesSortMode.PROXIMITY &&
                    (currentState.userLatitude == null || currentState.userLongitude == null)
                ) {
                    return@update currentState
                }
                val sortedInterfaces =
                    applySearchAndFilter(
                        source = currentState.originalInterfacesForFiltering(),
                        searchQuery = currentState.searchQuery,
                        typeFilters = currentState.typeFilters,
                        ifacOnly = currentState.ifacOnly,
                        sortMode = mode,
                        userLat = currentState.userLatitude,
                        userLon = currentState.userLongitude,
                    )
                currentState.copy(
                    sortMode = mode,
                    interfaces = sortedInterfaces,
                )
            }
        }

        /**
         * Sort interfaces based on the selected sort mode.
         *
         * - AVAILABILITY_AND_QUALITY: Returns list as-is (already sorted by Python)
         * - PROXIMITY: Sorts by distance (nearest first), interfaces without location at end
         */
        private fun sortInterfaces(
            interfaces: List<DiscoveredInterface>,
            sortMode: DiscoveredInterfacesSortMode,
            userLat: Double?,
            userLon: Double?,
        ): List<DiscoveredInterface> {
            return when (sortMode) {
                DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY -> {
                    // Already sorted by Python (status_code desc, stamp_value desc)
                    interfaces
                }
                DiscoveredInterfacesSortMode.PROXIMITY -> {
                    // If no user location, can't sort by proximity - return as-is
                    if (userLat == null || userLon == null) {
                        return interfaces
                    }

                    // Partition: interfaces with location vs without
                    val (withLocation, withoutLocation) =
                        interfaces.partition {
                            it.latitude != null && it.longitude != null
                        }

                    // Sort those with location by distance (nearest first)
                    val sortedWithLocation =
                        withLocation.sortedBy { iface ->
                            haversineDistance(userLat, userLon, iface.latitude!!, iface.longitude!!)
                        }

                    // Combine: distance-sorted first, then non-located (in original order)
                    sortedWithLocation + withoutLocation
                }
            }
        }

        /**
         * Calculate distance between user and discovered interface in kilometers.
         * Returns null if user location or interface location is not available.
         */
        fun calculateDistance(iface: DiscoveredInterface): Double? {
            val state = _state.value
            val userLat = state.userLatitude ?: return null
            val userLon = state.userLongitude ?: return null

            // Use let to combine interface location checks
            return iface.latitude?.let { lat ->
                iface.longitude?.let { lon ->
                    haversineDistance(userLat, userLon, lat, lon)
                }
            }
        }

        /**
         * Check if a discovered interface is currently auto-connected.
         * Matches by comparing the interface's reachable_on:port with auto-connected endpoints.
         */
        fun isAutoconnected(iface: DiscoveredInterface): Boolean {
            val endpoints = _state.value.autoconnectedEndpoints
            val host = iface.reachableOn
            val port = iface.port

            // TCP-based interfaces have reachable_on and port; check if endpoint is in set
            return endpoints.isNotEmpty() &&
                host != null &&
                port != null &&
                endpoints.contains("$host:$port")
        }

        /**
         * Calculate distance between two coordinates using Haversine formula.
         * Returns distance in kilometers.
         */
        private fun haversineDistance(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val earthRadiusKm = 6371.0

            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)

            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

            return earthRadiusKm * c
        }
    }
