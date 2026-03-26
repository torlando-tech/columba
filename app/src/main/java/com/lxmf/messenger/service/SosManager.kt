package com.lxmf.messenger.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.BatteryManager
import android.util.Log
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the current state of the SOS system.
 */
sealed class SosState {
    data object Idle : SosState()

    data class Countdown(
        val remainingSeconds: Int,
        val totalSeconds: Int,
    ) : SosState()

    data object Sending : SosState()

    data class Active(
        val sentCount: Int,
        val failedCount: Int,
    ) : SosState()
}

/**
 * Manages the SOS emergency messaging state machine.
 *
 * State flow: Idle -> Countdown -> Sending -> Active -> Idle
 *
 * When triggered, the manager reads SOS settings, optionally counts down,
 * sends emergency messages to all configured SOS contacts, and enters
 * an active state with optional periodic location updates.
 */
@Suppress("TooManyFunctions")
@Singleton
class SosManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contactRepository: ContactRepository,
        private val settingsRepository: SettingsRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val notificationHelper: NotificationHelper,
        private val audioRecorder: SosAudioRecorder,
    ) {
        companion object {
            private const val TAG = "SosManager"
        }

        /** Overridable for testing — provides current location. */
        internal var locationProvider: (suspend () -> Location?)? = null

        /** Override in tests to use a test dispatcher. */
        internal var dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

        private val scope by lazy {
            CoroutineScope(SupervisorJob() + dispatcher).also { s ->
                s.launch {
                    launch { settingsRepository.sosDeactivationPin.collect { cachedDeactivationPin = it } }
                    launch { settingsRepository.sosSilentAutoAnswer.collect { cachedSilentAutoAnswer = it } }
                }
            }
        }

        private val _state = MutableStateFlow<SosState>(SosState.Idle)
        val state: StateFlow<SosState> = _state.asStateFlow()

        private val triggerMutex = kotlinx.coroutines.sync.Mutex()
        private var triggerJob: Job? = null
        private var countdownJob: Job? = null
        private var periodicUpdateJob: Job? = null
        private var audioRecordingJob: Job? = null

        @Volatile private var cachedDeactivationPin: String? = null

        @Volatile private var cachedSilentAutoAnswer: Boolean = false

        /**
         * Restore persisted SOS active state after app/phone restart.
         * Should be called once at app startup (e.g., from Application.onCreate or service init).
         */
        fun restoreIfActive() {
            scope.launch {
                try {
                    val wasActive = settingsRepository.sosActive.first()
                    if (!wasActive) return@launch

                    val sentCount = settingsRepository.sosActiveSentCount.first()
                    val failedCount = settingsRepository.sosActiveFailedCount.first()
                    _state.value = SosState.Active(sentCount, failedCount)
                    notificationHelper.showSosActiveNotification(sentCount, failedCount)
                    Log.d(TAG, "Restored SOS active state: sent=$sentCount, failed=$failedCount")

                    val periodicUpdates = settingsRepository.sosPeriodicUpdates.first()
                    if (periodicUpdates) {
                        startPeriodicUpdates()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring SOS state", e)
                }
            }
        }

        /**
         * Trigger the SOS sequence. Reads settings to determine countdown duration,
         * then proceeds to send emergency messages to all SOS contacts.
         */
        fun trigger() {
            if (!triggerMutex.tryLock()) return // already in progress
            triggerJob =
                scope.launch {
                    try {
                        if (_state.value !is SosState.Idle) {
                            Log.d(TAG, "SOS already in progress (${_state.value}), ignoring trigger")
                            return@launch
                        }

                        val enabled = settingsRepository.sosEnabled.first()
                        if (!enabled) {
                            Log.d(TAG, "SOS not enabled, ignoring trigger")
                            return@launch
                        }

                        val countdownSeconds = settingsRepository.sosCountdownSeconds.first()
                        if (countdownSeconds <= 0) {
                            sendSosMessages()
                        } else {
                            startCountdown(countdownSeconds)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error triggering SOS", e)
                        _state.value = SosState.Idle
                    } finally {
                        triggerMutex.unlock()
                    }
                }
        }

        /**
         * Cancel the SOS during the countdown phase. Returns to Idle.
         */
        fun cancel() {
            if (_state.value is SosState.Countdown) {
                countdownJob?.cancel()
                countdownJob = null
                triggerJob?.cancel()
                triggerJob = null
                _state.value = SosState.Idle
                Log.d(TAG, "SOS countdown cancelled")
            }
        }

        /**
         * Deactivate SOS from the Active state.
         *
         * @param pin Optional PIN for deactivation. If a deactivation PIN is configured
         *            in settings, the provided pin must match.
         * @return true if successfully deactivated, false if PIN mismatch or not in Active state.
         */
        fun deactivate(pin: String? = null): Boolean {
            if (_state.value !is SosState.Active) return false

            val requiredPin = cachedDeactivationPin
            if (!requiredPin.isNullOrBlank() && requiredPin != pin) {
                Log.d(TAG, "SOS deactivation PIN mismatch")
                return false
            }

            periodicUpdateJob?.cancel()
            periodicUpdateJob = null
            audioRecordingJob?.cancel()
            audioRecordingJob = null
            audioRecorder.cancel()
            notificationHelper.cancelNotification(NotificationHelper.NOTIFICATION_ID_SOS)
            _state.value = SosState.Idle
            scope.launch {
                settingsRepository.clearSosActiveState()
                sendCancellationMessage()
            }
            Log.d(TAG, "SOS deactivated")
            return true
        }

        /**
         * Force deactivate SOS bypassing PIN check.
         * Used when the feature toggle is disabled at the app level.
         */
        fun forceDeactivate() {
            if (_state.value is SosState.Idle) return
            triggerJob?.cancel()
            triggerJob = null
            countdownJob?.cancel()
            countdownJob = null
            periodicUpdateJob?.cancel()
            periodicUpdateJob = null
            audioRecordingJob?.cancel()
            audioRecordingJob = null
            audioRecorder.cancel()
            notificationHelper.cancelNotification(NotificationHelper.NOTIFICATION_ID_SOS)
            val shouldSendCancellation = _state.value is SosState.Active || _state.value is SosState.Sending
            _state.value = SosState.Idle
            scope.launch {
                settingsRepository.clearSosActiveState()
                if (shouldSendCancellation) sendCancellationMessage()
            }
            Log.d(TAG, "SOS force-deactivated (feature toggle disabled)")
        }

        /**
         * Check if incoming calls should be auto-answered due to active SOS.
         *
         * @return true if SOS is active and silent auto-answer is enabled in settings.
         */
        fun shouldAutoAnswer(): Boolean {
            if (_state.value !is SosState.Active) return false
            return cachedSilentAutoAnswer
        }

        private suspend fun startCountdown(totalSeconds: Int) {
            // Use coroutineScope so countdown is a child of the caller (triggerJob).
            // Cancelling triggerJob in forceDeactivate() will also cancel the countdown.
            coroutineScope {
                countdownJob =
                    launch {
                        try {
                            for (remaining in totalSeconds downTo 1) {
                                _state.value = SosState.Countdown(remaining, totalSeconds)
                                delay(1_000L)
                            }
                            sendSosMessages()
                        } catch (e: CancellationException) {
                            Log.d(TAG, "Countdown coroutine cancelled")
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during countdown/send", e)
                            _state.value = SosState.Idle
                        }
                    }
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun sendSosMessages() {
            kotlin.coroutines.coroutineContext.ensureActive() // Honour pending cancellation before touching state
            _state.value = SosState.Sending

            val template = settingsRepository.sosMessageTemplate.first()
            val includeLocation = settingsRepository.sosIncludeLocation.first()

            val location = if (includeLocation) getLastKnownLocation() else null
            val batteryLevel = getBatteryLevel()

            val messageContent =
                buildString {
                    append(template)
                    location?.let {
                        append("\nGPS: ${String.format(java.util.Locale.US, "%.6f", it.latitude)}, ${String.format(java.util.Locale.US, "%.6f", it.longitude)}")
                        append(" (accuracy: ${it.accuracy.toInt()}m)")
                    }
                    batteryLevel?.let { level ->
                        append("\nBattery: $level%")
                    }
                }

            // Build FIELD_TELEMETRY JSON for Sideband-compatible telemetry
            val telemetryJson = buildTelemetryJson(location, batteryLevel)

            val contacts = contactRepository.getSosContacts()
            if (contacts.isEmpty()) {
                Log.w(TAG, "No SOS contacts configured")
                _state.value = SosState.Active(sentCount = 0, failedCount = 0)
                settingsRepository.persistSosActiveState(0, 0)
                notificationHelper.showSosActiveNotification(0, 0)
                return
            }

            val identity = loadIdentity()
            if (identity == null) {
                Log.e(TAG, "Failed to load identity, cannot send SOS messages")
                _state.value = SosState.Active(sentCount = 0, failedCount = contacts.size)
                settingsRepository.persistSosActiveState(0, contacts.size)
                notificationHelper.showSosActiveNotification(0, contacts.size)
                return
            }

            var sentCount = 0
            var failedCount = 0

            for (contact in contacts) {
                try {
                    val destHashBytes = contact.destinationHash.hexToByteArray()
                    val result =
                        reticulumProtocol.sendLxmfMessageWithMethod(
                            destinationHash = destHashBytes,
                            content = messageContent,
                            sourceIdentity = identity,
                            telemetryJson = telemetryJson,
                            sosState = "active",
                        )
                    if (result.isSuccess) {
                        sentCount++
                        Log.d(TAG, "SOS message sent to ${contact.destinationHash.take(8)}...")
                    } else {
                        failedCount++
                        Log.e(TAG, "SOS message failed for ${contact.destinationHash.take(8)}...: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedCount++
                    Log.e(TAG, "Error sending SOS message to ${contact.destinationHash.take(8)}...", e)
                }
            }

            kotlin.coroutines.coroutineContext.ensureActive() // honour pending cancellation before touching state
            _state.value = SosState.Active(sentCount, failedCount)
            settingsRepository.persistSosActiveState(sentCount, failedCount)
            notificationHelper.showSosActiveNotification(sentCount, failedCount)
            Log.d(TAG, "SOS messages sent: $sentCount success, $failedCount failed")

            val periodicUpdates = settingsRepository.sosPeriodicUpdates.first()
            if (periodicUpdates) {
                startPeriodicUpdates()
            }

            val audioEnabled = settingsRepository.sosAudioEnabled.first()
            if (audioEnabled) {
                startAudioRecording(identity, contacts.map { it.destinationHash })
            }
        }

        @SuppressLint("MissingPermission")
        private fun startPeriodicUpdates() {
            periodicUpdateJob?.cancel()
            periodicUpdateJob =
                scope.launch {
                    val intervalSeconds = settingsRepository.sosUpdateIntervalSeconds.first()

                    // Wait for the Reticulum service to be bound and ready before
                    // loading identity. At boot, the mesh network may not be up yet.
                    if (reticulumProtocol is ServiceReticulumProtocol) {
                        try {
                            reticulumProtocol.bindService()
                            reticulumProtocol.waitForReady(timeoutMs = 30_000)
                        } catch (e: Exception) {
                            ensureActive()
                            Log.w(TAG, "Reticulum not ready yet, periodic updates will retry", e)
                        }
                    }

                    var identity = loadIdentity()

                    while (true) {
                        delay(intervalSeconds * 1_000L)

                        // Retry identity load if it failed on first attempt (service wasn't ready)
                        if (identity == null) {
                            identity = loadIdentity()
                            if (identity == null) continue
                        }

                        val updateLocation = getLastKnownLocation()
                        val updateBattery = getBatteryLevel()

                        val updateMessage =
                            buildString {
                                append("SOS Update")
                                updateLocation?.let { loc ->
                                    append(" - GPS: ${String.format(java.util.Locale.US, "%.6f", loc.latitude)}, ${String.format(java.util.Locale.US, "%.6f", loc.longitude)}")
                                    append(" (accuracy: ${loc.accuracy.toInt()}m)")
                                }
                                updateBattery?.let { level ->
                                    append(" - Battery: $level%")
                                }
                            }

                        val updateTelemetry = buildTelemetryJson(updateLocation, updateBattery)

                        try {
                            val contacts = contactRepository.getSosContacts()
                            for (contact in contacts) {
                                try {
                                    val destHashBytes = contact.destinationHash.hexToByteArray()
                                    reticulumProtocol.sendLxmfMessageWithMethod(
                                        destinationHash = destHashBytes,
                                        content = updateMessage,
                                        sourceIdentity = identity,
                                        telemetryJson = updateTelemetry,
                                        sosState = "update",
                                    )
                                } catch (e: Exception) {
                                    ensureActive()
                                    Log.e(TAG, "Error sending SOS update to ${contact.destinationHash.take(8)}...", e)
                                }
                            }
                            Log.d(TAG, "Periodic SOS update sent to ${contacts.size} contacts")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching SOS contacts for periodic update", e)
                        }
                    }
                }
        }

        private fun startAudioRecording(
            identity: com.lxmf.messenger.reticulum.model.Identity,
            contactHashes: List<String>,
        ) {
            audioRecordingJob =
                scope.launch {
                    val durationSeconds = settingsRepository.sosAudioDurationSeconds.first()

                    val started = withContext(Dispatchers.Main) { audioRecorder.start() }
                    if (!started) {
                        Log.w(TAG, "Audio recording failed to start")
                        return@launch
                    }

                    Log.d(TAG, "SOS audio recording for ${durationSeconds}s")
                    delay(durationSeconds * 1_000L)

                    withContext(Dispatchers.Main) { audioRecorder.stopRecorder() }
                    val audioBytes = withContext(Dispatchers.IO) { audioRecorder.readAndDeleteOutputFile() }
                    if (audioBytes == null) {
                        Log.w(TAG, "Audio recording returned no data")
                        return@launch
                    }

                    Log.d(TAG, "Sending SOS audio (${audioBytes.size} bytes) to ${contactHashes.size} contacts")
                    for (hash in contactHashes) {
                        try {
                            val destHashBytes = hash.hexToByteArray()
                            reticulumProtocol.sendLxmfMessageWithMethod(
                                destinationHash = destHashBytes,
                                content = "Audio from SOS alert",
                                sourceIdentity = identity,
                                audioData = audioBytes,
                                sosState = "active",
                            )
                        } catch (e: Exception) {
                            ensureActive()
                            Log.e(TAG, "Error sending SOS audio to ${hash.take(8)}...", e)
                        }
                    }
                    Log.d(TAG, "SOS audio sent to ${contactHashes.size} contacts")
                }
        }

        @SuppressLint("MissingPermission")
        private suspend fun getLastKnownLocation(): Location? =
            try {
                locationProvider?.invoke()
                    ?: kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        com.lxmf.messenger.util.LocationCompat.getCurrentLocation(context) { location ->
                            cont.resume(location, null)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting location", e)
                null
            }

        private fun buildTelemetryJson(
            location: Location?,
            batteryLevel: Int?,
        ): String? {
            if (location == null) return null // Don't emit telemetry without a real position (avoids Null Island 0,0)
            return JSONObject().apply {
                put("lat", location.latitude)
                put("lng", location.longitude)
                put("acc", location.accuracy.toDouble())
                put("ts", System.currentTimeMillis())
                if (location.hasAltitude()) put("altitude", location.altitude)
                if (location.hasSpeed()) put("speed", location.speed.toDouble())
                if (location.hasBearing()) put("bearing", location.bearing.toDouble())
                if (batteryLevel != null) {
                    put("battery_percent", batteryLevel)
                    put("battery_charging", isBatteryCharging())
                }
            }.toString()
        }

        private fun isBatteryCharging(): Boolean =
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                batteryManager.isCharging
            } catch (e: Exception) {
                Log.w(TAG, "Error checking battery charging state", e)
                false
            }

        private fun getBatteryLevel(): Int? =
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (level in 0..100) level else null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting battery level", e)
                null
            }

        private suspend fun loadIdentity(): com.lxmf.messenger.reticulum.model.Identity? =
            try {
                if (reticulumProtocol is ServiceReticulumProtocol) {
                    reticulumProtocol.getLxmfIdentity().getOrNull()
                } else {
                    reticulumProtocol.loadIdentity("default_identity").getOrNull()
                        ?: reticulumProtocol.createIdentity().getOrThrow().also {
                            reticulumProtocol.saveIdentity(it, "default_identity")
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading identity", e)
                null
            }

        private suspend fun sendCancellationMessage() {
            try {
                val contacts = contactRepository.getSosContacts()
                if (contacts.isEmpty()) return
                val identity = loadIdentity() ?: return
                for (contact in contacts) {
                    try {
                        reticulumProtocol.sendLxmfMessageWithMethod(
                            destinationHash = contact.destinationHash.hexToByteArray(),
                            content = "SOS Cancelled — I am safe.",
                            sourceIdentity = identity,
                            sosState = "cancelled",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send cancellation to ${contact.destinationHash.take(8)}", e)
                    }
                }
                Log.d(TAG, "SOS cancellation sent to ${contacts.size} contacts")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending cancellation messages", e)
            }
        }

        /**
         * Convert a hex string to a ByteArray.
         */
        private fun String.hexToByteArray(): ByteArray {
            check(length % 2 == 0) { "Hex string must have even length" }
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
