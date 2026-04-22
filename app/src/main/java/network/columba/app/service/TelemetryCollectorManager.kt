package network.columba.app.service

import android.content.Context
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.model.NetworkStatus
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.util.LocationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a telemetry send operation.
 */
sealed class TelemetrySendResult {
    data object Success : TelemetrySendResult()

    data class Error(
        val message: String,
    ) : TelemetrySendResult()

    data object NoCollectorConfigured : TelemetrySendResult()

    data object NoLocationAvailable : TelemetrySendResult()

    data object NetworkNotReady : TelemetrySendResult()
}

/**
 * Result of a telemetry request operation.
 */
sealed class TelemetryRequestResult {
    data object Success : TelemetryRequestResult()

    data class Error(
        val message: String,
    ) : TelemetryRequestResult()

    data object NoCollectorConfigured : TelemetryRequestResult()

    data object NetworkNotReady : TelemetryRequestResult()
}

/**
 * Location telemetry data for sending to collector.
 */
@Serializable
private data class LocationTelemetryData(
    val lat: Double,
    val lng: Double,
    val acc: Float,
    val ts: Long,
    val altitude: Double = 0.0,
    val speed: Float = 0.0f,
    val bearing: Float = 0.0f,
)

/**
 * Manages sending location telemetry to a configured collector.
 *
 * Features:
 * - Configure collector address (32-char hex destination hash)
 * - Automatic scheduled sending at configurable intervals
 * - Manual send trigger
 * - State tracking (enabled, last send time, sending status)
 *
 * The collector can respond with FIELD_TELEMETRY_STREAM containing locations
 * from multiple sources, which are handled by the Python layer and passed
 * to LocationSharingManager for storage.
 */
