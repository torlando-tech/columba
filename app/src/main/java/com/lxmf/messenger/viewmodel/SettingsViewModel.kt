package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.PresetTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    val wasUsingSharedInstance: Boolean = false, // True if we were using shared but it went offline
    val sharedInstanceAvailable: Boolean = false, // True when shared instance becomes newly available (notification)
    val sharedInstanceOnline: Boolean = false, // True if shared instance is currently reachable (from service query)
    // Message delivery state
    val defaultDeliveryMethod: String = "direct", // "direct" or "propagated"
    val tryPropagationOnFail: Boolean = true,
    val autoSelectPropagationNode: Boolean = true,
    val currentRelayName: String? = null,
    val currentRelayHops: Int? = null,
    // Message retrieval state
    val autoRetrieveEnabled: Boolean = true,
    val retrievalIntervalSeconds: Int = 30,
    val lastSyncTimestamp: Long? = null,
    val isSyncing: Boolean = false,
    // Transport node state
    val transportNodeEnabled: Boolean = true,
)

@Suppress("TooManyFunctions", "LargeClass") // ViewModel with many user interaction methods is expected
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val identityRepository: IdentityRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val interfaceConfigManager: com.lxmf.messenger.service.InterfaceConfigManager,
        private val propagationNodeManager: PropagationNodeManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "SettingsViewModel"
            private const val INIT_DELAY_MS = 500L // Allow Reticulum service to initialize
            private const val MAX_RETRIES = 3
            private const val RETRY_DELAY_MS = 1000L
            private const val SHARED_INSTANCE_MONITOR_INTERVAL_MS = 5_000L // Check every 5 seconds
            private const val SHARED_INSTANCE_PORT = 37428 // Default RNS shared instance port (for logging)

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
                startRelayMonitor()
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
                        settingsRepository.transportNodeEnabledFlow,
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

                        @Suppress("UNCHECKED_CAST")
                        val transportNodeEnabled = flows[9] as Boolean

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
                            // Preserve state from availability monitor
                            sharedInstanceOnline = _state.value.sharedInstanceOnline,
                            sharedInstanceAvailable = _state.value.sharedInstanceAvailable,
                            wasUsingSharedInstance = _state.value.wasUsingSharedInstance,
                            // Preserve relay state from startRelayMonitor()
                            currentRelayName = _state.value.currentRelayName,
                            currentRelayHops = _state.value.currentRelayHops,
                            autoSelectPropagationNode = _state.value.autoSelectPropagationNode,
                            // Transport node state
                            transportNodeEnabled = transportNodeEnabled,
                        )
                    }.distinctUntilChanged().collect { newState ->
                        val previousState = _state.value

                        // Initialize sharedInstanceOnline on first load:
                        // If isSharedInstance is true at startup, the shared instance was online when we connected
                        val isFirstLoadWithSharedInstance =
                            previousState.isLoading && !newState.isLoading && newState.isSharedInstance
                        val needsOnlineInit = isFirstLoadWithSharedInstance && !newState.sharedInstanceOnline
                        val initializedOnline =
                            if (needsOnlineInit) {
                                Log.d(
                                    TAG,
                                    "Initializing sharedInstanceOnline=true since isSharedInstance=true at startup",
                                )
                                true
                            } else {
                                newState.sharedInstanceOnline
                            }

                        // Log state for debugging
                        if (previousState.isSharedInstance != newState.isSharedInstance) {
                            Log.i(
                                TAG,
                                "isSharedInstance changed: ${previousState.isSharedInstance} -> ${newState.isSharedInstance}, " +
                                    "preferOwnInstance=${newState.preferOwnInstance}",
                            )
                        }

                        // Detect transition: was using shared instance, now not, and user didn't choose this
                        // This indicates the shared instance went offline and RNS auto-switched
                        val wasUsingShared =
                            if (previousState.isSharedInstance &&
                                !newState.isSharedInstance &&
                                !newState.preferOwnInstance // User didn't actively choose own instance
                            ) {
                                Log.i(
                                    TAG,
                                    "Detected shared instance went offline - " +
                                        "Columba auto-switched to own instance",
                                )
                                true
                            } else {
                                // Keep current value unless shared comes back online
                                previousState.wasUsingSharedInstance
                            }

                        _state.value =
                            newState.copy(
                                sharedInstanceOnline = initializedOnline,
                                wasUsingSharedInstance = wasUsingShared,
                            )
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

        /**
         * Restart service after shared instance went offline.
         * Called automatically when we detect shared instance is no longer available.
         * After restart, Python will detect no shared instance and use Columba's own interfaces.
         * Keeps wasUsingSharedInstance = true to show informational banner after restart.
         */
        private fun restartServiceAfterSharedInstanceLost() {
            viewModelScope.launch {
                try {
                    Log.i(TAG, "Auto-restarting service after shared instance went offline")

                    // Set preferOwnInstance = true since we're now using own instance
                    // This ensures the toggle shows the correct state after restart
                    settingsRepository.savePreferOwnInstance(true)

                    // Use InterfaceConfigManager which handles the full restart lifecycle
                    // Python will check for shared instance, find it offline, and use own interfaces
                    interfaceConfigManager.applyInterfaceChanges()
                        .onSuccess {
                            Log.i(TAG, "Service restart completed - now using Columba's own instance")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Service restart failed: ${error.message}", error)
                        }
                        .getOrThrow()

                    // Keep wasUsingSharedInstance = true to show informational banner
                    _state.value =
                        _state.value.copy(
                            isRestarting = false,
                            wasUsingSharedInstance = true,
                        )
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting service after shared instance lost", e)
                    _state.value =
                        _state.value.copy(
                            isRestarting = false,
                            wasUsingSharedInstance = true,
                        )
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
         * Tracks network status changes for logging purposes.
         *
         * Note: The actual detection of shared instance going offline is now handled
         * in loadSettings() combine flow, which detects when isSharedInstance transitions
         * from true to false while sharedInstanceOnline is false.
         */
        private fun startSharedInstanceMonitor() {
            sharedInstanceMonitorJob?.cancel()
            sharedInstanceMonitorJob =
                viewModelScope.launch {
                    // Wait for initial setup
                    delay(INIT_DELAY_MS * 2)

                    // Monitor the service's network status for logging
                    reticulumProtocol.networkStatus.collect { status ->
                        val currentState = _state.value

                        // Only log when we're using a shared instance
                        if (currentState.isSharedInstance && !currentState.preferOwnInstance) {
                            val isConnected = status is NetworkStatus.READY

                            if (!isConnected) {
                                if (sharedInstanceDisconnectedTime == null) {
                                    sharedInstanceDisconnectedTime = System.currentTimeMillis()
                                    Log.d(TAG, "Shared instance network status: $status")
                                }
                            } else {
                                // Connection restored
                                if (sharedInstanceDisconnectedTime != null) {
                                    Log.d(TAG, "Shared instance connection restored")
                                    sharedInstanceDisconnectedTime = null
                                }
                            }
                        } else {
                            // Not in shared instance mode, reset tracking
                            sharedInstanceDisconnectedTime = null
                        }
                    }
                }
        }

        /**
         * Dismiss the shared instance lost warning without taking action.
         */
        fun dismissSharedInstanceLostWarning() {
            _state.value = _state.value.copy(wasUsingSharedInstance = false)
            // Reset timer so warning doesn't immediately reappear
            sharedInstanceDisconnectedTime = System.currentTimeMillis()
        }

        /**
         * Handle shared instance loss by switching to own instance.
         */
        fun switchToOwnInstanceAfterLoss() {
            viewModelScope.launch {
                Log.i(TAG, "User chose to switch to own instance after shared instance loss")
                _state.value = _state.value.copy(wasUsingSharedInstance = false)
                settingsRepository.savePreferOwnInstance(true)
                if (!_state.value.isRestarting) {
                    restartService()
                }
            }
        }

        /**
         * Start monitoring for shared instance availability.
         * This periodically queries the service to check if a shared instance is reachable.
         * Updates sharedInstanceOnline for toggle enable logic and sharedInstanceAvailable
         * for "newly available" notifications.
         *
         * Also clears wasUsingSharedInstance when the shared instance comes back online.
         */
        private fun startSharedInstanceAvailabilityMonitor() {
            sharedInstanceAvailabilityJob?.cancel()
            sharedInstanceAvailabilityJob =
                viewModelScope.launch {
                    // Wait for initial setup
                    delay(INIT_DELAY_MS * 4) // Give more time for service to fully start

                    while (true) {
                        val currentState = _state.value

                        // Only monitor when not restarting
                        if (!currentState.isRestarting) {
                            // Query service for shared instance availability
                            val isOnline =
                                if (reticulumProtocol is
                                        com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
                                ) {
                                    reticulumProtocol.isSharedInstanceAvailable()
                                } else {
                                    false
                                }

                            // Update sharedInstanceOnline if changed
                            if (isOnline != currentState.sharedInstanceOnline) {
                                Log.d(TAG, "Shared instance online status changed: $isOnline")

                                // Detect shared instance going offline while we were using it
                                val wasUsingShared = currentState.sharedInstanceOnline && currentState.isSharedInstance
                                val shouldAutoRestart = !isOnline && wasUsingShared && !currentState.preferOwnInstance
                                if (shouldAutoRestart) {
                                    Log.i(
                                        TAG,
                                        "Shared instance went offline while we were using it - " +
                                            "restarting with Columba's own instance",
                                    )
                                    _state.value =
                                        currentState.copy(
                                            sharedInstanceOnline = isOnline,
                                            wasUsingSharedInstance = true,
                                            isRestarting = true,
                                        )
                                    // Restart service - Python will detect no shared instance
                                    // and initialize with Columba's own interfaces
                                    restartServiceAfterSharedInstanceLost()
                                } else {
                                    _state.value = currentState.copy(sharedInstanceOnline = isOnline)
                                }
                            }

                            // Clear wasUsingSharedInstance when shared instance comes back online
                            if (isOnline && currentState.wasUsingSharedInstance) {
                                Log.i(TAG, "Shared instance is back online - clearing informational state")
                                _state.value = _state.value.copy(wasUsingSharedInstance = false)
                            }

                            // Trigger "newly available" notification if applicable
                            // (only when not currently using shared and notification not already shown)
                            if (isOnline &&
                                !currentState.isSharedInstance &&
                                !currentState.sharedInstanceAvailable
                            ) {
                                Log.i(TAG, "Shared instance became available on port $SHARED_INSTANCE_PORT")
                                _state.value = _state.value.copy(sharedInstanceAvailable = true)
                            } else if (!isOnline && currentState.sharedInstanceAvailable) {
                                // Shared instance went away, clear notification
                                Log.d(TAG, "Shared instance no longer available")
                                _state.value = _state.value.copy(sharedInstanceAvailable = false)
                            }
                        }

                        // Wait before next poll
                        delay(SHARED_INSTANCE_MONITOR_INTERVAL_MS)
                    }
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

        // Message delivery methods

        /**
         * Set the default delivery method for messages.
         * @param method "direct" or "propagated"
         */
        fun setDefaultDeliveryMethod(method: String) {
            viewModelScope.launch {
                settingsRepository.saveDefaultDeliveryMethod(method)
                _state.value = _state.value.copy(defaultDeliveryMethod = method)
                Log.d(TAG, "Default delivery method set to: $method")
            }
        }

        /**
         * Toggle the try propagation on fail setting.
         */
        fun setTryPropagationOnFail(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.saveTryPropagationOnFail(enabled)
                _state.value = _state.value.copy(tryPropagationOnFail = enabled)
                Log.d(TAG, "Try propagation on fail set to: $enabled")
            }
        }

        /**
         * Toggle auto-select propagation node setting.
         */
        fun setAutoSelectPropagationNode(autoSelect: Boolean) {
            viewModelScope.launch {
                if (autoSelect) {
                    propagationNodeManager.enableAutoSelect()
                }
                settingsRepository.saveAutoSelectPropagationNode(autoSelect)
                _state.value = _state.value.copy(autoSelectPropagationNode = autoSelect)
                Log.d(TAG, "Auto-select propagation node set to: $autoSelect")
            }
        }

        /**
         * Start observing current relay info from PropagationNodeManager.
         * Call this after init to update state with relay information.
         */
        private fun startRelayMonitor() {
            viewModelScope.launch {
                propagationNodeManager.currentRelay.collect { relayInfo ->
                    _state.value =
                        _state.value.copy(
                            currentRelayName = relayInfo?.displayName,
                            // -1 means unknown hops (relay restored without announce data)
                            currentRelayHops = relayInfo?.hops?.takeIf { it >= 0 },
                            autoSelectPropagationNode = relayInfo?.isAutoSelected ?: true,
                        )
                    if (relayInfo != null) {
                        Log.d(TAG, "Current relay updated: ${relayInfo.displayName} (${relayInfo.hops} hops)")
                    }
                }
            }

            // Monitor sync state from PropagationNodeManager
            viewModelScope.launch {
                propagationNodeManager.isSyncing.collect { syncing ->
                    _state.value = _state.value.copy(isSyncing = syncing)
                }
            }

            // Monitor last sync timestamp from PropagationNodeManager
            viewModelScope.launch {
                propagationNodeManager.lastSyncTimestamp.collect { timestamp ->
                    _state.value = _state.value.copy(lastSyncTimestamp = timestamp)
                }
            }

            // Monitor retrieval settings from repository
            viewModelScope.launch {
                settingsRepository.autoRetrieveEnabledFlow.collect { enabled ->
                    _state.value = _state.value.copy(autoRetrieveEnabled = enabled)
                }
            }
            viewModelScope.launch {
                settingsRepository.retrievalIntervalSecondsFlow.collect { seconds ->
                    _state.value = _state.value.copy(retrievalIntervalSeconds = seconds)
                }
            }
        }

        // Message retrieval methods

        /**
         * Toggle auto-retrieve enabled state.
         */
        fun setAutoRetrieveEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.saveAutoRetrieveEnabled(enabled)
                Log.d(TAG, "Auto-retrieve ${if (enabled) "enabled" else "disabled"}")
            }
        }

        /**
         * Set the retrieval interval in seconds.
         * @param seconds Interval in seconds (30, 60, 120, or 300)
         */
        fun setRetrievalIntervalSeconds(seconds: Int) {
            viewModelScope.launch {
                settingsRepository.saveRetrievalIntervalSeconds(seconds)
                Log.d(TAG, "Retrieval interval set to $seconds seconds")
            }
        }

        /**
         * Trigger a manual sync with the propagation node.
         */
        fun syncNow() {
            viewModelScope.launch {
                Log.d(TAG, "User triggered manual sync")
                propagationNodeManager.triggerSync()
            }
        }

        // Transport node methods

        /**
         * Set the transport node enabled setting.
         * When enabled, this device forwards traffic for the mesh network.
         * When disabled, only handles its own traffic.
         * Requires service restart to apply.
         */
        fun setTransportNodeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.saveTransportNodeEnabled(enabled)
                Log.d(TAG, "Transport node ${if (enabled) "enabled" else "disabled"}")
                // Restart service to apply the change
                if (!_state.value.isRestarting) {
                    restartService()
                }
            }
        }
    }
