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

        // Jobs for periodic operations
        private var settingsObserverJob: Job? = null
        private var periodicSendJob: Job? = null

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
            settingsObserverJob = scope.launch {
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

            // Start periodic sending
            restartPeriodicSend()
        }

        /**
         * Stop the manager.
         */
        fun stop() {
            Log.d(TAG, "Stopping TelemetryCollectorManager")
            settingsObserverJob?.cancel()
            periodicSendJob?.cancel()
            settingsObserverJob = null
            periodicSendJob = null
        }

        /**
         * Update the collector address.
         *
         * @param address 32-character hex destination hash, or null to clear
         */
        suspend fun setCollectorAddress(address: String?) {
            if (address != null && address.length != 32) {
                Log.w(TAG, "Invalid collector address length: ${address.length} (expected 32)")
                return
            }
            settingsRepository.saveTelemetryCollectorAddress(address)
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

            periodicSendJob = scope.launch {
                // Initial delay to let things settle
                delay(10_000)

                while (true) {
                    val intervalSeconds = _sendIntervalSeconds.value
                    val lastSend = _lastSendTime.value ?: 0L
                    val now = System.currentTimeMillis()
                    val nextSendTime = lastSend + (intervalSeconds * 1000L)

                    if (now >= nextSendTime) {
                        // Time to send
                        if (reticulumProtocol.networkStatus.value is NetworkStatus.READY) {
                            Log.d(TAG, "📡 Periodic telemetry send to collector")
                            val result = sendTelemetryToCollector(_collectorAddress.value!!)
                            when (result) {
                                is TelemetrySendResult.Success ->
                                    Log.i(TAG, "✅ Periodic telemetry sent successfully")
                                is TelemetrySendResult.Error ->
                                    Log.w(TAG, "❌ Periodic telemetry send failed: ${result.message}")
                                else ->
                                    Log.d(TAG, "Periodic send skipped: $result")
                            }
                        } else {
                            Log.d(TAG, "Network not ready, skipping periodic send")
                        }
                    }

                    // Check again in 30 seconds
                    delay(30_000)
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
                val serviceProtocol = reticulumProtocol as? ServiceReticulumProtocol
                    ?: return TelemetrySendResult.Error("Protocol not available")

                val sourceIdentity = try {
                    serviceProtocol.getLxmfIdentity().getOrNull()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get LXMF identity", e)
                    null
                } ?: return TelemetrySendResult.Error("No LXMF identity")

                // Build location JSON
                val telemetryData = LocationTelemetryData(
                    lat = location.latitude,
                    lng = location.longitude,
                    acc = location.accuracy,
                    ts = System.currentTimeMillis(),
                    altitude = location.altitude,
                    speed = location.speed,
                    bearing = location.bearing,
                )
                val locationJson = Json.encodeToString(telemetryData)

                // Convert collector hash to bytes
                val collectorBytes = collectorHash.hexToByteArray()

                // Send via protocol
                val result = reticulumProtocol.sendLocationTelemetry(
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
         * Get the current device location.
         */
        @Suppress("MissingPermission") // Permission checked at higher level
        private suspend fun getCurrentLocation(): Location? {
            return suspendCancellableCoroutine { continuation ->
                val cancellationTokenSource = CancellationTokenSource()

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token,
                ).addOnSuccessListener { location ->
                    continuation.resume(location)
                }.addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to get location", exception)
                    continuation.resume(null)
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