@Singleton
@Suppress("TooManyFunctions") // Manager class with distinct responsibilities
class TelemetryCollectorManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val identityRepository: network.columba.app.data.repository.IdentityRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        // Only initialize FusedLocationProviderClient when Google Play Services is available
        // to avoid flooding the log with warnings on devices without GMS (issue #456)
        private val useGms = LocationCompat.isPlayServicesAvailable(context)
        private val fusedLocationClient: FusedLocationProviderClient? =
            if (useGms) LocationServices.getFusedLocationProviderClient(context) else null

        companion object {
            private const val TAG = "TelemetryCollectorManager"
            private const val DEST_HASH_LENGTH = 32
        }

        // State flows for UI observation
        private val _collectorAddress = MutableStateFlow<String?>(null)
        val collectorAddress: StateFlow<String?> = _collectorAddress.asStateFlow()

        private val _isEnabled = MutableStateFlow(false)
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

        private val _sendIntervalSeconds = MutableStateFlow(SettingsRepository.DEFAULT_TELEMETRY_SEND_INTERVAL_SECONDS)
        val sendIntervalSeconds: StateFlow<Int> = _sendIntervalSeconds.asStateFlow()

        private val _lastSendTime = MutableStateFlow<Long?>(null)
        val lastSendTime: StateFlow<Long?> = _lastSendTime.asStateFlow()

        private val _isSending = MutableStateFlow(false)
        val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

        // Request state flows
        private val _isRequestEnabled = MutableStateFlow(false)
        val isRequestEnabled: StateFlow<Boolean> = _isRequestEnabled.asStateFlow()

        private val _requestIntervalSeconds = MutableStateFlow(SettingsRepository.DEFAULT_TELEMETRY_REQUEST_INTERVAL_SECONDS)
        val requestIntervalSeconds: StateFlow<Int> = _requestIntervalSeconds.asStateFlow()

        private val _lastRequestTime = MutableStateFlow<Long?>(null)
        val lastRequestTime: StateFlow<Long?> = _lastRequestTime.asStateFlow()

        private val _isRequesting = MutableStateFlow(false)
        val isRequesting: StateFlow<Boolean> = _isRequesting.asStateFlow()

        // Host mode state (acting as collector for others)
        private val _isHostModeEnabled = MutableStateFlow(false)
        val isHostModeEnabled: StateFlow<Boolean> = _isHostModeEnabled.asStateFlow()

        // Allowed requesters for host mode (empty = block all for security)
        private val _allowedRequesters = MutableStateFlow<Set<String>>(emptySet())
        val allowedRequesters: StateFlow<Set<String>> = _allowedRequesters.asStateFlow()

        // Jobs for periodic operations
        private var settingsObserverJob: Job? = null
        private var periodicSendJob: Job? = null
        private var periodicRequestJob: Job? = null

        // Continuous location tracking (keeps a recent valid fix for background sends).
        private val locationTracker = TelemetryLocationTracker(context, useGms, fusedLocationClient)

        // Last attempt timestamps (success OR failure) used to throttle retries.
        // We keep last successful timestamps in SettingsRepository for UI/history,
        // and use these in-memory values only for scheduler pacing.
        // Initialize from persisted values to avoid immediate retry after process death.
        private var lastSendAttemptAt: Long? = _lastSendTime.value
        private var lastRequestAttemptAt: Long? = _lastRequestTime.value

        /**
         * Start the manager - begin observing settings and schedule periodic sends.
         * Call this when the Reticulum service becomes ready.
         */
        fun start() {
            if (settingsObserverJob != null) {
                Log.d(TAG, "TelemetryCollectorManager already started, skipping")
                return
            }
            Log.d(TAG, "Starting TelemetryCollectorManager")

            // Observe settings changes
            settingsObserverJob =
                scope.launch {
                    launchSendSettingsObservers()
                    launchRequestSettingsObservers()
                    launchHostModeObservers()
                }

            // Start periodic sending and requesting
            restartPeriodicSend()
            restartPeriodicRequest()
            locationTracker.update(shouldTrackLocation())
        }

        private fun CoroutineScope.launchSendSettingsObservers() {
            launch {
                settingsRepository.telemetryCollectorAddressFlow
                    .distinctUntilChanged()
                    .collect { address ->
                        // Migrate legacy truncated collector addresses: earlier versions
                        // could persist a truncated destination hash prefix. Only treat as
                        // legacy when the short address is a prefix of the local identity
                        // hash — non-Columba peers may use valid shorter representations.
                        if (address != null && address.length < DEST_HASH_LENGTH) {
                            val localDestHash = identityRepository.getActiveIdentitySync()?.destinationHash?.lowercase()
                            if (localDestHash != null && localDestHash.startsWith(address.lowercase())) {
                                Log.i(TAG, "Migrating truncated collector address (${address.length} chars) to full local destination hash")
                                settingsRepository.saveTelemetryCollectorAddress(localDestHash)
                                return@collect // The save will re-emit via the flow
                            } else {
                                Log.w(TAG, "Collector address is ${address.length} chars (expected $DEST_HASH_LENGTH) — not a local prefix, clearing")
                                settingsRepository.saveTelemetryCollectorAddress(null)
                                return@collect
                            }
                        }
                        _collectorAddress.value = address
                        Log.d(TAG, "Collector address updated: ${address?.take(16) ?: "none"}")
                        restartPeriodicSend()
                        restartPeriodicRequest()
                        locationTracker.update(shouldTrackLocation())
                    }
            }
            launch {
                settingsRepository.telemetryCollectorEnabledFlow
                    .distinctUntilChanged()
                    .collect { enabled ->
                        _isEnabled.value = enabled
                        Log.d(TAG, "Collector enabled: $enabled")
                        restartPeriodicSend()
                        locationTracker.update(shouldTrackLocation())
                    }
            }
            launch {
                settingsRepository.telemetrySendIntervalSecondsFlow
                    .distinctUntilChanged()
                    .collect { interval ->
                        _sendIntervalSeconds.value = interval
                        Log.d(TAG, "Send interval updated: ${interval}s")
                        restartPeriodicSend()
                    }
            }
            launch {
                settingsRepository.lastTelemetrySendTimeFlow
                    .distinctUntilChanged()
                    .collect { timestamp ->
                        _lastSendTime.value = timestamp
                    }
            }
        }

        private fun CoroutineScope.launchRequestSettingsObservers() {
            launch {
                settingsRepository.telemetryRequestEnabledFlow
                    .distinctUntilChanged()
                    .collect { enabled ->
                        _isRequestEnabled.value = enabled
                        Log.d(TAG, "Request enabled: $enabled")
                        restartPeriodicRequest()
                    }
            }
            launch {
                settingsRepository.telemetryRequestIntervalSecondsFlow
                    .distinctUntilChanged()
                    .collect { interval ->
                        _requestIntervalSeconds.value = interval
                        Log.d(TAG, "Request interval updated: ${interval}s")
                        restartPeriodicRequest()
                    }
            }
            launch {
                settingsRepository.lastTelemetryRequestTimeFlow
                    .distinctUntilChanged()
                    .collect { timestamp ->
                        _lastRequestTime.value = timestamp
                    }
            }
        }

        private fun CoroutineScope.launchHostModeObservers() {
            launch {
                settingsRepository.telemetryHostModeEnabledFlow
                    .distinctUntilChanged()
                    .collect { enabled ->
                        _isHostModeEnabled.value = enabled
                        Log.d(TAG, "Host mode: $enabled")
                        syncHostModeWithPython(enabled)
                    }
            }
            launch {
                settingsRepository.telemetryAllowedRequestersFlow
                    .distinctUntilChanged()
                    .collect { allowedHashes ->
                        Log.d(TAG, "telemetryAllowedRequestersFlow emitted: ${allowedHashes.size} hashes")
                        _allowedRequesters.value = allowedHashes
                        syncAllowedRequestersWithPython(allowedHashes)
                    }
            }

            // Settings can emit before Reticulum network reaches READY.
            // Re-apply current host-mode + allowlist once READY to avoid stale Python-side state.
            launch {
                reticulumProtocol.networkStatus
                    .collect { status ->
                        if (status is NetworkStatus.READY) {
                            Log.d(TAG, "Network became READY, re-syncing host mode + allowed requesters")
                            syncHostModeWithPython(_isHostModeEnabled.value)
                            syncAllowedRequestersWithPython(_allowedRequesters.value)
                        }
                    }
            }
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping TelemetryCollectorManager")
            settingsObserverJob?.cancel()
            periodicSendJob?.cancel()
            periodicRequestJob?.cancel()
            locationTracker.stop()
            settingsObserverJob = null
            periodicSendJob = null
            periodicRequestJob = null
        }

        private fun shouldTrackLocation(): Boolean = _isEnabled.value && _collectorAddress.value != null

        /**
         * Update the collector address.
         *
         * @param address 32-character hex destination hash, or null to clear
         */
        suspend fun setCollectorAddress(address: String?) {
            if (address != null) {
                if (address.length != 32) {
                    Log.w(TAG, "Invalid collector address length: ${address.length} (expected 32)")
                    return
                }
                // Normalize to lowercase for consistent storage and comparison
                val normalized = address.lowercase()
                if (!normalized.matches(Regex("^[0-9a-f]{32}$"))) {
                    Log.w(TAG, "Invalid collector address: contains non-hex characters")
                    return
                }
                settingsRepository.saveTelemetryCollectorAddress(normalized)
            } else {
                settingsRepository.saveTelemetryCollectorAddress(null)
            }
        }

        /**
         * Enable or disable automatic telemetry sending.
         */
        suspend fun setEnabled(enabled: Boolean) {
            settingsRepository.saveTelemetryCollectorEnabled(enabled)
        }

        /**
         * Update the send interval.
         *
         * @param seconds Interval in seconds (minimum 60, maximum 86400)
         */
        suspend fun setSendIntervalSeconds(seconds: Int) {
            settingsRepository.saveTelemetrySendIntervalSeconds(seconds)
        }

        /**
         * Enable or disable automatic telemetry requesting from collector.
         */
        suspend fun setRequestEnabled(enabled: Boolean) {
            settingsRepository.saveTelemetryRequestEnabled(enabled)
        }

        /**
         * Update the request interval.
         *
         * @param seconds Interval in seconds (minimum 60, maximum 86400)
         */
        suspend fun setRequestIntervalSeconds(seconds: Int) {
            settingsRepository.saveTelemetryRequestIntervalSeconds(seconds)
        }

        /**
         * Enable or disable host mode (acting as a collector for others).
         * When enabled, this device will:
         * - Store incoming FIELD_TELEMETRY location data from peers
         * - Handle FIELD_COMMANDS telemetry requests
         * - Respond with FIELD_TELEMETRY_STREAM containing all stored entries
         *
         * @param enabled True to enable host mode, False to disable
         */
        suspend fun setHostModeEnabled(enabled: Boolean) {
            settingsRepository.saveTelemetryHostModeEnabled(enabled)
            // The observer will automatically sync with Python
        }

        /**
         * Sync host mode state with Python layer.
         * Called when the setting changes.
         */
        private suspend fun syncHostModeWithPython(enabled: Boolean) {
            if (reticulumProtocol.networkStatus.value !is NetworkStatus.READY) {
                Log.d(TAG, "Network not ready, will sync host mode when ready")
                return
            }

            try {
                val result = reticulumProtocol.setTelemetryCollectorMode(enabled)
                if (result.isSuccess) {
                    Log.i(TAG, "✅ Host mode synced with Python: $enabled")
                } else {
                    Log.e(TAG, "❌ Failed to sync host mode with Python: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing host mode with Python", e)
            }
        }

        /**
         * Set the list of identity hashes allowed to request telemetry when in host mode.
         * Only requesters in the set will receive responses; others will be blocked.
         * If the set is empty, all requests will be blocked.
         *
         * @param allowedHashes Set of 32-character hex identity hash strings
         */
        suspend fun setAllowedRequesters(allowedHashes: Set<String>) {
            Log.d(TAG, "setAllowedRequesters: saving ${allowedHashes.size} hashes to repository")
            settingsRepository.saveTelemetryAllowedRequesters(allowedHashes)
            Log.d(TAG, "setAllowedRequesters: saved to repository, observer will sync with Python")
            // The observer will automatically sync with Python
        }

        /**
         * Return local identity hashes used to detect self-echo telemetry entries.
         */
        suspend fun getLocalIdentityHashes(): List<String> =
            identityRepository
                .getActiveIdentitySync()
                ?.let { activeIdentity ->
                    listOfNotNull(activeIdentity.destinationHash, activeIdentity.identityHash)
                        .map { it.lowercase() }
                } ?: emptyList()

        /**
         * Sync allowed requesters with Python layer.
         * Called when the setting changes.
         */
        private suspend fun syncAllowedRequestersWithPython(allowedHashes: Set<String>) {
            if (reticulumProtocol.networkStatus.value !is NetworkStatus.READY) {
                Log.d(TAG, "Network not ready, will sync allowed requesters when ready")
                return
            }

            try {
                val result = reticulumProtocol.setTelemetryAllowedRequesters(allowedHashes)
                if (result.isSuccess) {
                    Log.i(TAG, "✅ Allowed requesters synced with Python: ${allowedHashes.size}")
                } else {
                    Log.e(TAG, "❌ Failed to sync allowed requesters with Python: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing allowed requesters with Python", e)
            }
        }

        /**
         * Manually trigger a telemetry request from the collector.
         *
         * @return Result of the request operation
         */
        suspend fun requestTelemetryNow(): TelemetryRequestResult {
            val collector = _collectorAddress.value
            if (collector == null) {
                Log.d(TAG, "No collector configured for request")
                return TelemetryRequestResult.NoCollectorConfigured
            }

            if (reticulumProtocol.networkStatus.value !is NetworkStatus.READY) {
                Log.d(TAG, "Network not ready for request")
                return TelemetryRequestResult.NetworkNotReady
            }

            return requestTelemetryFromCollector(collector)
        }

        /**
         * Manually trigger a telemetry send to the collector.
         *
         * @return Result of the send operation
         */
        suspend fun sendTelemetryNow(): TelemetrySendResult {
            val collector = _collectorAddress.value
            if (collector == null) {
                Log.d(TAG, "No collector configured")
                return TelemetrySendResult.NoCollectorConfigured
            }

            if (reticulumProtocol.networkStatus.value !is NetworkStatus.READY) {
                Log.d(TAG, "Network not ready")
                return TelemetrySendResult.NetworkNotReady
            }

            return sendTelemetryToCollector(collector)
        }

        /**
         * Restart the periodic send job with current settings.
         */
        private fun restartPeriodicSend() {
            periodicSendJob?.cancel()

            val enabled = _isEnabled.value
            val collector = _collectorAddress.value

            if (!enabled || collector == null) {
                Log.d(TAG, "Periodic send disabled (enabled=$enabled, collector=${collector != null})")
                return
            }

            periodicSendJob =
                scope.launch {
                    try {
                        _isSending.first { !it }
                        reticulumProtocol.networkStatus.first { it is NetworkStatus.READY }
                        Log.d(TAG, "Network ready, starting periodic telemetry sends")

                        while (isActive) {
                            val nextSendTime = executePeriodicSendIteration()
                            delayUntil(nextSendTime)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Periodic send job crashed", e)
                        if (_isEnabled.value && _collectorAddress.value != null) {
                            delay(5_000L)
                            restartPeriodicSend()
                        }
                    }
                }
        }

        /**
         * Execute one iteration of the periodic send loop.
         * @return The next scheduled send time (epoch ms).
         */
        private suspend fun executePeriodicSendIteration(): Long {
            val intervalSeconds = _sendIntervalSeconds.value
            val lastSend = maxOf(_lastSendTime.value ?: 0L, lastSendAttemptAt ?: 0L)
            val nextSendTime = lastSend + (intervalSeconds * 1000L)

            if (System.currentTimeMillis() >= nextSendTime) {
                val currentCollector = _collectorAddress.value
                if (currentCollector != null && reticulumProtocol.networkStatus.value is NetworkStatus.READY) {
                    lastSendAttemptAt = System.currentTimeMillis()
                    Log.d(TAG, "📡 Periodic telemetry send to collector")
                    logSendResult(sendTelemetryToCollector(currentCollector))
                } else {
                    Log.d(TAG, "Skipping periodic send (collector=${currentCollector != null}, network=${reticulumProtocol.networkStatus.value})")
                }
            }
            return nextSendTime
        }

        private fun logSendResult(result: TelemetrySendResult) {
            when (result) {
                is TelemetrySendResult.Success -> Log.i(TAG, "✅ Periodic telemetry sent successfully")
                is TelemetrySendResult.Error -> Log.w(TAG, "❌ Periodic telemetry send failed: ${result.message}")
                else -> Log.d(TAG, "Periodic send skipped: $result")
            }
        }

        /**
         * Restart the periodic request job with current settings.
         */
        private fun restartPeriodicRequest() {
            periodicRequestJob?.cancel()

            val enabled = _isRequestEnabled.value
            val collector = _collectorAddress.value

            if (!enabled || collector == null) {
                Log.d(TAG, "Periodic request disabled (enabled=$enabled, collector=${collector != null})")
                return
            }

            periodicRequestJob =
                scope.launch {
                    try {
                        _isRequesting.first { !it }
                        reticulumProtocol.networkStatus.first { it is NetworkStatus.READY }
                        Log.d(TAG, "Network ready, starting periodic telemetry requests")

                        while (isActive) {
                            val nextRequestTime = executePeriodicRequestIteration()
                            delayUntil(nextRequestTime)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Periodic request job crashed", e)
                        if (_isRequestEnabled.value && _collectorAddress.value != null) {
                            delay(5_000L)
                            restartPeriodicRequest()
                        }
                    }
                }
        }

        /**
         * Execute one iteration of the periodic request loop.
         * @return The next scheduled request time (epoch ms).
         */
        private suspend fun executePeriodicRequestIteration(): Long {
            val intervalSeconds = _requestIntervalSeconds.value
            val lastRequest = maxOf(_lastRequestTime.value ?: 0L, lastRequestAttemptAt ?: 0L)
            val nextRequestTime = lastRequest + (intervalSeconds * 1000L)

            if (System.currentTimeMillis() >= nextRequestTime) {
                val currentCollector = _collectorAddress.value
                if (currentCollector != null && reticulumProtocol.networkStatus.value is NetworkStatus.READY) {
                    lastRequestAttemptAt = System.currentTimeMillis()
                    Log.d(TAG, "📡 Periodic telemetry request from collector")
                    logRequestResult(requestTelemetryFromCollector(currentCollector))
                } else {
                    Log.d(TAG, "Skipping periodic request (collector=${currentCollector != null}, network=${reticulumProtocol.networkStatus.value})")
                }
            }
            return nextRequestTime
        }

        private fun logRequestResult(result: TelemetryRequestResult) {
            when (result) {
                is TelemetryRequestResult.Success -> Log.i(TAG, "✅ Periodic telemetry request sent successfully")
                is TelemetryRequestResult.Error -> Log.w(TAG, "❌ Periodic telemetry request failed: ${result.message}")
                else -> Log.d(TAG, "Periodic request skipped: $result")
            }
        }

        /**
         * Delay until [targetTimeMs], capping at 30 seconds for responsiveness.
         */
        private suspend fun delayUntil(targetTimeMs: Long) {
            val timeUntil = maxOf(0L, targetTimeMs - System.currentTimeMillis())
            delay(minOf(timeUntil, 30_000L))
        }

        /**
         * Check if the given collector hash is the local device's own destination hash.
         */
        private suspend fun isLocalDestination(collectorHash: String): Boolean {
            val localHash = identityRepository.getActiveIdentitySync()?.destinationHash ?: return false
            return collectorHash.equals(localHash, ignoreCase = true)
        }

        private suspend fun syncHostModeIfNeededForLocalStore(): TelemetrySendResult.Error? {
            if (!_isHostModeEnabled.value) return null

            val hostModeSyncResult = reticulumProtocol.setTelemetryCollectorMode(true)
            if (hostModeSyncResult.isSuccess) return null

            val syncError = hostModeSyncResult.exceptionOrNull()?.message ?: "Unknown host mode sync error"
            Log.e(TAG, "❌ Failed to re-sync host mode before self telemetry store: $syncError")
            return TelemetrySendResult.Error("Failed to enable host mode: $syncError")
        }

        /**
         * Send telemetry to the specified collector.
         */
        @Suppress("ReturnCount") // Early returns for validation are clearer than nested conditions
        private suspend fun sendTelemetryToCollector(collectorHash: String): TelemetrySendResult {
            if (_isSending.value) {
                Log.d(TAG, "Already sending, skipping")
                return TelemetrySendResult.Error("Already sending")
            }

            _isSending.value = true

            try {
                // Use continuously tracked location when available; otherwise try a fresh one-shot fix.
                val location = locationTracker.getTelemetryLocation()
                if (location == null) {
                    Log.w(TAG, "No recent valid location available")
                    return TelemetrySendResult.NoLocationAvailable
                }

                // Build location JSON using the location's actual capture time
                // Fallback to current time if location.time is 0 (unknown)
                val telemetryData =
                    LocationTelemetryData(
                        lat = location.latitude,
                        lng = location.longitude,
                        acc = location.accuracy,
                        ts = if (location.time > 0) location.time else System.currentTimeMillis(),
                        altitude = location.altitude,
                        speed = location.speed,
                        bearing = location.bearing,
                    )
                val locationJson = Json.encodeToString(telemetryData)

                // Get user's icon appearance for Sideband/MeshChat interoperability
                val iconAppearance =
                    identityRepository.getActiveIdentitySync()?.let { activeId ->
                        val name = activeId.iconName
                        val fg = activeId.iconForegroundColor
                        val bg = activeId.iconBackgroundColor
                        if (name != null && fg != null && bg != null) {
                            network.columba.app.reticulum.protocol.IconAppearance(
                                iconName = name,
                                foregroundColor = fg,
                                backgroundColor = bg,
                            )
                        } else {
                            null
                        }
                    }

                // If the collector is ourselves, store locally instead of sending via network
                val result =
                    if (isLocalDestination(collectorHash)) {
                        syncHostModeIfNeededForLocalStore()?.let { return it }
                        Log.d(TAG, "📍 Collector is self, storing own telemetry locally")
                        reticulumProtocol.storeOwnTelemetry(locationJson, iconAppearance)
                    } else {
                        // Get LXMF identity for network send
                        val sourceIdentity =
                            try {
                                reticulumProtocol.getLxmfIdentity().getOrNull()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to get LXMF identity", e)
                                null
                            } ?: return TelemetrySendResult.Error("No LXMF identity")

                        val collectorBytes = collectorHash.hexToByteArray()
                        reticulumProtocol.sendLocationTelemetry(
                            destinationHash = collectorBytes,
                            locationJson = locationJson,
                            sourceIdentity = sourceIdentity,
                            iconAppearance = iconAppearance,
                        )
                    }

                return if (result.isSuccess) {
                    // Update last send time
                    val timestamp = System.currentTimeMillis()
                    settingsRepository.saveLastTelemetrySendTime(timestamp)
                    Log.i(TAG, "✅ Telemetry sent to collector ${collectorHash.take(16)}")
                    TelemetrySendResult.Success
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "❌ Failed to send telemetry: $error")
                    TelemetrySendResult.Error(error)
                }
            } finally {
                _isSending.value = false
            }
        }

        /**
         * Request telemetry from the specified collector.
         * The collector will respond with FIELD_TELEMETRY_STREAM containing locations from all peers.
         */
        @Suppress("ReturnCount") // Early returns for guard conditions improve readability
        private suspend fun requestTelemetryFromCollector(collectorHash: String): TelemetryRequestResult {
            if (_isRequesting.value) {
                Log.d(TAG, "Already requesting, skipping")
                return TelemetryRequestResult.Error("Already requesting")
            }

            _isRequesting.value = true

            try {
                // Get LXMF identity
                val sourceIdentity =
                    try {
                        reticulumProtocol.getLxmfIdentity().getOrNull()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get LXMF identity", e)
                        null
                    } ?: return TelemetryRequestResult.Error("No LXMF identity")

                // Convert collector hash to bytes
                val collectorBytes = collectorHash.hexToByteArray()

                // Use last request time as timebase (request telemetry since last request)
                // Pass null for first request to get all available telemetry
                val timebase = _lastRequestTime.value

                // Send telemetry request via protocol
                val result =
                    reticulumProtocol.sendTelemetryRequest(
                        destinationHash = collectorBytes,
                        sourceIdentity = sourceIdentity,
                        timebase = timebase,
                        isCollectorRequest = true,
                    )

                return if (result.isSuccess) {
                    // Update last request time
                    val timestamp = System.currentTimeMillis()
                    settingsRepository.saveLastTelemetryRequestTime(timestamp)
                    Log.i(TAG, "✅ Telemetry request sent to collector ${collectorHash.take(16)}")
                    TelemetryRequestResult.Success
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "❌ Failed to send telemetry request: $error")
                    TelemetryRequestResult.Error(error)
                }
            } finally {
                _isRequesting.value = false
            }
        }

        /**
         * Convert hex string to ByteArray.
         */
        private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
