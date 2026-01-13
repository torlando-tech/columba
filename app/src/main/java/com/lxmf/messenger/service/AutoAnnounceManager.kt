package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * Manages automatic periodic announces based on user settings.
 *
 * This class observes the auto-announce settings (enabled state and interval)
 * and triggers announces at the configured interval when enabled.
 * The interval is randomized by +/- 1 hour (with minute precision) to prevent network congestion.
 * The timer can be reset when a network topology change triggers an immediate announce.
 */
@Singleton
class AutoAnnounceManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val identityRepository: IdentityRepository,
        private val reticulumProtocol: ReticulumProtocol,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "AutoAnnounceManager"
            private const val RANDOMIZATION_RANGE_MINUTES = 60 // ±1 hour in minutes
            private const val MIN_INTERVAL_MINUTES = 60 // 1 hour minimum
            private const val MAX_INTERVAL_MINUTES = 720 // 12 hours maximum
        }

        private var autoAnnounceJob: Job? = null
        private var networkChangeObserverJob: Job? = null
        private val resetTimerSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /**
         * Reset the announce timer. Call this when a network topology change
         * triggers an immediate announce, so the periodic timer restarts from zero.
         */
        fun resetTimer() {
            Log.d(TAG, "Timer reset requested")
            resetTimerSignal.tryEmit(Unit)
        }

        /**
         * Start observing settings and managing auto-announces.
         * Call this when the app starts or when the Reticulum service becomes ready.
         */
        fun start() {
            Log.d(TAG, "Starting AutoAnnounceManager")

            // Observe network change announces from the service process (cross-process via DataStore)
            // When the service triggers an announce due to network topology change, it saves a timestamp
            // which we observe here to reset our periodic timer
            networkChangeObserverJob =
                scope.launch {
                    settingsRepository.networkChangeAnnounceTimeFlow.collect { timestamp ->
                        if (timestamp != null) {
                            Log.d(TAG, "Network change announce detected from service, resetting timer")
                            resetTimer()
                        }
                    }
                }

            // Observe settings changes
            autoAnnounceJob =
                scope.launch {
                    combine(
                        settingsRepository.autoAnnounceEnabledFlow,
                        settingsRepository.autoAnnounceIntervalHoursFlow,
                        identityRepository.activeIdentity,
                    ) { enabled, intervalHours, activeIdentity ->
                        Triple(enabled, intervalHours, activeIdentity?.displayName)
                    }.collect { (enabled, intervalHours, displayName) ->
                        Log.d(TAG, "Settings changed: enabled=$enabled, interval=${intervalHours}h, displayName=$displayName")

                        if (enabled) {
                            startAnnounceLoop(intervalHours, displayName)
                        } else {
                            Log.d(TAG, "Auto-announce disabled, stopping loop")
                            // Clear the next announce time when disabled
                            settingsRepository.saveNextAutoAnnounceTime(null)
                        }
                    }
                }
        }

        /**
         * Stop the auto-announce manager.
         * Call this when the app is shutting down.
         */
        fun stop() {
            Log.d(TAG, "Stopping AutoAnnounceManager")
            networkChangeObserverJob?.cancel()
            networkChangeObserverJob = null
            autoAnnounceJob?.cancel()
            autoAnnounceJob = null
        }

        /**
         * Run the announce loop with the specified interval.
         * This is launched in a new coroutine each time settings change.
         * The interval is randomized by +/- 1 hour with minute precision.
         */
        private suspend fun startAnnounceLoop(
            intervalHours: Int,
            displayName: String?,
        ) {
            val baseIntervalMinutes = intervalHours * 60
            Log.d(TAG, "Starting announce loop with base interval ${intervalHours}h (±${RANDOMIZATION_RANGE_MINUTES}min randomization)")

            // The loop will be cancelled and restarted if settings change
            while (true) {
                try {
                    // Only perform announce if using ServiceReticulumProtocol
                    if (reticulumProtocol is ServiceReticulumProtocol) {
                        // Perform announce
                        val effectiveDisplayName = displayName ?: "Anonymous Peer"
                        Log.d(TAG, "Triggering auto-announce...")

                        val result = reticulumProtocol.triggerAutoAnnounce(effectiveDisplayName)

                        if (result.isSuccess) {
                            // Update last announce timestamp
                            val timestamp = System.currentTimeMillis()
                            settingsRepository.saveLastAutoAnnounceTime(timestamp)
                            Log.d(TAG, "Auto-announce successful")
                        } else {
                            Log.e(TAG, "Auto-announce failed: ${result.exceptionOrNull()?.message}")
                        }
                    } else {
                        Log.w(TAG, "Auto-announce skipped: ReticulumProtocol is not ServiceReticulumProtocol")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during auto-announce", e)
                }

                // Calculate randomized delay with minute precision: base interval +/- 1 hour
                val randomOffsetMinutes = Random.nextInt(-RANDOMIZATION_RANGE_MINUTES, RANDOMIZATION_RANGE_MINUTES + 1)
                val actualDelayMinutes =
                    (baseIntervalMinutes + randomOffsetMinutes)
                        .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
                val delayMillis = actualDelayMinutes.minutes.inWholeMilliseconds

                // Save the scheduled next announce time for UI display
                val nextAnnounceTime = System.currentTimeMillis() + delayMillis
                settingsRepository.saveNextAutoAnnounceTime(nextAnnounceTime)

                val hours = actualDelayMinutes / 60
                val mins = actualDelayMinutes % 60
                Log.d(TAG, "Next announce in ${hours}h ${mins}m (base: ${intervalHours}h, offset: ${randomOffsetMinutes}min)")

                // Wait for the randomized interval, or reset if network change occurs
                // withTimeoutOrNull returns null on timeout, or the signal value if reset signal received
                val wasReset =
                    withTimeoutOrNull(delayMillis) {
                        resetTimerSignal.first()
                        true
                    } ?: false

                if (wasReset) {
                    Log.d(TAG, "Timer was reset by network change, restarting delay loop")
                    continue // Skip announce, network change already triggered one
                }
            }
        }
    }
