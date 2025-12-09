package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.PresetTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class SettingsState(
    val displayName: String = "",
    val defaultDisplayName: String = "",
    val isLoading: Boolean = true,
    val showSaveSuccess: Boolean = false,
    val autoAnnounceEnabled: Boolean = true,
    val autoAnnounceIntervalMinutes: Int = 5,
    val lastAutoAnnounceTime: Long? = null,
    val isManualAnnouncing: Boolean = false,
    val showManualAnnounceSuccess: Boolean = false,
    val manualAnnounceError: String? = null,
    val identityHash: String? = null,
    val destinationHash: String? = null,
    val showQrDialog: Boolean = false,
    val selectedTheme: AppTheme = PresetTheme.VIBRANT,
    val customThemes: List<AppTheme> = emptyList(),
    val isRestarting: Boolean = false,
    // Shared instance state
    val isSharedInstance: Boolean = false,
    val preferOwnInstance: Boolean = false,
    val isSharedInstanceBannerExpanded: Boolean = false,
    val rpcKey: String? = null,
    val sharedInstanceLost: Boolean = false, // True when shared instance disconnected
    val sharedInstanceAvailable: Boolean = false, // True when shared instance detected while running own
)

@Suppress("TooManyFunctions") // ViewModel with many user interaction methods is expected
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val identityRepository: IdentityRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val interfaceConfigManager: com.lxmf.messenger.service.InterfaceConfigManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "SettingsViewModel"
            private const val INIT_DELAY_MS = 500L // Allow Reticulum service to initialize
            private const val MAX_RETRIES = 3
            private const val RETRY_DELAY_MS = 1000L
            private const val SHARED_INSTANCE_MONITOR_INTERVAL_MS = 5_000L // Check every 5 seconds
            private const val SHARED_INSTANCE_LOST_THRESHOLD_MS = 10_000L // 10 seconds to consider lost
            private const val SHARED_INSTANCE_PORT = 37428 // Default RNS shared instance port
            private const val SOCKET_TIMEOUT_MS = 1000 // Socket connection timeout

            /**
             * Controls whether shared instance monitors are started in init.
             * Set to false for unit testing to avoid infinite loops.
             * @suppress VisibleForTesting
             */
            internal var enableMonitors = true
        }

        private val _state =
            MutableStateFlow(
                SettingsState(
                    // Start with default theme - actual theme loads asynchronously in loadSettings()
                    selectedTheme = PresetTheme.VIBRANT,
                ),
            )
        val state: StateFlow<SettingsState> = _state.asStateFlow()

        // Track when we first noticed shared instance disconnected
        private var sharedInstanceDisconnectedTime: Long? = null
        private var sharedInstanceMonitorJob: Job? = null
        private var sharedInstanceAvailabilityJob: Job? = null

        init {
            loadSettings()
            if (enableMonitors) {
                startSharedInstanceMonitor()
                startSharedInstanceAvailabilityMonitor()
            }
        }

        @Suppress("LongMethod") // Complex flow combination logic is best kept together for readability
        private fun loadSettings() {
            viewModelScope.launch {
                // Add initial delay to allow Reticulum service and DataStore to fully initialize
                // This prevents race conditions during app startup
                delay(INIT_DELAY_MS)

                // Get active identity from database for display name
                val activeIdentity = identityRepository.getActiveIdentitySync()
                val defaultName = activeIdentity?.displayName ?: "Unknown Peer"

                // Load identity information from Reticulum
                val identityInfo = loadIdentityInfo()

                Log.d(TAG, "Loaded active identity: ${activeIdentity?.displayName} (${activeIdentity?.identityHash?.take(8)})")
                Log.d(TAG, "Loaded identity hash from Reticulum: ${identityInfo.first}")

                // Collect all settings flows and update state
                try {
                    // Load custom themes and convert to AppTheme list
                    val customThemesFlow =
                        settingsRepository.getAllCustomThemes()
                            .map { themeDataList ->
                                themeDataList.map { themeData ->
                                    settingsRepository.customThemeDataToAppTheme(themeData)
                                }
                            }

                    combine(
                        identityRepository.activeIdentity,
                        settingsRepository.autoAnnounceEnabledFlow,
                        settingsRepository.autoAnnounceIntervalMinutesFlow,
                        settingsRepository.lastAutoAnnounceTimeFlow,
                        settingsRepository.themePreferenceFlow,
                        customThemesFlow,
                        settingsRepository.preferOwnInstanceFlow,
                        settingsRepository.isSharedInstanceFlow,
                        settingsRepository.rpcKeyFlow,
                    ) { flows ->
                        @Suppress("UNCHECKED_CAST")
                        val activeIdentity = flows[0] as com.lxmf.messenger.data.db.entity.LocalIdentityEntity?

                        @Suppress("UNCHECKED_CAST")
                        val autoAnnounceEnabled = flows[1] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val intervalMinutes = flows[2] as Int

                        @Suppress("UNCHECKED_CAST")
                        val lastAnnounceTime = flows[3] as Long?

                        @Suppress("UNCHECKED_CAST")
                        val selectedTheme = flows[4] as AppTheme

                        @Suppress("UNCHECKED_CAST")
                        val customThemes = flows[5] as List<AppTheme>

                        @Suppress("UNCHECKED_CAST")
                        val preferOwnInstance = flows[6] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val isSharedInstance = flows[7] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val rpcKey = flows[8] as String?

                        val displayName = activeIdentity?.displayName ?: defaultName

                        SettingsState(
                            displayName = displayName,
                            defaultDisplayName = defaultName,
                            isLoading = false,
                            showSaveSuccess = _state.value.showSaveSuccess,
                            autoAnnounceEnabled = autoAnnounceEnabled,
                            autoAnnounceIntervalMinutes = intervalMinutes,
                            lastAutoAnnounceTime = lastAnnounceTime,
                            isManualAnnouncing = _state.value.isManualAnnouncing,
                            showManualAnnounceSuccess = _state.value.showManualAnnounceSuccess,
                            manualAnnounceError = _state.value.manualAnnounceError,
                            identityHash = identityInfo.first,
                            destinationHash = identityInfo.second,
                            showQrDialog = _state.value.showQrDialog,
                            selectedTheme = selectedTheme,
                            customThemes = customThemes,
                            isRestarting = _state.value.isRestarting,
                            // Shared instance state from repository (set by service)
                            isSharedInstance = isSharedInstance,
                            preferOwnInstance = preferOwnInstance,
                            isSharedInstanceBannerExpanded = _state.value.isSharedInstanceBannerExpanded,
                            rpcKey = rpcKey,
                        )
                    }.collect { newState ->
                        _state.value = newState
                        Log.d(
                            TAG,
                            "Settings updated: displayName=${newState.displayName}, autoAnnounce=${newState.autoAnnounceEnabled}, interval=${newState.autoAnnounceIntervalMinutes}min, theme=${newState.selectedTheme}, customThemes=${newState.customThemes.size}",
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting settings flows", e)
                    // Still set loading to false so UI doesn't hang
                    _state.value =
                        _state.value.copy(
                            displayName = defaultName,
                            defaultDisplayName = defaultName,
                            isLoading = false,
                            identityHash = identityInfo.first,
                            destinationHash = identityInfo.second,
                            selectedTheme = PresetTheme.VIBRANT,
                        )
                }
            }
        }

        /**
         * Load identity and destination hashes.
         * Returns Pair(identityHashHex, destinationHashHex), both nullable.
         */
        private suspend fun loadIdentityInfo(): Pair<String?, String?> {
            var attemptCount = 0
            var lastException: Exception? = null

            while (attemptCount < MAX_RETRIES) {
                try {
                    if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                        // Get identity
                        val identityResult = reticulumProtocol.getLxmfIdentity()
                        if (!identityResult.isSuccess) {
                            Log.w(TAG, "Identity result was not successful on attempt ${attemptCount + 1}")
                            attemptCount++
                            if (attemptCount < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS)
                            }
                            continue
                        }

                        val identity = identityResult.getOrNull()
                        if (identity == null) {
                            Log.w(TAG, "Identity is null on attempt ${attemptCount + 1}")
                            attemptCount++
                            if (attemptCount < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS)
                            }
                            continue
                        }

                        // Get destination
                        val destinationResult = reticulumProtocol.getLxmfDestination()
                        if (!destinationResult.isSuccess) {
                            Log.w(TAG, "Destination result was not successful on attempt ${attemptCount + 1}")
                            attemptCount++
                            if (attemptCount < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS)
                            }
                            continue
                        }

                        val destination = destinationResult.getOrNull()
                        if (destination == null) {
                            Log.w(TAG, "Destination is null on attempt ${attemptCount + 1}")
                            attemptCount++
                            if (attemptCount < MAX_RETRIES) {
                                delay(RETRY_DELAY_MS)
                            }
                            continue
                        }

                        // Convert to hex strings
                        val identityHashHex = identity.hash.joinToString("") { "%02x".format(it) }
                        val destinationHashHex = destination.hexHash

                        Log.d(TAG, "Successfully loaded identity info on attempt ${attemptCount + 1}")
                        return Pair(identityHashHex, destinationHashHex)
                    } else {
                        Log.w(TAG, "ReticulumProtocol is not ServiceReticulumProtocol")
                        return Pair(null, null)
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Attempt ${attemptCount + 1} failed to get identity info: ${e.message}")
                }

                attemptCount++
                if (attemptCount < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                }
            }

            Log.e(TAG, "Failed to get identity info after $MAX_RETRIES attempts", lastException)
            return Pair(null, null)
        }

        fun updateDisplayName(newDisplayName: String) {
            viewModelScope.launch {
                try {
                    // Get active identity
                    val activeIdentity = identityRepository.getActiveIdentitySync()

                    if (activeIdentity != null) {
                        // Update the display name in the database
                        val nameToSave = newDisplayName.trim()
                        identityRepository.updateDisplayName(activeIdentity.identityHash, nameToSave)
                            .onSuccess {
                                Log.d(TAG, "Display name updated successfully to: $nameToSave")
                                // Show success message
                                _state.value = _state.value.copy(showSaveSuccess = true)
                            }
                            .onFailure { error ->
                                Log.e(TAG, "Failed to update display name", error)
                            }
                    } else {
                        Log.w(TAG, "Cannot update display name - no active identity")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating display name", e)
                }
            }
        }

        fun clearSaveSuccess() {
            _state.value = _state.value.copy(showSaveSuccess = false)
        }

        /**
         * Get the effective display name (custom or default) to be used in announces and messages.
         */
        fun getEffectiveDisplayName(): String {
            return state.value.displayName
        }

        /**
         * Toggle the QR code dialog visibility.
         */
        fun toggleQrDialog(show: Boolean) {
            _state.value = _state.value.copy(showQrDialog = show)
        }

        // Auto-announce methods

        /**
         * Toggle auto-announce enabled state.
         */
        fun toggleAutoAnnounce(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.saveAutoAnnounceEnabled(enabled)
                Log.d(TAG, "Auto-announce ${if (enabled) "enabled" else "disabled"}")
            }
        }

        /**
         * Update the auto-announce interval in minutes.
         */
        fun setAnnounceInterval(minutes: Int) {
            viewModelScope.launch {
                settingsRepository.saveAutoAnnounceIntervalMinutes(minutes)
                Log.d(TAG, "Auto-announce interval set to $minutes minutes")
            }
        }

        /**
         * Trigger a manual announce immediately.
         */
        fun triggerManualAnnounce() {
            viewModelScope.launch {
                try {
                    _state.value =
                        _state.value.copy(
                            isManualAnnouncing = true,
                            showManualAnnounceSuccess = false,
                            manualAnnounceError = null,
                        )
                    Log.d(TAG, "Triggering manual announce...")

                    // Get display name
                    val displayName = state.value.displayName

                    // Trigger announce if using ServiceReticulumProtocol
                    if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                        val result = reticulumProtocol.triggerAutoAnnounce(displayName)

                        if (result.isSuccess) {
                            // Update last announce timestamp
                            val timestamp = System.currentTimeMillis()
                            settingsRepository.saveLastAutoAnnounceTime(timestamp)

                            _state.value =
                                _state.value.copy(
                                    isManualAnnouncing = false,
                                    showManualAnnounceSuccess = true,
                                )
                            Log.d(TAG, "Manual announce successful")

                            // Auto-dismiss success message after 3 seconds
                            delay(3000)
                            clearManualAnnounceStatus()
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "Unknown error"
                            _state.value =
                                _state.value.copy(
                                    isManualAnnouncing = false,
                                    manualAnnounceError = error,
                                )
                            Log.e(TAG, "Manual announce failed: $error")

                            // Auto-dismiss error message after 5 seconds
                            delay(5000)
                            clearManualAnnounceStatus()
                        }
                    } else {
                        _state.value =
                            _state.value.copy(
                                isManualAnnouncing = false,
                                manualAnnounceError = "Service not available",
                            )
                        Log.w(TAG, "Manual announce skipped: ReticulumProtocol is not ServiceReticulumProtocol")

                        // Auto-dismiss error message after 5 seconds
                        delay(5000)
                        clearManualAnnounceStatus()
                    }
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            isManualAnnouncing = false,
                            manualAnnounceError = e.message ?: "Error triggering announce",
                        )
                    Log.e(TAG, "Error triggering manual announce", e)

                    // Auto-dismiss error message after 5 seconds
                    delay(5000)
                    clearManualAnnounceStatus()
                }
            }
        }

        /**
         * Clear manual announce status messages.
         */
        fun clearManualAnnounceStatus() {
            _state.value =
                _state.value.copy(
                    showManualAnnounceSuccess = false,
                    manualAnnounceError = null,
                )
        }

        // Theme methods

        /**
         * Set the app theme.
         * The theme will be applied immediately and persisted across app restarts.
         *
         * @param theme The theme to apply
         */
        fun setTheme(theme: AppTheme) {
            viewModelScope.launch {
                settingsRepository.saveThemePreference(theme)
                Log.d(TAG, "Theme changed to: ${theme.displayName}")
            }
        }

        /**
         * Apply a custom theme by its ID
         */
        fun applyCustomTheme(themeId: Long) {
            viewModelScope.launch {
                try {
                    val themeData = settingsRepository.getCustomThemeById(themeId)
                    if (themeData != null) {
                        val appTheme = settingsRepository.customThemeDataToAppTheme(themeData)
                        settingsRepository.saveThemePreference(appTheme)
                        Log.d(TAG, "Applied custom theme: ${appTheme.displayName}")
                    } else {
                        Log.e(TAG, "Failed to apply custom theme: Theme ID $themeId not found")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying custom theme", e)
                }
            }
        }

        fun shutdownService() {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "User requested service shutdown")
                    reticulumProtocol.shutdown()
                    Log.i(TAG, "Service shutdown complete")
                } catch (e: Exception) {
                    Log.e(TAG, "Error shutting down service", e)
                }
            }
        }

        fun restartService() {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "User requested service restart")
                    _state.value = _state.value.copy(isRestarting = true)

                    // Use InterfaceConfigManager which handles the full restart lifecycle:
                    // 1. Shutdown current service
                    // 2. Restart the service process
                    // 3. Re-initialize with config from database
                    interfaceConfigManager.applyInterfaceChanges()
                        .onSuccess {
                            Log.i(TAG, "Service restart completed successfully")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Service restart failed: ${error.message}", error)
                        }
                        .getOrThrow() // Convert failure to exception for catch block

                    _state.value = _state.value.copy(isRestarting = false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting service", e)
                    _state.value = _state.value.copy(isRestarting = false)
                }
            }
        }

        // Shared instance methods

        /**
         * Toggle the prefer own instance setting.
         * When enabled, Columba will use its own RNS instance even if a shared one is available.
         */
        fun togglePreferOwnInstance(preferOwn: Boolean) {
            viewModelScope.launch {
                settingsRepository.savePreferOwnInstance(preferOwn)
                Log.d(TAG, "Prefer own instance set to: $preferOwn")
                // Restart service to apply the change
                if (!_state.value.isRestarting) {
                    restartService()
                }
            }
        }

        /**
         * Toggle the shared instance banner expansion state.
         */
        fun toggleSharedInstanceBannerExpanded(expanded: Boolean) {
            _state.value = _state.value.copy(isSharedInstanceBannerExpanded = expanded)
        }

        /**
         * Save the RPC key for shared instance authentication.
         * When the key changes, restart the service to apply it.
         *
         * Handles multiple input formats:
         * - Raw hex key: "e17abc123..."
         * - Sideband config format: "shared_instance_type = tcp\nrpc_key = e17abc123..."
         */
        fun saveRpcKey(rpcKey: String?) {
            viewModelScope.launch {
                val parsedKey = parseRpcKey(rpcKey)
                settingsRepository.saveRpcKey(parsedKey)
                Log.d(TAG, "RPC key ${if (parsedKey != null) "updated" else "cleared"}")
                // Restart service to apply the change
                if (!_state.value.isRestarting && _state.value.isSharedInstance) {
                    restartService()
                }
            }
        }

        /**
         * Parse RPC key from various input formats.
         * Extracts the hex key value from Sideband's config export format.
         */
        internal fun parseRpcKey(input: String?): String? {
            if (input.isNullOrBlank()) return null

            // Check if it's the Sideband config format (contains "rpc_key")
            val rpcKeyPattern = Regex("""rpc_key\s*=\s*([a-fA-F0-9]+)""")
            val match = rpcKeyPattern.find(input)

            val result =
                when {
                    match != null -> {
                        val key = match.groupValues[1]
                        Log.d(TAG, "Parsed RPC key from config format (${key.length} chars)")
                        key
                    }
                    input.trim().matches(Regex("^[a-fA-F0-9]+$")) -> {
                        val trimmed = input.trim()
                        Log.d(TAG, "Using raw hex RPC key (${trimmed.length} chars)")
                        trimmed
                    }
                    else -> {
                        Log.w(TAG, "Invalid RPC key format, ignoring")
                        null
                    }
                }
            return result
        }

        /**
         * Start monitoring shared instance connection status.
         * Detects when shared instance is disconnected for too long and sets sharedInstanceLost flag.
         * Uses direct TCP port probing since Python doesn't report connection loss.
         */
        private fun startSharedInstanceMonitor() {
            sharedInstanceMonitorJob?.cancel()
            sharedInstanceMonitorJob =
                viewModelScope.launch {
                    // Wait for initial setup
                    delay(INIT_DELAY_MS * 2)

                    while (true) {
                        delay(SHARED_INSTANCE_MONITOR_INTERVAL_MS)

                        val currentState = _state.value

                        // Only monitor when we're using a shared instance
                        if (currentState.isSharedInstance && !currentState.preferOwnInstance) {
                            // Probe the shared instance port directly - more reliable than networkStatus
                            // because Python doesn't actively detect connection loss
                            val isPortOpen = probeSharedInstancePort()

                            if (!isPortOpen) {
                                val now = System.currentTimeMillis()
                                if (sharedInstanceDisconnectedTime == null) {
                                    sharedInstanceDisconnectedTime = now
                                    Log.d(TAG, "Shared instance port closed, starting timer...")
                                } else {
                                    val disconnectedDuration = now - sharedInstanceDisconnectedTime!!
                                    if (disconnectedDuration >= SHARED_INSTANCE_LOST_THRESHOLD_MS &&
                                        !currentState.sharedInstanceLost
                                    ) {
                                        Log.w(
                                            TAG,
                                            "Shared instance lost for ${disconnectedDuration / 1000}s, " +
                                                "notifying user",
                                        )
                                        _state.value = currentState.copy(sharedInstanceLost = true)
                                    }
                                }
                            } else {
                                // Connection restored
                                if (sharedInstanceDisconnectedTime != null) {
                                    Log.d(TAG, "Shared instance port open again")
                                    sharedInstanceDisconnectedTime = null
                                    if (currentState.sharedInstanceLost) {
                                        _state.value = currentState.copy(sharedInstanceLost = false)
                                    }
                                }
                            }
                        } else {
                            // Not in shared instance mode, reset tracking
                            sharedInstanceDisconnectedTime = null
                            if (currentState.sharedInstanceLost) {
                                _state.value = currentState.copy(sharedInstanceLost = false)
                            }
                        }
                    }
                }
        }

        /**
         * Dismiss the shared instance lost warning without taking action.
         */
        fun dismissSharedInstanceLostWarning() {
            _state.value = _state.value.copy(sharedInstanceLost = false)
            // Reset timer so warning doesn't immediately reappear
            sharedInstanceDisconnectedTime = System.currentTimeMillis()
        }

        /**
         * Handle shared instance loss by switching to own instance.
         */
        fun switchToOwnInstanceAfterLoss() {
            viewModelScope.launch {
                Log.i(TAG, "User chose to switch to own instance after shared instance loss")
                _state.value = _state.value.copy(sharedInstanceLost = false)
                settingsRepository.savePreferOwnInstance(true)
                if (!_state.value.isRestarting) {
                    restartService()
                }
            }
        }

        /**
         * Start monitoring for shared instance becoming available.
         * When Columba is running its own instance (not by user preference),
         * this periodically probes the shared instance port to detect if
         * another app (like Sideband) has started.
         */
        private fun startSharedInstanceAvailabilityMonitor() {
            sharedInstanceAvailabilityJob?.cancel()
            sharedInstanceAvailabilityJob =
                viewModelScope.launch {
                    // Wait for initial setup
                    delay(INIT_DELAY_MS * 4) // Give more time for service to fully start

                    while (true) {
                        val currentState = _state.value

                        // Probe when running our own instance (regardless of preference)
                        // This allows the toggle to know if switching to shared is possible
                        if (!currentState.isSharedInstance && !currentState.isRestarting) {
                            val isAvailable = probeSharedInstancePort()

                            if (isAvailable && !currentState.sharedInstanceAvailable) {
                                Log.i(TAG, "Shared instance detected on port $SHARED_INSTANCE_PORT")
                                _state.value = currentState.copy(sharedInstanceAvailable = true)
                            } else if (!isAvailable && currentState.sharedInstanceAvailable) {
                                // Shared instance went away
                                Log.d(TAG, "Shared instance no longer available")
                                _state.value = currentState.copy(sharedInstanceAvailable = false)
                            }
                        } else if (currentState.isSharedInstance) {
                            // Already using shared instance, reset availability flag
                            if (currentState.sharedInstanceAvailable) {
                                _state.value = currentState.copy(sharedInstanceAvailable = false)
                            }
                        }

                        // Wait before next poll
                        delay(SHARED_INSTANCE_MONITOR_INTERVAL_MS)
                    }
                }
        }

        /**
         * Probe the shared instance port to check if it's listening.
         * Returns true if a connection can be established.
         */
        @Suppress("TooGenericExceptionCaught") // Socket operations can throw various exceptions
        private fun probeSharedInstancePort(): Boolean {
            return try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", SHARED_INSTANCE_PORT),
                        SOCKET_TIMEOUT_MS,
                    )
                    true
                }
            } catch (
                @Suppress("SwallowedException") e: Exception,
            ) {
                // Connection refused, timeout, etc. = no shared instance
                // Not logging to avoid spamming logs during polling
                false
            }
        }

        /**
         * Dismiss the shared instance available notification without switching.
         * The sharedInstanceAvailable flag will be set again by monitoring if still available,
         * but the banner won't show again since preferOwnInstance will be true.
         */
        fun dismissSharedInstanceAvailable() {
            viewModelScope.launch {
                Log.d(TAG, "User dismissed shared instance available notification, preferring own")
                // Set preference first - this hides the "available" banner
                settingsRepository.savePreferOwnInstance(true)
                // Note: sharedInstanceAvailable will continue to be updated by monitoring
                // so the toggle knows if switching to shared is possible
            }
        }

        /**
         * Switch to the newly available shared instance.
         */
        fun switchToSharedInstance() {
            viewModelScope.launch {
                Log.i(TAG, "User chose to switch to shared instance")
                _state.value = _state.value.copy(sharedInstanceAvailable = false)
                // Ensure preference is cleared and restart
                settingsRepository.savePreferOwnInstance(false)
                if (!_state.value.isRestarting) {
                    restartService()
                }
            }
        }
    }
