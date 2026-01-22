package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.DiscoveredInterface
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
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
 * State for the discovered interfaces screen.
 */
@androidx.compose.runtime.Immutable
data class DiscoveredInterfacesState(
    val interfaces: List<DiscoveredInterface> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val availableCount: Int = 0,
    val unknownCount: Int = 0,
    val staleCount: Int = 0,
    // User's location for distance calculation (nullable)
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    // Discovery settings (from DataStore - user preference)
    val discoverInterfacesEnabled: Boolean = false,
    val autoconnectCount: Int = 0,
    // Runtime status (from RNS - current state)
    val isDiscoveryEnabled: Boolean = false,
    // Bootstrap interfaces that enable discovery
    val bootstrapInterfaceNames: List<String> = emptyList(),
    // Show mock data for UI testing
    val showMockData: Boolean = false,
    // Service is currently restarting
    val isRestarting: Boolean = false,
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
        private val interfaceConfigManager: InterfaceConfigManager,
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
                    val availableCount = discovered.count { it.status == "available" }
                    val unknownCount = discovered.count { it.status == "unknown" }
                    val staleCount = discovered.count { it.status == "stale" }

                    // Use mock data if enabled and no real interfaces
                    val finalInterfaces = if (_state.value.showMockData && discovered.isEmpty()) {
                        createMockInterfaces()
                    } else {
                        discovered
                    }
                    val finalAvailable = if (_state.value.showMockData && discovered.isEmpty()) {
                        finalInterfaces.count { it.status == "available" }
                    } else {
                        availableCount
                    }
                    val finalUnknown = if (_state.value.showMockData && discovered.isEmpty()) {
                        finalInterfaces.count { it.status == "unknown" }
                    } else {
                        unknownCount
                    }
                    val finalStale = if (_state.value.showMockData && discovered.isEmpty()) {
                        finalInterfaces.count { it.status == "stale" }
                    } else {
                        staleCount
                    }

                    _state.update {
                        it.copy(
                            interfaces = finalInterfaces,
                            isLoading = false,
                            availableCount = finalAvailable,
                            unknownCount = finalUnknown,
                            staleCount = finalStale,
                            isDiscoveryEnabled = isEnabled,
                        )
                    }
                    Log.d(TAG, "Loaded ${finalInterfaces.size} discovered interfaces (mock=${_state.value.showMockData})")
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
                    val autoconnectCount = settingsRepository.getAutoconnectDiscoveredCount()
                    val bootstrapNames = interfaceRepository.bootstrapInterfaceNames.first()

                    _state.update {
                        it.copy(
                            discoverInterfacesEnabled = discoverEnabled,
                            autoconnectCount = autoconnectCount,
                            bootstrapInterfaceNames = bootstrapNames,
                        )
                    }
                    Log.d(TAG, "Loaded discovery settings: enabled=$discoverEnabled, autoconnect=$autoconnectCount, bootstrap=$bootstrapNames")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load discovery settings", e)
                }
            }
        }

        /**
         * Toggle interface discovery on/off.
         * When enabled, sets autoconnect count to 3 (reasonable default).
         * When disabled, sets autoconnect count to 0.
         * Automatically restarts the Reticulum service to apply changes.
         */
        fun toggleDiscovery() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val currentEnabled = _state.value.discoverInterfacesEnabled
                    val newEnabled = !currentEnabled
                    val newAutoconnect = if (newEnabled) 3 else 0

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
                    settingsRepository.saveAutoconnectDiscoveredCount(newAutoconnect)
                    Log.d(TAG, "Discovery settings saved: enabled=$newEnabled, autoconnect=$newAutoconnect")

                    // Restart the Reticulum service to apply changes
                    Log.d(TAG, "Restarting Reticulum service to apply discovery settings...")
                    interfaceConfigManager.applyInterfaceChanges()
                        .onSuccess {
                            Log.d(TAG, "Reticulum service restarted successfully")
                            // Reload discovered interfaces after restart
                            loadDiscoveredInterfaces()
                            _state.update { it.copy(isRestarting = false) }
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Failed to restart Reticulum service", error)
                            _state.update {
                                it.copy(
                                    isRestarting = false,
                                    errorMessage = "Failed to restart service: ${error.message}",
                                )
                            }
                        }
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
         * Toggle mock data display for UI testing.
         */
        fun toggleMockData() {
            _state.update { it.copy(showMockData = !it.showMockData) }
            loadDiscoveredInterfaces()
        }

        /**
         * Create mock interfaces for UI testing.
         */
        private fun createMockInterfaces(): List<DiscoveredInterface> {
            val now = System.currentTimeMillis() / 1000
            return listOf(
                DiscoveredInterface(
                    name = "Beleth TCP Server",
                    type = "TCPServerInterface",
                    transportId = "abc123def456789012345678901234567890",
                    networkId = "net123456789",
                    status = "available",
                    statusCode = 1000,
                    lastHeard = now - 120,  // 2 minutes ago
                    heardCount = 15,
                    hops = 3,
                    stampValue = 14,
                    reachableOn = "rns.beleth.net",
                    port = 4242,
                    frequency = null,
                    bandwidth = null,
                    spreadingFactor = null,
                    codingRate = null,
                    modulation = null,
                    channel = null,
                    latitude = null,
                    longitude = null,
                    height = null,
                ),
                DiscoveredInterface(
                    name = "Mobile RNode",
                    type = "RNodeInterface",
                    transportId = "def456abc789012345678901234567890123",
                    networkId = "net987654321",
                    status = "available",
                    statusCode = 1000,
                    lastHeard = now - 300,  // 5 minutes ago
                    heardCount = 8,
                    hops = 1,
                    stampValue = 14,
                    reachableOn = null,
                    port = null,
                    frequency = 915000000,
                    bandwidth = 125000,
                    spreadingFactor = 8,
                    codingRate = 5,
                    modulation = "LoRa",
                    channel = null,
                    latitude = 37.7749,
                    longitude = -122.4194,
                    height = 50.0,
                ),
                DiscoveredInterface(
                    name = "Community Node",
                    type = "TCPServerInterface",
                    transportId = "789012345678901234567890123456789012",
                    networkId = "netabc123",
                    status = "unknown",
                    statusCode = 100,
                    lastHeard = now - 900,  // 15 minutes ago
                    heardCount = 3,
                    hops = 5,
                    stampValue = 12,
                    reachableOn = "tcp.example.com",
                    port = 4242,
                    frequency = null,
                    bandwidth = null,
                    spreadingFactor = null,
                    codingRate = null,
                    modulation = null,
                    channel = null,
                    latitude = null,
                    longitude = null,
                    height = null,
                ),
                DiscoveredInterface(
                    name = "Old Gateway",
                    type = "TCPServerInterface",
                    transportId = "stale12345678901234567890123456789012",
                    networkId = "netstale",
                    status = "stale",
                    statusCode = 0,
                    lastHeard = now - 7200,  // 2 hours ago
                    heardCount = 1,
                    hops = 8,
                    stampValue = 10,
                    reachableOn = "192.168.1.100",
                    port = 4965,
                    frequency = null,
                    bandwidth = null,
                    spreadingFactor = null,
                    codingRate = null,
                    modulation = null,
                    channel = null,
                    latitude = null,
                    longitude = null,
                    height = null,
                ),
            )
        }

        /**
         * Set user's location for distance calculation.
         */
        fun setUserLocation(latitude: Double, longitude: Double) {
            _state.update {
                it.copy(userLatitude = latitude, userLongitude = longitude)
            }
        }

        /**
         * Clear error message.
         */
        fun clearError() {
            _state.update { it.copy(errorMessage = null) }
        }

        /**
         * Calculate distance between user and discovered interface in kilometers.
         * Returns null if user location or interface location is not available.
         */
        fun calculateDistance(iface: DiscoveredInterface): Double? {
            val state = _state.value
            val userLat = state.userLatitude ?: return null
            val userLon = state.userLongitude ?: return null
            val ifaceLat = iface.latitude ?: return null
            val ifaceLon = iface.longitude ?: return null

            return haversineDistance(userLat, userLon, ifaceLat, ifaceLon)
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
