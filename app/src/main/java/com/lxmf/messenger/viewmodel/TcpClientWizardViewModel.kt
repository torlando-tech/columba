package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.TcpCommunityServer
import com.lxmf.messenger.data.model.TcpCommunityServers
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wizard step enumeration for TCP Client setup.
 */
enum class TcpClientWizardStep {
    SERVER_SELECTION,
    REVIEW_CONFIGURE,
}

/**
 * State for the TCP Client setup wizard.
 */
@androidx.compose.runtime.Immutable
data class TcpClientWizardState(
    // Wizard navigation
    val currentStep: TcpClientWizardStep = TcpClientWizardStep.SERVER_SELECTION,
    // Edit mode - when non-null, we're editing an existing interface
    val editingInterfaceId: Long? = null,
    // Server selection
    val selectedServer: TcpCommunityServer? = null,
    val isCustomMode: Boolean = false,
    // Configuration fields
    val interfaceName: String = "",
    val targetHost: String = "",
    val targetPort: String = "",
    // RNS 1.1.x Bootstrap Interface option
    val bootstrapOnly: Boolean = false,
    // SOCKS5 proxy (Tor/Orbot) settings
    val socksProxyEnabled: Boolean = false,
    val socksProxyHost: String = "127.0.0.1",
    val socksProxyPort: String = "9050",
    // Save state
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
)

/**
 * ViewModel for the TCP Client interface setup wizard.
 */
