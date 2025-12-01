package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.service.InterfaceConfigManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the onboarding/welcome screen.
 */
@androidx.compose.runtime.Immutable
data class OnboardingState(
    val isLoading: Boolean = true,
    val hasCompletedOnboarding: Boolean = false,
    val displayNameInput: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for managing the first-launch onboarding flow.
 * Handles display name setup and tracking onboarding completion.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val identityRepository: IdentityRepository,
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
         * Update the display name input as the user types.
         */
        fun updateDisplayNameInput(name: String) {
            _state.value = _state.value.copy(displayNameInput = name, error = null)
        }

        /**
         * Complete onboarding with the entered display name.
         * If the input is blank, uses the default "Anonymous Peer".
         *
         * @param onComplete Callback invoked after successful completion
         */
        fun completeOnboarding(onComplete: () -> Unit) {
            viewModelScope.launch {
                try {
                    _state.value = _state.value.copy(isSaving = true, error = null)

                    val nameToSave =
                        _state.value.displayNameInput.trim().ifEmpty {
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
                            }

                        // Restart service so Python reinitializes with the new display name
                        // This ensures announces are sent with the correct name
                        Log.d(TAG, "Restarting service to apply new display name...")
                        interfaceConfigManager.applyInterfaceChanges()
                            .onSuccess {
                                Log.d(TAG, "Service restarted with new display name")
                            }
                            .onFailure { error ->
                                Log.w(TAG, "Failed to restart service (name saved but may need manual restart)", error)
                            }
                    } else {
                        Log.w(TAG, "No active identity found - display name will be set when identity is created")
                    }

                    // Mark onboarding as completed
                    settingsRepository.markOnboardingCompleted()

                    _state.value =
                        _state.value.copy(
                            isSaving = false,
                            hasCompletedOnboarding = true,
                        )
                    Log.d(TAG, "Onboarding completed successfully")

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
         * Skip onboarding and use the default display name.
         *
         * @param onComplete Callback invoked after skipping
         */
        fun skipOnboarding(onComplete: () -> Unit) {
            viewModelScope.launch {
                try {
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

                    // Mark onboarding as completed
                    settingsRepository.markOnboardingCompleted()

                    _state.value = _state.value.copy(hasCompletedOnboarding = true)
                    Log.d(TAG, "Onboarding skipped, using default display name")

                    onComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error skipping onboarding", e)
                    _state.value = _state.value.copy(error = e.message)
                }
            }
        }
    }
