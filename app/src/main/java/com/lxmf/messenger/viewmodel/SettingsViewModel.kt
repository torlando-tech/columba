package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.AvailableRelaysState
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.RelayInfo
import com.lxmf.messenger.service.TelemetryCollectorManager
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.PresetTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Enum representing all collapsible cards in the Settings screen.
 * Used to track expansion state for each card.
 */
enum class SettingsCardId {
    NETWORK,
    IDENTITY,
    PRIVACY,
    NOTIFICATIONS,
    AUTO_ANNOUNCE,
    LOCATION_SHARING,
    MAP_SOURCES,
    MESSAGE_DELIVERY,
    IMAGE_COMPRESSION,
    THEME,
    BATTERY,
    DATA_MIGRATION,
    RNODE_FLASHER,
    SHARE_COLUMBA,
    ABOUT,
    SHARED_INSTANCE_BANNER,
}

@androidx.compose.runtime.Immutable
data class SettingsState(
    val displayName: String = "",
    val defaultDisplayName: String = "",
    val isLoading: Boolean = true,
    val showSaveSuccess: Boolean = false,
    val autoAnnounceEnabled: Boolean = true,
    val autoAnnounceIntervalHours: Int = 3,
    val lastAutoAnnounceTime: Long? = null,
    val nextAutoAnnounceTime: Long? = null,
    val isManualAnnouncing: Boolean = false,
    val showManualAnnounceSuccess: Boolean = false,
    val manualAnnounceError: String? = null,
    val identityHash: String? = null,
    val destinationHash: String? = null,
    val showQrDialog: Boolean = false,
    // Profile icon settings
    val iconName: String? = null,
    // Hex RGB e.g., "FFFFFF"
    val iconForegroundColor: String? = null,
    // Hex RGB e.g., "1E88E5"
    val iconBackgroundColor: String? = null,
    val selectedTheme: AppTheme = PresetTheme.VIBRANT,
    val customThemes: List<AppTheme> = emptyList(),
    val isRestarting: Boolean = false,
    // Shared instance state
    val isSharedInstance: Boolean = false,
    val preferOwnInstance: Boolean = false,
    val isSharedInstanceBannerExpanded: Boolean = false,
    val rpcKey: String? = null,
    // True if we were using shared but it went offline
    val wasUsingSharedInstance: Boolean = false,
    // True when shared instance becomes newly available (notification)
    val sharedInstanceAvailable: Boolean = false,
    // True if shared instance is currently reachable (from service query)
    val sharedInstanceOnline: Boolean = false,
    // Message delivery state
    // "direct" or "propagated"
    val defaultDeliveryMethod: String = "direct",
    val tryPropagationOnFail: Boolean = true,
    val autoSelectPropagationNode: Boolean = true,
    val currentRelayName: String? = null,
    val currentRelayHops: Int? = null,
    val currentRelayHash: String? = null,
    val availableRelays: List<RelayInfo> = emptyList(),
    val availableRelaysLoading: Boolean = true,
    // Message retrieval state
    val autoRetrieveEnabled: Boolean = true,
    val retrievalIntervalSeconds: Int = 3600,
    val lastSyncTimestamp: Long? = null,
    val isSyncing: Boolean = false,
    // Transport node state
    val transportNodeEnabled: Boolean = true,
    // Location sharing state
    val locationSharingEnabled: Boolean = true,
    val activeSharingSessions: List<com.lxmf.messenger.service.SharingSession> = emptyList(),
    val defaultSharingDuration: String = "ONE_HOUR",
    val locationPrecisionRadius: Int = 0,
    // Notifications state
    val notificationsEnabled: Boolean = true,
    // Privacy state
    val blockUnknownSenders: Boolean = false,
    // Incoming message size limit (default 1MB)
    val incomingMessageSizeLimitKb: Int = 1024,
    // Image compression state
    val imageCompressionPreset: ImageCompressionPreset = ImageCompressionPreset.AUTO,
    /** Optimal preset based on interfaces */
    val detectedCompressionPreset: ImageCompressionPreset? = null,
    // Map source state
    val mapSourceHttpEnabled: Boolean = true,
    val mapSourceRmspEnabled: Boolean = false,
    val rmspServerCount: Int = 0,
    val hasOfflineMaps: Boolean = false,
    // Telemetry collector state
    val telemetryCollectorEnabled: Boolean = false,
    val telemetryCollectorAddress: String? = null,
    val telemetrySendIntervalSeconds: Int = 300,
    val lastTelemetrySendTime: Long? = null,
    val isSendingTelemetry: Boolean = false,
    // Telemetry request state
    val telemetryRequestEnabled: Boolean = false,
    val telemetryRequestIntervalSeconds: Int = 900,
    val lastTelemetryRequestTime: Long? = null,
    val isRequestingTelemetry: Boolean = false,
    // Telemetry host mode (acting as collector for others)
    val telemetryHostModeEnabled: Boolean = false,
    // Allowed requesters for host mode (empty = allow all)
    val telemetryAllowedRequesters: Set<String> = emptySet(),
    // Contacts (for allowed requesters picker)
    val contacts: List<EnrichedContact> = emptyList(),
    // Protocol versions (for About screen)
    val reticulumVersion: String? = null,
    val lxmfVersion: String? = null,
    val bleReticulumVersion: String? = null,
    // Card expansion states (all collapsed by default)
    val cardExpansionStates: Map<String, Boolean> =
        SettingsCardId.entries.associate { it.name to false },
)

