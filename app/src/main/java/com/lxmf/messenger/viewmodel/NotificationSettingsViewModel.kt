package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class NotificationSettingsState(
    val notificationsEnabled: Boolean = true,
    val receivedMessage: Boolean = true,
    val receivedMessageFavorite: Boolean = true,
    val heardAnnounce: Boolean = false,
    val bleConnected: Boolean = false,
    val bleDisconnected: Boolean = false,
    val hasRequestedNotificationPermission: Boolean = false,
    val isLoading: Boolean = true,
)

@HiltViewModel
class NotificationSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "NotificationSettingsVM"
        }

        private val _state = MutableStateFlow(NotificationSettingsState())
        val state: StateFlow<NotificationSettingsState> = _state.asStateFlow()

        init {
            loadSettings()
        }

        private fun loadSettings() {
            viewModelScope.launch {
                try {
                    // Combine all notification preference flows
                    combine(
                        settingsRepository.notificationsEnabledFlow,
                        settingsRepository.notificationReceivedMessageFlow,
                        settingsRepository.notificationReceivedMessageFavoriteFlow,
                        settingsRepository.notificationHeardAnnounceFlow,
                        settingsRepository.notificationBleConnectedFlow,
                        settingsRepository.notificationBleDisconnectedFlow,
                        settingsRepository.hasRequestedNotificationPermissionFlow,
                    ) { values: Array<Boolean> ->
                        NotificationSettingsState(
                            notificationsEnabled = values[0],
                            receivedMessage = values[1],
                            receivedMessageFavorite = values[2],
                            heardAnnounce = values[3],
                            bleConnected = values[4],
                            bleDisconnected = values[5],
                            hasRequestedNotificationPermission = values[6],
                            isLoading = false,
                        )
                    }.collect { newState ->
                        _state.value = newState
                        Log.d(TAG, "Notification settings updated: $newState")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting notification settings", e)
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }

        fun toggleNotificationsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    settingsRepository.saveNotificationsEnabled(enabled)
                    Log.d(TAG, "Master notifications enabled: $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving notifications enabled", e)
                }
            }
        }

        fun toggleReceivedMessage(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    settingsRepository.saveNotificationReceivedMessage(enabled)
                    Log.d(TAG, "Received message notifications: $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving received message notification", e)
                }
            }
        }

        fun toggleReceivedMessageFavorite(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    settingsRepository.saveNotificationReceivedMessageFavorite(enabled)
                    Log.d(TAG, "Received message from favorite notifications: $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving received message from favorite notification", e)
                }
            }
        }

        fun toggleHeardAnnounce(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    settingsRepository.saveNotificationHeardAnnounce(enabled)
                    Log.d(TAG, "Heard announce notifications: $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving heard announce notification", e)
                }
            }
        }

        fun toggleBleConnected(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    settingsRepository.saveNotificationBleConnected(enabled)
                    Log.d(TAG, "BLE connected notifications: $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving BLE connected notification", e)
                }
            }
        }

        fun toggleBleDisconnected(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    settingsRepository.saveNotificationBleDisconnected(enabled)
                    Log.d(TAG, "BLE disconnected notifications: $enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving BLE disconnected notification", e)
                }
            }
        }

        /**
         * Mark that we've requested notification permission from the user.
         * This prevents us from asking again on subsequent app launches.
         */
        fun markNotificationPermissionRequested() {
            viewModelScope.launch {
                try {
                    settingsRepository.markNotificationPermissionRequested()
                    Log.d(TAG, "Marked notification permission as requested")
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking notification permission as requested", e)
                }
            }
        }
    }