@Suppress("TooManyFunctions") // Wizard ViewModel exposes one method per UI control
@HiltViewModel
class TcpClientWizardViewModel
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
        private val configManager: InterfaceConfigManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "TcpClientWizard"
        }

        private val _state = MutableStateFlow(TcpClientWizardState())
        val state: StateFlow<TcpClientWizardState> = _state.asStateFlow()

        /**
         * Load an existing interface for editing.
         * Tries to match against community servers to pre-select one.
         */
        fun loadExistingInterface(interfaceId: Long) {
            viewModelScope.launch {
                try {
                    val entity = interfaceRepository.getInterfaceByIdOnce(interfaceId) ?: return@launch
                    val config = interfaceRepository.entityToConfig(entity)

                    if (config !is InterfaceConfig.TCPClient) {
                        Log.e(TAG, "Interface $interfaceId is not a TCPClient")
                        return@launch
                    }

                    // Try to find a matching community server
                    val matchingServer =
                        TcpCommunityServers.servers.find { server ->
                            server.host == config.targetHost && server.port == config.targetPort
                        }

                    _state.update {
                        it.copy(
                            editingInterfaceId = interfaceId,
                            selectedServer = matchingServer,
                            isCustomMode = matchingServer == null,
                            interfaceName = config.name,
                            targetHost = config.targetHost,
                            targetPort = config.targetPort.toString(),
                            bootstrapOnly = config.bootstrapOnly,
                            socksProxyEnabled = config.socksProxyEnabled,
                            socksProxyHost = config.socksProxyHost,
                            socksProxyPort = config.socksProxyPort.toString(),
                        )
                    }

                    Log.d(TAG, "Loaded interface for editing: ${config.name}, matched server: ${matchingServer?.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load interface $interfaceId", e)
                }
            }
        }

        /**
         * Set initial values when creating from a discovered interface.
         */
        fun setInitialValues(
            host: String,
            port: Int,
            name: String,
        ) {
            // Check if this matches a community server
            val matchingServer =
                TcpCommunityServers.servers.find { server ->
                    server.host == host && server.port == port
                }

            val isOnion = host.endsWith(".onion")
            _state.update {
                it.copy(
                    selectedServer = matchingServer,
                    isCustomMode = matchingServer == null,
                    interfaceName = name,
                    targetHost = host,
                    targetPort = port.toString(),
                    bootstrapOnly = matchingServer?.isBootstrap ?: false,
                    // Auto-enable SOCKS proxy for .onion addresses
                    socksProxyEnabled = isOnion,
                    // Skip to review step since we have all the info
                    currentStep = TcpClientWizardStep.REVIEW_CONFIGURE,
                )
            }
            Log.d(TAG, "Set initial values from discovered: $name @ $host:$port, matched server: ${matchingServer?.name}")
        }

        /**
         * Get the list of community servers.
         */
        fun getCommunityServers(): List<TcpCommunityServer> = TcpCommunityServers.servers

        /**
         * Select a community server.
         */
        fun selectServer(server: TcpCommunityServer) {
            val isOnion = server.host.endsWith(".onion")
            _state.update {
                it.copy(
                    selectedServer = server,
                    isCustomMode = false,
                    interfaceName = server.name,
                    targetHost = server.host,
                    targetPort = server.port.toString(),
                    bootstrapOnly = server.isBootstrap,
                    // Auto-enable SOCKS proxy for .onion servers
                    socksProxyEnabled = isOnion,
                )
            }
        }

        /**
         * Enable custom mode (user enters server details manually).
         */
        fun enableCustomMode() {
            _state.update {
                it.copy(
                    selectedServer = null,
                    isCustomMode = true,
                    interfaceName = "",
                    targetHost = "",
                    targetPort = "",
                    bootstrapOnly = false,
                    socksProxyEnabled = false,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = "9050",
                )
            }
        }

        /**
         * Toggle the bootstrap-only flag.
         * Bootstrap interfaces auto-detach once sufficient discovered interfaces are connected.
         */
        fun toggleBootstrapOnly(enabled: Boolean) {
            _state.update { it.copy(bootstrapOnly = enabled) }
        }

        /**
         * Update interface name field.
         */
        fun updateInterfaceName(value: String) {
            _state.update { it.copy(interfaceName = value) }
        }

        /**
         * Update target host field.
         * Auto-enables SOCKS proxy when a .onion address is entered.
         */
        fun updateTargetHost(value: String) {
            val isOnion = value.trim().endsWith(".onion")
            _state.update {
                it.copy(
                    targetHost = value,
                    // Auto-enable SOCKS proxy for .onion addresses
                    socksProxyEnabled = if (isOnion) true else it.socksProxyEnabled,
                )
            }
        }

        /**
         * Update target port field.
         */
        fun updateTargetPort(value: String) {
            _state.update { it.copy(targetPort = value) }
        }

        /**
         * Toggle SOCKS5 proxy (Tor/Orbot) for this connection.
         */
        fun toggleSocksProxy(enabled: Boolean) {
            _state.update {
                // Prevent disabling SOCKS for .onion addresses (they require Tor)
                val isOnion = it.targetHost.trim().endsWith(".onion")
                it.copy(socksProxyEnabled = enabled || isOnion)
            }
        }

        /**
         * Update SOCKS5 proxy host.
         */
        fun updateSocksProxyHost(value: String) {
            _state.update { it.copy(socksProxyHost = value) }
        }

        /**
         * Update SOCKS5 proxy port.
         */
        fun updateSocksProxyPort(value: String) {
            _state.update { it.copy(socksProxyPort = value) }
        }

        /**
         * Check if the user can proceed to the next step.
         */
        fun canProceed(): Boolean {
            val currentState = _state.value
            return when (currentState.currentStep) {
                TcpClientWizardStep.SERVER_SELECTION ->
                    currentState.selectedServer != null || currentState.isCustomMode
                TcpClientWizardStep.REVIEW_CONFIGURE ->
                    true // No validation required per user request
            }
        }

        /**
         * Navigate to the next wizard step.
         */
        fun goToNextStep() {
            val currentState = _state.value
            val nextStep =
                when (currentState.currentStep) {
                    TcpClientWizardStep.SERVER_SELECTION -> TcpClientWizardStep.REVIEW_CONFIGURE
                    TcpClientWizardStep.REVIEW_CONFIGURE -> return // Already at last step
                }
            _state.update { it.copy(currentStep = nextStep) }
        }

        /**
         * Navigate to the previous wizard step.
         */
        fun goToPreviousStep() {
            val currentState = _state.value
            val previousStep =
                when (currentState.currentStep) {
                    TcpClientWizardStep.SERVER_SELECTION -> return // Already at first step
                    TcpClientWizardStep.REVIEW_CONFIGURE -> TcpClientWizardStep.SERVER_SELECTION
                }
            _state.update { it.copy(currentStep = previousStep) }
        }

        /**
         * Save the TCP Client interface configuration.
         */
        fun saveConfiguration() {
            viewModelScope.launch {
                _state.update { it.copy(isSaving = true, saveError = null) }

                try {
                    val currentState = _state.value
                    val interfaceName = currentState.interfaceName.trim().ifEmpty { "TCP Connection" }
                    val isEditing = currentState.editingInterfaceId != null

                    // Check for duplicate interface names before saving
                    // Exclude current interface when editing
                    val existingInterfaces = interfaceRepository.allInterfaceEntities.first()
                    val existingNames =
                        existingInterfaces
                            .filter { it.id != currentState.editingInterfaceId }
                            .map { it.name }
                    when (
                        val uniqueResult =
                            InputValidator.validateInterfaceNameUniqueness(
                                interfaceName,
                                existingNames,
                            )
                    ) {
                        is ValidationResult.Error -> {
                            _state.update {
                                it.copy(
                                    isSaving = false,
                                    saveError = uniqueResult.message,
                                )
                            }
                            return@launch
                        }
                        is ValidationResult.Success -> { /* Name is unique, continue */ }
                    }

                    val config =
                        InterfaceConfig.TCPClient(
                            name = interfaceName,
                            enabled = true,
                            targetHost = currentState.targetHost.trim(),
                            targetPort = currentState.targetPort.toIntOrNull() ?: 4242,
                            kissFraming = false,
                            mode = "full",
                            bootstrapOnly = currentState.bootstrapOnly,
                            socksProxyEnabled = currentState.socksProxyEnabled,
                            socksProxyHost = currentState.socksProxyHost.trim().ifEmpty { "127.0.0.1" },
                            socksProxyPort = currentState.socksProxyPort.toIntOrNull() ?: 9050,
                        )

                    if (isEditing) {
                        interfaceRepository.updateInterface(currentState.editingInterfaceId!!, config)
                        Log.d(TAG, "Updated TCP Client interface: ${config.name}")
                    } else {
                        interfaceRepository.insertInterface(config)
                        Log.d(TAG, "Saved TCP Client interface: ${config.name}")
                    }

                    // Mark pending changes for InterfaceManagementScreen to show "Apply" button
                    configManager.setPendingChanges(true)

                    _state.update { it.copy(isSaving = false, saveSuccess = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save TCP Client interface", e)
                    _state.update {
                        it.copy(
                            isSaving = false,
                            saveError = e.message ?: "Failed to save configuration",
                        )
                    }
                }
            }
        }

        /**
         * Clear the save error.
         */
        fun clearSaveError() {
            _state.update { it.copy(saveError = null) }
        }
    }