@Suppress("TooManyFunctions", "LargeClass") // ViewModel with many user interaction methods is expected
@HiltViewModel
class SettingsViewModel
    @Suppress("LongParameterList") // ViewModel with many DI dependencies is expected
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val identityRepository: IdentityRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val interfaceConfigManager: com.lxmf.messenger.service.InterfaceConfigManager,
        private val propagationNodeManager: PropagationNodeManager,
        private val locationSharingManager: com.lxmf.messenger.service.LocationSharingManager,
        private val interfaceRepository: InterfaceRepository,
        private val mapTileSourceManager: MapTileSourceManager,
        private val telemetryCollectorManager: TelemetryCollectorManager,
        private val contactRepository: ContactRepository,
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

        // Track group telemetry state before toggle-off so we can restore it on toggle-on
        private var groupTelemetryWasEnabled = false

        // Track when we first noticed shared instance disconnected
        private var sharedInstanceDisconnectedTime: Long? = null
        private var sharedInstanceMonitorJob: Job? = null
        private var sharedInstanceAvailabilityJob: Job? = null

        init {
            loadSettings()
            // Always load location sharing settings (not dependent on monitors)
            loadLocationSharingSettings()
            loadImageCompressionSettings()
            // Load map source settings
            loadMapSourceSettings()
            // Load notifications enabled setting
            loadNotificationsSettings()
            // Load privacy settings
            loadPrivacySettings()
            // Load protocol versions for About screen
            fetchProtocolVersions()
            loadTelemetryCollectorSettings()
            // Load contacts for allowed requesters picker
            loadContacts()
            // Always start sync state monitoring (no infinite loops, needed for UI)
            startSyncStateMonitor()
            if (enableMonitors) {
                startSharedInstanceMonitor()
                startSharedInstanceAvailabilityMonitor()
                startRelayMonitor()
                startLocationSharingMonitor()
                startTelemetryCollectorMonitor()
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
                        settingsRepository
                            .getAllCustomThemes()
                            .map { themeDataList ->
                                themeDataList.map { themeData ->
                                    settingsRepository.customThemeDataToAppTheme(themeData)
                                }
                            }

                    combine(
                        identityRepository.activeIdentity,
                        settingsRepository.autoAnnounceEnabledFlow,
                        settingsRepository.autoAnnounceIntervalHoursFlow,
                        settingsRepository.lastAutoAnnounceTimeFlow,
                        settingsRepository.nextAutoAnnounceTimeFlow,
                        settingsRepository.themePreferenceFlow,
                        customThemesFlow,
                        settingsRepository.preferOwnInstanceFlow,
                        settingsRepository.isSharedInstanceFlow,
                        settingsRepository.rpcKeyFlow,
                        settingsRepository.transportNodeEnabledFlow,
                        settingsRepository.defaultDeliveryMethodFlow,
                        // Sync state flows - included here to avoid race conditions with separate collectors
                        // Note: isSyncing is excluded because it changes rapidly (true→false in ms)
                        // and would cause excessive recomposition. It's handled separately.
                        propagationNodeManager.lastSyncTimestamp,
                        settingsRepository.autoRetrieveEnabledFlow,
                        settingsRepository.retrievalIntervalSecondsFlow,
                    ) { flows ->
                        @Suppress("UNCHECKED_CAST")
                        val activeIdentity = flows[0] as com.lxmf.messenger.data.db.entity.LocalIdentityEntity?

                        @Suppress("UNCHECKED_CAST")
                        val autoAnnounceEnabled = flows[1] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val intervalHours = flows[2] as Int

                        @Suppress("UNCHECKED_CAST")
                        val lastAnnounceTime = flows[3] as Long?

                        @Suppress("UNCHECKED_CAST")
                        val nextAnnounceTime = flows[4] as Long?

                        @Suppress("UNCHECKED_CAST")
                        val selectedTheme = flows[5] as AppTheme

                        @Suppress("UNCHECKED_CAST")
                        val customThemes = flows[6] as List<AppTheme>

                        @Suppress("UNCHECKED_CAST")
                        val preferOwnInstance = flows[7] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val isSharedInstance = flows[8] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val rpcKey = flows[9] as String?

                        @Suppress("UNCHECKED_CAST")
                        val transportNodeEnabled = flows[10] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val defaultDeliveryMethod = flows[11] as String

                        // Sync state from flows (not preserved from _state.value to avoid races)
                        // Note: isSyncing is handled separately to avoid rapid recomposition
                        @Suppress("UNCHECKED_CAST")
                        val lastSyncTimestamp = flows[12] as Long?

                        @Suppress("UNCHECKED_CAST")
                        val autoRetrieveEnabled = flows[13] as Boolean

                        @Suppress("UNCHECKED_CAST")
                        val retrievalIntervalSeconds = flows[14] as Int

                        val displayName = activeIdentity?.displayName ?: defaultName

                        SettingsState(
                            displayName = displayName,
                            defaultDisplayName = defaultName,
                            isLoading = false,
                            showSaveSuccess = _state.value.showSaveSuccess,
                            autoAnnounceEnabled = autoAnnounceEnabled,
                            autoAnnounceIntervalHours = intervalHours,
                            lastAutoAnnounceTime = lastAnnounceTime,
                            nextAutoAnnounceTime = nextAnnounceTime,
                            isManualAnnouncing = _state.value.isManualAnnouncing,
                            showManualAnnounceSuccess = _state.value.showManualAnnounceSuccess,
                            manualAnnounceError = _state.value.manualAnnounceError,
                            identityHash = identityInfo.first,
                            destinationHash = identityInfo.second,
                            showQrDialog = _state.value.showQrDialog,
                            // Profile icon from active identity
                            iconName = activeIdentity?.iconName,
                            iconForegroundColor = activeIdentity?.iconForegroundColor,
                            iconBackgroundColor = activeIdentity?.iconBackgroundColor,
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
                            currentRelayHash = _state.value.currentRelayHash,
                            autoSelectPropagationNode = _state.value.autoSelectPropagationNode,
                            availableRelays = _state.value.availableRelays,
                            availableRelaysLoading = _state.value.availableRelaysLoading,
                            // Transport node state
                            transportNodeEnabled = transportNodeEnabled,
                            // Message delivery state
                            defaultDeliveryMethod = defaultDeliveryMethod,
                            // Sync state from flows (included in combine to avoid race conditions)
                            autoRetrieveEnabled = autoRetrieveEnabled,
                            retrievalIntervalSeconds = retrievalIntervalSeconds,
                            lastSyncTimestamp = lastSyncTimestamp,
                            // isSyncing handled separately to avoid rapid recomposition
                            isSyncing = _state.value.isSyncing,
                            // Preserve location sharing state from loadLocationSharingSettings()
                            locationSharingEnabled = _state.value.locationSharingEnabled,
                            activeSharingSessions = _state.value.activeSharingSessions,
                            defaultSharingDuration = _state.value.defaultSharingDuration,
                            locationPrecisionRadius = _state.value.locationPrecisionRadius,
                            // Preserve map source state from loadMapSourceSettings()
                            mapSourceHttpEnabled = _state.value.mapSourceHttpEnabled,
                            mapSourceRmspEnabled = _state.value.mapSourceRmspEnabled,
                            rmspServerCount = _state.value.rmspServerCount,
                            hasOfflineMaps = _state.value.hasOfflineMaps,
                            // Preserve telemetry collector state from loadTelemetryCollectorSettings()
                            telemetryCollectorEnabled = _state.value.telemetryCollectorEnabled,
                            telemetryCollectorAddress = _state.value.telemetryCollectorAddress,
                            telemetrySendIntervalSeconds = _state.value.telemetrySendIntervalSeconds,
                            lastTelemetrySendTime = _state.value.lastTelemetrySendTime,
                            isSendingTelemetry = _state.value.isSendingTelemetry,
                            // Preserve telemetry request state from loadTelemetryCollectorSettings()
                            telemetryRequestEnabled = _state.value.telemetryRequestEnabled,
                            telemetryRequestIntervalSeconds = _state.value.telemetryRequestIntervalSeconds,
                            lastTelemetryRequestTime = _state.value.lastTelemetryRequestTime,
                            isRequestingTelemetry = _state.value.isRequestingTelemetry,
                            // Preserve telemetry host mode state from loadTelemetryCollectorSettings()
                            telemetryHostModeEnabled = _state.value.telemetryHostModeEnabled,
                            // Preserve allowed requesters and contacts from loadTelemetryCollectorSettings()
                            telemetryAllowedRequesters = _state.value.telemetryAllowedRequesters,
                            contacts = _state.value.contacts,
                            // Preserve notifications state from loadNotificationsSettings()
                            notificationsEnabled = _state.value.notificationsEnabled,
                            // Preserve protocol versions from fetchProtocolVersions()
                            reticulumVersion = _state.value.reticulumVersion,
                            lxmfVersion = _state.value.lxmfVersion,
                            bleReticulumVersion = _state.value.bleReticulumVersion,
                            // Preserve card expansion states
                            cardExpansionStates = _state.value.cardExpansionStates,
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
                            "Settings updated: displayName=${newState.displayName}, " +
                                "autoAnnounce=${newState.autoAnnounceEnabled}, " +
                                "interval=${newState.autoAnnounceIntervalHours}h, " +
                                "theme=${newState.selectedTheme}, " +
                                "customThemes=${newState.customThemes.size}",
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

        private fun fetchProtocolVersions() {
            viewModelScope.launch {
                try {
                    // Add initial delay to allow Reticulum service to fully initialize
                    delay(INIT_DELAY_MS * 4)

                    var rnsVersion: String? = null
                    var lxmfVer: String? = null
                    var bleVer: String? = null

                    // Retry up to 3 times if versions are null (service might not be bound yet)
                    repeat(3) { attempt ->
                        rnsVersion = reticulumProtocol.getReticulumVersion()
                        lxmfVer = reticulumProtocol.getLxmfVersion()
                        bleVer = reticulumProtocol.getBleReticulumVersion()

                        if (rnsVersion != null || lxmfVer != null || bleVer != null) {
                            return@repeat
                        }

                        if (attempt < 2) {
                            delay(1000)
                        }
                    }

                    _state.update {
                        it.copy(
                            reticulumVersion = rnsVersion,
                            lxmfVersion = lxmfVer,
                            bleReticulumVersion = bleVer,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch protocol versions", e)
                }
            }
        }

        fun updateDisplayName(newDisplayName: String) {
            viewModelScope.launch {
                try {
                    // Get active identity
                    val activeIdentity = identityRepository.getActiveIdentitySync()

                    if (activeIdentity != null) {
                        // Update the display name in the database
                        val nameToSave = newDisplayName.trim()
                        identityRepository
                            .updateDisplayName(activeIdentity.identityHash, nameToSave)
                            .onSuccess {
                                Log.d(TAG, "Display name updated successfully to: $nameToSave")
                                // Show success message
                                _state.value = _state.value.copy(showSaveSuccess = true)
                            }.onFailure { error ->
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
         * Update the icon appearance (icon, foreground color, background color) for the active identity.
         *
         * @param iconName Material Design Icon name (e.g., "account", "star"), or null to clear
         * @param foregroundColor Hex RGB color for icon foreground (e.g., "FFFFFF"), or null to clear
         * @param backgroundColor Hex RGB color for icon background (e.g., "1E88E5"), or null to clear
         */
        fun updateIconAppearance(
            iconName: String?,
            foregroundColor: String?,
            backgroundColor: String?,
        ) {
            viewModelScope.launch {
                try {
                    val activeIdentity = identityRepository.getActiveIdentitySync()

                    if (activeIdentity != null) {
                        identityRepository
                            .updateIconAppearance(
                                activeIdentity.identityHash,
                                iconName,
                                foregroundColor,
                                backgroundColor,
                            ).onSuccess {
                                Log.d(TAG, "Icon appearance updated successfully")
                                _state.value = _state.value.copy(showSaveSuccess = true)
                            }.onFailure { error ->
                                Log.e(TAG, "Failed to update icon appearance", error)
                            }
                    } else {
                        Log.w(TAG, "Cannot update icon appearance - no active identity")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating icon appearance", e)
                }
            }
        }

        /**
         * Get the effective display name (custom or default) to be used in announces and messages.
         */
        fun getEffectiveDisplayName(): String = state.value.displayName

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
         * Update the auto-announce interval in hours.
         */
        fun setAnnounceInterval(hours: Int) {
            viewModelScope.launch {
                settingsRepository.saveAutoAnnounceIntervalHours(hours)
                Log.d(TAG, "Auto-announce interval set to $hours hours")
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
                    interfaceConfigManager
                        .applyInterfaceChanges()
                        .onSuccess {
                            Log.i(TAG, "Service restart completed successfully")
                        }.onFailure { error ->
                            Log.e(TAG, "Service restart failed: ${error.message}", error)
                        }.getOrThrow() // Convert failure to exception for catch block

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
                    interfaceConfigManager
                        .applyInterfaceChanges()
                        .onSuccess {
                            Log.i(TAG, "Service restart completed - now using Columba's own instance")
                        }.onFailure { error ->
                            Log.e(TAG, "Service restart failed: ${error.message}", error)
                        }.getOrThrow()

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
         * Toggle the expansion state of a settings card.
         *
         * @param cardId The card to toggle
         * @param expanded Whether the card should be expanded
         */
        fun toggleCardExpanded(
            cardId: SettingsCardId,
            expanded: Boolean,
        ) {
            _state.update {
                it.copy(
                    cardExpansionStates = it.cardExpansionStates + (cardId.name to expanded),
                )
            }
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

                        // Only monitor when not restarting and network is ready
                        val networkReady = reticulumProtocol.networkStatus.value is NetworkStatus.READY
                        if (!currentState.isRestarting && networkReady) {
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
         * Manually add a propagation node by destination hash.
         * Used when user enters a relay hash directly.
         *
         * @param destinationHash 32-character hex destination hash (already validated)
         * @param nickname Optional display name for this relay
         */
        fun addManualPropagationNode(
            destinationHash: String,
            nickname: String?,
        ) {
            viewModelScope.launch {
                propagationNodeManager.setManualRelayByHash(destinationHash, nickname)
                Log.d(TAG, "Manual propagation node added: $destinationHash")
            }
        }

        /**
         * Select a relay from the available relays list.
         * Used when user taps on the current relay card and selects a different one.
         */
        fun selectRelay(destinationHash: String) {
            viewModelScope.launch {
                propagationNodeManager.setManualRelay(destinationHash)
                Log.d(TAG, "Relay selected from list: ${destinationHash.take(16)}")
            }
        }

        /**
         * Start observing current relay info from PropagationNodeManager.
         * Call this after init to update state with relay information.
         *
         * Uses combine to observe both the relay state AND the auto-select setting.
         * This fixes a bug where when relayInfo was null, the auto-select state
         * would incorrectly default to true instead of reading from the actual setting.
         */
        @OptIn(FlowPreview::class)
        private fun startRelayMonitor() {
            viewModelScope.launch {
                // Combine relay info with the actual auto-select setting from DataStore
                // This ensures UI state is correct even when no relay is selected
                combine(
                    propagationNodeManager.currentRelay,
                    settingsRepository.autoSelectPropagationNodeFlow,
                ) { relayInfo, isAutoSelect ->
                    relayInfo to isAutoSelect
                }
                    // Add debouncing to prevent feedback loops from rapid relay state changes
                    // This ensures the UI doesn't trigger cascading updates during auto-selection
                    .debounce(300) // 300ms debounce - enough to break loops without affecting UX
                    .distinctUntilChanged { old, new ->
                        // Filter out emissions when relay info hasn't changed
                        val (oldRelay, oldAutoSelect) = old
                        val (newRelay, newAutoSelect) = new

                        oldAutoSelect == newAutoSelect &&
                            oldRelay?.destinationHash == newRelay?.destinationHash &&
                            oldRelay?.displayName == newRelay?.displayName &&
                            oldRelay?.hops == newRelay?.hops
                    }.collect { (relayInfo, isAutoSelect) ->
                        _state.value =
                            _state.value.copy(
                                currentRelayName = relayInfo?.displayName,
                                // -1 means unknown hops (relay restored without announce data)
                                currentRelayHops = relayInfo?.hops?.takeIf { it >= 0 },
                                currentRelayHash = relayInfo?.destinationHash,
                                // Use the actual setting from DataStore, not a default
                                autoSelectPropagationNode = isAutoSelect,
                            )
                        if (relayInfo != null) {
                            Log.d(TAG, "Current relay updated: ${relayInfo.displayName} (${relayInfo.hops} hops, autoSelect=$isAutoSelect)")
                        } else {
                            Log.d(TAG, "No relay selected (autoSelect=$isAutoSelect)")
                        }
                    }
            }
        }

        /**
         * Start monitoring available relays and isSyncing state.
         * Note: lastSyncTimestamp, autoRetrieveEnabled, and retrievalIntervalSeconds
         * are included in the main loadSettings() combine to avoid race conditions.
         * isSyncing is kept separate because it changes rapidly (true→false in ms).
         */
        private fun startSyncStateMonitor() {
            // Monitor available relays for selection UI
            viewModelScope.launch {
                propagationNodeManager.availableRelaysState
                    .distinctUntilChanged { old, new ->
                        // Only update when the list content actually changes
                        when {
                            old is AvailableRelaysState.Loading && new is AvailableRelaysState.Loading -> true
                            old is AvailableRelaysState.Loaded && new is AvailableRelaysState.Loaded ->
                                old.relays.size == new.relays.size &&
                                    old.relays.zip(new.relays).all { (oldRelay, newRelay) ->
                                        oldRelay.destinationHash == newRelay.destinationHash &&
                                            oldRelay.displayName == newRelay.displayName &&
                                            oldRelay.hops == newRelay.hops
                                    }
                            else -> false
                        }
                    }.collect { state ->
                        when (state) {
                            is AvailableRelaysState.Loading -> {
                                Log.d(TAG, "SettingsViewModel: available relays loading")
                                _state.update { it.copy(availableRelaysLoading = true) }
                            }
                            is AvailableRelaysState.Loaded -> {
                                Log.d(TAG, "SettingsViewModel received ${state.relays.size} available relays")
                                _state.update {
                                    it.copy(
                                        availableRelays = state.relays,
                                        availableRelaysLoading = false,
                                    )
                                }
                            }
                        }
                    }
            }

            // Monitor isSyncing separately - it changes rapidly and shouldn't trigger
            // full state recomposition via the main combine
            viewModelScope.launch {
                propagationNodeManager.isSyncing.collect { syncing ->
                    _state.update { it.copy(isSyncing = syncing) }
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

        // Notifications methods

        /**
         * Load notifications settings from the repository.
         * Subscribes to flow updates so the card stays synced with the Notifications page.
         */
        private fun loadNotificationsSettings() {
            viewModelScope.launch {
                settingsRepository.notificationsEnabledFlow.collect { enabled ->
                    _state.update { it.copy(notificationsEnabled = enabled) }
                }
            }
        }

        /**
         * Set the notifications enabled setting.
         * When disabled, all notifications are suppressed.
         */
        fun setNotificationsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.saveNotificationsEnabled(enabled)
                _state.update { it.copy(notificationsEnabled = enabled) }
                Log.d(TAG, "Notifications ${if (enabled) "enabled" else "disabled"}")
            }
        }

        // Privacy methods

        /**
         * Load privacy settings from the repository.
         * Subscribes to flow updates so the card stays synced.
         */
        private fun loadPrivacySettings() {
            viewModelScope.launch {
                settingsRepository.blockUnknownSendersFlow.collect { enabled ->
                    _state.update { it.copy(blockUnknownSenders = enabled) }
                }
            }
        }

        /**
         * Set the block unknown senders setting.
         * When enabled, messages from senders not in contacts are silently discarded.
         */
        fun setBlockUnknownSenders(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.saveBlockUnknownSenders(enabled)
                _state.update { it.copy(blockUnknownSenders = enabled) }
                Log.d(TAG, "Block unknown senders ${if (enabled) "enabled" else "disabled"}")
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

        // Location sharing methods

        /**
         * Load location sharing settings from the repository.
         * Called unconditionally to ensure settings persist across navigation.
         */
        private fun loadLocationSharingSettings() {
            // Location sharing toggle reflects EITHER individual sharing OR group telemetry send
            viewModelScope.launch {
                combine(
                    settingsRepository.locationSharingEnabledFlow,
                    telemetryCollectorManager.isEnabled,
                ) { individualEnabled, groupSendEnabled ->
                    individualEnabled || groupSendEnabled
                }.collect { combined ->
                    _state.update { it.copy(locationSharingEnabled = combined) }
                }
            }
            viewModelScope.launch {
                settingsRepository.defaultSharingDurationFlow.collect { duration ->
                    _state.update { it.copy(defaultSharingDuration = duration) }
                }
            }
            viewModelScope.launch {
                settingsRepository.locationPrecisionRadiusFlow.collect { radiusMeters ->
                    _state.update { it.copy(locationPrecisionRadius = radiusMeters) }
                }
            }
            viewModelScope.launch {
                settingsRepository.incomingMessageSizeLimitKbFlow.collect { limitKb ->
                    _state.update { it.copy(incomingMessageSizeLimitKb = limitKb) }
                }
            }
        }

        /**
         * Start monitoring active location sharing sessions from the LocationSharingManager.
         * Only called when monitors are enabled.
         */
        private fun startLocationSharingMonitor() {
            viewModelScope.launch {
                locationSharingManager.activeSessions.collect { sessions ->
                    _state.update { it.copy(activeSharingSessions = sessions) }
                }
            }
        }

        /**
         * Set the location sharing enabled setting.
         * When disabled, stops all active sharing sessions.
         */
        fun setLocationSharingEnabled(enabled: Boolean) {
            viewModelScope.launch {
                if (!enabled) {
                    // Save group telemetry state before disabling so we can restore on re-enable
                    groupTelemetryWasEnabled = telemetryCollectorManager.isEnabled.value
                    stopAllSharing()
                    telemetryCollectorManager.setEnabled(false)
                } else if (groupTelemetryWasEnabled) {
                    // Restore group telemetry if it was enabled before the toggle was turned off
                    telemetryCollectorManager.setEnabled(true)
                    groupTelemetryWasEnabled = false
                }
                settingsRepository.saveLocationSharingEnabled(enabled)
                Log.d(TAG, "Location sharing ${if (enabled) "enabled" else "disabled"}")
            }
        }

        /**
         * Stop sharing with a specific contact.
         *
         * @param destinationHash The contact to stop sharing with
         */
        fun stopSharingWith(destinationHash: String) {
            locationSharingManager.stopSharing(destinationHash)
            Log.d(TAG, "Stopped location sharing with $destinationHash")
        }

        /**
         * Stop all active location sharing sessions.
         */
        fun stopAllSharing() {
            locationSharingManager.stopSharing(null)
            Log.d(TAG, "Stopped all location sharing sessions")
        }

        /**
         * Set the default sharing duration.
         *
         * @param duration The SharingDuration enum name (e.g., "ONE_HOUR", "FOUR_HOURS")
         */
        fun setDefaultSharingDuration(duration: String) {
            viewModelScope.launch {
                settingsRepository.saveDefaultSharingDuration(duration)
                Log.d(TAG, "Default sharing duration set to: $duration")
            }
        }

        /**
         * Set the location precision radius.
         *
         * @param radiusMeters 0 for precise, or coarsening radius in meters (100, 1000, 10000, etc.)
         */
        fun setLocationPrecisionRadius(radiusMeters: Int) {
            viewModelScope.launch {
                settingsRepository.saveLocationPrecisionRadius(radiusMeters)
                Log.d(TAG, "Location precision radius set to: ${radiusMeters}m")
                // Send immediate update to all active sharing recipients with new precision
                locationSharingManager.sendImmediateUpdate()
            }
        }

        // Incoming message size limit methods

        /**
         * Set the incoming message size limit.
         * This controls the maximum size of LXMF messages that can be received.
         * Messages exceeding this limit will be rejected by the LXMF router.
         *
         * @param limitKb Size limit in KB (512 to 131072, representing 0.5MB to 128MB)
         */
        fun setIncomingMessageSizeLimit(limitKb: Int) {
            viewModelScope.launch {
                settingsRepository.saveIncomingMessageSizeLimitKb(limitKb)
                Log.d(TAG, "Incoming message size limit set to: ${limitKb}KB")

                // Apply the change at runtime via the protocol
                if (reticulumProtocol is com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol) {
                    reticulumProtocol.setIncomingMessageSizeLimit(limitKb)
                }
            }
        }

        // Image compression methods

        /**
         * Load image compression settings and start monitoring for changes.
         */
        private fun loadImageCompressionSettings() {
            viewModelScope.launch {
                // Load saved preset
                settingsRepository.imageCompressionPresetFlow.collect { preset ->
                    _state.value = _state.value.copy(imageCompressionPreset = preset)
                    Log.d(TAG, "Image compression preset loaded: ${preset.name}")

                    // Update detected preset when AUTO is selected
                    if (preset == ImageCompressionPreset.AUTO) {
                        updateDetectedPreset()
                    }
                }
            }

            // Also monitor interface changes to update detected preset
            viewModelScope.launch {
                interfaceRepository.enabledInterfaces.collect {
                    if (_state.value.imageCompressionPreset == ImageCompressionPreset.AUTO) {
                        updateDetectedPreset()
                    }
                }
            }
        }

        /**
         * Update the detected optimal preset.
         * Since link speed probing now determines the preset at send time,
         * we use MEDIUM as a reasonable default for display purposes.
         */
        private fun updateDetectedPreset() {
            val detected = ImageCompressionPreset.MEDIUM
            _state.value = _state.value.copy(detectedCompressionPreset = detected)
            Log.d(TAG, "Default compression preset: ${detected.name}")
        }

        /**
         * Set the image compression preset.
         *
         * @param preset The compression preset to use
         */
        fun setImageCompressionPreset(preset: ImageCompressionPreset) {
            viewModelScope.launch {
                settingsRepository.saveImageCompressionPreset(preset)
                Log.d(TAG, "Image compression preset set to: ${preset.name}")

                // Update detected preset if AUTO is selected
                if (preset == ImageCompressionPreset.AUTO) {
                    updateDetectedPreset()
                } else {
                    _state.value = _state.value.copy(detectedCompressionPreset = null)
                }
            }
        }

        /**
         * Get the effective compression preset (resolves AUTO to detected).
         */
        fun getEffectiveCompressionPreset(): ImageCompressionPreset {
            val current = _state.value.imageCompressionPreset
            return if (current == ImageCompressionPreset.AUTO) {
                _state.value.detectedCompressionPreset ?: ImageCompressionPreset.HIGH
            } else {
                current
            }
        }

        // Map source methods

        /**
         * Load map source settings from the repository.
         */
        private fun loadMapSourceSettings() {
            viewModelScope.launch {
                settingsRepository.mapSourceHttpEnabledFlow.collect { enabled ->
                    _state.update { it.copy(mapSourceHttpEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                settingsRepository.mapSourceRmspEnabledFlow.collect { enabled ->
                    _state.update { it.copy(mapSourceRmspEnabled = enabled) }
                }
            }
            // Collect RMSP server count from MapTileSourceManager
            viewModelScope.launch {
                mapTileSourceManager.observeRmspServerCount().collect { count ->
                    Log.d(TAG, "RMSP server count updated: $count")
                    _state.update { it.copy(rmspServerCount = count) }
                }
            }
            // Collect offline maps availability
            viewModelScope.launch {
                mapTileSourceManager.hasOfflineMaps().collect { hasOffline ->
                    Log.d(TAG, "Has offline maps updated: $hasOffline")
                    _state.update { it.copy(hasOfflineMaps = hasOffline) }
                }
            }
        }

        /**
         * Set the HTTP map source enabled setting.
         *
         * @param enabled Whether HTTP map source is enabled
         */
        fun setMapSourceHttpEnabled(enabled: Boolean) {
            // Allow disabling HTTP - MapScreen now shows a helpful overlay when no sources enabled
            viewModelScope.launch {
                settingsRepository.saveMapSourceHttpEnabled(enabled)
                Log.d(TAG, "HTTP map source ${if (enabled) "enabled" else "disabled"}")
            }
        }

        /**
         * Set the RMSP map source enabled setting.
         *
         * @param enabled Whether RMSP map source is enabled
         */
        fun setMapSourceRmspEnabled(enabled: Boolean) {
            // Prevent disabling both sources (unless offline maps are available)
            if (!enabled && !_state.value.mapSourceHttpEnabled && !_state.value.hasOfflineMaps) {
                Log.w(TAG, "Cannot disable RMSP when HTTP is also disabled and no offline maps")
                return
            }
            viewModelScope.launch {
                settingsRepository.saveMapSourceRmspEnabled(enabled)
                Log.d(TAG, "RMSP map source ${if (enabled) "enabled" else "disabled"}")
            }
        }

        // Telemetry collector methods

        /**
         * Load telemetry collector settings from the repository.
         */
        private fun loadTelemetryCollectorSettings() {
            viewModelScope.launch {
                settingsRepository.telemetryCollectorEnabledFlow.collect { enabled ->
                    _state.update { it.copy(telemetryCollectorEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                settingsRepository.telemetryCollectorAddressFlow.collect { address ->
                    _state.update { it.copy(telemetryCollectorAddress = address) }
                }
            }
            viewModelScope.launch {
                settingsRepository.telemetrySendIntervalSecondsFlow.collect { interval ->
                    _state.update { it.copy(telemetrySendIntervalSeconds = interval) }
                }
            }
            viewModelScope.launch {
                settingsRepository.lastTelemetrySendTimeFlow.collect { timestamp ->
                    _state.update { it.copy(lastTelemetrySendTime = timestamp) }
                }
            }
            // Request settings
            viewModelScope.launch {
                settingsRepository.telemetryRequestEnabledFlow.collect { enabled ->
                    _state.update { it.copy(telemetryRequestEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                settingsRepository.telemetryRequestIntervalSecondsFlow.collect { interval ->
                    _state.update { it.copy(telemetryRequestIntervalSeconds = interval) }
                }
            }
            viewModelScope.launch {
                settingsRepository.lastTelemetryRequestTimeFlow.collect { timestamp ->
                    _state.update { it.copy(lastTelemetryRequestTime = timestamp) }
                }
            }

            // Host mode
            viewModelScope.launch {
                settingsRepository.telemetryHostModeEnabledFlow.collect { enabled ->
                    _state.update { it.copy(telemetryHostModeEnabled = enabled) }
                }
            }

            // Allowed requesters for host mode
            viewModelScope.launch {
                settingsRepository.telemetryAllowedRequestersFlow.collect { allowedHashes ->
                    _state.update { it.copy(telemetryAllowedRequesters = allowedHashes) }
                }
            }
        }

        /**
         * Load contacts for the allowed requesters picker.
         */
        private fun loadContacts() {
            viewModelScope.launch {
                contactRepository.getEnrichedContacts().collect { contacts ->
                    _state.update { it.copy(contacts = contacts) }
                }
            }
        }

        /**
         * Start monitoring telemetry collector state from the manager.
         */
        private fun startTelemetryCollectorMonitor() {
            viewModelScope.launch {
                telemetryCollectorManager.isSending.collect { sending ->
                    _state.update { it.copy(isSendingTelemetry = sending) }
                }
            }
            viewModelScope.launch {
                telemetryCollectorManager.isRequesting.collect { requesting ->
                    _state.update { it.copy(isRequestingTelemetry = requesting) }
                }
            }
        }

        /**
         * Set the telemetry collector enabled state.
         * When enabled, location telemetry is automatically sent to the collector.
         */
        fun setTelemetryCollectorEnabled(enabled: Boolean) {
            viewModelScope.launch {
                telemetryCollectorManager.setEnabled(enabled)
                Log.d(TAG, "Telemetry collector ${if (enabled) "enabled" else "disabled"}")
            }
        }

        /**
         * Set the telemetry collector address.
         *
         * @param address 32-character hex destination hash, or null to clear
         */
        fun setTelemetryCollectorAddress(address: String?) {
            viewModelScope.launch {
                telemetryCollectorManager.setCollectorAddress(address)
                Log.d(TAG, "Telemetry collector address set to: ${address?.take(16) ?: "none"}")
            }
        }

        /**
         * Set the telemetry send interval.
         *
         * @param seconds Interval in seconds (minimum 60, maximum 86400)
         */
        fun setTelemetrySendInterval(seconds: Int) {
            viewModelScope.launch {
                telemetryCollectorManager.setSendIntervalSeconds(seconds)
                Log.d(TAG, "Telemetry send interval set to: ${seconds}s")
            }
        }

        /**
         * Manually trigger a telemetry send to the collector.
         */
        fun sendTelemetryNow() {
            viewModelScope.launch {
                Log.d(TAG, "User triggered manual telemetry send")
                telemetryCollectorManager.sendTelemetryNow()
            }
        }

        /**
         * Set the telemetry request enabled state.
         * When enabled, location telemetry is automatically requested from the collector.
         */
        fun setTelemetryRequestEnabled(enabled: Boolean) {
            viewModelScope.launch {
                telemetryCollectorManager.setRequestEnabled(enabled)
                Log.d(TAG, "Telemetry request ${if (enabled) "enabled" else "disabled"}")
            }
        }

        /**
         * Set the telemetry request interval.
         *
         * @param seconds Interval in seconds (minimum 60, maximum 86400)
         */
        fun setTelemetryRequestInterval(seconds: Int) {
            viewModelScope.launch {
                telemetryCollectorManager.setRequestIntervalSeconds(seconds)
                Log.d(TAG, "Telemetry request interval set to: ${seconds}s")
            }
        }

        /**
         * Manually trigger a telemetry request from the collector.
         */
        fun requestTelemetryNow() {
            viewModelScope.launch {
                Log.d(TAG, "User triggered manual telemetry request")
                telemetryCollectorManager.requestTelemetryNow()
            }
        }

        /**
         * Set the telemetry host mode enabled state.
         * When enabled, this device acts as a collector for others.
         */
        fun setTelemetryHostModeEnabled(enabled: Boolean) {
            viewModelScope.launch {
                telemetryCollectorManager.setHostModeEnabled(enabled)
                Log.d(TAG, "Telemetry host mode set to: $enabled")
            }
        }

        /**
         * Set the allowed requesters for telemetry host mode.
         * Only requesters in the set will receive responses; others will be blocked.
         * If the set is empty, all requests will be blocked.
         *
         * @param allowedHashes Set of 32-character hex identity hash strings
         */
        fun setTelemetryAllowedRequesters(allowedHashes: Set<String>) {
            Log.d(TAG, "setTelemetryAllowedRequesters called with ${allowedHashes.size} hashes: $allowedHashes")
            viewModelScope.launch {
                telemetryCollectorManager.setAllowedRequesters(allowedHashes)
                Log.d(TAG, "Telemetry allowed requesters saved: ${allowedHashes.size}")
            }
        }
    }
