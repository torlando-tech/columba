package com.lxmf.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.reticulum.model.DestinationType
import com.lxmf.messenger.reticulum.model.Direction
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.LogLevel
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main ViewModel for the Columba application.
 * Demonstrates integration with the Reticulum abstraction layer.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
        val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

        private var currentIdentity: Identity? = null

        fun initializeReticulum() {
            viewModelScope.launch {
                _uiState.value = UiState.Loading("Initializing Reticulum...")

                val config =
                    ReticulumConfig(
                        storagePath = "/tmp/columba",
                        enabledInterfaces = listOf(InterfaceConfig.AutoInterface()),
                        logLevel = LogLevel.INFO,
                    )

                reticulumProtocol.initialize(config)
                    .onSuccess {
                        _networkStatus.value = reticulumProtocol.networkStatus.value
                        _uiState.value = UiState.Success("Reticulum initialized successfully")
                    }
                    .onFailure { error ->
                        _uiState.value = UiState.Error("Failed to initialize: ${error.message}")
                    }
            }
        }

        fun createIdentity() {
            viewModelScope.launch {
                _uiState.value = UiState.Loading("Creating identity...")

                reticulumProtocol.createIdentity()
                    .onSuccess { identity ->
                        currentIdentity = identity
                        val hexHash = identity.hash.joinToString("") { "%02x".format(it) }
                        _uiState.value = UiState.Success("Identity created!\nHash: $hexHash")
                    }
                    .onFailure { error ->
                        _uiState.value = UiState.Error("Failed to create identity: ${error.message}")
                    }
            }
        }

        fun testSendPacket() {
            viewModelScope.launch {
                if (currentIdentity == null) {
                    _uiState.value = UiState.Error("Please create an identity first")
                    return@launch
                }

                _uiState.value = UiState.Loading("Creating destination and sending packet...")

                // Create a test destination
                reticulumProtocol.createDestination(
                    identity = currentIdentity!!,
                    direction = Direction.OUT,
                    type = DestinationType.SINGLE,
                    appName = "columba.test",
                    aspects = listOf("test"),
                ).onSuccess { destination ->
                    // Send a test packet
                    reticulumProtocol.sendPacket(
                        destination = destination,
                        data = "Hello, Reticulum!".toByteArray(),
                    ).onSuccess { receipt ->
                        _uiState.value =
                            UiState.Success(
                                "Packet sent!\nDelivered: ${receipt.delivered}\n" +
                                    "Receipt hash: ${receipt.hash.take(8).joinToString("") { "%02x".format(it) }}...",
                            )
                    }.onFailure { error ->
                        _uiState.value = UiState.Error("Failed to send packet: ${error.message}")
                    }
                }.onFailure { error ->
                    _uiState.value = UiState.Error("Failed to create destination: ${error.message}")
                }
            }
        }

        fun getNetworkStatusColor(): Long {
            return when (networkStatus.value) {
                is NetworkStatus.READY -> 0xFF4CAF50 // Green
                is NetworkStatus.INITIALIZING -> 0xFFFFC107 // Amber
                is NetworkStatus.CONNECTING -> 0xFF2196F3 // Blue
                is NetworkStatus.ERROR -> 0xFFF44336 // Red
                NetworkStatus.SHUTDOWN -> 0xFF9E9E9E // Gray
            }
        }
    }

sealed class UiState {
    object Initial : UiState()

    data class Loading(val message: String) : UiState()

    data class Success(val message: String) : UiState()

    data class Error(val message: String) : UiState()
}
