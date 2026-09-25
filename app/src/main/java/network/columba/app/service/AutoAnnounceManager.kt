package network.columba.app.service

import android.util.Log
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import kotlinx.coroutines.CancellationException
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
        private val rnsCore: RnsCore,
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
                        Triple(
                            enabled,
                            intervalHours,
                            activeIdentity?.let { it.identityHash to it.displayName },
                        )
                    }.collect { (enabled, intervalHours, namePair) ->
                        val displayName = namePair?.second
                        val identityHash = namePair?.first
                        Log.d(TAG, "Settings changed: enabled=$enabled, interval=${intervalHours}h")

                        if (enabled) {
                            startAnnounceLoop(intervalHours, displayName, identityHash)
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
            identityHash: String?,
        ) {
            val baseIntervalMinutes = intervalHours * 60
            Log.d(TAG, "Starting announce loop with base interval ${intervalHours}h (±${RANDOMIZATION_RANGE_MINUTES}min randomization)")

            // The loop will be cancelled and restarted if settings change
            while (true) {
                try {
                    // Perform announce. Re-read the bound identity's display
                    // name each tick rather than reusing [displayName] (captured
                    // once when the loop started): a display-name *edit* does
                    // not restart this loop, so without a fresh read every
                    // subsequent automatic tick would re-announce the stale
                    // name. The name is resolved for the identity this loop is
                    // bound to ([identityHash], captured when the loop started)
                    // so a rename lands on the correct destination and an
                    // identity switch can't mix a fresh name onto a stale one.
                    performAnnounceTick(identityHash, displayName)
                } catch (e: CancellationException) {
                    // Stopping the manager cancels this loop (e.g. a CancellationException
                    // from the per-tick identity read in performAnnounceTick). Propagate it
                    // so the coroutine ends cleanly instead of logging a spurious
                    // "Error during auto-announce" and continuing past cancellation.
                    throw e
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

        /**
         * Perform a single announce tick: resolve the bound identity's current
         * name and hand it to [RnsCore.triggerAutoAnnounce].
         *
         * Extracted from [startAnnounceLoop] so the name-resolution step is
         * directly testable - a regression that made the loop stop using the
         * fresh-name resolver would change what this method passes to
         * triggerAutoAnnounce.
         */
        internal suspend fun performAnnounceTick(
            identityHash: String?,
            fallback: String?,
        ) {
            val effectiveDisplayName = resolveCurrentDisplayName(
                identityHash = identityHash,
                fallback = fallback,
            )
            Log.d(TAG, "Triggering auto-announce...")

            val result = rnsCore.triggerAutoAnnounce(effectiveDisplayName)

            if (result.isSuccess) {
                // Update last announce timestamp
                val timestamp = System.currentTimeMillis()
                settingsRepository.saveLastAutoAnnounceTime(timestamp)
                Log.d(TAG, "Auto-announce successful")
            } else {
                Log.e(TAG, "Auto-announce failed: ${result.exceptionOrNull()?.message}")
            }
        }

        /**
         * Resolve the display name for the next auto-announce.
         *
         * Re-reads the *bound* identity's current name from the repository so a
         * display-name edit (which does not restart the loop) is picked up on
         * the next automatic tick. The name is looked up by [identityHash] -
         * the identity this loop was started for - rather than "the current
         * active identity": an identity switch rewrites the active row *before*
         * the old loop's service restart lands, and a tick inside that window
         * must not hand the old destination the new persona's name.
         *
         * Resolution order:
         *  1. A nonblank name read from the bound identity row - the current
         *     value, so a rename is picked up on the next tick.
         *  2. "Anonymous Peer" when the bound row exists but its name is blank:
         *     the edit path stores a trimmed, possibly-empty string when the
         *     user clears their name, and re-announcing the loop-start
         *     [fallback] would re-broadcast the name the user just removed.
         *  3. The loop-start [fallback] name, then "Anonymous Peer", only when
         *     the bound row is missing (deleted / not yet written / no bound
         *     hash), so an announce always carries a name.
         *
         * Reading is best-effort: a transient repository failure must not abort
         * the announce, so it degrades to the fallback. Coroutine cancellation
         * is rethrown (not swallowed by the failure path) so stopping the
         * manager during the read ends the loop immediately instead of logging
         * a spurious "read failure" and continuing with the fallback.
         */
        internal suspend fun resolveCurrentDisplayName(
            identityHash: String?,
            fallback: String?,
        ): String {
            val entity = try {
                if (identityHash == null) null else identityRepository.getIdentity(identityHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-read display name; using fallback", e)
                null
            }
            val freshName = entity?.displayName?.takeIf { it.isNotBlank() }
            return when {
                // A fresh, nonblank name from the bound row wins: a rename is
                // picked up on the next tick.
                freshName != null -> freshName
                // The bound row exists but its name is blank: the user cleared
                // a previously nonblank name (the edit path stores a trimmed,
                // possibly-empty string). Re-announcing the loop-start
                // [fallback] would re-broadcast the name the user just removed,
                // so an explicit clear is announced as anonymous.
                entity != null -> "Anonymous Peer"
                // The bound row is missing (deleted / not yet written / no bound
                // hash): the loop-start name is the best available, then
                // anonymous, so an announce always carries a name.
                else -> fallback ?: "Anonymous Peer"
            }
        }
    }
