package com.lxmf.messenger.service

import android.util.Log
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.di.ApplicationScope
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * Manages automatic periodic announces based on user settings.
 *
 * This class observes the auto-announce settings (enabled state and interval)
 * and triggers announces at the configured interval when enabled.
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
        }

        private var autoAnnounceJob: Job? = null

        /**
         * Start observing settings and managing auto-announces.
         * Call this when the app starts or when the Reticulum service becomes ready.
         */
        fun start() {
            Log.d(TAG, "Starting AutoAnnounceManager")

            // Observe settings changes
            autoAnnounceJob =
                scope.launch {
                    combine(
                        settingsRepository.autoAnnounceEnabledFlow,
                        settingsRepository.autoAnnounceIntervalMinutesFlow,
                        identityRepository.activeIdentity,
                    ) { enabled, intervalMinutes, activeIdentity ->
                        Triple(enabled, intervalMinutes, activeIdentity?.displayName)
                    }.collect { (enabled, intervalMinutes, displayName) ->
                        Log.d(TAG, "Settings changed: enabled=$enabled, interval=${intervalMinutes}min, displayName=$displayName")

                        if (enabled) {
                            startAnnounceLoop(intervalMinutes, displayName)
                        } else {
                            Log.d(TAG, "Auto-announce disabled, stopping loop")
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
            autoAnnounceJob?.cancel()
            autoAnnounceJob = null
        }

        /**
         * Run the announce loop with the specified interval.
         * This is launched in a new coroutine each time settings change.
         */
        private suspend fun startAnnounceLoop(
            intervalMinutes: Int,
            displayName: String?,
        ) {
            Log.d(TAG, "Starting announce loop with interval ${intervalMinutes}min")

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
                            Log.d(TAG, "Auto-announce successful, next in ${intervalMinutes}min")
                        } else {
                            Log.e(TAG, "Auto-announce failed: ${result.exceptionOrNull()?.message}")
                        }
                    } else {
                        Log.w(TAG, "Auto-announce skipped: ReticulumProtocol is not ServiceReticulumProtocol")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during auto-announce", e)
                }

                // Wait for the configured interval
                delay(intervalMinutes.minutes)
            }
        }
    }
