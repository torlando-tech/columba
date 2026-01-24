package com.lxmf.messenger.service

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Result of a telemetry send operation.
 */
sealed class TelemetrySendResult {
    data object Success : TelemetrySendResult()

    data class Error(val message: String) : TelemetrySendResult()

    data object NoCollectorConfigured : TelemetrySendResult()

    data object NoLocationAvailable : TelemetrySendResult()

    data object NetworkNotReady : TelemetrySendResult()
}

/**
 * Result of a telemetry request operation.
 */
sealed class TelemetryRequestResult {
    data object Success : TelemetryRequestResult()

    data class Error(val message: String) : TelemetryRequestResult()

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
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

        companion object {
            private const val TAG = "TelemetryCollectorManager"
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
        }

        private fun CoroutineScope.launchSendSettingsObservers() {
            launch {
                settingsRepository.telemetryCollectorAddressFlow
                    .distinctUntilChanged()
                    .collect { address ->
                        _collectorAddress.value = address
                        Log.d(TAG, "Collector address updated: ${address?.take(16) ?: "none"}")
                    }
            }
            launch {
                settingsRepository.telemetryCollectorEnabledFlow
                    .distinctUntilChanged()
                    .collect { enabled ->
                        _isEnabled.value = enabled
                        Log.d(TAG, "Collector enabled: $enabled")
                        restartPeriodicSend()
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
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping TelemetryCollectorManager")
            settingsObserverJob?.cancel()
            periodicSendJob?.cancel()
            periodicRequestJob?.cancel()
            settingsObserverJob = null
            periodicSendJob = null
            periodicRequestJob = null
        }

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
                    // Wait for any in-progress send to complete before starting new schedule
                    while (_isSending.value) {
                        delay(100)
                    }

                    // Wait for network to be ready before starting periodic sends
                    reticulumProtocol.networkStatus.first { it is NetworkStatus.READY }
                    Log.d(TAG, "Network ready, starting periodic telemetry sends")

                    while (true) {
                        val intervalSeconds = _sendIntervalSeconds.value
                        val lastSend = _lastSendTime.value ?: 0L
                        val now = System.currentTimeMillis()
                        val nextSendTime = lastSend + (intervalSeconds * 1000L)

                        if (now >= nextSendTime) {
                            // Time to send - capture address to avoid race condition
                            val currentCollector = _collectorAddress.value
                            if (currentCollector != null && reticulumProtocol.networkStatus.value is NetworkStatus.READY) {
                                Log.d(TAG, "📡 Periodic telemetry send to collector")
                                val result = sendTelemetryToCollector(currentCollector)
                                when (result) {
                                    is TelemetrySendResult.Success ->
                                        Log.i(TAG, "✅ Periodic telemetry sent successfully")
                                    is TelemetrySendResult.Error ->
                                        Log.w(TAG, "❌ Periodic telemetry send failed: ${result.message}")
                                    else ->
                                        Log.d(TAG, "Periodic send skipped: $result")
                                }
                            } else if (currentCollector == null) {
                                Log.d(TAG, "No collector configured, skipping periodic send")
                            } else {
                                Log.d(TAG, "Network not ready, skipping periodic send")
                            }
                        }

                        // Calculate time until next send, cap at 30 seconds for responsiveness
                        val timeUntilNextSend = maxOf(0L, nextSendTime - System.currentTimeMillis())
                        val delayTime = minOf(timeUntilNextSend, 30_000L)
                        delay(if (delayTime > 0) delayTime else 30_000L)
                    }
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
                    // Wait for any in-progress request to complete before starting new schedule
                    while (_isRequesting.value) {
                        delay(100)
                    }

                    // Wait for network to be ready before starting periodic requests
                    reticulumProtocol.networkStatus.first { it is NetworkStatus.READY }
                    Log.d(TAG, "Network ready, starting periodic telemetry requests")

                    while (true) {
                        val intervalSeconds = _requestIntervalSeconds.value
                        val lastRequest = _lastRequestTime.value ?: 0L
                        val now = System.currentTimeMillis()
                        val nextRequestTime = lastRequest + (intervalSeconds * 1000L)

                        if (now >= nextRequestTime) {
                            // Time to request - capture address to avoid race condition
                            val currentCollector = _collectorAddress.value
                            if (currentCollector != null && reticulumProtocol.networkStatus.value is NetworkStatus.READY) {
                                Log.d(TAG, "📡 Periodic telemetry request from collector")
                                val result = requestTelemetryFromCollector(currentCollector)
                                when (result) {
                                    is TelemetryRequestResult.Success ->
                                        Log.i(TAG, "✅ Periodic telemetry request sent successfully")
                                    is TelemetryRequestResult.Error ->
                                        Log.w(TAG, "❌ Periodic telemetry request failed: ${result.message}")
                                    else ->
                                        Log.d(TAG, "Periodic request skipped: $result")
                                }
                            } else if (currentCollector == null) {
                                Log.d(TAG, "No collector configured, skipping periodic request")
                            } else {
                                Log.d(TAG, "Network not ready, skipping periodic request")
                            }
                        }

                        // Calculate time until next request, cap at 30 seconds for responsiveness
                        val timeUntilNextRequest = maxOf(0L, nextRequestTime - System.currentTimeMillis())
                        val delayTime = minOf(timeUntilNextRequest, 30_000L)
                        delay(if (delayTime > 0) delayTime else 30_000L)
                    }
                }
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
                // Get current location
                val location = getCurrentLocation()
                if (location == null) {
                    Log.w(TAG, "No location available")
                    return TelemetrySendResult.NoLocationAvailable
                }

                // Get LXMF identity
                val serviceProtocol =
                    reticulumProtocol as? ServiceReticulumProtocol
                        ?: return TelemetrySendResult.Error("Protocol not available")

                val sourceIdentity =
                    try {
                        serviceProtocol.getLxmfIdentity().getOrNull()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get LXMF identity", e)
                        null
                    } ?: return TelemetrySendResult.Error("No LXMF identity")

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

                // Convert collector hash to bytes
                val collectorBytes = collectorHash.hexToByteArray()

                // Send via protocol
                val result =
                    reticulumProtocol.sendLocationTelemetry(
                        destinationHash = collectorBytes,
                        locationJson = locationJson,
                        sourceIdentity = sourceIdentity,
                    )

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
                val serviceProtocol =
                    reticulumProtocol as? ServiceReticulumProtocol
                        ?: return TelemetryRequestResult.Error("Protocol not available")

                val sourceIdentity =
                    try {
                        serviceProtocol.getLxmfIdentity().getOrNull()
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
         * Get the current device location.
         */
        @Suppress("MissingPermission") // Permission checked at higher level
        private suspend fun getCurrentLocation(): Location? {
            return suspendCancellableCoroutine { continuation ->
                val cancellationTokenSource = CancellationTokenSource()

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }

                // Check if already cancelled before making the request
                if (continuation.isActive) {
                    try {
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            cancellationTokenSource.token,
                        ).addOnSuccessListener { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }.addOnFailureListener { exception ->
                            Log.e(TAG, "Failed to get location", exception)
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        // CancellationToken was already cancelled - this can happen in race conditions
                        Log.w(TAG, "Location request cancelled before it could start", e)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            }
        }

        /**
         * Convert hex string to ByteArray.
         */
        private fun String.hexToByteArray(): ByteArray {
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
