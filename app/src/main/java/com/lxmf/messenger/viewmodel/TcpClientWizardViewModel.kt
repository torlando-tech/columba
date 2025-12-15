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
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // Server selection
    val selectedServer: TcpCommunityServer? = null,
    val isCustomMode: Boolean = false,
    // Configuration fields
    val interfaceName: String = "",
    val targetHost: String = "",
    val targetPort: String = "",
    // Save state
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false,
)

/**
 * ViewModel for the TCP Client interface setup wizard.
 */
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
         * Get the list of community servers.
         */
        fun getCommunityServers(): List<TcpCommunityServer> = TcpCommunityServers.servers

        /**
         * Select a community server.
         */
        fun selectServer(server: TcpCommunityServer) {
            _state.update {
                it.copy(
                    selectedServer = server,
                    isCustomMode = false,
                    interfaceName = server.name,
                    targetHost = server.host,
                    targetPort = server.port.toString(),
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
                )
            }
        }

        /**
         * Update interface name field.
         */
        fun updateInterfaceName(value: String) {
            _state.update { it.copy(interfaceName = value) }
        }

        /**
         * Update target host field.
         */
        fun updateTargetHost(value: String) {
            _state.update { it.copy(targetHost = value) }
        }

        /**
         * Update target port field.
         */
        fun updateTargetPort(value: String) {
            _state.update { it.copy(targetPort = value) }
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

                    // Check for duplicate interface names before saving
                    val existingNames = interfaceRepository.allInterfaces.first().map { it.name }
                    when (
                        val uniqueResult = InputValidator.validateInterfaceNameUniqueness(
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
                        )

                    interfaceRepository.insertInterface(config)
                    Log.d(TAG, "Saved TCP Client interface: ${config.name}")

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
