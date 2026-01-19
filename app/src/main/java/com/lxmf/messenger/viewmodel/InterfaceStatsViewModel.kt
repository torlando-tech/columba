package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * State for the interface stats screen.
 */
@androidx.compose.runtime.Immutable
data class InterfaceStatsState(
    val interfaceEntity: InterfaceEntity? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    // Runtime status
    val isOnline: Boolean = false,
    val isConnecting: Boolean = false,
    // RNode-specific stats (from live connection)
    val rssi: Int? = null,
    val snr: Float? = null,
    // Traffic stats (from Python/RNS)
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    // Parsed config fields for display
    val connectionMode: String? = null,
    val targetDeviceName: String? = null,
    val tcpHost: String? = null,
    val tcpPort: Int? = null,
    val usbDeviceId: Int? = null,
    val frequency: Long? = null,
    val bandwidth: Int? = null,
    val spreadingFactor: Int? = null,
    val txPower: Int? = null,
    val codingRate: Int? = null,
    val interfaceMode: String? = null,
)

/**
 * ViewModel for displaying interface statistics and status.
 */
@HiltViewModel
class InterfaceStatsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val interfaceRepository: InterfaceRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val configManager: InterfaceConfigManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "InterfaceStatsVM"
            private const val STATS_REFRESH_INTERVAL_MS = 1000L // Poll faster for responsive UI
            private const val CONNECTING_TIMEOUT_MS = 15000L // Stop showing "connecting" after 15s
        }

        private val interfaceId: Long = savedStateHandle["interfaceId"] ?: -1L

        private val _state = MutableStateFlow(InterfaceStatsState())
        val state: StateFlow<InterfaceStatsState> = _state.asStateFlow()

        // Track when we started showing "connecting" state
        private var connectingStartTime: Long = 0L
        private var hasEverBeenOnline: Boolean = false

        init {
            if (interfaceId >= 0) {
                loadInterface()
                startStatsPolling()
            } else {
                _state.update { it.copy(isLoading = false, errorMessage = "Invalid interface ID") }
            }
        }

        private fun loadInterface() {
            viewModelScope.launch {
                try {
                    val entity = interfaceRepository.getInterfaceByIdOnce(interfaceId)
                    if (entity != null) {
                        val parsedConfig = parseConfigJson(entity.configJson, entity.type)
                        _state.update {
                            it.copy(
                                interfaceEntity = entity,
                                isLoading = false,
                                connectionMode = parsedConfig.connectionMode,
                                targetDeviceName = parsedConfig.targetDeviceName,
                                tcpHost = parsedConfig.tcpHost,
                                tcpPort = parsedConfig.tcpPort,
                                usbDeviceId = parsedConfig.usbDeviceId,
                                frequency = parsedConfig.frequency,
                                bandwidth = parsedConfig.bandwidth,
                                spreadingFactor = parsedConfig.spreadingFactor,
                                txPower = parsedConfig.txPower,
                                codingRate = parsedConfig.codingRate,
                                interfaceMode = parsedConfig.interfaceMode,
                            )
                        }
                        Log.d(TAG, "Loaded interface: ${entity.name}")
                    } else {
                        _state.update {
                            it.copy(isLoading = false, errorMessage = "Interface not found")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading interface", e)
                    _state.update {
                        it.copy(isLoading = false, errorMessage = "Error loading interface: ${e.message}")
                    }
                }
            }
        }

        private fun startStatsPolling() {
            viewModelScope.launch {
                while (isActive) {
                    refreshStats()
                    delay(STATS_REFRESH_INTERVAL_MS)
                }
            }
        }

        private suspend fun refreshStats() {
            val entity = _state.value.interfaceEntity ?: return

            try {
                // Get interface online status from the protocol
                val interfaceStats = reticulumProtocol.getInterfaceStats(entity.name)
                val isOnline = interfaceStats?.get("online") as? Boolean ?: false
                val rxBytes = (interfaceStats?.get("rxb") as? Number)?.toLong() ?: 0L
                val txBytes = (interfaceStats?.get("txb") as? Number)?.toLong() ?: 0L

                // Track if interface has ever been online during this session
                if (isOnline) {
                    hasEverBeenOnline = true
                }

                // Determine connecting state:
                // Show "connecting" only when:
                // 1. Interface is enabled and offline
                // 2. Interface hasn't been online yet during this session (so we're waiting for initial connection)
                // 3. We haven't exceeded the timeout (avoid showing spinner forever)
                val isConnecting = if (entity.enabled && !isOnline && !hasEverBeenOnline) {
                    val now = System.currentTimeMillis()
                    if (connectingStartTime == 0L) {
                        connectingStartTime = now
                    }
                    (now - connectingStartTime) < CONNECTING_TIMEOUT_MS
                } else {
                    false
                }

                // For RNode interfaces, also get RSSI if available
                var rssi: Int? = null
                if (entity.type == "RNode" && isOnline) {
                    val rnodeRssi = configManager.getRNodeRssi()
                    if (rnodeRssi > -100) {
                        rssi = rnodeRssi
                    }
                }

                _state.update {
                    it.copy(
                        isOnline = isOnline,
                        isConnecting = isConnecting,
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        rssi = rssi,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing stats", e)
            }
        }

        /**
         * Toggle the enabled state of the interface.
         */
        fun toggleEnabled() {
            val entity = _state.value.interfaceEntity ?: return
            viewModelScope.launch {
                try {
                    val newEnabled = !entity.enabled
                    interfaceRepository.toggleInterfaceEnabled(entity.id, newEnabled)
                    _state.update {
                        it.copy(interfaceEntity = entity.copy(enabled = newEnabled))
                    }
                    Log.d(TAG, "Toggled interface ${entity.name} enabled: $newEnabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling interface", e)
                }
            }
        }

        /**
         * Parsed configuration fields from configJson.
         */
        private data class ParsedConfig(
            val connectionMode: String? = null,
            val targetDeviceName: String? = null,
            val tcpHost: String? = null,
            val tcpPort: Int? = null,
            val usbDeviceId: Int? = null,
            val frequency: Long? = null,
            val bandwidth: Int? = null,
            val spreadingFactor: Int? = null,
            val txPower: Int? = null,
            val codingRate: Int? = null,
            val interfaceMode: String? = null,
        )

        @Suppress("SwallowedException")
        private fun parseConfigJson(configJson: String, type: String): ParsedConfig {
            return try {
                val json = JSONObject(configJson)
                when (type) {
                    "RNode" -> ParsedConfig(
                        connectionMode = json.optString("connection_mode", "classic"),
                        targetDeviceName = json.optString("target_device_name", "").ifEmpty { null },
                        tcpHost = json.optString("tcp_host", "").ifEmpty { null },
                        tcpPort = if (json.has("tcp_port")) json.getInt("tcp_port") else null,
                        usbDeviceId = if (json.has("usb_device_id")) json.getInt("usb_device_id") else null,
                        frequency = json.optLong("frequency", 0).takeIf { it > 0 },
                        bandwidth = json.optInt("bandwidth", 0).takeIf { it > 0 },
                        spreadingFactor = json.optInt("spreading_factor", 0).takeIf { it > 0 },
                        txPower = json.optInt("tx_power", 0).takeIf { it > 0 },
                        codingRate = json.optInt("coding_rate", 0).takeIf { it > 0 },
                        interfaceMode = json.optString("mode", "full"),
                    )
                    "TCPClient" -> ParsedConfig(
                        tcpHost = json.optString("target_host", ""),
                        tcpPort = json.optInt("target_port", 4242),
                        interfaceMode = json.optString("mode", "full"),
                    )
                    "TCPServer" -> ParsedConfig(
                        tcpHost = json.optString("listen_ip", "0.0.0.0"),
                        tcpPort = json.optInt("listen_port", 4242),
                        interfaceMode = json.optString("mode", "full"),
                    )
                    else -> ParsedConfig(
                        interfaceMode = json.optString("mode", "full"),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing config JSON", e)
                ParsedConfig()
            }
        }
    }
