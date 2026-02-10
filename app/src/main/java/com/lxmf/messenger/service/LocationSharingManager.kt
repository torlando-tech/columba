package com.lxmf.messenger.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.db.entity.ReceivedLocationEntity
import com.lxmf.messenger.data.model.LocationTelemetry
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.ui.model.SharingDuration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Represents an active location sharing session.
 *
 * @property destinationHash The identity hash of the recipient (hex string)
 * @property displayName Display name of the recipient
 * @property startTime When sharing started (millis since epoch)
 * @property endTime When sharing will end (millis since epoch), null for indefinite
 */
data class SharingSession(
    val destinationHash: String,
    val displayName: String,
    val startTime: Long,
    val endTime: Long?,
)

/**
 * Manages location sharing sessions and telemetry.
 *
 * Responsibilities:
 * - Track active outgoing sharing sessions
 * - Periodically send location updates to recipients
 * - Receive and store incoming location telemetry
 * - Handle session expiration
 */
@Singleton
class LocationSharingManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reticulumProtocol: ReticulumProtocol,
        private val receivedLocationDao: ReceivedLocationDao,
        private val settingsRepository: SettingsRepository,
        private val identityRepository: com.lxmf.messenger.data.repository.IdentityRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "LocationSharingManager"
            private const val LOCATION_UPDATE_INTERVAL_MS = 60_000L // 60 seconds
            private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 30_000L // 30 seconds
            private const val SESSION_CHECK_INTERVAL_MS = 30_000L // 30 seconds
            private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        }

        private val fusedLocationClient: FusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(context)

        // Active outgoing sharing sessions
        private val _activeSessions = MutableStateFlow<List<SharingSession>>(emptyList())
        val activeSessions: StateFlow<List<SharingSession>> = _activeSessions.asStateFlow()

        // Whether we're currently sharing with anyone
        private val _isSharing = MutableStateFlow(false)
        val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

        // Events for UI feedback
        private val _sharingEvents = MutableSharedFlow<SharingEvent>(extraBufferCapacity = 10)
        val sharingEvents: SharedFlow<SharingEvent> = _sharingEvents.asSharedFlow()

        // Location update job
        private var locationUpdateJob: Job? = null
        private var sessionCheckJob: Job? = null
        private var maintenanceJob: Job? = null
        private var lastLocation: Location? = null

        // Location callback for updates
        private val locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        lastLocation = location
                        sendLocationToRecipients(location)
                    }
                }
            }

        init {
            // Start listening for incoming location telemetry
            startListeningForLocationTelemetry()

            // Start periodic cleanup of expired locations
            startMaintenanceLoop()
        }

        /**
         * Start sharing location with specified contacts.
         *
         * @param contactHashes List of destination hashes to share with
         * @param displayNames Map of destination hash to display name
         * @param duration How long to share
         */
        @SuppressLint("MissingPermission")
        fun startSharing(
            contactHashes: List<String>,
            displayNames: Map<String, String>,
            duration: SharingDuration,
        ) {
            val startTime = System.currentTimeMillis()
            val endTime = duration.calculateEndTime(startTime)

            val newSessions =
                contactHashes.map { hash ->
                    SharingSession(
                        destinationHash = hash,
                        displayName = displayNames[hash] ?: "Unknown",
                        startTime = startTime,
                        endTime = endTime,
                    )
                }

            // Add to existing sessions (replacing any for same contacts)
            val existingHashes = newSessions.map { it.destinationHash }.toSet()
            val updated =
                _activeSessions.value.filterNot { it.destinationHash in existingHashes } +
                    newSessions
            _activeSessions.value = updated
            _isSharing.value = updated.isNotEmpty()

            Log.d(TAG, "Started sharing with ${newSessions.size} contacts, duration=$duration")

            // Send last known location immediately (don't wait for first GPS update)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    Log.d(TAG, "Sending immediate location to new recipients")
                    sendLocationToRecipients(it)
                }
            }

            // Start location updates if not already running
            if (locationUpdateJob == null || locationUpdateJob?.isActive != true) {
                startLocationUpdates()
            }

            // Start session expiration checker
            if (sessionCheckJob == null || sessionCheckJob?.isActive != true) {
                startSessionCheck()
            }

            scope.launch {
                _sharingEvents.emit(SharingEvent.Started(newSessions.size))
            }
        }

        /**
         * Send an immediate location update to all active sharing recipients.
         *
         * Call this when settings change (e.g., precision) to immediately notify
         * recipients of the new settings rather than waiting for the next scheduled update.
         */
        @SuppressLint("MissingPermission")
        fun sendImmediateUpdate() {
            if (_activeSessions.value.isEmpty()) {
                Log.d(TAG, "No active sessions, skipping immediate update")
                return
            }

            // Use last known location if available, otherwise get current location
            if (lastLocation != null) {
                Log.d(TAG, "Sending immediate update with cached location")
                sendLocationToRecipients(lastLocation!!)
            } else {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        Log.d(TAG, "Sending immediate update with fresh location")
                        lastLocation = it
                        sendLocationToRecipients(it)
                    } ?: Log.w(TAG, "No location available for immediate update")
                }
            }
        }

        /**
         * Stop sharing with a specific contact.
         *
         * Sends a cease message to notify recipients before removing the session.
         *
         * @param destinationHash The contact to stop sharing with, or null to stop all
         */
        fun stopSharing(destinationHash: String? = null) {
            // Send cease messages BEFORE removing sessions
            val sessionsToNotify =
                if (destinationHash != null) {
                    _activeSessions.value.filter { it.destinationHash == destinationHash }
                } else {
                    _activeSessions.value
                }

            sessionsToNotify.forEach { session ->
                sendCeaseMessage(session.destinationHash)
            }

            // Now remove the sessions
            val updated =
                if (destinationHash != null) {
                    _activeSessions.value.filterNot { it.destinationHash == destinationHash }
                } else {
                    emptyList()
                }

            _activeSessions.value = updated
            _isSharing.value = updated.isNotEmpty()

            if (updated.isEmpty()) {
                stopLocationUpdates()
                sessionCheckJob?.cancel()
                sessionCheckJob = null
            }

            Log.d(TAG, "Stopped sharing, remaining sessions: ${updated.size}")

            scope.launch {
                _sharingEvents.emit(SharingEvent.Stopped(destinationHash))
            }
        }

        /**
         * Send a cease sharing message to a recipient.
         *
         * This tells the recipient to delete our location from their map.
         */
        private fun sendCeaseMessage(recipientHash: String) {
            scope.launch {
                val serviceProtocol = reticulumProtocol as? ServiceReticulumProtocol
                if (serviceProtocol == null) {
                    Log.e(TAG, "Cannot send cease message: ReticulumProtocol is not ServiceReticulumProtocol")
                    return@launch
                }

                val sourceIdentity =
                    try {
                        serviceProtocol.getLxmfIdentity().getOrNull()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get LXMF identity for cease message", e)
                        null
                    }

                if (sourceIdentity == null) {
                    Log.e(TAG, "No LXMF identity available for cease message")
                    return@launch
                }

                val ceaseJson =
                    Json.encodeToString(
                        LocationTelemetry(
                            lat = 0.0,
                            lng = 0.0,
                            acc = 0f,
                            ts = System.currentTimeMillis(),
                            expires = null,
                            cease = true,
                        ),
                    )

                try {
                    val destHashBytes = hexStringToByteArray(recipientHash)
                    reticulumProtocol.sendLocationTelemetry(
                        destinationHash = destHashBytes,
                        locationJson = ceaseJson,
                        sourceIdentity = sourceIdentity,
                    )
                    Log.d(TAG, "Sent cease sharing message to $recipientHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send cease message to $recipientHash", e)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun startLocationUpdates() {
            locationUpdateJob =
                scope.launch {
                    try {
                        val locationRequest =
                            LocationRequest
                                .Builder(
                                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                    LOCATION_UPDATE_INTERVAL_MS,
                                ).apply {
                                    setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
                                }.build()

                        fusedLocationClient.requestLocationUpdates(
                            locationRequest,
                            locationCallback,
                            context.mainLooper,
                        )

                        Log.d(TAG, "Location updates started")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Location permission not granted", e)
                        _sharingEvents.emit(SharingEvent.Error("Location permission required"))
                    }
                }
        }

        private fun stopLocationUpdates() {
            locationUpdateJob?.cancel()
            locationUpdateJob = null
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d(TAG, "Location updates stopped")
        }

        private fun startSessionCheck() {
            sessionCheckJob =
                scope.launch {
                    while (isActive) {
                        delay(SESSION_CHECK_INTERVAL_MS)
                        checkExpiredSessions()
                    }
                }
        }

        /**
         * Start periodic cleanup of expired received locations.
         * Runs every 5 minutes to remove locations that have expired past the grace period.
         */
        private fun startMaintenanceLoop() {
            maintenanceJob =
                scope.launch {
                    while (isActive) {
                        delay(CLEANUP_INTERVAL_MS)
                        cleanupExpiredLocations()
                    }
                }
            Log.d(TAG, "Started maintenance loop for location cleanup")
        }

        private suspend fun checkExpiredSessions() {
            val now = System.currentTimeMillis()
            val (expired, active) =
                _activeSessions.value.partition { session ->
                    session.endTime != null && session.endTime < now
                }

            if (expired.isNotEmpty()) {
                Log.d(TAG, "${expired.size} sessions expired")
                _activeSessions.value = active
                _isSharing.value = active.isNotEmpty()

                if (active.isEmpty()) {
                    stopLocationUpdates()
                }

                _sharingEvents.emit(SharingEvent.SessionsExpired(expired.size))
            }
        }

        private fun sendLocationToRecipients(location: Location) {
            val sessions = _activeSessions.value
            if (sessions.isEmpty()) return

            scope.launch {
                // Get LXMF identity from the protocol
                val serviceProtocol = reticulumProtocol as? ServiceReticulumProtocol
                if (serviceProtocol == null) {
                    Log.e(TAG, "ReticulumProtocol is not ServiceReticulumProtocol")
                    return@launch
                }

                val sourceIdentity =
                    try {
                        serviceProtocol.getLxmfIdentity().getOrNull()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get LXMF identity", e)
                        null
                    }

                if (sourceIdentity == null) {
                    Log.e(TAG, "No LXMF identity available for sending location")
                    return@launch
                }

                // Find the first expired session end time among all sessions
                val earliestExpiry = sessions.mapNotNull { it.endTime }.minOrNull()

                // Get precision radius setting (0 = precise, >0 = coarsen to that radius)
                val precisionRadius = settingsRepository.locationPrecisionRadiusFlow.first()

                // Coarsen location if needed
                val (finalLat, finalLng) = coarsenLocation(location.latitude, location.longitude, precisionRadius)

                val telemetry =
                    LocationTelemetry(
                        lat = finalLat,
                        lng = finalLng,
                        acc = if (precisionRadius > 0) precisionRadius.toFloat() else location.accuracy,
                        ts = System.currentTimeMillis(),
                        expires = earliestExpiry,
                        approxRadius = precisionRadius,
                    )

                val json = Json.encodeToString(telemetry)

                // Get user's icon appearance for Sideband/MeshChat interoperability
                val iconAppearance =
                    identityRepository.getActiveIdentitySync()?.let { activeId ->
                        val name = activeId.iconName
                        val fg = activeId.iconForegroundColor
                        val bg = activeId.iconBackgroundColor
                        if (name != null && fg != null && bg != null) {
                            com.lxmf.messenger.reticulum.protocol.IconAppearance(
                                iconName = name,
                                foregroundColor = fg,
                                backgroundColor = bg,
                            )
                        } else {
                            null
                        }
                    }

                // Send to each recipient
                sessions.forEach { session ->
                    try {
                        val destHashBytes = hexStringToByteArray(session.destinationHash)

                        val result =
                            reticulumProtocol.sendLocationTelemetry(
                                destinationHash = destHashBytes,
                                locationJson = json,
                                sourceIdentity = sourceIdentity,
                                iconAppearance = iconAppearance,
                            )

                        result
                            .onSuccess {
                                Log.d(TAG, "Location sent to ${session.destinationHash.take(16)} (approxRadius=$precisionRadius)")
                            }.onFailure { e ->
                                Log.e(TAG, "Failed to send location to ${session.destinationHash.take(16)}", e)
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending location to ${session.destinationHash}", e)
                    }
                }
            }
        }

        /**
         * Coarsen location coordinates to a grid based on the specified radius.
         *
         * @param lat Latitude in decimal degrees
         * @param lng Longitude in decimal degrees
         * @param radiusMeters Coarsening radius in meters (0 = no coarsening)
         * @return Pair of coarsened (lat, lng)
         */
        private fun coarsenLocation(
            lat: Double,
            lng: Double,
            radiusMeters: Int,
        ): Pair<Double, Double> {
            if (radiusMeters <= 0) return Pair(lat, lng)

            // Convert radius to degrees (approximate: 111km per degree at equator)
            val gridSizeDegrees = radiusMeters / 111_000.0
            val coarseLat = (lat / gridSizeDegrees).roundToInt() * gridSizeDegrees
            val coarseLng = (lng / gridSizeDegrees).roundToInt() * gridSizeDegrees
            return Pair(coarseLat, coarseLng)
        }

        private fun startListeningForLocationTelemetry() {
            // Cast to ServiceReticulumProtocol to access the locationTelemetryFlow
            val serviceProtocol = reticulumProtocol as? ServiceReticulumProtocol
            if (serviceProtocol == null) {
                Log.w(TAG, "ReticulumProtocol is not ServiceReticulumProtocol, cannot listen for location")
                return
            }

            scope.launch {
                serviceProtocol.locationTelemetryFlow.collect { locationJson ->
                    handleReceivedLocation(locationJson)
                }
            }
        }

        private suspend fun handleReceivedLocation(locationJson: String) {
            try {
                val json = JSONObject(locationJson)
                val senderHash = json.getString("source_hash")

                // Check for cease flag - sender has stopped sharing
                if (json.optBoolean("cease", false)) {
                    val ceaseTimestamp = json.optLong("ts", 0)
                    // Only delete if cease is newer than latest location (prevents race condition
                    // where old cease messages arrive after new sharing session starts)
                    val latestLocation = receivedLocationDao.getLatestLocationForSender(senderHash)
                    if (latestLocation == null || ceaseTimestamp > latestLocation.receivedAt) {
                        receivedLocationDao.deleteLocationsForSender(senderHash)
                        Log.d(TAG, "Ceased sharing from $senderHash - deleted locations")
                    } else {
                        Log.d(TAG, "Ignoring stale cease from $senderHash (cease=$ceaseTimestamp, latest=${latestLocation.receivedAt})")
                    }
                    return
                }

                val lat = json.getDouble("lat")
                val lng = json.getDouble("lng")
                val acc = json.getDouble("acc").toFloat()
                val ts = json.getLong("ts")
                val expires = if (json.has("expires") && !json.isNull("expires")) json.getLong("expires") else null
                val approxRadius = json.optInt("approxRadius", 0)

                // Extract appearance data if present (from FIELD_TELEMETRY_STREAM entries)
                val appearanceJson =
                    if (json.has("appearance") && !json.isNull("appearance")) {
                        json.getJSONObject("appearance").toString()
                    } else {
                        null
                    }

                val entity =
                    ReceivedLocationEntity(
                        id = UUID.randomUUID().toString(),
                        senderHash = senderHash,
                        latitude = lat,
                        longitude = lng,
                        accuracy = acc,
                        timestamp = ts,
                        expiresAt = expires,
                        receivedAt = System.currentTimeMillis(),
                        approximateRadius = approxRadius,
                        appearanceJson = appearanceJson,
                    )

                receivedLocationDao.insert(entity)
                Log.d(TAG, "Stored location from $senderHash: ($lat, $lng) approxRadius=$approxRadius appearance=${appearanceJson != null}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse/store received location: $locationJson", e)
            }
        }

        /**
         * Clean up expired received locations.
         * Call this periodically from the foreground service.
         */
        suspend fun cleanupExpiredLocations() {
            try {
                receivedLocationDao.deleteExpiredLocations()
                Log.d(TAG, "Cleaned up expired locations")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup expired locations", e)
            }
        }

        private fun hexStringToByteArray(hex: String): ByteArray {
            val cleanHex = hex.replace(" ", "").replace(":", "")
            val len = cleanHex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }

/**
 * Events emitted by LocationSharingManager for UI feedback.
 */
sealed class SharingEvent {
    data class Started(
        val contactCount: Int,
    ) : SharingEvent()

    data class Stopped(
        val destinationHash: String?,
    ) : SharingEvent()

    data class SessionsExpired(
        val count: Int,
    ) : SharingEvent()

    data class Error(
        val message: String,
    ) : SharingEvent()
}
