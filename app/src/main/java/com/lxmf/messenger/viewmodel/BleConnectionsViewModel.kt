package com.lxmf.messenger.viewmodel

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.BleConnectionInfo
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.data.repository.BleStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for BLE connections screen.
 */
sealed class BleConnectionsUiState {
    object Loading : BleConnectionsUiState()

    data class Success(
        val connections: List<BleConnectionInfo>,
        val totalConnections: Int,
        val centralConnections: Int,
        val peripheralConnections: Int,
    ) : BleConnectionsUiState()

    data class Error(val message: String) : BleConnectionsUiState()

    object PermissionsRequired : BleConnectionsUiState()

    object BluetoothDisabled : BleConnectionsUiState()
}

/**
 * ViewModel for BLE connection status display.
 * Provides real-time updates of connected BLE peers.
 */
@HiltViewModel
class BleConnectionsViewModel
    @Inject
    constructor(
        private val bleStatusRepository: BleStatusRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "BleConnectionsViewModel"
        }

        private val _uiState = MutableStateFlow<BleConnectionsUiState>(BleConnectionsUiState.Loading)
        val uiState: StateFlow<BleConnectionsUiState> = _uiState.asStateFlow()

        init {
            Log.d(TAG, "BleConnectionsViewModel initialized")
            startObservingConnections()
        }

        /**
         * Start observing BLE connections and adapter state.
         * Updates UI state immediately when Bluetooth is disabled/enabled.
         */
        private fun startObservingConnections() {
            Log.d(TAG, "Starting BLE connections observation")
            viewModelScope.launch {
                bleStatusRepository.getConnectedPeersFlow()
                    .catch { e ->
                        Log.e(TAG, "Error observing connections", e)
                        _uiState.value =
                            BleConnectionsUiState.Error(
                                e.message ?: "Unknown error",
                            )
                    }
                    .collect { state ->
                        Log.d(TAG, "Received state from flow: ${state.javaClass.simpleName}")
                        when (state) {
                            is BleConnectionsState.BluetoothDisabled -> {
                                Log.d(TAG, "Bluetooth disabled - updating UI")
                                _uiState.value = BleConnectionsUiState.BluetoothDisabled
                            }
                            is BleConnectionsState.Loading -> {
                                Log.d(TAG, "Loading - updating UI")
                                _uiState.value = BleConnectionsUiState.Loading
                            }
                            is BleConnectionsState.Success -> {
                                Log.d(TAG, "Received ${state.connections.size} connections")
                                updateUiState(state.connections)
                            }
                            is BleConnectionsState.Error -> {
                                Log.e(TAG, "Error state: ${state.message}")
                                _uiState.value = BleConnectionsUiState.Error(state.message)
                            }
                        }
                    }
            }
        }

        /**
         * Update UI state from connection list.
         */
        private fun updateUiState(connections: List<BleConnectionInfo>) {
            val centralCount =
                connections.count {
                    it.connectionType == ConnectionType.CENTRAL ||
                        it.connectionType == ConnectionType.BOTH
                }
            val peripheralCount =
                connections.count {
                    it.connectionType == ConnectionType.PERIPHERAL ||
                        it.connectionType == ConnectionType.BOTH
                }

            Log.d(TAG, "Updating UI state: ${connections.size} total, $centralCount central, $peripheralCount peripheral")

            _uiState.value =
                BleConnectionsUiState.Success(
                    // Sort by signal strength
                    connections = connections.sortedByDescending { it.rssi },
                    totalConnections = connections.size,
                    centralConnections = centralCount,
                    peripheralConnections = peripheralCount,
                )
        }

        /**
         * Manually refresh connection status.
         */
        fun refresh() {
            viewModelScope.launch {
                try {
                    val connections = bleStatusRepository.getConnectedPeers()
                    updateUiState(connections)
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing connections", e)
                    _uiState.value =
                        BleConnectionsUiState.Error(
                            e.message ?: "Failed to refresh",
                        )
                }
            }
        }

        /**
         * Disconnect from a specific peer.
         */
        fun disconnectPeer(address: String) {
            viewModelScope.launch {
                try {
                    bleStatusRepository.disconnectPeer(address)
                    // Refresh will happen automatically via flow
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting peer $address", e)
                }
            }
        }

        /**
         * Get an Intent to request enabling Bluetooth.
         * For Android 12+, this is the recommended way to enable Bluetooth.
         *
         * @return Intent to enable Bluetooth, or null if not available
         */
        fun getEnableBluetoothIntent(): Intent? {
            return try {
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create enable Bluetooth intent", e)
                null
            }
        }

        /**
         * Get an Intent to open Bluetooth settings.
         * This allows the user to disable Bluetooth manually.
         *
         * @return Intent to open Bluetooth settings
         */
        fun getBluetoothSettingsIntent(): Intent {
            return Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        override fun onCleared() {
            super.onCleared()
            Log.d(TAG, "BleConnectionsViewModel cleared")
        }
    }
