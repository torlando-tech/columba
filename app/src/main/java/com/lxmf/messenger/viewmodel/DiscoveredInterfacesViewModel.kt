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
    // Service is currently restarting
    val isRestarting: Boolean = false,
    // Currently auto-connected interface endpoints (e.g., "host:port")
    val autoconnectedEndpoints: Set<String> = emptySet(),
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
                    val autoconnectedEndpoints = reticulumProtocol.getAutoconnectedEndpoints()
                    val availableCount = discovered.count { it.status == "available" }
                    val unknownCount = discovered.count { it.status == "unknown" }
                    val staleCount = discovered.count { it.status == "stale" }

                    _state.update {
                        it.copy(
                            interfaces = discovered,
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
            return endpoints.isNotEmpty() && host != null && port != null &&
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
