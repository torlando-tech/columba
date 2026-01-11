package com.lxmf.messenger.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.TcpCommunityServers
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.ui.screens.onboarding.OnboardingInterfaceType
import com.lxmf.messenger.ui.screens.onboarding.OnboardingState
import com.lxmf.messenger.util.BatteryOptimizationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing the multi-page onboarding flow.
 * Handles identity setup, interface selection, and permission requests.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val identityRepository: IdentityRepository,
        private val interfaceRepository: InterfaceRepository,
        private val interfaceConfigManager: InterfaceConfigManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "OnboardingViewModel"
            const val DEFAULT_DISPLAY_NAME = "Anonymous Peer"
        }

        private val _state = MutableStateFlow(OnboardingState())
        val state: StateFlow<OnboardingState> = _state.asStateFlow()

        init {
            checkOnboardingStatus()
        }

        /**
         * Check if onboarding has already been completed.
         */
        private fun checkOnboardingStatus() {
            viewModelScope.launch {
                try {
                    val hasCompleted = settingsRepository.hasCompletedOnboardingFlow.first()
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            hasCompletedOnboarding = hasCompleted,
                        )
                    Log.d(TAG, "Onboarding status checked: hasCompleted=$hasCompleted")
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking onboarding status", e)
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }

        /**
         * Set the current page index.
         */
        fun setCurrentPage(page: Int) {
            _state.value = _state.value.copy(currentPage = page)
        }

        /**
         * Update the display name as the user types.
         */
        fun updateDisplayName(name: String) {
            _state.value = _state.value.copy(displayName = name, error = null)
        }

        /**
         * Toggle an interface type selection.
         */
        fun toggleInterface(interfaceType: OnboardingInterfaceType) {
            val currentSelection = _state.value.selectedInterfaces.toMutableSet()
            if (currentSelection.contains(interfaceType)) {
                currentSelection.remove(interfaceType)
            } else {
                currentSelection.add(interfaceType)
            }
            _state.value = _state.value.copy(selectedInterfaces = currentSelection)
        }

        /**
         * Handle notification permission result.
         */
        fun onNotificationPermissionResult(granted: Boolean) {
            _state.value =
                _state.value.copy(
                    notificationsEnabled = granted,
                    notificationsGranted = granted,
                )
            Log.d(TAG, "Notification permission result: granted=$granted")
        }

        /**
         * Handle BLE permissions result.
         */
        fun onBlePermissionsResult(
            allGranted: Boolean,
            anyDenied: Boolean,
        ) {
            _state.value =
                _state.value.copy(
                    blePermissionsGranted = allGranted,
                    blePermissionsDenied = anyDenied && !allGranted,
                )
            Log.d(TAG, "BLE permissions result: allGranted=$allGranted, anyDenied=$anyDenied")

            // If permissions were denied, remove BLE from selected interfaces
            if (!allGranted && anyDenied) {
                val currentSelection = _state.value.selectedInterfaces.toMutableSet()
                currentSelection.remove(OnboardingInterfaceType.BLE)
                _state.value = _state.value.copy(selectedInterfaces = currentSelection)
            }
        }

        /**
         * Check current battery optimization status.
         */
        fun checkBatteryOptimizationStatus(context: Context) {
            val isExempt = BatteryOptimizationManager.isIgnoringBatteryOptimizations(context)
            _state.value = _state.value.copy(batteryOptimizationExempt = isExempt)
            Log.d(TAG, "Battery optimization status: exempt=$isExempt")
        }

        /**
         * Complete onboarding with all selected settings.
         * Creates interfaces and saves display name.
         *
         * @param onComplete Callback invoked after successful completion
         */
        fun completeOnboarding(onComplete: () -> Unit) {
            viewModelScope.launch {
                try {
                    _state.value = _state.value.copy(isSaving = true, error = null)
                    var hasWarnings = false

                    val nameToSave =
                        _state.value.displayName.trim().ifEmpty {
                            DEFAULT_DISPLAY_NAME
                        }

                    // Update display name in database
                    val activeIdentity = identityRepository.getActiveIdentitySync()
                    if (activeIdentity != null) {
                        identityRepository.updateDisplayName(activeIdentity.identityHash, nameToSave)
                            .onSuccess {
                                Log.d(TAG, "Display name set to: $nameToSave")
                            }
                            .onFailure { error ->
                                Log.e(TAG, "Failed to update display name", error)
                                hasWarnings = true
                            }
                    } else {
                        Log.w(TAG, "No active identity found - display name will be set when identity is created")
                    }

                    // Create selected interfaces
                    val interfacesCreated = createSelectedInterfaces()
                    if (!interfacesCreated) {
                        hasWarnings = true
                    }

                    // Mark onboarding as completed - this is critical and will throw if it fails
                    settingsRepository.markOnboardingCompleted()

                    // Restart service to apply changes
                    Log.d(TAG, "Restarting service to apply onboarding settings...")
                    interfaceConfigManager.applyInterfaceChanges()
                        .onSuccess {
                            Log.d(TAG, "Service restarted with new settings")
                        }
                        .onFailure { error ->
                            Log.w(TAG, "Failed to restart service (settings saved but may need manual restart)", error)
                            hasWarnings = true
                        }

                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            hasCompletedOnboarding = true,
                        )

                    if (hasWarnings) {
                        Log.w(TAG, "Onboarding completed with warnings - some settings may need manual configuration")
                    } else {
                        Log.d(TAG, "Onboarding completed successfully")
                    }

                    onComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error completing onboarding", e)
                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            error = e.message ?: "Failed to save",
                        )
                }
            }
        }

        /**
         * Create or update interface configurations based on user selections.
         * - Selected interfaces: create if missing, enable if exists
         * - Unselected interfaces: disable if exists (preserve user config)
         *
         * @return true if all interfaces were created/updated successfully, false if any failed
         */
        private suspend fun createSelectedInterfaces(): Boolean {
            var success = true
            val selectedInterfaces = _state.value.selectedInterfaces

            // Get existing interface entities (which include IDs)
            val existingEntities = interfaceRepository.allInterfaceEntities.first()

            // Map interface types to database type strings
            val typeMapping =
                mapOf(
                    OnboardingInterfaceType.AUTO to "AutoInterface",
                    OnboardingInterfaceType.BLE to "AndroidBLE",
                    OnboardingInterfaceType.TCP to "TCPClient",
                    OnboardingInterfaceType.RNODE to "RNode",
                )

            // Process each interface type
            for ((onboardingType, dbType) in typeMapping) {
                val isSelected = selectedInterfaces.contains(onboardingType)
                val existingEntity = existingEntities.find { it.type == dbType }

                when {
                    // RNode requires wizard setup, don't auto-create or modify
                    onboardingType == OnboardingInterfaceType.RNODE -> {
                        Log.d(TAG, "RNode requires wizard setup, skipping")
                    }

                    // Interface exists - update enabled state based on selection
                    existingEntity != null -> {
                        interfaceRepository.toggleInterfaceEnabled(existingEntity.id, isSelected)
                        Log.d(TAG, "$dbType ${if (isSelected) "enabled" else "disabled"}")
                    }

                    // Interface doesn't exist and is selected - create it
                    isSelected -> {
                        val config =
                            when (onboardingType) {
                                OnboardingInterfaceType.AUTO ->
                                    InterfaceConfig.AutoInterface(
                                        name = "Local WiFi",
                                        enabled = true,
                                    )
                                OnboardingInterfaceType.BLE ->
                                    InterfaceConfig.AndroidBLE(
                                        name = "Bluetooth LE",
                                        enabled = true,
                                    )
                                OnboardingInterfaceType.TCP -> {
                                    val defaultServer = TcpCommunityServers.servers.firstOrNull()
                                    if (defaultServer != null) {
                                        InterfaceConfig.TCPClient(
                                            name = defaultServer.name,
                                            enabled = true,
                                            targetHost = defaultServer.host,
                                            targetPort = defaultServer.port,
                                        )
                                    } else {
                                        Log.w(TAG, "No default TCP server available")
                                        null
                                    }
                                }
                                OnboardingInterfaceType.RNODE -> null
                            }
                        config?.let {
                            try {
                                interfaceRepository.insertInterface(it)
                                Log.d(TAG, "Created interface: ${it.name}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to create interface: ${it.name}", e)
                                success = false
                            }
                        }
                    }

                    // Interface doesn't exist and is not selected - nothing to do
                    else -> {
                        Log.d(TAG, "$dbType not selected and doesn't exist, skipping")
                    }
                }
            }
            return success
        }

        /**
         * Skip onboarding with default settings.
         * Creates only AutoInterface (local WiFi) with default display name.
         *
         * @param onComplete Callback invoked after skipping
         */
        fun skipOnboarding(onComplete: () -> Unit) {
            viewModelScope.launch {
                try {
                    _state.value = _state.value.copy(isSaving = true)

                    // Set default display name
                    val activeIdentity = identityRepository.getActiveIdentitySync()
                    if (activeIdentity != null) {
                        identityRepository.updateDisplayName(activeIdentity.identityHash, DEFAULT_DISPLAY_NAME)
                            .onSuccess {
                                Log.d(TAG, "Display name set to default: $DEFAULT_DISPLAY_NAME")
                            }
                            .onFailure { error ->
                                Log.e(TAG, "Failed to set default display name", error)
                            }
                    }

                    // Create only AutoInterface for skip (safe default)
                    val existingInterfaces = interfaceRepository.allInterfaces.first()
                    val hasAutoInterface = existingInterfaces.any { it is InterfaceConfig.AutoInterface }
                    if (!hasAutoInterface) {
                        interfaceRepository.insertInterface(
                            InterfaceConfig.AutoInterface(
                                name = "Local WiFi",
                                enabled = true,
                            ),
                        )
                        Log.d(TAG, "Created default AutoInterface for skip")
                    }

                    // Mark onboarding as completed
                    settingsRepository.markOnboardingCompleted()

                    // Restart service to apply changes
                    Log.d(TAG, "Restarting service to apply default settings...")
                    interfaceConfigManager.applyInterfaceChanges()
                        .onSuccess {
                            Log.d(TAG, "Service restarted with default settings")
                        }
                        .onFailure { error ->
                            Log.w(
                                TAG,
                                "Failed to restart service (settings saved but may need manual restart)",
                                error,
                            )
                        }

                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            hasCompletedOnboarding = true,
                        )
                    Log.d(TAG, "Onboarding skipped, using default settings")

                    onComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error skipping onboarding", e)
                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            error = e.message,
                        )
                }
            }
        }

        // Legacy method for backwards compatibility with old WelcomeScreen
        @Deprecated("Use updateDisplayName instead", ReplaceWith("updateDisplayName(name)"))
        fun updateDisplayNameInput(name: String) = updateDisplayName(name)
    }
